package com.github.paicoding.forum.service.statistics.listener;

import com.github.paicoding.forum.api.model.event.ArticleViewedEvent;
import com.github.paicoding.forum.service.statistics.service.CountService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Handles article views using a single Redis String total. */
@Component
@RequiredArgsConstructor
public class ArticleViewStatisticsListener {
    private final CountService countService;

    @Async("statisticsExecutor")
    @EventListener
    public void onArticleViewed(ArticleViewedEvent event) {
        countService.incrArticleReadCount(event.authorId(), event.articleId());
    }
}
