package com.github.paicoding.forum.web.hook.filter;

import com.github.paicoding.forum.api.model.context.ReqInfoContext;
import com.github.paicoding.forum.api.model.event.PageViewedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReqRecordFilterPageViewedEventTest {

    @Test
    void shouldPublishLoggedInVisitorUsingUserId() {
        PageViewedEvent event = publishEvent(88L, "device-1");

        assertEquals("u:88", event.visitorId());
        assertEquals(88L, event.userId());
        assertEquals("/article/42", event.path());
        assertNotNull(event.occurredAt());
        UUID.fromString(event.eventId());
    }

    @Test
    void shouldPublishAnonymousVisitorUsingDeviceId() {
        PageViewedEvent event = publishEvent(null, "device-1");

        assertEquals("d:device-1", event.visitorId());
        assertNull(event.userId());
    }

    private PageViewedEvent publishEvent(Long userId, String deviceId) {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ReqRecordFilter filter = new ReqRecordFilter();
        ReflectionTestUtils.setField(filter, "publisher", publisher);
        ReqInfoContext.ReqInfo reqInfo = new ReqInfoContext.ReqInfo();
        reqInfo.setPath("/article/42");
        reqInfo.setUserId(userId);
        reqInfo.setDeviceId(deviceId);

        ReflectionTestUtils.invokeMethod(filter, "publishPageViewedEvent", reqInfo);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(captor.capture());
        return assertInstanceOf(PageViewedEvent.class, captor.getValue());
    }
}
