package com.github.paicoding.forum.service.article.service.search;

import com.alibaba.otter.canal.protocol.CanalEntry.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.paicoding.forum.api.model.vo.PageParam;
import com.github.paicoding.forum.service.article.repository.dao.ArticleDao;
import com.github.paicoding.forum.service.article.repository.entity.ArticleSearchDocumentDTO;
import com.github.paicoding.forum.service.article.repository.mapper.ArticleMapper;
import com.github.paicoding.forum.service.article.service.search.impl.ArticleSearchServiceImpl;
import com.github.paicoding.forum.service.article.service.search.sync.*;
import com.github.paicoding.forum.service.article.service.search.sync.mapper.SearchSyncMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Connection;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="search.integration",matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchRealIntegrationTest {
    private JdbcTemplate db;
    private DriverManagerDataSource datasource;
    private SearchSyncMapper mapper;
    private ArticleDao dao;
    private ArticleIndexStore store;
    private ArticleSearchServiceImpl search;
    private CanalInboxService inbox;
    private SearchSyncEngine engine;
    private RestHighLevelClient client;
    private String index;

    @BeforeAll void setup() throws Exception {
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.WARN);
        // Hard-coded isolated endpoint: never permit a production database override.
        datasource=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3309/search_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8","root","search-test-only");
        db=new JdbcTemplate(datasource);
        assertEquals("search_it",db.queryForObject("SELECT DATABASE()",String.class));
        db.update("DELETE FROM article_detail WHERE article_id>=100");
        db.update("DELETE FROM article WHERE id>=100");
        db.update("DELETE FROM column_article");
        db.update("UPDATE user_info SET user_name='Search Author' WHERE user_id=1");
        db.update("UPDATE article SET title=?,summary=? WHERE id=1","Elasticsearch 全文检索教程","Canal 增量同步测试");
        db.update("UPDATE article_detail SET content=? WHERE article_id=1","Elasticsearch 支持关键字匹配。分布式系统使用消息队列。");
        assertTrue(db.queryForObject("SELECT content FROM article_detail WHERE article_id=1 LIMIT 1",String.class).contains("消息队列"));
        org.apache.ibatis.session.Configuration config=new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(SearchSyncMapper.class);
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();
        factory.setDataSource(datasource);
        factory.setConfiguration(config);
        factory.setMapperLocations(new ClassPathResource("mapper/ArticleMapper.xml"));
        SqlSessionTemplate session=new SqlSessionTemplate(factory.getObject());
        mapper=session.getMapper(SearchSyncMapper.class);
        dao=new ArticleDao();
        ReflectionTestUtils.setField(dao,"articleMapper",session.getMapper(ArticleMapper.class));
        client=new RestHighLevelClient(RestClient.builder(new HttpHost("127.0.0.1",9202,"http")));
        DefaultListableBeanFactory beans=new DefaultListableBeanFactory();
        beans.registerSingleton("es",client);
        store=new ArticleIndexStore(beans.getBeanProvider(RestHighLevelClient.class),new ObjectMapper());
        index="search_it_"+System.currentTimeMillis();
        ReflectionTestUtils.setField(store,"index",index);
        ReflectionTestUtils.setField(store,"analyzer","cjk");
        ReflectionTestUtils.setField(store,"searchAnalyzer","cjk");
        SearchCanalProperties props=new SearchCanalProperties();
        props.setEnabled(true); props.setDatabase("search_it"); props.setPort(11112); props.setDestination("example");
        inbox=new CanalInboxService(mapper,props,store,new DataSourceTransactionManager(datasource));
        engine=new SearchSyncEngine(props,mapper,inbox,store,dao);
        beans.registerSingleton("sync",engine);
        search=new ArticleSearchServiceImpl(store,beans.getBeanProvider(SearchSyncEngine.class));
        mapper.ensureCheckpoint(index);
    }

    @AfterAll void close() throws Exception {
        if(engine!=null) engine.stop();
        if(client!=null) client.close();
    }

    private PageParam page() { return PageParam.newPageInstance(); }
    private ArticleSearchResult find(String key) { return search.searchOnlineArticleIds(key,page()); }
    private void tick() {
        engine.poll();
        for(int i=0;i<30 && inbox.routeNext();i++) { }
        engine.project();
        try { store.request("POST","/"+index+"/_refresh",null); }
        catch(Exception e) { throw new RuntimeException(e); }
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline=System.currentTimeMillis()+90000;
        while(System.currentTimeMillis()<deadline) {
            tick();
            if(condition.getAsBoolean()) return;
            Thread.sleep(250);
        }
        fail("Timed out: "+engine.status());
    }
    private void insert(long id,String title,String body) {
        db.update("INSERT INTO article(id,user_id,title,summary) VALUES(?,1,?,'public summary')",id,title);
        db.update("INSERT INTO article_detail(article_id,version,content) VALUES(?,1,?)",id,body);
    }
    private long version(long id) {
        return db.queryForObject("SELECT requested_version FROM search_index_task WHERE index_name=? AND article_id=?",Long.class,index,id);
    }

    @Test @Order(1) void backfillKeywordFuzzyChineseAndRealHighlight() throws Exception {
        await(()->!find("elastcsearch").getArticleIds().isEmpty());
        ArticleSearchResult result=find("elastcsearch");
        assertTrue(result.getArticleIds().contains(1L));
        assertTrue(result.getTitleHighlights().get(1L).toLowerCase().contains("<mark>elasticsearch</mark>"));
        assertTrue(find("消息队列").getArticleIds().contains(1L));
        assertTrue(find("notpresentzzzz").getArticleIds().isEmpty());
        assertTrue(mapper.initialized(index)==1);
    }

    @Test @Order(2) void directSqlLatestBodyAndAuthorFanout() throws Exception {
        insert(100,"Canal pipeline","originaltoken");
        await(()->find("originaltoken").getArticleIds().contains(100L));
        db.update("INSERT INTO article_detail(article_id,version,content) VALUES(100,2,'incrementaltoken Elasticsearch')");
        await(()->find("incrementaltoken").getArticleIds().contains(100L));
        assertFalse(find("originaltoken").getArticleIds().contains(100L));
        db.update("UPDATE user_info SET user_name='Renamed Author' WHERE user_id=1");
        await(()-> {
            try { return store.request("GET","/"+index+"/_doc/100",null).toString().contains("Renamed Author"); }
            catch(Exception e) { return false; }
        });
    }

    @Test @Order(3) void rolledBackMysqlTransactionNeverCreatesDocument() throws Exception {
        try(Connection c=datasource.getConnection()) {
            c.setAutoCommit(false);
            c.createStatement().executeUpdate("INSERT INTO article(id,user_id,title) VALUES(101,1,'rollbacktoken')");
            for(int i=0;i<5;i++) tick();
            assertTrue(find("rollbacktoken").getArticleIds().isEmpty());
            c.rollback();
        }
        for(int i=0;i<5;i++) tick();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM search_index_task WHERE index_name=? AND article_id=101",Integer.class,index));
    }

    @Test @Order(4) void splitTransactionAndReplayAreIdempotent() throws Exception {
        Entry begin=Entry.newBuilder().setEntryType(EntryType.TRANSACTIONBEGIN)
                .setHeader(Header.newBuilder().setLogfileName("unit-bin.000001").setLogfileOffset(10)).build();
        RowData row=RowData.newBuilder().addAfterColumns(Column.newBuilder().setName("id").setValue("100")).build();
        Entry change=Entry.newBuilder().setEntryType(EntryType.ROWDATA)
                .setHeader(Header.newBuilder().setSchemaName("search_it").setTableName("article").setLogfileName("unit-bin.000001").setLogfileOffset(20))
                .setStoreValue(RowChange.newBuilder().setEventType(EventType.UPDATE).addRowDatas(row).build().toByteString()).build();
        Entry end=Entry.newBuilder().setEntryType(EntryType.TRANSACTIONEND)
                .setHeader(Header.newBuilder().setLogfileName("unit-bin.000001").setLogfileOffset(30)).build();
        long previous=version(100);
        inbox.accept(Arrays.asList(begin,change));
        while(inbox.routeNext()) { }
        assertEquals(previous,version(100),"Uncommitted batch must not become runnable");
        inbox.accept(Collections.singletonList(end));
        while(inbox.routeNext()) { }
        assertEquals(previous+1,version(100));
        inbox.accept(Arrays.asList(begin,change,end));
        while(inbox.routeNext()) { }
        assertEquals(previous+1,version(100),"Replay must not advance the version twice");
        inbox.accept(Arrays.asList(change,end));
        while(inbox.routeNext()) { }
        assertEquals(previous+1,version(100),"Lost ACK on a continuation batch must not require BEGIN again");
    }

    @Test @Order(5) void staleWritesAndCrashAfterEsSuccessCannotOverwriteLatestState() throws Exception {
        await(()->find("incrementaltoken").getArticleIds().contains(100L));
        engine.enqueueRepair(100);
        String token=UUID.randomUUID().toString();
        assertEquals(1,mapper.claim(index,100,token));
        SearchTask task=mapper.claimed(index,100,token);
        store.write(100,task.getRequestedVersion(),dao.queryArticleSearchDocument(100L));
        // Simulate process death after ES success and lease expiration before DB completion.
        db.update("UPDATE search_index_task SET locked_until='2000-01-01' WHERE index_name=? AND article_id=100",index);
        tick();
        assertEquals(task.getRequestedVersion(),db.queryForObject("SELECT completed_version FROM search_index_task WHERE index_name=? AND article_id=100",Long.class,index));
        ArticleSearchDocumentDTO stale=dao.queryArticleSearchDocument(100L);
        stale.setTitle("staleoverwrite");
        store.write(100,1,stale);
        store.request("POST","/"+index+"/_refresh",null);
        assertFalse(find("staleoverwrite").getArticleIds().contains(100L));
    }

    @Test @Order(6) void restrictedContentAndDeletesDisappearThroughBinlog() throws Exception {
        insert(102,"publicmetadata","secrettoken");
        await(()->find("secrettoken").getArticleIds().contains(102L));
        db.update("UPDATE article SET read_type=4 WHERE id=102");
        await(()->find("secrettoken").getArticleIds().isEmpty());
        assertTrue(find("publicmetadata").getArticleIds().contains(102L));
        db.update("UPDATE article SET status=0 WHERE id=102");
        await(()->find("publicmetadata").getArticleIds().isEmpty());
        db.update("DELETE FROM article WHERE id=100");
        await(()->find("incrementaltoken").getArticleIds().isEmpty());
        ArticleSearchDocumentDTO stale=new ArticleSearchDocumentDTO();
        stale.setStatus(1); stale.setDeleted(0); stale.setReadType(0); stale.setTitle("resurrecttoken");
        store.write(100,1,stale);
        store.request("POST","/"+index+"/_refresh",null);
        assertTrue(find("resurrecttoken").getArticleIds().isEmpty());
    }

    @Test @Order(7) void esOutageRetainsTasksAndAutomaticallyRecovers() throws Exception {
        // Stop only the test-owned ES container; always restart it in finally.
        docker("stop","-t","1","paicoding-search-it-elasticsearch-1");
        try {
            insert(103,"recoverytoken","persisted during ES outage");
            long deadline=System.currentTimeMillis()+30000;
            while(System.currentTimeMillis()<deadline) {
                engine.poll();
                while(inbox.routeNext()) { }
                engine.project();
                Integer pending=db.queryForObject("SELECT COUNT(*) FROM search_index_task WHERE index_name=? AND article_id=103 AND retry_count>0 AND requested_version>completed_version",Integer.class,index);
                if(pending>0) break;
                Thread.sleep(300);
            }
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM search_index_task WHERE index_name=? AND article_id=103 AND retry_count>0 AND requested_version>completed_version",Integer.class,index));
        } finally { docker("start","paicoding-search-it-elasticsearch-1"); }
        long deadline=System.currentTimeMillis()+90000;
        while(System.currentTimeMillis()<deadline) {
            try { store.request("GET","/_cluster/health",null); break; }
            catch(Exception e) { Thread.sleep(500); }
        }
        await(()->find("recoverytoken").getArticleIds().contains(103L));
    }

    @Test @Order(8) void canalServerRestartResumesPersistedPosition() throws Exception {
        docker("restart","-t","1","paicoding-search-it-canal-1");
        insert(104,"resumetoken","written while Canal restarts");
        await(()->find("resumetoken").getArticleIds().contains(104L));
        assertEquals(0L,mapper.inboxPending(index));
    }

    private void docker(String...args) throws Exception {
        List<String> command=new ArrayList<>();
        command.add("docker"); Collections.addAll(command,args);
        Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        try(java.io.InputStream output=process.getInputStream()) { byte[] b=new byte[1024]; while(output.read(b)!=-1) { } }
        assertEquals(0,process.waitFor(),"Docker operation failed: "+command);
    }
}
