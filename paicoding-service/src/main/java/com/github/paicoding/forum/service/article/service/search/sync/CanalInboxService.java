package com.github.paicoding.forum.service.article.service.search.sync;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.github.paicoding.forum.service.article.service.search.ArticleIndexStore;
import com.github.paicoding.forum.service.article.service.search.sync.mapper.SearchSyncMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Durable receipt and commit gating: Canal batches are not MySQL transaction boundaries. */
@Service
public class CanalInboxService {
    private final SearchSyncMapper mapper;
    private final SearchCanalProperties properties;
    private final ArticleIndexStore store;
    private final TransactionTemplate tx;

    public CanalInboxService(SearchSyncMapper mapper, SearchCanalProperties properties,
                             ArticleIndexStore store, PlatformTransactionManager manager) {
        this.mapper=mapper;
        this.properties=properties;
        this.store=store;
        this.tx=new TransactionTemplate(manager);
    }

    public void accept(List<CanalEntry.Entry> entries) {
        tx.executeWithoutResult(status -> {
            String index=store.index();
            mapper.ensureCheckpoint(index);
            String transaction=mapper.lockCheckpoint(index);
            String position=null;
            for(CanalEntry.Entry entry:entries) {
                CanalEntry.Header header=entry.getHeader();
                position=properties.getSourceId()+":"+properties.getSourceEpoch()+":"+
                        header.getLogfileName()+":"+header.getLogfileOffset();
                // ACK can be lost after a batch containing only ROW/END has committed.
                // Deduplicate transaction markers too, before changing persisted open_tx.
                if(mapper.receipt(DigestUtils.sha256Hex(index+":"+position+":"+entry.getEntryType()))==0) continue;
                if(entry.getEntryType()==CanalEntry.EntryType.TRANSACTIONBEGIN) {
                    transaction=position;
                } else if(entry.getEntryType()==CanalEntry.EntryType.TRANSACTIONEND) {
                    if(transaction!=null) mapper.release(index,transaction);
                    transaction=null;
                } else if(entry.getEntryType()==CanalEntry.EntryType.ROWDATA) {
                    if(!properties.getDatabase().equals(header.getSchemaName())) continue;
                    CanalEntry.RowChange change;
                    try { change=CanalEntry.RowChange.parseFrom(entry.getStoreValue()); }
                    catch(Exception e) { throw new IllegalArgumentException("Invalid Canal row event",e); }
                    String table=header.getTableName();
                    if(!Arrays.asList("article","article_detail","user_info","column_article","search_cdc_signal").contains(table)) continue;
                    if(change.getIsDdl()) throw new IllegalStateException("Search source DDL requires schema review: "+table);
                    if(transaction==null)
                        throw new IllegalStateException("Missing Canal transaction begin; set canal.instance.filter.transaction.entry=false");
                    int row=0;
                    for(CanalEntry.RowData data:change.getRowDatasList()) {
                        String key=index+":"+position+":"+table+":"+row++;
                        if("search_cdc_signal".equals(table)) {
                            if(change.getEventType()==CanalEntry.EventType.INSERT &&
                                    index.equals(value(data.getAfterColumnsList(),"index_name")))
                                receive(key,index,transaction,"ALL",0);
                            continue;
                        }
                        String field="article".equals(table) ? "id" :
                                "user_info".equals(table) ? "user_id" : "article_id";
                        String type="user_info".equals(table) ? "AUTHOR" : "ARTICLE";
                        Set<Long> subjects=new LinkedHashSet<>();
                        add(subjects,value(data.getBeforeColumnsList(),field));
                        add(subjects,value(data.getAfterColumnsList(),field));
                        if(subjects.isEmpty()) throw new IllegalStateException("Missing affected ID: "+table+"."+field);
                        for(Long subject:subjects) receive(key,index,transaction,type,subject);
                    }
                }
            }
            mapper.checkpoint(index,transaction,position);
        });
    }

    private void receive(String key,String index,String transaction,String type,long subject) {
        mapper.receive(DigestUtils.sha256Hex(key+":"+type+":"+subject),index,transaction,type,subject);
    }
    private static String value(List<CanalEntry.Column> columns,String name) {
        for(CanalEntry.Column c:columns) if(name.equals(c.getName()) && !c.getIsNull()) return c.getValue();
        return null;
    }
    private static void add(Set<Long> ids,String value) { if(value!=null) ids.add(Long.parseLong(value)); }

    /** A bounded, restartable fan-out page; cursor and task versions commit atomically. */
    public boolean routeNext() {
        Boolean routed=tx.execute(status -> {
            SearchTask event=mapper.lockInbox(store.index());
            if(event==null) return false;
            if("ARTICLE".equals(event.getTaskType())) {
                mapper.enqueue(store.index(),event.getSubjectId());
                mapper.advance(event.getId(),event.getSubjectId(),true);
            } else {
                List<Long> ids="AUTHOR".equals(event.getTaskType()) ?
                        mapper.authorArticles(event.getSubjectId(),event.getCursorId()) :
                        mapper.allArticles(store.index(),event.getCursorId());
                for(Long id:ids) mapper.enqueue(store.index(),id);
                boolean done=ids.size()<100;
                mapper.advance(event.getId(),ids.isEmpty()?event.getCursorId():ids.get(ids.size()-1),done);
                if(done && "ALL".equals(event.getTaskType())) mapper.initializedDone(store.index());
            }
            return true;
        });
        return Boolean.TRUE.equals(routed);
    }
}
