package com.github.paicoding.forum.service.statistics.service.impl;

import com.github.paicoding.forum.api.model.enums.DocumentTypeEnum;
import com.github.paicoding.forum.service.article.repository.dao.ArticleDao;
import com.github.paicoding.forum.service.article.repository.mapper.ReadCountMapper;
import com.github.paicoding.forum.service.comment.service.CommentReadService;
import com.github.paicoding.forum.service.statistics.constants.StatisticsRedisKey;
import com.github.paicoding.forum.service.user.repository.dao.UserDao;
import com.github.paicoding.forum.service.user.repository.dao.UserFootDao;
import com.github.paicoding.forum.service.user.repository.dao.UserRelationDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CountServiceImplTest {

    @Mock
    private UserFootDao userFootDao;
    @Mock
    private UserRelationDao userRelationDao;
    @Mock
    private ArticleDao articleDao;
    @Mock
    private CommentReadService commentReadService;
    @Mock
    private UserDao userDao;
    @Mock
    private ReadCountMapper readCountMapper;
    @Mock
    private RedisTemplate<String, String> stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private Cursor<String> cursor;

    private CountServiceImpl service;

    @BeforeEach
    void setUp() {
        service = spy(new CountServiceImpl(userFootDao));
        ReflectionTestUtils.setField(service, "userRelationDao", userRelationDao);
        ReflectionTestUtils.setField(service, "articleDao", articleDao);
        ReflectionTestUtils.setField(service, "commentReadService", commentReadService);
        ReflectionTestUtils.setField(service, "userDao", userDao);
        ReflectionTestUtils.setField(service, "readCountMapper", readCountMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    void scanKeysShouldReturnRawArticleViewKeys() {
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(
                "stats:article:view:total:101",
                "stats:article:view:total:102");

        Set<String> keys = service.scanKeys(StatisticsRedisKey.articleTotalViewPattern());

        assertThat(keys).containsExactlyInAnyOrder(
                "stats:article:view:total:101",
                "stats:article:view:total:102");
        verify(cursor).close();
    }

    @Test
    void syncArticleReadCountToDbShouldOnlyPersistPositiveCounts() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add("stats:article:view:total:101");
        keys.add("stats:article:view:total:bad");
        keys.add("stats:article:view:total:102");
        keys.add("stats:article:view:total:103");

        doReturn(keys).when(service).scanKeys(StatisticsRedisKey.articleTotalViewPattern());
        doReturn(8).when(service).getArticleReadCount("stats:article:view:total:101");
        doReturn(0).when(service).getArticleReadCount("stats:article:view:total:102");
        doReturn(null).when(service).getArticleReadCount("stats:article:view:total:103");

        service.syncArticleReadCountToDb();

        verify(readCountMapper).insertOrUpdate(101L, DocumentTypeEnum.ARTICLE.getCode(), 8);
        verify(service, never()).getArticleReadCount("stats:article:view:total:bad");
        verifyNoMoreInteractions(readCountMapper);
    }

    @Test
    void getArticleReadCountShouldReadStringValue() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("stats:article:view:total:101")).thenReturn("18");

        assertThat(service.getArticleReadCount("stats:article:view:total:101")).isEqualTo(18);
    }

    @Test
    void syncTaskShouldRunEveryTenMinutes() throws NoSuchMethodException {
        Method method = CountServiceImpl.class.getMethod("syncArticleReadCountToDb");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 */10 * * * ?");
    }

    @Test
    void readCountMapperShouldUpsertAbsoluteCount() throws NoSuchMethodException {
        Method method = ReadCountMapper.class.getMethod("insertOrUpdate", Long.class, Integer.class, Integer.class);
        org.apache.ibatis.annotations.Insert insert = method.getAnnotation(org.apache.ibatis.annotations.Insert.class);

        assertThat(insert).isNotNull();
        assertThat(insert.value()).hasSize(1);
        assertThat(insert.value()[0])
                .contains("ON DUPLICATE KEY UPDATE cnt = #{cnt}")
                .doesNotContain("cnt = cnt + #{cnt}");
    }
}
