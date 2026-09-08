package com.github.paicoding.forum.test.search;

import com.github.paicoding.forum.api.model.vo.article.dto.ArticleDTO;
import com.github.paicoding.forum.api.model.vo.user.dto.ArticleFootCountDTO;
import com.github.paicoding.forum.service.article.service.search.ArticleIndexStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring5.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SearchTemplateTest {
    private String render(boolean highlighted) {
        ArticleDTO article=new ArticleDTO();
        article.setArticleId(1L); article.setAuthor(1L); article.setAuthorName("Author");
        article.setTitle("Elasticsearch <unsafe>"); article.setSummary("ordinary summary");
        article.setStatus(1); article.setReadType(0); article.setToppingStat(0);
        article.setCreateTime(System.currentTimeMillis());
        article.setCount(new ArticleFootCountDTO()); article.setTags(Collections.emptyList());
        if(highlighted) {
            article.setTitleHighlight(ArticleIndexStore.safeHighlight("<mark>Elasticsearch</mark> &lt;unsafe&gt;"));
            article.setContentHighlight(ArticleIndexStore.safeHighlight("body <mark>match</mark> &lt;script&gt;alert(1)&lt;/script&gt;"));
        }
        ClassLoaderTemplateResolver resolver=new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine=new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        MockServletContext servlet=new MockServletContext();
        MockHttpServletRequest request=new MockHttpServletRequest(servlet);
        request.setRequestURI("/search");
        WebContext context=new WebContext(request,new MockHttpServletResponse(),servlet,Locale.CHINA);
        context.setVariable("article",article);
        context.setVariable("global",Collections.singletonMap("siteInfo",Collections.singletonMap("oss","")));
        return engine.process("components/article/article-card",Collections.singleton("article_card"),context);
    }
    @Test void rendersActualMarkTagsAndEscapesArticleHtml() {
        String html=render(true);
        assertTrue(html.contains("<mark>Elasticsearch</mark>"));
        assertTrue(html.contains("<mark>match</mark>"));
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertFalse(html.contains("<unsafe>"));
    }
    @Test void ordinaryCardsStillRenderWithoutSearchFragments() {
        String html=render(false);
        assertTrue(html.contains("ordinary summary"));
        assertTrue(html.contains("Elasticsearch &lt;unsafe&gt;"));
        assertFalse(html.contains("<mark>"));
    }
}
