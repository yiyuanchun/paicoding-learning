CREATE TABLE mq_interaction_state (
  aggregate_key VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

CREATE TABLE mq_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  delivery_key VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_key VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload LONGTEXT NOT NULL,
  exchange_name VARCHAR(100) NOT NULL,
  routing_key VARCHAR(100) NOT NULL,
  attempt INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  lock_token VARCHAR(36) NULL,
  locked_until DATETIME(3) NULL,
  last_error VARCHAR(1024) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  sent_at DATETIME(3) NULL,
  UNIQUE KEY uk_delivery_key (delivery_key),
  KEY idx_outbox_pending (status,next_retry_time),
  KEY idx_outbox_lease (status,locked_until),
  KEY idx_outbox_event (event_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE mq_inbox (
  consumer_name VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_key VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  processed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (consumer_name,event_key)
) ENGINE=InnoDB;

ALTER TABLE notify_msg
  ADD COLUMN notification_key VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN last_event_version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN visible TINYINT NOT NULL DEFAULT 1,
  ADD COLUMN last_event_key VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin NULL;

-- Preserve every historical row. Old comment notifications do not carry a reliable comment ID.
UPDATE notify_msg SET notification_key=CONCAT('legacy:',id);
-- Collections and follows have unambiguous historic identities. Adopt the latest row only.
UPDATE notify_msg m
INNER JOIN (SELECT MAX(id) AS id FROM (SELECT id,type,related_id,operate_user_id,notify_user_id FROM notify_msg) historic
            WHERE type IN (4,5) GROUP BY type,related_id,operate_user_id,notify_user_id) latest ON latest.id=m.id
SET m.notification_key=CASE WHEN m.type=4
  THEN CONCAT('collect:article:',m.related_id,':actor:',m.operate_user_id,':receiver:',m.notify_user_id,':type:4')
  ELSE CONCAT('follow:user:',m.notify_user_id,':actor:',m.operate_user_id,':receiver:',m.notify_user_id,':type:5') END;
-- Hide old duplicates, retaining them for audit instead of deleting data.
UPDATE notify_msg m
INNER JOIN (SELECT type,related_id,operate_user_id,notify_user_id,MAX(id) AS latest_id
            FROM (SELECT id,type,related_id,operate_user_id,notify_user_id FROM notify_msg) historic
            WHERE type IN (4,5) GROUP BY type,related_id,operate_user_id,notify_user_id) latest
ON latest.type=m.type AND latest.related_id=m.related_id AND latest.operate_user_id=m.operate_user_id
AND latest.notify_user_id=m.notify_user_id
SET m.visible=0 WHERE m.id<>latest.latest_id;

ALTER TABLE notify_msg
  ADD UNIQUE KEY uk_notification_key (notification_key),
  ADD KEY idx_notify_visible (notify_user_id,visible,type,state);
