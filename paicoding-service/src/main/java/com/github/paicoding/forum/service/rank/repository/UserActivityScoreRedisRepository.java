package com.github.paicoding.forum.service.rank.repository;

import com.github.paicoding.forum.core.util.DateUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.Arrays;

/**
 * Atomically updates a user's daily action record and the related activity rankings.
 */
@Repository
@RequiredArgsConstructor
public class UserActivityScoreRedisRepository {
    private static final DefaultRedisScript<Long> UPDATE_ACTIVITY_SCORE_SCRIPT;
    private static final long SCRIPT_APPLIED = 1L;

    static {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/update_user_activity_score.lua"));
        script.setResultType(Long.class);
        UPDATE_ACTIVITY_SCORE_SCRIPT = script;
    }

    private final StringRedisTemplate redisTemplate;

    /**
     * Applies an activity score only when the corresponding daily action state changes.
     * All hash and sorted-set operations are executed in one Redis Lua script.
     */
    public boolean updateScore(String userActionKey,
                               String todayRankKey,
                               String monthRankKey,
                               String field,
                               Long userId,
                               int scoreDelta) {
        Long result = redisTemplate.execute(
                UPDATE_ACTIVITY_SCORE_SCRIPT,
                Arrays.asList(userActionKey, todayRankKey, monthRankKey),
                field,
                String.valueOf(userId),
                String.valueOf(scoreDelta),
                String.valueOf(31 * DateUtil.ONE_DAY_SECONDS),
                String.valueOf(31 * DateUtil.ONE_DAY_SECONDS),
                String.valueOf(12 * DateUtil.ONE_MONTH_SECONDS)
        );
        return result != null && result == SCRIPT_APPLIED;
    }
}
