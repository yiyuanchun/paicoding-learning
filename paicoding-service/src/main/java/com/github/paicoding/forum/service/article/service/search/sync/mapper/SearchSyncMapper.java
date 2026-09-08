package com.github.paicoding.forum.service.article.service.search.sync.mapper;

import com.github.paicoding.forum.service.article.service.search.sync.SearchTask;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

/** All mutations use the business datasource transaction manager. */
@Mapper
public interface SearchSyncMapper {
    @Insert("INSERT IGNORE INTO search_cdc_receipt(event_key) VALUES(#{key})")
    int receipt(String key);

    @Update("UPDATE search_cdc_checkpoint SET leader_owner=#{owner},leader_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)) WHERE index_name=#{index} AND (leader_owner=#{owner} OR leader_until IS NULL OR leader_until<CURRENT_TIMESTAMP(3))")
    int claimLeader(@Param("index") String index, @Param("owner") String owner);

    @Update("UPDATE search_cdc_checkpoint SET leader_owner=NULL,leader_until=NULL WHERE index_name=#{index} AND leader_owner=#{owner}")
    void releaseLeader(@Param("index") String index, @Param("owner") String owner);

    @Insert("INSERT INTO search_cdc_checkpoint(index_name) VALUES(#{index}) ON DUPLICATE KEY UPDATE index_name=VALUES(index_name)")
    void ensureCheckpoint(String index);

    @Select("SELECT open_tx FROM search_cdc_checkpoint WHERE index_name=#{index} FOR UPDATE")
    String lockCheckpoint(String index);

    @Update("UPDATE search_cdc_checkpoint SET open_tx=#{tx},last_position=#{position},updated_at=CURRENT_TIMESTAMP(3) WHERE index_name=#{index}")
    void checkpoint(@Param("index") String index, @Param("tx") String tx, @Param("position") String position);

    @Select("SELECT initialized FROM search_cdc_checkpoint WHERE index_name=#{index}")
    int initialized(String index);

    @Update("UPDATE search_cdc_checkpoint SET initialized=1 WHERE index_name=#{index}")
    void initializedDone(String index);

    @Insert("INSERT INTO search_cdc_signal(id,index_name) VALUES(#{id},#{index})")
    void signal(@Param("id") String id, @Param("index") String index);

    @Insert("INSERT INTO search_cdc_inbox(event_key,index_name,tx_key,task_type,subject_id) VALUES(#{key},#{index},#{tx},#{type},#{subject}) ON DUPLICATE KEY UPDATE event_key=VALUES(event_key)")
    void receive(@Param("key") String key, @Param("index") String index, @Param("tx") String tx,
                 @Param("type") String type, @Param("subject") long subject);

    @Update("UPDATE search_cdc_inbox SET ready=1 WHERE index_name=#{index} AND tx_key=#{tx}")
    void release(@Param("index") String index, @Param("tx") String tx);

    @Select("SELECT * FROM search_cdc_inbox WHERE index_name=#{index} AND ready=1 AND done=0 ORDER BY id LIMIT 1 FOR UPDATE")
    SearchTask lockInbox(String index);

    @Update("UPDATE search_cdc_inbox SET cursor_id=#{cursor},done=#{done} WHERE id=#{id}")
    void advance(@Param("id") long id, @Param("cursor") long cursor, @Param("done") boolean done);

    @Select("SELECT id FROM article WHERE user_id=#{author} AND id>#{cursor} ORDER BY id LIMIT 100")
    List<Long> authorArticles(@Param("author") long author, @Param("cursor") long cursor);

    // Include known IDs so reconciliation also repairs physically deleted articles.
    @Select("SELECT article_id FROM (SELECT id AS article_id FROM article WHERE id>#{cursor} UNION SELECT article_id FROM search_index_task WHERE index_name=#{index} AND article_id>#{cursor}) ids ORDER BY article_id LIMIT 100")
    List<Long> allArticles(@Param("index") String index, @Param("cursor") long cursor);

    @Insert("INSERT INTO search_index_task(index_name,article_id) VALUES(#{index},#{id}) ON DUPLICATE KEY UPDATE requested_version=requested_version+1,next_retry_time=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3)")
    void enqueue(@Param("index") String index, @Param("id") long id);

    @Select("SELECT * FROM search_index_task WHERE index_name=#{index} AND requested_version>completed_version AND next_retry_time<=CURRENT_TIMESTAMP(3) AND (locked_until IS NULL OR locked_until<CURRENT_TIMESTAMP(3)) ORDER BY next_retry_time,article_id LIMIT 30")
    List<SearchTask> pending(String index);

    @Update("UPDATE search_index_task SET lock_token=#{token},locked_until=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(3)) WHERE index_name=#{index} AND article_id=#{id} AND requested_version>completed_version AND (locked_until IS NULL OR locked_until<CURRENT_TIMESTAMP(3))")
    int claim(@Param("index") String index, @Param("id") long id, @Param("token") String token);

    @Select("SELECT * FROM search_index_task WHERE index_name=#{index} AND article_id=#{id} AND lock_token=#{token}")
    SearchTask claimed(@Param("index") String index, @Param("id") long id, @Param("token") String token);

    @Update("UPDATE search_index_task SET completed_version=GREATEST(completed_version,#{version}),lock_token=NULL,locked_until=NULL,retry_count=0,last_error=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE index_name=#{index} AND article_id=#{id} AND lock_token=#{token}")
    void complete(@Param("index") String index, @Param("id") long id, @Param("token") String token, @Param("version") long version);

    @Update("UPDATE search_index_task SET lock_token=NULL,locked_until=NULL,retry_count=retry_count+1,last_error=#{error},next_retry_time=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(3)),updated_at=CURRENT_TIMESTAMP(3) WHERE index_name=#{index} AND article_id=#{id} AND lock_token=#{token}")
    void fail(@Param("index") String index, @Param("id") long id, @Param("token") String token,
              @Param("error") String error, @Param("delay") int delay);

    @Select("SELECT COUNT(*) AS total,COALESCE(SUM(requested_version>completed_version),0) AS pending,COALESCE(SUM(retry_count>0),0) AS retrying,MIN(CASE WHEN requested_version>completed_version THEN updated_at END) AS oldest_pending FROM search_index_task WHERE index_name=#{index}")
    Map<String,Object> status(String index);

    @Select("SELECT COUNT(*) FROM search_cdc_inbox WHERE index_name=#{index} AND done=0")
    long inboxPending(String index);
}
