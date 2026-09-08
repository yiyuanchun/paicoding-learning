package com.github.paicoding.forum.service.article.service.search.sync;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "search.canal")
public class SearchCanalProperties {
    private boolean enabled;
    private String host = "127.0.0.1";
    private int port = 11111;
    private String destination = "paicoding";
    private String username = "";
    private String password = "";
    private String database = "pai_coding";
    private String sourceId = "paicoding-mysql-1";
    private String sourceEpoch = "1";
    private int batchSize = 200;
    private int retryMaxSeconds = 300;
    private long reconcileIntervalSeconds = 86400;
}
