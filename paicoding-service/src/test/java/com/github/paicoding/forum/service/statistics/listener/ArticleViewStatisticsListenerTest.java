package com.github.paicoding.forum.service.statistics.listener;

import com.github.paicoding.forum.api.model.event.ArticleViewedEvent;
import com.github.paicoding.forum.service.statistics.service.CountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ArticleViewStatisticsListenerTest {
    @Mock private CountService countService;
    @InjectMocks private ArticleViewStatisticsListener listener;

    @Test
    void shouldIncrementOnlyArticleStringTotal() {
        ArticleViewedEvent event = new ArticleViewedEvent(
                "event-1", 42L, 7L, 9L, "visitor-1", Instant.parse("2026-08-06T00:00:00Z"));
        listener.onArticleViewed(event);
        verify(countService).incrArticleReadCount(7L, 42L);
    }
}
