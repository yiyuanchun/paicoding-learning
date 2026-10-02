package com.github.paicoding.forum.service.statistics.listener;

import com.github.paicoding.forum.api.model.enums.DocumentTypeEnum;
import com.github.paicoding.forum.api.model.event.ArticleViewedEvent;
import com.github.paicoding.forum.service.article.repository.mapper.ReadCountMapper;
import com.github.paicoding.forum.service.statistics.constants.StatisticsRedisKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleViewStatisticsListenerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ReadCountMapper readCountMapper;

    @InjectMocks
    private ArticleViewStatisticsListener listener;

    @Test
    void shouldIncrementExistingArticleViewString() {
        String key = StatisticsRedisKey.articleTotalView(42L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn("12");

        listener.onArticleViewed(event());

        verify(valueOperations).increment(key);
        verify(readCountMapper, never()).queryCount(42L, DocumentTypeEnum.ARTICLE.getCode());
    }

    @Test
    void shouldInitializeFromMysqlBeforeFirstIncrement() {
        String key = StatisticsRedisKey.articleTotalView(42L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(null);
        when(readCountMapper.queryCount(42L, DocumentTypeEnum.ARTICLE.getCode())).thenReturn(12);

        listener.onArticleViewed(event());

        verify(valueOperations).setIfAbsent(key, "12");
        verify(valueOperations).increment(key);
    }

    private ArticleViewedEvent event() {
        return new ArticleViewedEvent(
                "event-1", 42L, 7L, 9L, "visitor-1", Instant.parse("2026-08-06T00:00:00Z"));
    }
}
