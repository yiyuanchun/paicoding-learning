package com.github.paicoding.forum.api.model.event;

import lombok.Value;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * 用户活跃度变更事件。
 *
 * @author PaiCoding
 */
@Value
@Accessors(fluent = true)
public class UserActivityEvent {
    String eventId;
    Long userId;
    ActivityType activityType;
    Long businessId;
    int scoreDelta;
    Instant occurredAt;
}
