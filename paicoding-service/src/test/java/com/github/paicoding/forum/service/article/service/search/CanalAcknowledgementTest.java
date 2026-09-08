package com.github.paicoding.forum.service.article.service.search;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.CanalEntry.*;
import com.alibaba.otter.canal.protocol.Message;
import com.github.paicoding.forum.service.article.repository.dao.ArticleDao;
import com.github.paicoding.forum.service.article.service.search.sync.*;
import com.github.paicoding.forum.service.article.service.search.sync.mapper.SearchSyncMapper;
import org.junit.jupiter.api.*;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.*;

class CanalAcknowledgementTest {
    private SearchSyncEngine engine;
    private CanalConnector connector;
    private CanalInboxService inbox;
    @BeforeEach void setup() {
        SearchSyncMapper mapper=mock(SearchSyncMapper.class);
        when(mapper.claimLeader(anyString(),anyString())).thenReturn(1);
        ArticleIndexStore store=mock(ArticleIndexStore.class);
        when(store.index()).thenReturn("test");
        inbox=mock(CanalInboxService.class);
        engine=new SearchSyncEngine(new SearchCanalProperties(),mapper,inbox,store,mock(ArticleDao.class));
        connector=mock(CanalConnector.class);
        ReflectionTestUtils.setField(engine,"connector",connector);
        ReflectionTestUtils.setField(engine,"nextSignalAt",Long.MAX_VALUE);
    }
    @AfterEach void close() { engine.stop(); }
    private Entry entry(EntryType type) { return Entry.newBuilder().setEntryType(type).build(); }

    @Test void acknowledgesEachBatchInOrderOnlyAfterDurableReceipt() {
        when(connector.getWithoutAck(anyInt(),anyLong(),any(TimeUnit.class))).thenReturn(
                new Message(10,Arrays.asList(entry(EntryType.TRANSACTIONBEGIN),entry(EntryType.ROWDATA))),
                new Message(11,Collections.singletonList(entry(EntryType.TRANSACTIONEND))));
        engine.poll();
        verify(inbox,never()).accept(anyList());
        verify(connector,never()).ack(anyLong());
        engine.poll();
        InOrder order=inOrder(inbox,connector);
        order.verify(inbox).accept(anyList());
        order.verify(connector).ack(10);
        order.verify(connector).ack(11);
    }
    @Test void emptyFilteredTransactionsDoNotGenerateMetadataWrites() {
        when(connector.getWithoutAck(anyInt(),anyLong(),any(TimeUnit.class))).thenReturn(
                new Message(12,Arrays.asList(entry(EntryType.TRANSACTIONBEGIN),entry(EntryType.TRANSACTIONEND))));
        engine.poll();
        verify(inbox,never()).accept(anyList());
        verify(connector).ack(12);
    }
    @Test void databaseFailureNeverAcknowledgesTheCanalBatch() {
        when(connector.getWithoutAck(anyInt(),anyLong(),any(TimeUnit.class))).thenReturn(
                new Message(13,Arrays.asList(entry(EntryType.TRANSACTIONBEGIN),entry(EntryType.ROWDATA),entry(EntryType.TRANSACTIONEND))));
        doThrow(new IllegalStateException("database unavailable")).when(inbox).accept(anyList());
        engine.poll();
        verify(connector,never()).ack(anyLong());
        verify(connector).disconnect();
    }
}
