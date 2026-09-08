package com.github.paicoding.forum.service.notify.mq;

import lombok.Data;

/** Durable delivery job used for initial publication and consumer retry. */
@Data
public class MqDelivery {
    private Long id;
    private String deliveryKey;
    private String eventKey;
    private String payload;
    private String exchangeName;
    private String routingKey;
    private Integer attempt;
    private String lockToken;
}
