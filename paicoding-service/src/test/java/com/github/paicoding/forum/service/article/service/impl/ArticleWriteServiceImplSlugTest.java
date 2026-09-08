package com.github.paicoding.forum.service.article.service.impl;

import com.github.paicoding.forum.api.model.vo.article.ArticlePostReq;
import com.github.paicoding.forum.core.util.UrlSlugUtil;
import com.github.paicoding.forum.service.article.repository.dao.ArticleDao;
import com.github.paicoding.forum.service.article.repository.dao.ArticleTagDao;
import com.github.paicoding.forum.service.article.repository.dao.ColumnDao;
import com.github.paicoding.forum.service.article.repository.entity.ArticleDO;
import com.github.paicoding.forum.service.article.service.SlugGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArticleWriteServiceImplSlugTest {
    private ArticleDao articles;
    private ColumnDao columns;
    private SlugGeneratorService ai;
    private ArticleWriteServiceImpl service;

    @BeforeEach void setUp() {
        articles = mock(ArticleDao.class);
        columns = mock(ColumnDao.class);
        ai = mock(SlugGeneratorService.class);
        service = new ArticleWriteServiceImpl(articles, mock(ArticleTagDao.class));
        ReflectionTestUtils.setField(service, "columnDao", columns);
        ReflectionTestUtils.setField(service, "slugGeneratorService", ai);
    }

    @Test void missingApiKeyFallsBackToChineseSlugAndChecksBothNamespaces() {
        String title = "自我介绍测试文章";
        when(ai.generateSlugWithAI(title)).thenThrow(new IllegalStateException("未配置 zhipu.apiSecretKey"));
        String base = UrlSlugUtil.generateSlug(title);
        when(articles.existsUrlSlug(base, null)).thenReturn(true);
        when(columns.existsUrlSlug(base + "-2", null)).thenReturn(true);
        String slug = resolve(title);
        assertEquals(base + "-3", slug);
        assertTrue(UrlSlugUtil.isValidSlug(slug));
    }

    @Test void aiOutageStillProducesANonNumericUrlForNumericTitle() {
        when(ai.generateSlugWithAI("1234567")).thenThrow(new IllegalStateException("AI request timed out"));
        assertEquals("article-1234567", resolve("1234567"));
    }

    @Test void successfulAiSuggestionIsPreserved() {
        when(ai.generateSlugWithAI("消息通知测试")).thenReturn("rabbitmq-notifications");
        assertEquals("rabbitmq-notifications", resolve("消息通知测试"));
    }

    @Test void editingAnExistingArticleKeepsItsUrlWithoutCallingAi() {
        ArticleDO existing = new ArticleDO();
        existing.setUrlSlug("existing-article");
        when(articles.getById(42L)).thenReturn(existing);
        ArticlePostReq req = new ArticlePostReq();
        req.setArticleId(42L);
        req.setTitle("修改后的文章标题");
        assertEquals("existing-article", ReflectionTestUtils.invokeMethod(service, "resolveArticleUrlSlug", req));
        verifyNoInteractions(ai);
    }

    private String resolve(String title) {
        ArticlePostReq req = new ArticlePostReq();
        req.setTitle(title);
        return ReflectionTestUtils.invokeMethod(service, "resolveArticleUrlSlug", req);
    }
}
