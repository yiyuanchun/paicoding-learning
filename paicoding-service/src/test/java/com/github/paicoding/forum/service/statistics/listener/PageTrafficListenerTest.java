package com.github.paicoding.forum.service.statistics.listener;

import com.github.paicoding.forum.api.model.event.PageViewedEvent;
import com.github.paicoding.forum.service.statistics.constants.StatisticsRedisKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PageTrafficListenerTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HyperLogLogOperations<String, String> hyperLogLogOperations;

    @InjectMocks
    private PageTrafficListener listener;

    @Test
    void shouldIncrementTrafficAndSetTtlWhenDailyKeysAreCreated() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHyperLogLog()).thenReturn(hyperLogLogOperations);
        when(valueOperations.increment("stats:site:pv:day:20260807")).thenReturn(1L);
        when(redisTemplate.hasKey("stats:site:uv:day:20260807")).thenReturn(false);
        PageViewedEvent event = eventAt("2026-08-06T16:30:00Z");

        listener.onPageViewed(event);

        LocalDate day = LocalDate.of(2026, 8, 7);
        String pvKey = StatisticsRedisKey.dailyPv(day);
        String uvKey = StatisticsRedisKey.dailyUv(day);
        verify(valueOperations).increment(pvKey);
        verify(hyperLogLogOperations).add(uvKey, "d:visitor-1");
        verify(redisTemplate).expire(pvKey, Duration.ofDays(30));
        verify(redisTemplate).expire(uvKey, Duration.ofDays(30));
    }

    @Test
    void shouldNotExtendTtlForExistingDailyKeys() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHyperLogLog()).thenReturn(hyperLogLogOperations);
        when(valueOperations.increment(anyString())).thenReturn(2L);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        listener.onPageViewed(eventAt("2026-08-07T08:00:00Z"));

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    private PageViewedEvent eventAt(String occurredAt) {
        return new PageViewedEvent(
                "event-1", "/article/42", null, "d:visitor-1", Instant.parse(occurredAt));
    }
}
