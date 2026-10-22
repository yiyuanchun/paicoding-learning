package com.github.paicoding.forum.service.article.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.paicoding.forum.service.article.repository.entity.ReadCountDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 持久化阅读总数。 */
public interface ReadCountMapper extends BaseMapper<ReadCountDO> {

    @Select("SELECT cnt FROM read_count WHERE document_id = #{documentId} AND document_type = #{documentType}")
    Integer findArticleReadCount(@Param("documentId") Long documentId,
                                 @Param("documentType") Integer documentType);

    /** Absolute count write; a stale concurrent scheduler must not decrease it. */
    @Insert("INSERT INTO read_count (document_id, document_type, cnt, create_time, update_time) " +
            "VALUES (#{documentId}, #{documentType}, #{cnt}, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE cnt = GREATEST(cnt, VALUES(cnt)), update_time = NOW()")
    void insertOrUpdate(@Param("documentId") Long documentId,
                        @Param("documentType") Integer documentType,
                        @Param("cnt") Integer cnt);
}
