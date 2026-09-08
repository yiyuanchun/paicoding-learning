package com.github.paicoding.forum.service.notify.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.paicoding.forum.api.model.vo.notify.InteractionMessage;
import com.github.paicoding.forum.service.notify.service.NotifyService;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.File;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Opt-in real MySQL/RabbitMQ/Redis test. Run only against the dedicated compose test services.
 * Creates a random database and uses an isolated RabbitMQ vhost and Redis database 15.
 */
@EnabledIfEnvironmentVariable(named = "PAICODING_MQ_INTEGRATION", matches = "true")
class InteractionBrokerIntegrationTest {
    private static final String DATABASE = "mq_it_" + UUID.randomUUID().toString().replace("-", "");
    private static AnnotationConfigApplicationContext context;
    private static DriverManagerDataSource adminDs;
    private static CachingConnectionFactory rabbit;
    private static LettuceConnectionFactory lettuce;
    private static RabbitTemplate template;
    private static JdbcTemplate jdbc;
    private static StringRedisTemplate redis;
    private static InteractionRabbitListener listener;
    private static OutboxRelay relay;

    @Configuration
    static class Config extends NotificationTransactionTest.Config {
        @Override @Bean DataSource dataSource() {
            return mysql(DATABASE);
        }
        @Bean InteractionRedisProjection redisProjection() { return new InteractionRedisProjection(redis); }
        @Bean InteractionAuxiliaryConsumer auxiliary(InteractionMqMapper mq, InteractionRedisProjection projection) {
            return new InteractionAuxiliaryConsumer(mq, projection);
        }
        @Bean InteractionRetryService retries(InteractionMqMapper mq) { return new InteractionRetryService(mq); }
    }

    private static DriverManagerDataSource mysql(String database) {
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3308/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                "root", "mq-test-only");
    }

    @BeforeAll static void connect() {
        adminDs = mysql("");
        new JdbcTemplate(adminDs).execute("CREATE DATABASE " + DATABASE + " CHARACTER SET utf8mb4");
        jdbc = new JdbcTemplate(mysql(DATABASE));
        jdbc.execute("CREATE TABLE notify_msg(id BIGINT AUTO_INCREMENT PRIMARY KEY,related_id BIGINT DEFAULT 0,"
                + "notify_user_id BIGINT,operate_user_id BIGINT,msg VARCHAR(1024) DEFAULT '',type INT,state INT DEFAULT 0,"
                + "comment_id BIGINT,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO notify_msg(related_id,notify_user_id,operate_user_id,type) VALUES (100,20,10,4),(100,20,10,4),(0,20,10,5)");
        File migration = new File("../paicoding-web/src/main/resources/liquibase/data/update_schema_260907_mq.sql");
        if (!migration.exists()) migration = new File("paicoding-web/src/main/resources/liquibase/data/update_schema_260907_mq.sql");
        new ResourceDatabasePopulator(new FileSystemResource(migration)).execute(mysql(DATABASE));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notify_msg WHERE visible=0", Integer.class));

        lettuce = new LettuceConnectionFactory("127.0.0.1", 6380);
        lettuce.setDatabase(15);
        lettuce.afterPropertiesSet();
        redis = new StringRedisTemplate(lettuce);
        context = new AnnotationConfigApplicationContext(Config.class);
        rabbit = new CachingConnectionFactory("127.0.0.1", 5673);
        rabbit.setUsername("mqtest");
        rabbit.setPassword("mq-test-only");
        rabbit.setVirtualHost("mq-integration");
        rabbit.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        rabbit.setPublisherReturns(true);
        template = new InteractionRabbitConfig().interactionRabbitTemplate(rabbit);
        RabbitAdmin admin = new RabbitAdmin(rabbit);
        for (Declarable item : new InteractionRabbitConfig().interactionTopology("classic").getDeclarables()) {
            if (item instanceof Exchange) admin.declareExchange((Exchange) item);
            else if (item instanceof Queue) admin.declareQueue((Queue) item);
            else if (item instanceof Binding) admin.declareBinding((Binding) item);
        }
        relay = new OutboxRelay(context.getBean(InteractionMqMapper.class), template);
        ReflectionTestUtils.setField(relay, "enabled", true);
        listener = new InteractionRabbitListener(new ObjectMapper(), context.getBean(NotificationConsumerService.class),
                context.getBean(InteractionAuxiliaryConsumer.class), context.getBean(InteractionRetryService.class),
                mock(NotifyService.class));
    }

    @Test void realPersistentFanoutManualAckAndDuplicateConsumption() throws Exception {
        long id = System.currentTimeMillis() % 1000000000;
        InteractionMessage event = NotificationTransactionTest.event(InteractionMessage.Kind.PRAISE, 1, true);
        event.setActorId(id); event.setReceiverId(id + 1); event.setArticleId(id + 2); event.setTargetId(id + 2);
        event.setAggregateKey(event.businessKey());
        event.setEventKey(event.businessKey() + ":v1");
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status -> {
            context.getBean(InteractionOutbox.class).append(event);
            return null;
        });
        assertEquals(0, countForEvent(event, "mq_inbox"));
        relay.publishPending();
        assertEquals("SENT", jdbc.queryForObject("SELECT status FROM mq_outbox WHERE event_key=?", String.class, event.getEventKey()));
        assertEquals(0, countForEvent(event, "mq_inbox")); // No direct or Spring-event notification shortcut.

        consumeOne(InteractionRabbitConfig.NOTIFICATION, event);
        consumeOne(InteractionRabbitConfig.STATISTICS, event);
        consumeOne(InteractionRabbitConfig.ACTIVITY, event);
        assertEquals(3, countForEvent(event, "mq_inbox"));
        assertEquals(1, countForEvent(event, "notify_msg"));
        Object stats = redis.opsForHash().get("article_statistic_" + event.getArticleId(), "praiseCount");
        assertEquals("1", stats);
        // Replay the exact persistent envelope through the broker, keeping the event key unchanged.
        jdbc.update("UPDATE mq_outbox SET status='PENDING',next_retry_time=CURRENT_TIMESTAMP(3) WHERE event_key=?", event.getEventKey());
        relay.publishPending();
        consumeOne(InteractionRabbitConfig.NOTIFICATION, event);
        consumeOne(InteractionRabbitConfig.STATISTICS, event);
        consumeOne(InteractionRabbitConfig.ACTIVITY, event);
        assertEquals(3, countForEvent(event, "mq_inbox"));
        assertEquals(1, countForEvent(event, "notify_msg"));
        assertEquals(stats, redis.opsForHash().get("article_statistic_" + event.getArticleId(), "praiseCount"));

        // Positive publisher confirm for an unroutable message must not mark the outbox SENT.
        context.getBean(InteractionMqMapper.class).enqueue("unroutable:" + id, event.getEventKey(), "{}", "", "absent." + id, 0, 0);
        relay.publishPending();
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM mq_outbox WHERE delivery_key=?", String.class, "unroutable:" + id));
        jdbc.update("DELETE FROM mq_outbox WHERE delivery_key=?", "unroutable:" + id);
    }

    @Test void allInteractionKindsAndCancellationsTravelThroughTheBroker() {
        InteractionMessage.Kind[] kinds = {InteractionMessage.Kind.COMMENT, InteractionMessage.Kind.COMMENT,
                InteractionMessage.Kind.PRAISE, InteractionMessage.Kind.COLLECT, InteractionMessage.Kind.FOLLOW};
        long base = System.currentTimeMillis() % 1000000000;
        for (int i = 0; i < kinds.length; i++) {
            long id = base + 100 + i * 10;
            InteractionMessage event = NotificationTransactionTest.event(kinds[i], 1, true);
            event.setActorId(id); event.setReceiverId(id + 1); event.setTargetId(id + 2);
            event.setArticleId(kinds[i] == InteractionMessage.Kind.FOLLOW ? null : id + 3);
            if (kinds[i] == InteractionMessage.Kind.FOLLOW) event.setTargetId(event.getReceiverId());
            if (i == 1) { event.setParentCommentId(id + 4); event.setParentUserId(id + 5); }
            appendAndDeliver(event);
            int recipients = i == 1 ? 2 : 1;
            assertEquals(recipients, countForEvent(event, "notify_msg"));
            assertEquals(3, countForEvent(event, "mq_inbox"));
            assertEquals(recipients, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notify_msg WHERE last_event_key=? AND visible=1",
                    Integer.class, event.getEventKey()));

            event.setActive(false); event.setPreviousActive(true);
            appendAndDeliver(event);
            assertEquals(2L, event.getAggregateVersion());
            assertEquals(3, countForEvent(event, "mq_inbox"));
            assertEquals(recipients, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notify_msg WHERE last_event_key=? AND visible=0 AND last_event_version=2",
                    Integer.class, event.getEventKey()));
        }
    }

    private void appendAndDeliver(InteractionMessage event) {
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status -> {
            context.getBean(InteractionOutbox.class).append(event);
            return null;
        });
        assertEquals(0, countForEvent(event, "mq_inbox"));
        relay.publishPending();
        consumeOne(InteractionRabbitConfig.NOTIFICATION, event);
        consumeOne(InteractionRabbitConfig.STATISTICS, event);
        consumeOne(InteractionRabbitConfig.ACTIVITY, event);
    }

    private static int countForEvent(InteractionMessage event, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE "
                + ("notify_msg".equals(table) ? "last_event_key" : "event_key") + "=?", Integer.class, event.getEventKey());
    }

    private void consumeOne(String queue, InteractionMessage event) {
        template.execute(channel -> {
            GetResponse response = channel.basicGet(queue, false);
            assertNotNull(response, "Expected a persistent message waiting in " + queue);
            assertEquals(2, response.getProps().getDeliveryMode());
            assertEquals(event.getEventKey(), response.getProps().getMessageId());
            MessageProperties properties = new MessageProperties();
            properties.setDeliveryTag(response.getEnvelope().getDeliveryTag());
            if (response.getProps().getHeaders() != null) properties.getHeaders().putAll(response.getProps().getHeaders());
            Message message = new Message(response.getBody(), properties);
            if (InteractionRabbitConfig.NOTIFICATION.equals(queue)) listener.notification(message, channel);
            else if (InteractionRabbitConfig.STATISTICS.equals(queue)) listener.statistics(message, channel);
            else listener.activity(message, channel);
            assertNull(channel.basicGet(queue, true), "Queue should be empty after manual ACK");
            return null;
        });
    }

    @AfterAll static void close() {
        if (context != null) context.close();
        if (rabbit != null) rabbit.destroy();
        if (lettuce != null) lettuce.destroy();
        if (adminDs != null && DATABASE.matches("mq_it_[a-f0-9]{32}")) {
            new JdbcTemplate(adminDs).execute("DROP DATABASE IF EXISTS " + DATABASE);
        }
    }
}
