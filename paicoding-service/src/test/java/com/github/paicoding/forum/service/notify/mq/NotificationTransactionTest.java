package com.github.paicoding.forum.service.notify.mq;

import com.github.paicoding.forum.api.model.vo.notify.InteractionMessage;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(NotificationTransactionTest.Config.class)
class NotificationTransactionTest {
    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:notification;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            return ds;
        }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(ds);
            org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(InteractionMqMapper.class);
            configuration.addMapper(NotificationProjectionMapper.class);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }
        @Bean SqlSessionTemplate session(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean InteractionMqMapper mq(SqlSessionTemplate session) { return session.getMapper(InteractionMqMapper.class); }
        @Bean NotificationProjectionMapper projection(SqlSessionTemplate session) { return session.getMapper(NotificationProjectionMapper.class); }
        @Bean NotificationConsumerService service(InteractionMqMapper mq, NotificationProjectionMapper projection) {
            return new NotificationConsumerService(mq, projection);
        }
        @Bean InteractionOutbox outbox(InteractionMqMapper mq) { return new InteractionOutbox(mq); }
    }

    @Autowired DataSource ds;
    @Autowired NotificationConsumerService service;
    @Autowired InteractionOutbox outbox;
    @Autowired InteractionMqMapper mq;
    @Autowired PlatformTransactionManager transactionManager;
    JdbcTemplate jdbc;

    @BeforeEach void schema() {
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE mq_interaction_state(aggregate_key VARCHAR(191) PRIMARY KEY, version BIGINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE mq_inbox(consumer_name VARCHAR(40),event_key VARCHAR(191),processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(consumer_name,event_key))");
        jdbc.execute("CREATE TABLE notify_msg(id BIGINT AUTO_INCREMENT PRIMARY KEY,notification_key VARCHAR(191) UNIQUE,related_id BIGINT,comment_id BIGINT,notify_user_id BIGINT,operate_user_id BIGINT,type INT,state INT,msg VARCHAR(1024),visible INT,last_event_version BIGINT,last_event_key VARCHAR(191),create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE mq_outbox(id BIGINT AUTO_INCREMENT PRIMARY KEY,delivery_key VARCHAR(191) UNIQUE,event_key VARCHAR(191),payload CLOB,exchange_name VARCHAR(100),routing_key VARCHAR(100),attempt INT,status VARCHAR(16),next_retry_time TIMESTAMP)");
    }

    static InteractionMessage event(InteractionMessage.Kind kind, long version, boolean active) {
        InteractionMessage event = new InteractionMessage();
        event.setKind(kind);
        event.setActorId(10L); event.setReceiverId(20L); event.setTargetId(100L);
        event.setTargetType(kind == InteractionMessage.Kind.COMMENT ? "COMMENT" : kind == InteractionMessage.Kind.FOLLOW ? "USER" : "ARTICLE");
        event.setArticleId(kind == InteractionMessage.Kind.FOLLOW ? null : 100L);
        event.setActive(active); event.setPreviousActive(!active); event.setOccurredAt(1770000000000L);
        event.setAggregateKey(event.businessKey()); event.setAggregateVersion(version);
        event.setEventKey(event.businessKey() + ":v" + version);
        event.setContent("example comment");
        return event;
    }

    @Test void allKindsAndReplyHaveExpectedRecipients() {
        for (InteractionMessage.Kind kind : InteractionMessage.Kind.values()) service.consume(event(kind,1,true));
        InteractionMessage reply = event(InteractionMessage.Kind.COMMENT,1,true);
        reply.setTargetId(101L); reply.setAggregateKey(reply.businessKey()); reply.setEventKey(reply.businessKey()+":v1");
        reply.setParentCommentId(99L); reply.setParentUserId(30L);
        service.consume(reply);
        assertEquals(6, count("notify_msg"));
        assertEquals(5, count("mq_inbox"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notify_msg WHERE type=2 AND notify_user_id=30 AND comment_id=101",Integer.class));
    }

    @Test void concurrentRedeliveryCreatesExactlyOneNotification() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (int i=0;i<12;i++) jobs.add(() -> service.consume(event(InteractionMessage.Kind.PRAISE,1,true)).size());
            int applied=0;
            for (Future<Integer> result : workers.invokeAll(jobs)) applied += result.get(15,TimeUnit.SECONDS);
            assertEquals(1,applied);
            assertEquals(1,count("mq_inbox"));
            assertEquals(1,count("notify_msg"));
        } finally { workers.shutdownNow(); }
    }

    @Test void cancellationBeforeCreationCannotResurrectNotification() {
        service.consume(event(InteractionMessage.Kind.PRAISE,2,false));
        service.consume(event(InteractionMessage.Kind.PRAISE,1,true));
        assertEquals(0,jdbc.queryForObject("SELECT visible FROM notify_msg",Integer.class));
        service.consume(event(InteractionMessage.Kind.PRAISE,3,true));
        jdbc.update("UPDATE notify_msg SET state=1");
        service.consume(event(InteractionMessage.Kind.PRAISE,3,true));
        service.consume(event(InteractionMessage.Kind.PRAISE,2,false));
        assertEquals(1,jdbc.queryForObject("SELECT visible FROM notify_msg",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT state FROM notify_msg",Integer.class));
        assertEquals(3L,jdbc.queryForObject("SELECT last_event_version FROM notify_msg",Long.class));
        assertEquals(3,count("mq_inbox"));
    }

    @Test void partialReplyFailureRollsBackInboxAndFirstNotification() {
        jdbc.execute("ALTER TABLE notify_msg ADD CONSTRAINT reject_reply CHECK(type<>2)");
        InteractionMessage reply=event(InteractionMessage.Kind.COMMENT,1,true);
        reply.setParentCommentId(99L); reply.setParentUserId(30L);
        assertThrows(RuntimeException.class, () -> service.consume(reply));
        assertEquals(0,count("mq_inbox"));
        assertEquals(0,count("notify_msg"));
        jdbc.execute("ALTER TABLE notify_msg DROP CONSTRAINT reject_reply");
        assertEquals(2,service.consume(reply).size());
        assertEquals(1,count("mq_inbox"));
    }

    @Test void outboxAndBusinessTransactionRollBackTogether() {
        jdbc.execute("CREATE TABLE business_operation(id INT PRIMARY KEY)");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            jdbc.update("INSERT INTO business_operation VALUES (1)");
            outbox.append(event(InteractionMessage.Kind.COLLECT,1,true));
            throw new IllegalStateException("simulate business rollback");
        }));
        assertEquals(0,count("business_operation"));
        assertEquals(0,count("mq_outbox"));
        assertEquals(0,count("mq_interaction_state"));
        tx.execute(status -> { outbox.append(event(InteractionMessage.Kind.COLLECT,1,true)); return null; });
        assertEquals(1,count("mq_outbox"));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM mq_outbox",String.class));
    }

    @Test void outboxRequiresExistingBusinessTransaction() {
        assertThrows(RuntimeException.class, () -> outbox.append(event(InteractionMessage.Kind.FOLLOW,1,true)));
        assertEquals(0,count("mq_outbox"));
    }

    @Test void retryCanBeRearmedAfterManualRedriveWithoutDuplicatingPendingJobs() {
        mq.enqueue("retry:notification:test:v1:1", "test:v1", "{}", "", "test.queue", 1, 0);
        mq.enqueue("retry:notification:test:v1:1", "test:v1", "{}", "", "test.queue", 1, 0);
        assertEquals(1, count("mq_outbox"));
        jdbc.update("UPDATE mq_outbox SET status='SENT'");
        mq.enqueue("retry:notification:test:v1:1", "test:v1", "{}", "", "test.queue", 1, 0);
        assertEquals(1, count("mq_outbox"));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM mq_outbox", String.class));
    }

    @Test void forgedBusinessKeyIsRejectedBeforeWriting() {
        InteractionMessage event=event(InteractionMessage.Kind.PRAISE,1,true);
        event.setEventKey("unrelated");
        assertThrows(IllegalArgumentException.class, () -> service.consume(event));
        assertEquals(0,count("mq_inbox"));
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
}
