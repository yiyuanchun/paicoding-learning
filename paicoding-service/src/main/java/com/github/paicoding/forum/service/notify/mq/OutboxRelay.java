package com.github.paicoding.forum.service.notify.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class OutboxRelay {
    private final InteractionMqMapper mapper;
    private final RabbitTemplate template;
    @Value("${paicoding.mq.publisher-enabled:true}")
    private boolean enabled;

    public OutboxRelay(InteractionMqMapper mapper,
                       @Qualifier("interactionRabbitTemplate") RabbitTemplate template) {
        this.mapper = mapper;
        this.template = template;
    }

    @Scheduled(initialDelay = 10000, fixedDelayString = "${paicoding.mq.publish-interval-ms:2000}")
    public void publishPending() {
        if (!enabled) {
            return;
        }
        for (MqDelivery delivery : mapper.pending()) {
            String token = UUID.randomUUID().toString();
            if (mapper.claim(delivery.getId(), token) == 0) {
                continue;
            }
            try {
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setContentEncoding("UTF-8");
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setMessageId(delivery.getEventKey());
                properties.setHeader("x-attempt", delivery.getAttempt());
                properties.setHeader("x-delivery-key", delivery.getDeliveryKey());
                Message message = new Message(delivery.getPayload().getBytes(StandardCharsets.UTF_8), properties);
                CorrelationData correlation = new CorrelationData(token);
                template.send(delivery.getExchangeName(), delivery.getRoutingKey(), message, correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                // A mandatory unroutable message can still receive a positive publisher confirm.
                if (!confirm.isAck() || correlation.getReturned() != null) {
                    throw new IllegalStateException("NACK or unroutable: " + confirm.getReason());
                }
                mapper.sent(delivery.getId(), token);
                log.info("MQ_PUBLISHED eventKey={} destination={}:{} attempt={}",
                        delivery.getEventKey(), delivery.getExchangeName(), delivery.getRoutingKey(), delivery.getAttempt());
            } catch (Exception error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                mapper.failed(delivery.getId(), token, abbreviate(error.toString()), 10);
                log.warn("MQ_PUBLISH_RETRY eventKey={} reason={}", delivery.getEventKey(), error.toString());
                break;
            }
        }
    }

    static String abbreviate(String text) {
        return text == null ? "" : text.substring(0, Math.min(1000, text.length()));
    }
}
