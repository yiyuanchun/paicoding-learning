package com.github.paicoding.forum.api.model.event;

import lombok.Value;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 页面访问事件。
 *
 * @author PaiCoding
 */
@Value
@Accessors(fluent = true)
public class PageViewedEvent {
    String eventId;
    String path;
    Long userId;
    String visitorId;
    Instant occurredAt;
}
