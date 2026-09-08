package com.github.paicoding.forum.service.article.service.search.sync;

import lombok.Data;

@Data
public class SearchTask {
    private Long id;
    private Long articleId;
    private Long subjectId;
    private Long cursorId;
    private Long requestedVersion;
    private Long completedVersion;
    private Integer retryCount;
    private String taskType;
    private String lockToken;
}
