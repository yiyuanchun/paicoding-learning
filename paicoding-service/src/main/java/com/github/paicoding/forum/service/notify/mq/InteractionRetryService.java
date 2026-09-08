package com.github.paicoding.forum.service.notify.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Retry jobs reuse mq_outbox, with a separate delivery key and the original business event key. */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionRetryService {
    private final InteractionMqMapper mapper;
    @Value("${paicoding.mq.max-consume-retries:3}")
    private int maxRetries;

    @Transactional(rollbackFor = Exception.class)
    public void schedule(String consumer, String queue, String eventKey, String payload, int attempt, boolean invalid) {
        int next = attempt + 1;
        boolean dead = invalid || next > maxRetries;
        mapper.enqueue((dead ? "dead:" : "retry:") + consumer + ":" + eventKey + ":" + next,
                eventKey, payload, dead ? InteractionRabbitConfig.DEAD_EXCHANGE : "",
                dead ? consumer : queue, next, dead ? 0 : next == 1 ? 5 : next == 2 ? 30 : 120);
        log.warn("{} consumer={} eventKey={} attempt={}", dead ? "MQ_DEAD" : "MQ_CONSUME_RETRY", consumer, eventKey, next);
    }
}
