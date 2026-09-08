package com.github.paicoding.forum.service.rank.repository;

import com.github.paicoding.forum.core.util.DateUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserActivityScoreRedisRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private UserActivityScoreRedisRepository repository;

    @Test
    void shouldExecuteActivityUpdateInOneLuaInvocation() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(),
                any(), any(), any(), any(), any(), any()
        )).thenReturn(1L);

        boolean changed = repository.updateScore(
                "activity_rank_720260807",
                "activity_rank_20260807",
                "activity_rank_202608",
                "42_praise",
                7L,
                2
        );

        assertTrue(changed);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(Arrays.asList(
                        "activity_rank_720260807",
                        "activity_rank_20260807",
                        "activity_rank_202608"
                )),
                eq("42_praise"),
                eq("7"),
                eq("2"),
                eq(String.valueOf(31 * DateUtil.ONE_DAY_SECONDS)),
                eq(String.valueOf(31 * DateUtil.ONE_DAY_SECONDS)),
                eq(String.valueOf(12 * DateUtil.ONE_MONTH_SECONDS))
        );
    }

    @Test
    void shouldReportUnchangedWhenLuaRejectsDuplicateAction() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(),
                any(), any(), any(), any(), any(), any()
        )).thenReturn(0L);

        boolean changed = repository.updateScore(
                "user-action", "daily-rank", "monthly-rank",
                "42_praise", 7L, 2);

        assertFalse(changed);
    }
}
