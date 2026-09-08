package com.github.paicoding.forum.service.article.service.search.impl;

import com.github.paicoding.forum.api.model.vo.PageParam;
import com.github.paicoding.forum.api.model.vo.article.dto.ArticleSearchSnippetDTO;
import com.github.paicoding.forum.service.article.repository.params.SearchArticleParams;
import com.github.paicoding.forum.service.article.service.search.*;
import com.github.paicoding.forum.service.article.service.search.sync.SearchSyncEngine;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.github.paicoding.forum.service.article.service.search.ArticleIndexStore.map;

/** Read-only Elasticsearch recall. Index creation and synchronization belong to Canal workers. */
@Service
@RequiredArgsConstructor
public class ArticleSearchServiceImpl implements ArticleSearchService {
    private final ArticleIndexStore store;
    private final ObjectProvider<SearchSyncEngine> sync;

    @Override public boolean enabled() { return store.available(); }

    @Override public ArticleSearchResult searchHintArticleIds(String key, int limit) {
        return search(key,0,limit,true,null);
    }

    @Override public ArticleSearchResult searchOnlineArticleIds(String key, PageParam page) {
        return search(key,page == null ? 0 : page.getOffset(),
                page == null ? 10 : page.getPageSize(),false,null);
    }

    @Override public ArticleSearchResult searchAdminArticleIds(SearchArticleParams p) {
        if (p == null) return new ArticleSearchResult();
        // Draft/review searches remain database-backed; public ES never indexes their bodies.
        if (p.getStatus() != null && p.getStatus() != -1 && p.getStatus() != 1) return null;
        return search(p.getKeyword(),p.getOffset(),p.getPageSize(),false,p);
    }

    private ArticleSearchResult search(String key, long offset, long pageSize, boolean hint, SearchArticleParams admin) {
        if (!enabled()) return null;
        if (StringUtils.isBlank(key)) return new ArticleSearchResult();
        if (key.length()>200) throw new IllegalArgumentException("搜索关键字不能超过 200 字符");
        int size=(int)Math.max(1,Math.min(100,pageSize));
        if (offset<0 || offset+size>10000) throw new IllegalArgumentException("搜索页码超出范围");
        try {
            return parse(store.request("POST","/"+store.index()+"/_search",query(key.trim(),offset,size,hint,admin)));
        } catch (Exception e) {
            throw new IllegalStateException("全文搜索暂不可用，请检查 Elasticsearch 和索引同步状态",e);
        }
    }

    public Map<String,Object> query(String key, long offset, int size, boolean hint, SearchArticleParams admin) {
        List<String> fields = hint ? Arrays.asList("title^5","shortTitle^4") :
                Arrays.asList("title^5","shortTitle^4","summary^2","content");
        List<Object> should = new ArrayList<>();
        should.add(map("multi_match",map("query",key,"fields",fields,"type","best_fields","operator","and","boost",3)));
        should.add(map("match_phrase",map("title",map("query",key,"boost",6))));
        should.add(map("multi_match",map("query",key,"fields",fields,"type","best_fields",
                "operator","and","fuzziness","AUTO","prefix_length",1,"max_expansions",30,"boost",0.5)));
        List<Object> filters = new ArrayList<>();
        filters.add(map("term",map("deleted",0)));
        filters.add(map("term",map("status",1)));
        List<Object> must = new ArrayList<>();
        must.add(map("bool",map("should",should,"minimum_should_match",1)));
        if (admin != null) {
            filter(filters,"articleId",admin.getArticleId());
            filter(filters,"authorId",admin.getUserId());
            filter(filters,"officalStat",admin.getOfficalStat());
            filter(filters,"toppingStat",admin.getToppingStat());
            filter(filters,"columnIds",admin.getColumnId());
            if (StringUtils.isNotBlank(admin.getUrlSlug())) filters.add(map("term",map("urlSlug",admin.getUrlSlug())));
            if (StringUtils.isNotBlank(admin.getTitle())) must.add(map("match",map("title",admin.getTitle())));
            if (StringUtils.isNotBlank(admin.getUserName())) must.add(map("match_phrase",map("authorName",admin.getUserName())));
        }
        Map<String,Object> highlights = map("title",map("number_of_fragments",0),
                "shortTitle",map("number_of_fragments",0),"summary",map(),"content",map());
        return map("from",offset,"size",size,"track_total_hits",true,"_source",false,
                "query",map("bool",map("must",must,"filter",filters)),
                "sort",Arrays.asList(map("_score","desc"),map("updateTime","desc"),map("articleId","desc")),
                "highlight",map("type","unified","encoder","html","pre_tags",Collections.singletonList("<mark>"),
                        "post_tags",Collections.singletonList("</mark>"),"fragment_size",160,
                        "number_of_fragments",2,"fields",highlights));
    }

    private void filter(List<Object> filters,String field,Number value) {
        if (value!=null && value.longValue()!=-1) filters.add(map("term",map(field,value)));
    }

    @SuppressWarnings("unchecked")
    public ArticleSearchResult parse(Map<String,Object> response) {
        ArticleSearchResult result = new ArticleSearchResult();
        Map<String,Object> hits=(Map<String,Object>)response.get("hits");
        Object total=hits.get("total");
        result.setTotal(((Number)(total instanceof Map ? ((Map<?,?>)total).get("value") : total)).longValue());
        List<Long> ids=new ArrayList<>();
        Map<Long,String> snippets=new HashMap<>(),titles=new HashMap<>(),contents=new HashMap<>();
        Map<Long,List<ArticleSearchSnippetDTO>> structured=new HashMap<>();
        for (Map<String,Object> hit : (List<Map<String,Object>>)hits.get("hits")) {
            long id=Long.parseLong(hit.get("_id").toString());
            ids.add(id);
            Map<String,List<String>> h=(Map<String,List<String>>)hit.get("highlight");
            if (h==null) continue;
            List<ArticleSearchSnippetDTO> parts=new ArrayList<>();
            for (String field : Arrays.asList("title","shortTitle","content","summary")) {
                List<String> fragments=h.get(field);
                if (fragments==null) continue;
                for (String fragment:fragments) {
                    String safe=ArticleIndexStore.safeHighlight(fragment);
                    ArticleSearchSnippetDTO part=new ArticleSearchSnippetDTO();
                    part.setField(field);
                    part.setFieldName(field);
                    part.setFragment(safe);
                    parts.add(part);
                    if ("title".equals(field)) titles.putIfAbsent(id,safe);
                    if ("content".equals(field) || "summary".equals(field)) contents.putIfAbsent(id,safe);
                    snippets.putIfAbsent(id,safe);
                }
            }
            structured.put(id,parts);
        }
        result.setArticleIds(ids);
        result.setHighlights(snippets);
        result.setTitleHighlights(titles);
        result.setContentHighlights(contents);
        result.setSnippets(structured);
        return result;
    }

    private SearchSyncEngine engine() {
        SearchSyncEngine engine=sync.getIfAvailable();
        if (engine==null) throw new IllegalStateException("请启用 Canal 索引同步");
        return engine;
    }
    @Override public void rebuildArticleIndex() { engine().requestRebuild(); }
    @Override public void syncArticle(Long id) { if(id!=null) engine().enqueueRepair(id); }
    @Override public void deleteArticle(Long id) { syncArticle(id); }
    // Kept for binary/source compatibility with existing admin callers; searches never write.
    @Override public void syncHintKeyword(String key,int limit) { }
    @Override public void syncOnlineKeyword(String key,PageParam page) { }
    @Override public void syncAdminKeyword(SearchArticleParams p) { }
}
