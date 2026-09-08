package com.github.paicoding.forum.service.rank.service.impl;

import com.github.paicoding.forum.service.rank.repository.UserActivityScoreRedisRepository;
import com.github.paicoding.forum.service.rank.service.model.ActivityScoreBo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserActivityRankServiceImplTest {

    @Mock
    private UserActivityScoreRedisRepository activityScoreRedisRepository;

    private UserActivityRankServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserActivityRankServiceImpl();
        ReflectionTestUtils.setField(
                service, "activityScoreRedisRepository", activityScoreRedisRepository);
    }

    @Test
    void shouldSubmitPraiseScoreToAtomicRedisRepository() {
        service.addActivityScore(
                7L,
                new ActivityScoreBo().setArticleId(42L).setPraise(true)
        );

        verify(activityScoreRedisRepository).updateScore(
                matches("activity_rank_7\\d{8}"),
                matches("activity_rank_\\d{8}"),
                matches("activity_rank_\\d{6}"),
                eq("42_praise"),
                eq(7L),
                eq(2)
        );
    }

    @Test
    void shouldSubmitNegativeScoreWhenPraiseIsCancelled() {
        service.addActivityScore(
                7L,
                new ActivityScoreBo().setArticleId(42L).setPraise(false)
        );

        verify(activityScoreRedisRepository).updateScore(
                matches("activity_rank_7\\d{8}"),
                matches("activity_rank_\\d{8}"),
                matches("activity_rank_\\d{6}"),
                eq("42_praise"),
                eq(7L),
                eq(-2)
        );
    }

    @Test
    void shouldIgnoreAnonymousActivity() {
        service.addActivityScore(null, new ActivityScoreBo().setPath("/article/42"));

        verify(activityScoreRedisRepository, never()).updateScore(
                matches(".*"), matches(".*"), matches(".*"),
                matches(".*"), eq(7L), eq(1));
    }
}
