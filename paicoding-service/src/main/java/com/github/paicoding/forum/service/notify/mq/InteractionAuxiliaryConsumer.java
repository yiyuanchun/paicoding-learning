package com.github.paicoding.forum.service.notify.mq;

import com.github.paicoding.forum.api.model.vo.notify.InteractionMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InteractionAuxiliaryConsumer {
    private final InteractionMqMapper mapper;
    private final InteractionRedisProjection redis;

    @Transactional(rollbackFor = Exception.class)
    public void consume(String consumer, InteractionMessage event) {
        event.validate();
        String key = consumer + ":" + event.getAggregateKey();
        mapper.ensureState(key);
        mapper.lockState(key);
        if (mapper.consumed(consumer, event.getEventKey()) != 0) {
            return;
        }
        // Redis failure rolls back the inbox. A Redis success followed by DB failure is safe:
        // replay reaches the same atomic Lua deduplication/version guard.
        if ("statistics".equals(consumer)) {
            redis.statistics(event);
        } else if ("activity".equals(consumer)) {
            redis.activity(event);
        } else {
            throw new IllegalArgumentException("Unknown consumer");
        }
        mapper.consume(consumer, event.getEventKey());
    }
}
