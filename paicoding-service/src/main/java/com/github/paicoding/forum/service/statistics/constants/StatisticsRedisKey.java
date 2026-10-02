package com.github.paicoding.forum.service.statistics.constants;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 统计业务 Redis Key 的统一生成入口。
 *
 * @author PaiCoding
 */
public final class StatisticsRedisKey {

    public static final String ARTICLE_VIEW_TOTAL_PREFIX =
            "stats:article:view:total:";

    private StatisticsRedisKey() {
    }

    public static String articleTotalView(long articleId) {
        return ARTICLE_VIEW_TOTAL_PREFIX + articleId;
    }

    public static String articleTotalViewPattern() {
        return ARTICLE_VIEW_TOTAL_PREFIX + "*";
    }

    public static long articleIdFromTotalViewKey(String key) {
        if (key == null || !key.startsWith(ARTICLE_VIEW_TOTAL_PREFIX)) {
            throw new IllegalArgumentException("invalid article view key: " + key);
        }
        return Long.parseLong(key.substring(ARTICLE_VIEW_TOTAL_PREFIX.length()));
    }

    public static String dailyPv(LocalDate date) {
        return "stats:site:pv:day:"
                + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    public static String dailyUv(LocalDate date) {
        return "stats:site:uv:day:"
                + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    public static String weeklyActivity(String weekId) {
        return "stats:user:activity:week:" + weekId;
    }
}
