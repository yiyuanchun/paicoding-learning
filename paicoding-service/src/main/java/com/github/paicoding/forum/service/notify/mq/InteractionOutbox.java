package com.github.paicoding.forum.service.notify.mq;

import com.github.paicoding.forum.api.model.vo.notify.InteractionMessage;
import com.github.paicoding.forum.core.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Called inside the business transaction, never publishes Spring application events. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class InteractionOutbox {
    private final InteractionMqMapper mapper;

    public void lock(String key) {
        mapper.ensureState(key);
        mapper.lockState(key);
    }

    public void append(InteractionMessage event) {
        String key = event.businessKey();
        lock(key);
        mapper.incrementVersion(key);
        event.setAggregateKey(key);
        event.setAggregateVersion(mapper.lockState(key));
        event.setEventKey(key + ":v" + event.getAggregateVersion());
        event.setOccurredAt(System.currentTimeMillis());
        event.validate();
        mapper.enqueue(event.getEventKey(), event.getEventKey(), JsonUtil.toStr(event),
                InteractionRabbitConfig.EXCHANGE, "interaction." + event.getKind().name().toLowerCase(java.util.Locale.ROOT),
                0, 0);
    }
}
