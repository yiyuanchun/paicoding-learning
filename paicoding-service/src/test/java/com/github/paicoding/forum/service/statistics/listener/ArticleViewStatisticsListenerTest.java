package com.github.paicoding.forum.service.statistics.listener;

import com.github.paicoding.forum.api.model.event.ArticleViewedEvent;
import com.github.paicoding.forum.service.statistics.constants.StatisticsRedisKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleViewStatisticsListenerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @InjectMocks
    private ArticleViewStatisticsListener listener;

    @Test
    void shouldIncrementArticleTotalAndCurrentDelta() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        ArticleViewedEvent event = new ArticleViewedEvent(
                "event-1", 42L, 7L, 9L, "visitor-1", Instant.parse("2026-08-06T00:00:00Z"));

        listener.onArticleViewed(event);

        verify(valueOperations).increment(StatisticsRedisKey.articleTotalView(42L));
        verify(hashOperations).increment(
                StatisticsRedisKey.ARTICLE_VIEW_DELTA_CURRENT, "42", 1L);
    }
}
