package com.github.paicoding.forum.api.model.event;

import lombok.Value;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 文章访问事件。
 *
 * @author PaiCoding
 */
@Value
@Accessors(fluent = true)
public class ArticleViewedEvent {
    String eventId;
    Long articleId;
    Long authorId;
    Long userId;
    String visitorId;
    Instant occurredAt;
}
