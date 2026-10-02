package com.github.paicoding.forum.service.statistics.constants;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatisticsRedisKeyTest {

    @Test
    void shouldBuildArticleViewKeys() {
        assertEquals("stats:article:view:total:42", StatisticsRedisKey.articleTotalView(42L));
        assertEquals("stats:article:view:total:*", StatisticsRedisKey.articleTotalViewPattern());
        assertEquals(42L, StatisticsRedisKey.articleIdFromTotalViewKey("stats:article:view:total:42"));
    }

    @Test
    void shouldBuildDailySiteKeysWithBasicIsoDate() {
        LocalDate date = LocalDate.of(2026, 8, 6);

        assertEquals("stats:site:pv:day:20260806", StatisticsRedisKey.dailyPv(date));
        assertEquals("stats:site:uv:day:20260806", StatisticsRedisKey.dailyUv(date));
    }

    @Test
    void shouldBuildWeeklyActivityKey() {
        assertEquals("stats:user:activity:week:2026-W32", StatisticsRedisKey.weeklyActivity("2026-W32"));
    }
}
