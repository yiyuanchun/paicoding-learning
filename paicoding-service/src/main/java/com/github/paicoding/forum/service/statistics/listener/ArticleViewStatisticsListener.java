package com.github.paicoding.forum.service.statistics.listener;

import com.github.paicoding.forum.api.model.enums.DocumentTypeEnum;
import com.github.paicoding.forum.api.model.event.ArticleViewedEvent;
import com.github.paicoding.forum.service.article.repository.mapper.ReadCountMapper;
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
    private final ReadCountMapper readCountMapper;

    @Async("statisticsExecutor")
    @EventListener
    public void onArticleViewed(ArticleViewedEvent event) {
        String articleKey = StatisticsRedisKey.articleTotalView(event.articleId());

        // Redis 只保存文章浏览总量。首次命中时用 MySQL 已有值作为基线，
        // 避免改造后从 0 开始计数覆盖历史浏览量。
        if (redisTemplate.opsForValue().get(articleKey) == null) {
            Integer persistedCount = readCountMapper.queryCount(
                    event.articleId(), DocumentTypeEnum.ARTICLE.getCode());
            redisTemplate.opsForValue().setIfAbsent(
                    articleKey, String.valueOf(persistedCount == null ? 0 : persistedCount));
        }

        redisTemplate.opsForValue().increment(articleKey);
    }
}
