package com.github.paicoding.forum.service.article.service.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.paicoding.forum.service.article.repository.entity.ArticleSearchDocumentDTO;
import lombok.RequiredArgsConstructor;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import java.io.IOException;
import java.util.*;

/** Elasticsearch I/O shared by read-only queries and the durable projection worker. */
@Component
@RequiredArgsConstructor
public class ArticleIndexStore {
    private final ObjectProvider<RestHighLevelClient> clients;
    private final ObjectMapper json;
    @Value("${elasticsearch.article-index:paicoding_article_v2}")
    private String index;
    @Value("${elasticsearch.article-analyzer:cjk}")
    private String analyzer;
    @Value("${elasticsearch.article-search-analyzer:cjk}")
    private String searchAnalyzer;
    private volatile boolean indexReady;

    public String index() {
        if (index == null || !index.matches("[a-z0-9][a-z0-9_-]{0,100}"))
            throw new IllegalArgumentException("Invalid article index name");
        return index;
    }

    public boolean available() { return clients.getIfAvailable() != null; }

    public Map<String,Object> request(String method, String path, Map<String,Object> body) throws IOException {
        String[] endpoint = path.split("\\?", 2);
        Request r = new Request(method, endpoint[0]);
        if (endpoint.length == 2) {
            for (String pair : endpoint[1].split("&")) {
                String[] parameter = pair.split("=", 2);
                r.addParameter(parameter[0], parameter[1]);
            }
        }
        if (body != null) r.setJsonEntity(json.writeValueAsString(body));
        RestHighLevelClient client = clients.getIfAvailable();
        if (client == null) throw new IllegalStateException("Elasticsearch is disabled");
        Response response = client.getLowLevelClient().performRequest(r);
        // RestClient treats HEAD 404 as a normal response; it still means the index is absent.
        if (response.getStatusLine().getStatusCode() >= 400) throw new ResponseException(response);
        if (response.getEntity() == null) return Collections.emptyMap();
        return json.readValue(EntityUtils.toString(response.getEntity(), "UTF-8"),
                new TypeReference<Map<String,Object>>() {});
    }

    /** Called only by the synchronization worker, never on the search request path. */
    public synchronized void ensureIndex() throws IOException {
        if (indexReady) return;
        try {
            request("HEAD", "/" + index(), null);
            indexReady = true;
            return;
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() != 404) throw e;
        }
        Map<String,Object> fields = new LinkedHashMap<>();
        for (String f : Arrays.asList("title","shortTitle","summary","content","authorName"))
            fields.put(f, map("type","text","analyzer",analyzer,"search_analyzer",searchAnalyzer));
        for (String f : Arrays.asList("articleId","authorId","columnIds"))
            fields.put(f, map("type","long"));
        for (String f : Arrays.asList("status","deleted","readType","officalStat","toppingStat"))
            fields.put(f, map("type","integer"));
        fields.put("urlSlug", map("type","keyword"));
        fields.put("updateTime", map("type","date","format","epoch_millis"));
        try {
            request("PUT", "/" + index(), map("settings",map("number_of_shards",1,"number_of_replicas",0),
                    "mappings",map("dynamic","strict","properties",fields)));
        } catch (ResponseException e) {
            String response = EntityUtils.toString(e.getResponse().getEntity(), "UTF-8");
            if (e.getResponse().getStatusLine().getStatusCode() != 400 ||
                    !response.contains("resource_already_exists_exception")) throw e;
        }
        indexReady = true;
    }

    public Map<String,Object> document(long id, ArticleSearchDocumentDTO d) {
        if (d == null || !Objects.equals(d.getDeleted(),0) || !Objects.equals(d.getStatus(),1))
            return map("articleId",id,"status",0,"deleted",1);
        // Restricted bodies and tutorial bodies must not leak through public search.
        boolean publicBody = Objects.equals(d.getReadType(),0) && d.parseColumnIds().isEmpty();
        return map("articleId",id,"authorId",d.getAuthorId(),"authorName",d.getAuthorName(),
                "title",d.getTitle(),"shortTitle",d.getShortTitle(),"summary",d.getSummary(),
                "content",publicBody ? plain(d.getContent()) : "",
                "urlSlug",d.getUrlSlug(),"status",d.getStatus(),"deleted",d.getDeleted(),
                "readType",d.getReadType(),"officalStat",d.getOfficalStat(),
                "toppingStat",d.getToppingStat(),"columnIds",d.parseColumnIds(),
                "updateTime",d.getUpdateTime() == null ? 0 : d.getUpdateTime().getTime());
    }

    public void write(long id, long version, ArticleSearchDocumentDTO document) throws IOException {
        // Retain versioned tombstones: a late pre-delete update must never resurrect a document.
        String path = "/" + index() + "/_doc/" + id + "?version=" + version + "&version_type=external";
        try {
            request("PUT", path, document(id,document));
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) indexReady = false;
            String response = EntityUtils.toString(e.getResponse().getEntity(), "UTF-8");
            if (e.getResponse().getStatusLine().getStatusCode() != 409 ||
                    !response.contains("version_conflict_engine_exception")) throw e;
            // This exact version was already accepted, or a newer task superseded it.
        }
    }

    public static String safeHighlight(String fragment) {
        if (fragment == null) return null;
        return HtmlUtils.htmlEscape(HtmlUtils.htmlUnescape(fragment))
                .replace("&lt;mark&gt;","<mark>").replace("&lt;/mark&gt;","</mark>");
    }

    private static String plain(String content) {
        return content == null ? "" : content.replaceAll("<[^>]*>"," ");
    }

    public static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
}
