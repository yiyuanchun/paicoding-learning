package com.github.paicoding.forum.service.notify.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.paicoding.forum.api.model.enums.NotifyTypeEnum;
import com.github.paicoding.forum.api.model.vo.notify.InteractionMessage;
import com.github.paicoding.forum.service.notify.repository.entity.NotifyMsgDO;
import com.github.paicoding.forum.service.notify.service.NotifyService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionRabbitListener {
    private final ObjectMapper objectMapper;
    private final NotificationConsumerService notifications;
    private final InteractionAuxiliaryConsumer auxiliary;
    private final InteractionRetryService retries;
    private final NotifyService notifyService;

    @RabbitListener(queues = InteractionRabbitConfig.NOTIFICATION, containerFactory = "interactionListenerFactory",
            autoStartup = "${paicoding.mq.consumer-enabled:true}")
    public void notification(Message message, Channel channel) throws IOException {
        receive("notification", InteractionRabbitConfig.NOTIFICATION, message, channel);
    }

    @RabbitListener(queues = InteractionRabbitConfig.STATISTICS, containerFactory = "interactionListenerFactory",
            autoStartup = "${paicoding.mq.consumer-enabled:true}")
    public void statistics(Message message, Channel channel) throws IOException {
        receive("statistics", InteractionRabbitConfig.STATISTICS, message, channel);
    }

    @RabbitListener(queues = InteractionRabbitConfig.ACTIVITY, containerFactory = "interactionListenerFactory",
            autoStartup = "${paicoding.mq.consumer-enabled:true}")
    public void activity(Message message, Channel channel) throws IOException {
        receive("activity", InteractionRabbitConfig.ACTIVITY, message, channel);
    }

    private void receive(String consumer, String queue, Message message, Channel channel) throws IOException {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        String eventKey = "invalid:" + hash(message.getBody());
        long tag = message.getMessageProperties().getDeliveryTag();
        List<NotifyMsgDO> changes = Collections.emptyList();
        boolean validated = false;
        int attempt = 0;
        try {
            Object retryHeader = message.getMessageProperties().getHeaders().get("x-attempt");
            if (retryHeader != null) {
                attempt = Integer.parseInt(retryHeader.toString());
                if (attempt < 0 || attempt > 100) throw new IllegalArgumentException("Invalid retry attempt");
            }
            InteractionMessage event = objectMapper.readValue(message.getBody(), InteractionMessage.class);
            event.validate();
            eventKey = event.getEventKey();
            validated = true;
            if ("notification".equals(consumer)) {
                changes = notifications.consume(event);
            } else {
                auxiliary.consume(consumer, event);
            }
        } catch (Exception error) {
            log.warn("MQ_CONSUME_FAILED consumer={} eventKey={} reason={}", consumer, eventKey, error.toString());
            try {
                retries.schedule(consumer, queue, eventKey, payload, attempt, !validated);
            } catch (Exception unavailable) {
                // No durable handoff: leave the delivery unacknowledged and force recovery/backoff.
                log.error("MQ_RETRY_STORE_FAILED consumer={} eventKey={}", consumer, eventKey, unavailable);
                try { channel.close(); } catch (Exception closeError) { log.debug("Channel recovery", closeError); }
                return;
            }
            // Retry handoff transaction has committed. Its relay also uses confirms + returns.
            channel.basicAck(tag, false);
            return;
        }
        // Deliberately outside the processing catch: an ACK failure must only cause broker redelivery.
        channel.basicAck(tag, false);
        log.info("MQ_CONSUMED consumer={} eventKey={}", consumer, eventKey);
        for (NotifyMsgDO change : changes) {
            if (change.getVisible() == 1) {
                try {
                    NotifyTypeEnum type = change.getType() == 1 ? NotifyTypeEnum.COMMENT :
                            change.getType() == 2 ? NotifyTypeEnum.REPLY : change.getType() == 3 ? NotifyTypeEnum.PRAISE :
                            change.getType() == 4 ? NotifyTypeEnum.COLLECT : NotifyTypeEnum.FOLLOW;
                    notifyService.notifyToUser(change.getNotifyUserId(), type, "您有一条新的" + type.getMsg() + "通知");
                } catch (Exception pushError) {
                    // Offline/best-effort push never changes the durable notification result.
                    log.warn("MQ_PUSH_FAILED eventKey={}", eventKey, pushError);
                }
            }
        }
    }

    private String hash(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
