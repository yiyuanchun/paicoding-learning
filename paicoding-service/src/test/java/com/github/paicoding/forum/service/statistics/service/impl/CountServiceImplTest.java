package com.github.paicoding.forum.service.statistics.service.impl;

import com.github.paicoding.forum.api.model.enums.DocumentTypeEnum;
import com.github.paicoding.forum.service.article.repository.mapper.ReadCountMapper;
import com.github.paicoding.forum.service.statistics.constants.StatisticsRedisKey;
import com.github.paicoding.forum.service.user.repository.dao.UserFootDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;
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
    @Mock private UserFootDao userFootDao;
    @Mock private ReadCountMapper readCountMapper;
    @Mock private RedisTemplate<String, String> stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private Cursor<String> cursor;

    private CountServiceImpl service;

    @BeforeEach
    void setUp() {
        service = spy(new CountServiceImpl(userFootDao));
        ReflectionTestUtils.setField(service, "readCountMapper", readCountMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    void scanKeysShouldUsePlainStringKeys() {
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("stats:article:view:total:v2:101", "stats:article:view:total:v2:102");
        Set<String> keys = service.scanKeys(StatisticsRedisKey.ARTICLE_VIEW_TOTAL_PREFIX + "*");
        assertThat(keys).containsExactlyInAnyOrder("stats:article:view:total:v2:101", "stats:article:view:total:v2:102");
        verify(cursor).close();
    }

    @Test
    void syncArticleReadCountToDbShouldOnlyPersistStringTotals() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add("stats:article:view:total:v2:101");
        keys.add("stats:article:view:total:v2:bad");
        keys.add("stats:article:view:total:v2:102");
        keys.add("stats:article:view:total:v2:103");

        doReturn(keys).when(service).scanKeys(StatisticsRedisKey.ARTICLE_VIEW_TOTAL_PREFIX + "*");
        doReturn(8).when(service).getArticleReadCount("stats:article:view:total:v2:101");
        doReturn(0).when(service).getArticleReadCount("stats:article:view:total:v2:102");
        doReturn(null).when(service).getArticleReadCount("stats:article:view:total:v2:103");

        service.syncArticleReadCountToDb();

        verify(readCountMapper).insertOrUpdate(101L, DocumentTypeEnum.ARTICLE.getCode(), 8);
        verify(readCountMapper).insertOrUpdate(102L, DocumentTypeEnum.ARTICLE.getCode(), 0);
        verify(service, never()).getArticleReadCount("stats:article:view:total:v2:bad");
        verifyNoMoreInteractions(readCountMapper);
    }

    @Test
    void shouldSeedTotalFromDatabaseAndLegacyStringBeforeIncr() {
        String key = StatisticsRedisKey.articleTotalView(101L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(null, "114");
        when(valueOperations.get(StatisticsRedisKey.legacyArticleTotalView(101L))).thenReturn("14");
        when(readCountMapper.findArticleReadCount(101L, DocumentTypeEnum.ARTICLE.getCode())).thenReturn(100);

        when(valueOperations.setIfAbsent(key, "114")).thenReturn(true);

        service.incrArticleReadCount(5L, 101L);

        verify(valueOperations).setIfAbsent(key, "114");
        verify(stringRedisTemplate).delete(StatisticsRedisKey.legacyArticleTotalView(101L));
        verify(valueOperations).increment(key);
    }

    @Test
    void shouldNotReinitializeAnExistingTotal() {
        String key = StatisticsRedisKey.articleTotalView(101L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn("73");

        service.incrArticleReadCount(5L, 101L);

        verify(valueOperations, never()).setIfAbsent(any(), any());
        verify(valueOperations).increment(key);
        verifyNoMoreInteractions(readCountMapper);
    }

    @Test
    void readCountMapperShouldUpsertMonotonicAbsoluteCount() throws NoSuchMethodException {
        Method method = ReadCountMapper.class.getMethod("insertOrUpdate", Long.class, Integer.class, Integer.class);
        org.apache.ibatis.annotations.Insert insert = method.getAnnotation(org.apache.ibatis.annotations.Insert.class);
        assertThat(insert).isNotNull();
        assertThat(insert.value()).hasSize(1);
        assertThat(insert.value()[0])
                .contains("ON DUPLICATE KEY UPDATE cnt = GREATEST(cnt, VALUES(cnt))")
                .doesNotContain("cnt = cnt + #{cnt}");
    }
}
