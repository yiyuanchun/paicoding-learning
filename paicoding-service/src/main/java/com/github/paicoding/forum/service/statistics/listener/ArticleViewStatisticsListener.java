package com.github.paicoding.forum.service.statistics.listener;

import com.github.paicoding.forum.api.model.event.ArticleViewedEvent;
import com.github.paicoding.forum.service.statistics.constants.StatisticsRedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Listens for article views and updates Redis statistics.
 *
 * @author PaiCoding
 */
@Component
@RequiredArgsConstructor
public class ArticleViewStatisticsListener {

    private final StringRedisTemplate redisTemplate;

    @Async("statisticsExecutor")
    @EventListener
    public void onArticleViewed(ArticleViewedEvent event) {
        String articleKey = StatisticsRedisKey.articleTotalView(event.articleId());

        redisTemplate.opsForValue().increment(articleKey);

        redisTemplate.opsForHash().increment(
                StatisticsRedisKey.ARTICLE_VIEW_DELTA_CURRENT,
                event.articleId().toString(),
                1L
        );
    }
}
