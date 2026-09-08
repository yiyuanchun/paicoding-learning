package com.github.paicoding.forum.service.article.service.search.sync;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.github.paicoding.forum.service.article.repository.dao.ArticleDao;
import com.github.paicoding.forum.service.article.service.search.ArticleIndexStore;
import com.github.paicoding.forum.service.article.service.search.sync.mapper.SearchSyncMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="search.canal",name="enabled",havingValue="true")
public class SearchSyncEngine {
    private final SearchCanalProperties properties;
    private final SearchSyncMapper mapper;
    private final CanalInboxService inbox;
    private final ArticleIndexStore store;
    private final ArticleDao articles;
    private final String owner=UUID.randomUUID().toString();
    private final ScheduledExecutorService executor=Executors.newScheduledThreadPool(3,r -> {
        Thread t=new Thread(r,"article-search-sync");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean started;
    private volatile boolean connected;
    private volatile String lastError;
    private volatile long lastReceivedAt;
    private long nextConnectAt;
    private long nextSignalAt;
    private CanalConnector connector;
    private final List<CanalEntry.Entry> unacknowledged = new ArrayList<>();
    private final List<Long> unacknowledgedBatches = new ArrayList<>();

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if(started) return;
        if(!store.available()) throw new IllegalStateException("Canal search sync requires elasticsearch.open=true");
        mapper.ensureCheckpoint(store.index());
        started=true;
        executor.scheduleWithFixedDelay(() -> guarded(this::poll),0,500,TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(() -> guarded(() -> { for(int i=0;i<10 && inbox.routeNext();i++) { } }),0,200,TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(() -> guarded(this::project),0,500,TimeUnit.MILLISECONDS);
    }

    private void guarded(Runnable work) {
        try { work.run(); }
        catch(Exception e) { lastError=e.getMessage(); log.error("Search synchronization failed; durable work retained",e); }
    }

    public synchronized void poll() {
        if(System.currentTimeMillis()<nextConnectAt) return;
        if(mapper.claimLeader(store.index(),owner)==0) {
            disconnect();
            return;
        }
        try {
            if(connector==null) {
                connector=CanalConnectors.newSingleConnector(new InetSocketAddress(properties.getHost(),properties.getPort()),
                        properties.getDestination(),properties.getUsername(),properties.getPassword());
                connector.connect();
                if (!properties.getDatabase().matches("[a-zA-Z0-9_]+"))
                    throw new IllegalArgumentException("Unsupported database identifier");
                // Canal uses a Perl-compatible filter; Java Pattern.quote is not supported.
                connector.subscribe(properties.getDatabase()+"\\.(article|article_detail|user_info|column_article|search_cdc_signal)");
                connector.rollback();
                connected=true;
                log.info("Canal search consumer connected: destination={}, index={}",properties.getDestination(),store.index());
            }
            long now=System.currentTimeMillis();
            if(now>=nextSignalAt) {
                requestRebuild();
                nextSignalAt=now+(mapper.initialized(store.index())==0 ? 30000 :
                        Math.max(60,properties.getReconcileIntervalSeconds())*1000);
            }
            Message message=connector.getWithoutAck(properties.getBatchSize(),100L,TimeUnit.MILLISECONDS);
            if(message.getId()==-1) return;
            unacknowledgedBatches.add(message.getId());
            List<CanalEntry.Entry> entries=message.getEntries();
            if(message.isRaw()) {
                entries=new ArrayList<>();
                for(com.google.protobuf.ByteString raw:message.getRawEntries()) entries.add(CanalEntry.Entry.parseFrom(raw));
            }
            for (CanalEntry.Entry entry : entries) {
                if (entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONBEGIN ||
                    entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONEND ||
                    entry.getEntryType() == CanalEntry.EntryType.ROWDATA) unacknowledged.add(entry);
            }
            if (unacknowledged.size() > 10000)
                throw new IllegalStateException("Canal transaction exceeds 10000 entries; inspect source transaction and buffer configuration");
            if (!unacknowledged.isEmpty() &&
                unacknowledged.get(unacknowledged.size()-1).getEntryType() != CanalEntry.EntryType.TRANSACTIONEND) return;
            // Canal also emits empty BEGIN/END pairs for excluded tables. Persisting those
            // would turn our own metadata writes into an endless Binlog feedback loop.
            List<CanalEntry.Entry> meaningful = new ArrayList<>();
            List<CanalEntry.Entry> transaction = new ArrayList<>();
            boolean hasRows = false;
            for (CanalEntry.Entry entry : unacknowledged) {
                transaction.add(entry);
                hasRows |= entry.getEntryType() == CanalEntry.EntryType.ROWDATA;
                if (entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONEND) {
                    if (hasRows) meaningful.addAll(transaction);
                    transaction.clear();
                    hasRows = false;
                }
            }
            if (!meaningful.isEmpty()) inbox.accept(meaningful);
            // ACK only after durable receipt/commit metadata. A crash here safely replays the batch.
            if(mapper.claimLeader(store.index(),owner)==0) { disconnect(); return; }
            for (Long batchId : unacknowledgedBatches) connector.ack(batchId);
            unacknowledged.clear();
            unacknowledgedBatches.clear();
            lastReceivedAt=System.currentTimeMillis();
            lastError=null;
        } catch(Exception e) {
            lastError=e.getMessage();
            log.warn("Canal search receipt failed; reconnecting from last ACK",e);
            disconnect();
            nextConnectAt=System.currentTimeMillis()+5000;
        }
    }

    public void project() {
        storeEnsure();
        List<SearchTask> tasks=mapper.pending(store.index());
        if(tasks.isEmpty()) return;
        for(SearchTask candidate:tasks) {
            String token=UUID.randomUUID().toString();
            if(mapper.claim(store.index(),candidate.getArticleId(),token)==0) continue;
            SearchTask task=mapper.claimed(store.index(),candidate.getArticleId(),token);
            try {
                // Capture revision before reading primary DB, never use article.update_time as a version.
                long version=task.getRequestedVersion();
                store.write(task.getArticleId(),version,articles.queryArticleSearchDocument(task.getArticleId()));
                mapper.complete(store.index(),task.getArticleId(),token,version);
                log.debug("Search projection complete: articleId={}, version={}",task.getArticleId(),version);
            } catch(Exception e) {
                int retries=task.getRetryCount()==null ? 0 : task.getRetryCount();
                int delay=(int)Math.min(properties.getRetryMaxSeconds(),1L<<Math.min(20,retries+1));
                mapper.fail(store.index(),task.getArticleId(),token,StringUtils.left(e.toString(),1500),delay);
                log.warn("Search projection retained for retry: articleId={}, retry={}",task.getArticleId(),retries+1,e);
            }
        }
    }

    private void storeEnsure() {
        try { store.ensureIndex(); }
        catch(Exception e) { throw new IllegalStateException("Cannot initialize search index; tasks retained",e); }
    }

    /** A stream marker ensures full scan begins only after Canal is capturing changes. */
    public void requestRebuild() {
        mapper.signal(UUID.randomUUID().toString(),store.index());
    }

    public void enqueueRepair(long id) { mapper.enqueue(store.index(),id); }

    public Map<String,Object> status() {
        Map<String,Object> result=new LinkedHashMap<>(mapper.status(store.index()));
        result.put("index",store.index());
        result.put("canalConnected",connected);
        result.put("lastReceivedAt",lastReceivedAt);
        result.put("lastError",lastError);
        result.put("inboxPending",mapper.inboxPending(store.index()));
        result.put("backfillEnqueued",mapper.initialized(store.index())==1);
        return result;
    }

    private void disconnect() {
        connected=false;
        unacknowledged.clear();
        unacknowledgedBatches.clear();
        if(connector!=null) {
            try { connector.disconnect(); } catch(Exception ignored) { }
            connector=null;
        }
    }

    @PreDestroy public synchronized void stop() {
        executor.shutdownNow();
        disconnect();
        mapper.releaseLeader(store.index(),owner);
    }
}
