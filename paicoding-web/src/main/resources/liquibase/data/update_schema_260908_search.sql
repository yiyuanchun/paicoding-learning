CREATE TABLE search_cdc_receipt (
 event_key char(64) NOT NULL PRIMARY KEY,
 created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE search_cdc_checkpoint (
 index_name varchar(128) NOT NULL PRIMARY KEY,
 open_tx varchar(255) DEFAULT NULL,
 leader_owner varchar(36) DEFAULT NULL,
 leader_until datetime(3) DEFAULT NULL,
 initialized tinyint NOT NULL DEFAULT 0,
 last_position varchar(255) DEFAULT NULL,
 updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE search_cdc_inbox (
 id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_key char(64) NOT NULL,
 index_name varchar(128) NOT NULL,
 tx_key varchar(255) NOT NULL,
 task_type varchar(16) NOT NULL,
 subject_id bigint NOT NULL DEFAULT 0,
 cursor_id bigint NOT NULL DEFAULT 0,
 ready tinyint NOT NULL DEFAULT 0,
 done tinyint NOT NULL DEFAULT 0,
 created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_search_event(event_key),
 KEY idx_search_inbox(index_name,ready,done,id),
 KEY idx_search_tx(index_name,tx_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE search_index_task (
 index_name varchar(128) NOT NULL,
 article_id bigint NOT NULL,
 requested_version bigint NOT NULL DEFAULT 1,
 completed_version bigint NOT NULL DEFAULT 0,
 lock_token varchar(36) DEFAULT NULL,
 locked_until datetime(3) DEFAULT NULL,
 retry_count int NOT NULL DEFAULT 0,
 next_retry_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 last_error varchar(1500) DEFAULT NULL,
 updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(index_name,article_id),
 KEY idx_search_pending(index_name,next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- This is the only search metadata table subscribed by Canal. Seeing this
-- marker in the stream fences initial backfill after capture has started.
CREATE TABLE search_cdc_signal (
 id varchar(36) NOT NULL PRIMARY KEY,
 index_name varchar(128) NOT NULL,
 created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
