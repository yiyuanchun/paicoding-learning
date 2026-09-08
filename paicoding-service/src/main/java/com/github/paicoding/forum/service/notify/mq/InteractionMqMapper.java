package com.github.paicoding.forum.service.notify.mq;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface InteractionMqMapper {
    @Insert("INSERT INTO mq_interaction_state (aggregate_key, version) VALUES (#{key},0) ON DUPLICATE KEY UPDATE aggregate_key=VALUES(aggregate_key)")
    void ensureState(String key);

    @Select("SELECT version FROM mq_interaction_state WHERE aggregate_key=#{key} FOR UPDATE")
    long lockState(String key);

    @Update("UPDATE mq_interaction_state SET version=version+1 WHERE aggregate_key=#{key}")
    void incrementVersion(String key);

    @Insert("INSERT INTO mq_outbox (delivery_key,event_key,payload,exchange_name,routing_key,attempt,status,next_retry_time) VALUES (#{deliveryKey},#{eventKey},#{payload},#{exchangeName},#{routingKey},#{attempt},'PENDING',TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(3))) ON DUPLICATE KEY UPDATE next_retry_time=CASE WHEN status='SENT' THEN VALUES(next_retry_time) ELSE next_retry_time END,status=CASE WHEN status='SENT' THEN 'PENDING' ELSE status END")
    void enqueue(@Param("deliveryKey") String deliveryKey, @Param("eventKey") String eventKey,
                 @Param("payload") String payload, @Param("exchangeName") String exchangeName,
                 @Param("routingKey") String routingKey, @Param("attempt") int attempt, @Param("delay") int delay);

    @Select("SELECT id,delivery_key,event_key,payload,exchange_name,routing_key,attempt FROM mq_outbox WHERE (status='PENDING' AND next_retry_time<=CURRENT_TIMESTAMP(3)) OR (status='SENDING' AND locked_until<CURRENT_TIMESTAMP(3)) ORDER BY id LIMIT 20")
    List<MqDelivery> pending();

    @Update("UPDATE mq_outbox SET status='SENDING',lock_token=#{token},locked_until=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(3)) WHERE id=#{id} AND ((status='PENDING' AND next_retry_time<=CURRENT_TIMESTAMP(3)) OR (status='SENDING' AND locked_until<CURRENT_TIMESTAMP(3)))")
    int claim(@Param("id") long id, @Param("token") String token);

    @Update("UPDATE mq_outbox SET status='SENT',sent_at=CURRENT_TIMESTAMP(3),lock_token=NULL,locked_until=NULL,last_error=NULL WHERE id=#{id} AND lock_token=#{token}")
    void sent(@Param("id") long id, @Param("token") String token);

    @Update("UPDATE mq_outbox SET status='PENDING',retry_count=retry_count+1,next_retry_time=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(3)),last_error=#{error},lock_token=NULL,locked_until=NULL WHERE id=#{id} AND lock_token=#{token}")
    void failed(@Param("id") long id, @Param("token") String token, @Param("error") String error, @Param("delay") int delay);

    @Select("SELECT COUNT(*) FROM mq_inbox WHERE consumer_name=#{consumer} AND event_key=#{eventKey}")
    int consumed(@Param("consumer") String consumer, @Param("eventKey") String eventKey);

    @Insert("INSERT INTO mq_inbox (consumer_name,event_key) VALUES (#{consumer},#{eventKey})")
    void consume(@Param("consumer") String consumer, @Param("eventKey") String eventKey);
}
