package com.github.paicoding.forum.service.statistics.constants;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** 统计业务 Redis Key 的统一生成入口。 */
public final class StatisticsRedisKey {
    // v2 holds the real total; the former String key counted only post-event visits.
    public static final String ARTICLE_VIEW_TOTAL_PREFIX = "stats:article:view:total:v2:";
    private static final String LEGACY_ARTICLE_VIEW_TOTAL_PREFIX = "stats:article:view:total:";

    private StatisticsRedisKey() {
    }

    public static String articleTotalView(long articleId) {
        return ARTICLE_VIEW_TOTAL_PREFIX + articleId;
    }

    public static String legacyArticleTotalView(long articleId) {
        return LEGACY_ARTICLE_VIEW_TOTAL_PREFIX + articleId;
    }

    public static String dailyPv(LocalDate date) {
        return "stats:site:pv:day:" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    public static String dailyUv(LocalDate date) {
        return "stats:site:uv:day:" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    public static String weeklyActivity(String weekId) {
        return "stats:user:activity:week:" + weekId;
    }
}
