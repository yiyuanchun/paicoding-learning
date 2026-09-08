package com.github.paicoding.forum.service.article.service.search;

import com.github.paicoding.forum.service.article.repository.entity.ArticleSearchDocumentDTO;
import com.github.paicoding.forum.service.article.service.search.impl.ArticleSearchServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.github.paicoding.forum.service.article.service.search.ArticleIndexStore.map;

class SearchQueryTest {
    @Test void highlightsAllowOnlyMarkTagsEvenWithHostileArticleHtml() {
        String safe=ArticleIndexStore.safeHighlight("<mark>Elasticsearch</mark>&lt;img src=x onerror=alert(1)&gt;");
        assertTrue(safe.contains("<mark>Elasticsearch</mark>"));
        assertFalse(safe.contains("<img"));
        assertFalse(ArticleIndexStore.safeHighlight("<script>alert(1)</script>").contains("<script>"));
        assertFalse(ArticleIndexStore.safeHighlight("<mark onclick='alert(1)'>x</mark>").contains("<mark onclick"));
    }

    @Test void restrictedBodiesAreNeverPutInPublicIndexAndDeletionErasesContent() {
        ArticleIndexStore store=new ArticleIndexStore(mock(ObjectProvider.class),new com.fasterxml.jackson.databind.ObjectMapper());
        ArticleSearchDocumentDTO d=new ArticleSearchDocumentDTO();
        d.setArticleId(1L); d.setStatus(1); d.setDeleted(0); d.setReadType(4); d.setContent("secret");
        assertEquals("",store.document(1,d).get("content"));
        d.setReadType(0);
        assertEquals("secret",store.document(1,d).get("content"));
        d.setColumnIds("3");
        assertEquals("",store.document(1,d).get("content"));
        d.setDeleted(1);
        assertFalse(store.document(1,d).containsKey("content"));
        assertEquals(1,store.document(1,null).get("deleted"));
    }

    @Test void zeroHitsReturnAnEmptyResultWithoutWritingAnIndex() throws Exception {
        ArticleIndexStore store=mock(ArticleIndexStore.class);
        when(store.available()).thenReturn(true);
        when(store.index()).thenReturn("articles");
        when(store.request(eq("POST"),eq("/articles/_search"),anyMap()))
                .thenReturn(map("hits",map("total",map("value",0),"hits",Collections.emptyList())));
        ArticleSearchServiceImpl search=new ArticleSearchServiceImpl(store,mock(ObjectProvider.class));
        assertTrue(search.searchHintArticleIds("unknown",10).getArticleIds().isEmpty());
        verify(store,never()).ensureIndex();
        verify(store,never()).write(anyLong(),anyLong(),any());
    }

    @Test void esFailureIsVisibleRatherThanSilentlySwitchingSearchBackend() throws Exception {
        ArticleIndexStore store=mock(ArticleIndexStore.class);
        when(store.available()).thenReturn(true);
        when(store.index()).thenReturn("articles");
        when(store.request(anyString(),anyString(),anyMap())).thenThrow(new java.io.IOException("offline"));
        ArticleSearchServiceImpl search=new ArticleSearchServiceImpl(store,mock(ObjectProvider.class));
        assertThrows(IllegalStateException.class,()->search.searchHintArticleIds("keyword",10));
    }
}
