package com.github.paicoding.forum.service.article.service.impl;

import com.github.paicoding.forum.api.model.context.ReqInfoContext;
import com.github.paicoding.forum.api.model.enums.ArticleReadTypeEnum;
import com.github.paicoding.forum.api.model.enums.PushStatusEnum;
import com.github.paicoding.forum.api.model.event.ArticleViewedEvent;
import com.github.paicoding.forum.api.model.vo.article.dto.ArticleDTO;
import com.github.paicoding.forum.api.model.vo.article.dto.CategoryDTO;
import com.github.paicoding.forum.api.model.vo.user.dto.ArticleFootCountDTO;
import com.github.paicoding.forum.service.article.repository.dao.ArticleDao;
import com.github.paicoding.forum.service.article.repository.dao.ArticleTagDao;
import com.github.paicoding.forum.service.article.service.ArticlePayService;
import com.github.paicoding.forum.service.article.service.CategoryService;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchService;
import com.github.paicoding.forum.service.sensitive.service.SensitiveBypassService;
import com.github.paicoding.forum.service.statistics.service.CountService;
import com.github.paicoding.forum.service.user.service.UserFootService;
import com.github.paicoding.forum.service.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleReadServiceImplArticleViewEventTest {

    @Mock
    private ArticleDao articleDao;
    @Mock
    private ArticleTagDao articleTagDao;
    @Mock
    private CategoryService categoryService;
    @Mock
    private UserFootService userFootService;
    @Mock
    private CountService countService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ArticlePayService articlePayService;
    @Mock
    private UserService userService;
    @Mock
    private SensitiveBypassService sensitiveBypassService;
    @Mock
    private ArticleSearchService articleSearchService;

    @InjectMocks
    private ArticleReadServiceImpl articleReadService;

    @BeforeEach
    void setUpRequestContext() {
        ReqInfoContext.ReqInfo reqInfo = new ReqInfoContext.ReqInfo();
        reqInfo.setDeviceId("visitor-1");
        ReqInfoContext.addReqInfo(reqInfo);
    }

    @AfterEach
    void clearRequestContext() {
        ReqInfoContext.clear();
    }

    @Test
    void shouldPublishEventAfterAccessibleArticleIsLoaded() {
        ArticleDTO article = prepareArticle(ArticleReadTypeEnum.NORMAL);

        articleReadService.queryFullArticleInfo(42L, null);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ArticleViewedEvent event = assertInstanceOf(ArticleViewedEvent.class, eventCaptor.getValue());
        assertEquals(42L, event.articleId());
        assertEquals(7L, event.authorId());
        assertNull(event.userId());
        assertEquals("visitor-1", event.visitorId());
        verify(countService, never()).incrArticleReadCount(any(), any());
    }

    @Test
    void shouldNotPublishEventWhenAnonymousUserCannotReadArticle() {
        prepareArticle(ArticleReadTypeEnum.LOGIN);

        articleReadService.queryFullArticleInfo(42L, null);

        verify(eventPublisher, never()).publishEvent(any(Object.class));
        verify(countService, never()).incrArticleReadCount(any(), any());
    }

    private ArticleDTO prepareArticle(ArticleReadTypeEnum readType) {
        ArticleDTO article = new ArticleDTO();
        article.setArticleId(42L);
        article.setAuthor(7L);
        article.setStatus(PushStatusEnum.ONLINE.getCode());
        article.setReadType(readType.getType());
        article.setCategory(new CategoryDTO(3L, "category"));

        when(articleDao.queryArticleDetail(42L)).thenReturn(article);
        when(categoryService.queryCategoryName(3L)).thenReturn("category");
        when(articleTagDao.queryArticleTagDetails(42L)).thenReturn(Collections.emptyList());
        when(countService.queryArticleStatisticInfo(42L)).thenReturn(new ArticleFootCountDTO());
        when(userFootService.queryArticlePraisedUsers(42L)).thenReturn(Collections.emptyList());
        when(sensitiveBypassService.shouldBypassByUserId(7L)).thenReturn(true);
        return article;
    }
}
