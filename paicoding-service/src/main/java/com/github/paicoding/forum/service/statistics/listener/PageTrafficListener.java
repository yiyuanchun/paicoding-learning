package com.github.paicoding.forum.service.statistics.listener;

import com.github.paicoding.forum.api.model.event.PageViewedEvent;
import com.github.paicoding.forum.service.statistics.constants.StatisticsRedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
public class PageTrafficListener {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration KEY_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;

    @Async("statisticsExecutor")
    @EventListener
    public void onPageViewed(PageViewedEvent event) {
        LocalDate day = event.occurredAt()
                .atZone(ZONE)
                .toLocalDate();

        String pvKey = StatisticsRedisKey.dailyPv(day);
        String uvKey = StatisticsRedisKey.dailyUv(day);

        Long pv = redisTemplate.opsForValue().increment(pvKey);
        if (Long.valueOf(1L).equals(pv)) {
            redisTemplate.expire(pvKey, KEY_TTL);
        }

        Boolean uvExists = redisTemplate.hasKey(uvKey);
        redisTemplate.opsForHyperLogLog().add(uvKey, event.visitorId());
        if (!Boolean.TRUE.equals(uvExists)) {
            redisTemplate.expire(uvKey, KEY_TTL);
        }
    }
}
