package com.github.paicoding.forum.service.notify.mq;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableRabbit
public class InteractionRabbitConfig {
    public static final String EXCHANGE = "paicoding.interaction.exchange";
    public static final String NOTIFICATION = "paicoding.notification.queue";
    public static final String STATISTICS = "paicoding.statistics.queue";
    public static final String ACTIVITY = "paicoding.activity.queue";
    public static final String DEAD_EXCHANGE = "paicoding.interaction.dead.exchange";
    public static final String DEAD_QUEUE = "paicoding.interaction.dead.queue";

    @Bean
    public Declarables interactionTopology(@Value("${paicoding.mq.queue-type:classic}") String queueType) {
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        TopicExchange dead = new TopicExchange(DEAD_EXCHANGE, true, false);
        Queue notification = queue(NOTIFICATION, queueType);
        Queue statistics = queue(STATISTICS, queueType);
        Queue activity = queue(ACTIVITY, queueType);
        Queue deadQueue = queue(DEAD_QUEUE, queueType);
        return new Declarables(exchange, dead, notification, statistics, activity, deadQueue,
                BindingBuilder.bind(notification).to(exchange).with("interaction.#"),
                BindingBuilder.bind(statistics).to(exchange).with("interaction.#"),
                BindingBuilder.bind(activity).to(exchange).with("interaction.#"),
                BindingBuilder.bind(deadQueue).to(dead).with("#"));
    }

    private Queue queue(String name, String type) {
        if (!"classic".equals(type) && !"quorum".equals(type)) {
            throw new IllegalArgumentException("paicoding.mq.queue-type must be classic or quorum");
        }
        return QueueBuilder.durable(name).withArgument("x-queue-type", type).build();
    }

    @Bean
    public RabbitTemplate interactionRabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory interactionListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(20);
        factory.setDefaultRequeueRejected(false);
        factory.setMissingQueuesFatal(false);
        return factory;
    }
}
