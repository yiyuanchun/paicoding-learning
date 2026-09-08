-- This fixture runs only in docker-compose.search-test.yml's isolated database.
SET NAMES utf8mb4;
CREATE USER 'canal'@'%' IDENTIFIED WITH mysql_native_password BY 'search-test-only';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
USE search_it;
CREATE TABLE article (
 id bigint NOT NULL PRIMARY KEY, user_id bigint NOT NULL,
 title varchar(255), short_title varchar(255), url_slug varchar(255), summary text,
 status int NOT NULL DEFAULT 1, deleted int NOT NULL DEFAULT 0,
 read_type int NOT NULL DEFAULT 0, offical_stat int DEFAULT 0, topping_stat int DEFAULT 0,
 update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE article_detail (
 id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, article_id bigint NOT NULL,
 version int NOT NULL, content longtext, deleted int NOT NULL DEFAULT 0
);
CREATE TABLE user_info (id bigint NOT NULL PRIMARY KEY, user_id bigint NOT NULL, user_name varchar(255));
CREATE TABLE column_article (id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, article_id bigint, column_id bigint);
INSERT INTO user_info VALUES(1,1,'Search Author');
INSERT INTO article(id,user_id,title,summary) VALUES(1,1,'Elasticsearch 全文检索教程','Canal 增量同步测试');
INSERT INTO article_detail(article_id,version,content) VALUES(1,1,'Elasticsearch 支持关键字匹配。分布式系统使用消息队列。');
