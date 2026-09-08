package com.github.paicoding.forum.service.notify.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.paicoding.forum.api.model.vo.notify.InteractionMessage;
import com.github.paicoding.forum.service.notify.service.NotifyService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InteractionDeliveryTest {
    final ObjectMapper json = new ObjectMapper();
    NotificationConsumerService notifications;
    InteractionAuxiliaryConsumer auxiliary;
    InteractionRetryService retry;
    Channel channel;
    InteractionRabbitListener listener;

    @BeforeEach void setup() {
        notifications=mock(NotificationConsumerService.class);
        auxiliary=mock(InteractionAuxiliaryConsumer.class);
        retry=mock(InteractionRetryService.class);
        channel=mock(Channel.class);
        listener=new InteractionRabbitListener(json,notifications,auxiliary,retry,mock(NotifyService.class));
    }

    private Message message() throws Exception {
        MessageProperties props=new MessageProperties();
        props.setDeliveryTag(7L);
        return new Message(json.writeValueAsBytes(NotificationTransactionTest.event(InteractionMessage.Kind.PRAISE,1,true)),props);
    }

    @Test void ackOnlyAfterTransactionalServiceReturns() throws Exception {
        when(notifications.consume(any())).thenReturn(Collections.emptyList());
        listener.notification(message(),channel);
        InOrder order=inOrder(notifications,channel);
        order.verify(notifications).consume(any());
        order.verify(channel).basicAck(7L,false);
    }

    @Test void failedConsumptionHandsOffDurablyBeforeAck() throws Exception {
        when(notifications.consume(any())).thenThrow(new IllegalStateException("db down"));
        listener.notification(message(),channel);
        InOrder order=inOrder(retry,channel);
        order.verify(retry).schedule(eq("notification"),eq(InteractionRabbitConfig.NOTIFICATION),anyString(),anyString(),eq(0),eq(false));
        order.verify(channel).basicAck(7L,false);
    }

    @Test void retryStorageFailureNeverAcknowledgesOriginal() throws Exception {
        when(notifications.consume(any())).thenThrow(new IllegalStateException("db down"));
        doThrow(new IllegalStateException("outbox down")).when(retry).schedule(anyString(),anyString(),anyString(),anyString(),anyInt(),anyBoolean());
        listener.notification(message(),channel);
        verify(channel,never()).basicAck(anyLong(),anyBoolean());
        verify(channel).close();
    }

    @Test void ackFailureDoesNotCreateExtraRetryJob() throws Exception {
        when(notifications.consume(any())).thenReturn(Collections.emptyList());
        doThrow(new java.io.IOException("connection lost")).when(channel).basicAck(7L,false);
        assertThrows(java.io.IOException.class,()->listener.notification(message(),channel));
        verifyNoInteractions(retry);
    }

    @Test void invalidJsonGoesToDurableDeadLetterHandoff() throws Exception {
        MessageProperties props=new MessageProperties(); props.setDeliveryTag(9L);
        listener.notification(new Message("{invalid".getBytes(StandardCharsets.UTF_8),props),channel);
        verify(retry).schedule(eq("notification"),anyString(),startsWith("invalid:"),anyString(),eq(0),eq(true));
        verifyNoInteractions(notifications);
        verify(channel).basicAck(9L,false);
    }

    @Test void publisherSendsPersistentMessageWithStableBusinessId() throws Exception {
        InteractionMqMapper mapper=mock(InteractionMqMapper.class);
        RabbitTemplate template=mock(RabbitTemplate.class);
        MqDelivery delivery=new MqDelivery();
        delivery.setId(1L); delivery.setEventKey("praise:article:100:actor:10:v1"); delivery.setDeliveryKey(delivery.getEventKey());
        delivery.setPayload("{}"); delivery.setAttempt(0); delivery.setExchangeName(InteractionRabbitConfig.EXCHANGE); delivery.setRoutingKey("interaction.praise");
        when(mapper.pending()).thenReturn(Collections.singletonList(delivery));
        when(mapper.claim(eq(1L),anyString())).thenReturn(1);
        doAnswer(invocation -> {
            CorrelationData correlation=invocation.getArgument(3);
            correlation.getFuture().set(new CorrelationData.Confirm(true,null));
            return null;
        }).when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        OutboxRelay relay=new OutboxRelay(mapper,template);
        ReflectionTestUtils.setField(relay,"enabled",true);
        relay.publishPending();
        ArgumentCaptor<Message> capture=ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq(InteractionRabbitConfig.EXCHANGE),eq("interaction.praise"),capture.capture(),any(CorrelationData.class));
        assertEquals(MessageDeliveryMode.PERSISTENT,capture.getValue().getMessageProperties().getDeliveryMode());
        assertEquals(delivery.getEventKey(),capture.getValue().getMessageProperties().getMessageId());
        verify(mapper).sent(eq(1L),anyString());
    }

    @Test void unroutableMessageIsRetriedEvenWithPositiveConfirm() {
        InteractionMqMapper mapper=mock(InteractionMqMapper.class);
        RabbitTemplate template=mock(RabbitTemplate.class);
        MqDelivery delivery=new MqDelivery(); delivery.setId(1L); delivery.setEventKey("event");
        delivery.setDeliveryKey("event"); delivery.setPayload("{}"); delivery.setAttempt(0);
        delivery.setExchangeName("missing-binding"); delivery.setRoutingKey("key");
        when(mapper.pending()).thenReturn(Collections.singletonList(delivery));
        when(mapper.claim(eq(1L),anyString())).thenReturn(1);
        doAnswer(invocation -> {
            CorrelationData correlation=invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(invocation.getArgument(2),312,"NO_ROUTE","missing-binding","key"));
            correlation.getFuture().set(new CorrelationData.Confirm(true,null));
            return null;
        }).when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        OutboxRelay relay=new OutboxRelay(mapper,template); ReflectionTestUtils.setField(relay,"enabled",true);
        relay.publishPending();
        verify(mapper,never()).sent(anyLong(),anyString());
        verify(mapper).failed(eq(1L),anyString(),anyString(),eq(10));
    }

    @Test void retriesUseOriginalKeyAndTargetOnlyFailedConsumer() {
        InteractionMqMapper mapper=mock(InteractionMqMapper.class);
        InteractionRetryService service=new InteractionRetryService(mapper);
        ReflectionTestUtils.setField(service,"maxRetries",3);
        service.schedule("notification",InteractionRabbitConfig.NOTIFICATION,"event","{}",0,false);
        verify(mapper).enqueue("retry:notification:event:1","event","{}","",InteractionRabbitConfig.NOTIFICATION,1,5);
        service.schedule("notification",InteractionRabbitConfig.NOTIFICATION,"event","{}",3,false);
        verify(mapper).enqueue("dead:notification:event:4","event","{}",InteractionRabbitConfig.DEAD_EXCHANGE,"notification",4,0);
    }
}
