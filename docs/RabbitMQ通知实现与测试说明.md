> 本机部署已改为统一使用 `paicoding-mysql/pai_coding`。请优先阅读 [本机业务库启动与验收](本机业务库启动与验收.md)，统一运行 `scripts/start-local.ps1`。本文的独立测试容器与测试结果属于历史验证，不是当前业务部署。

# RabbitMQ 站内通知实现与验收说明

适用目录：D:\java_workplace\paicoding。命令使用 Windows PowerShell，均从项目根目录执行。

## 1. 本次实现的流程

业务接口 → MySQL 业务数据与 mq_outbox 同一事务提交 → OutboxRelay 定时投递 → RabbitMQ → 三个独立消费者：

| 队列 | 作用 | 消费完成的证据 |
| --- | --- | --- |
| paicoding.notification.queue | 生成或撤销站内通知 | notify_msg、mq_inbox 中 notification 记录 |
| paicoding.statistics.queue | 更新评论数、点赞数、收藏数、关注/粉丝数 | Redis 计数、mq_inbox 中 statistics 记录 |
| paicoding.activity.queue | 更新操作人的活跃积分 | Redis 排行榜、mq_inbox 中 activity 记录 |
| paicoding.interaction.dead.queue | 保存消费失败超过重试次数或格式非法的消息 | 管理界面 Ready 数、MQ_DEAD 日志 |

主交换机：paicoding.interaction.exchange，类型 topic；路由键 interaction.comment、interaction.praise、interaction.collect、interaction.follow。回复属于 COMMENT 业务事实，携带父评论与父评论作者信息，由通知消费者分别产生文章评论通知和回复通知。统计消费者只计一次，避免回复导致评论数重复加一。

这些交互操作及其取消/删除不再发布或监听 Spring NotifyMsgEvent。注册欢迎、支付通知、文章发布事件保留原机制；它们不属于本次交互通知迁移范围。旧 application-rabbitmq.yml 中 rabbitmq.switchFlag 不控制本功能；新功能使用 spring.rabbitmq 和 paicoding.mq 配置，不会在 RabbitMQ 不可用时回退到 Spring 事件。

### 持久化和幂等规则

1. 业务状态与待发消息写入同一个 MySQL 事务；事务失败时二者一起回滚。MQ 不可用时，业务可以成功，消息留在数据库等待补发。
2. 交换机、队列均 durable；消息 delivery_mode=2；发送启用 publisher confirm 和 mandatory return。仅正向确认且未被退回才标记 SENT。SENT 表示 broker 接收成功，**不等于已经生成通知**。
3. 发送程序每批最多处理 20 条，通过数据库租约和令牌防止多实例重复抢占。进程中断后，SENDING 租约约 60 秒过期可重新发送。发送失败约 10 秒后重试，轮询间隔默认 2 秒，首次启动延迟约 10 秒。
4. 业务唯一标识不是每次投递生成的 UUID。示例：praise:article:100:actor:10:v1。aggregate_key 标识操作关系，aggregate_version 标识一次状态变更；重试和重放必须保留原 eventKey。
5. mq_inbox 主键为 (consumer_name,event_key)，同一事件允许三个消费者分别成功一次。
6. 通知逻辑唯一键是 aggregateKey + 接收人 + 通知类型。取消后保留 visible=0 的记录及版本，旧消息晚到不会把通知重新显示。重新点赞产生更高版本，恢复同一行并设为未读；重复消费不会再次重置已读状态。
7. 消费者通过独立的 Spring 事务服务写入 inbox 和 notify_msg，事务提交后才手动 ACK。这里使用 Spring 事务管理，但没有使用 Spring 事件来传递交互通知。
8. 消费异常先将重试任务持久化到 mq_outbox，再 ACK 原消息；按约 5、30、120 秒重试，之后进入死信交换机 paicoding.interaction.dead.exchange。重试使用默认交换机直投失败的队列，其他成功的消费者不必重做。重试存储失败则不 ACK，关闭通道触发恢复。
9. Redis 计数使用 Lua 原子去重，活跃积分使用业务版本防乱序；Redis 成功但 MySQL inbox 提交失败时，再投递不会重复加分。通知队列不依赖 Redis 计数更新成功。
10. WebSocket 提醒在通知落库且 ACK 后尽力发送；离线或推送失败不影响站内列表。进程在落库后退出可能漏掉即时弹窗，但刷新通知页能看到持久化记录。

投递语义为“至少一次”，业务效果通过数据库唯一约束和版本控制实现幂等，不能把 broker 投递次数理解为“恰好一次”。

## 2. 启动前准备

### 2.1 Java 8 和完整构建

本仓库要求用 Java 8 构建、测试。不要执行 mvn clean。

~~~powershell
Set-Location D:\java_workplace\paicoding
# 本次验证准备的临时 Java 8；若该目录不存在，替换为你安装的 JDK 8 目录。
$env:JAVA_HOME = "$env:TEMP\paicoding-java8\jdk8u504-b01"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -version
mvn -B -Pdev -pl paicoding-web -am install -DskipTests
~~~

两条版本检查都应显示 Java 1.8。先 install 全部依赖模块，再单独启动 web，避免读取本地 Maven 仓库里的旧 service 包。

### 2.2 启动 RabbitMQ

先启动 Docker Desktop 并等到引擎就绪，docker version 应包含 Server。MySQL、Redis 沿用你项目已有配置；下面主环境 Compose 只增加 RabbitMQ。

~~~powershell
$env:PAICODING_MQ_USERNAME = "paicoding"
$env:PAICODING_MQ_PASSWORD = "请替换为你的本地MQ密码"
$env:PAICODING_MQ_HOST = "127.0.0.1"
$env:PAICODING_MQ_PORT = "5672"
$env:PAICODING_MQ_VHOST = "/"

docker compose -p paicoding-mq -f docker-compose.mq.yml up -d --wait
docker compose -p paicoding-mq -f docker-compose.mq.yml ps
~~~

打开 http://127.0.0.1:15672，用上面设置的账号密码登录。若已有 RabbitMQ，直接配置它的地址、端口、账号、vhost，无需再启动一个占用相同端口的实例。

Compose 使用固定 hostname 和命名数据卷。重启容器保留消息；不要删除数据卷来做持久化测试。初次初始化后，修改 DEFAULT_USER/PASS 环境变量不会自动修改已有 RabbitMQ 用户密码，应使用管理界面修改并同步应用配置。

PowerShell 环境变量仅对当前终端及其子进程生效。在 IDEA 启动时，把相同变量加入运行配置；不要只设置在另一个终端。项目读取 .env 时可将变量加入你自己的 .env，但不要提交密码。

### 2.3 启动应用并确认数据库升级

~~~powershell
$env:PAICODING_MQ_CONSUMER_ENABLED = "true"
$env:PAICODING_MQ_PUBLISHER_ENABLED = "true"
mvn -Pdev -pl paicoding-web spring-boot:run "-Dspring-boot.run.fork=false"
~~~

Windows profile 已包含 fork=false，此处显式指定也可以避免此前 CreateProcess error=206。无需额外启动前端，现有 Thymeleaf 页面由 Spring Boot 提供。访问 http://127.0.0.1:8080。

首次启动由 Liquibase 自动执行 changeset 20260907_interaction_mq。不要在正常启动前手动重复执行同一个 SQL 文件。数据库账号需要建表、改表权限。

在项目实际数据库中执行（默认库名 pai_coding）：

~~~sql
SELECT DATABASE();
SELECT id, exectype, dateexecuted
FROM DATABASECHANGELOG WHERE id='20260907_interaction_mq';

SHOW TABLES LIKE 'mq_%';
SHOW INDEX FROM mq_inbox;
SHOW INDEX FROM notify_msg WHERE Key_name='uk_notification_key';
SHOW COLUMNS FROM notify_msg LIKE 'visible';
~~~

应存在 mq_interaction_state、mq_outbox、mq_inbox；notify_msg 新增 notification_key、last_event_version、last_event_key、visible。

RabbitMQ 管理页面应出现本说明中的两个交换机和四个队列。正常运行时三个业务队列各有消费者；死信队列不自动消费。队列 durable=true，默认 x-queue-type=classic。队列类型是创建时属性，已有同名队列不能直接改成 quorum；集群部署须先规划队列迁移及至少三节点，不要通过删除积压队列切换类型。

## 3. 五类正常业务验收

准备三个不同账号 A、B、C；A 发布一篇文章，B 发表评论，C 用于回复。尽量选择未在旧版本产生通知的新文章/新操作，并避免测试内容触发 AI 自动回复。

打开管理页面 Queues，观察三个业务队列的 Publish/Deliver/Ack 变化。同时使用以下查询观察新消息：

~~~sql
SELECT id,delivery_key,event_key,status,attempt,retry_count,last_error,created_at,sent_at
FROM mq_outbox ORDER BY id DESC LIMIT 30;

SELECT consumer_name,event_key,processed_at
FROM mq_inbox ORDER BY processed_at DESC LIMIT 30;

SELECT id,notification_key,type,notify_user_id,operate_user_id,related_id,comment_id,
       visible,state,last_event_version,last_event_key,msg
FROM notify_msg WHERE notification_key NOT LIKE 'legacy:%'
ORDER BY id DESC LIMIT 30;
~~~

| 操作 | 预期通知 |
| --- | --- |
| B 评论 A 的文章 | A 收到 type=1；comment_id 是新评论 ID |
| C 回复 B 的评论 | A 收到 type=1，B 收到 type=2；二者对应同一 eventKey |
| B 点赞 A 的文章 | A 收到 type=3，key 含 praise:article |
| C 点赞 B 的评论 | B 收到 type=3，key 含 praise:comment，不误通知文章作者 |
| B 收藏 A 的文章 | A 收到 type=4，key 含 collect:article |
| B 关注 A | A 收到 type=5，key 含 follow:user |

每个新业务 eventKey 最终应有三个 inbox 行。回复产生两个 notify_msg 行，但仍只有一个业务事件。通知为异步处理，等待几秒再刷新；评论表、点赞按钮已更新而通知稍后出现是正常现象。

同一已点赞/已收藏/已关注状态再次提交，不应产生新业务事件。取消点赞、取消收藏、取消关注、删除评论后应产生更高版本消息，关联通知 visible=0，通知列表和未读数量不再包含它。删除回复同时隐藏文章评论通知和父评论作者的回复通知。

重新点赞/收藏/关注：同一 notification_key 行恢复 visible=1，last_event_version 增大，state=0。同一个评论内容被提交两次并生成两个 comment_id 属于两条不同评论，本实现不把不同 HTTP 创建请求合并为一条。

## 4. 最直接的证明：停消费者，观察通知停止且队列积压

这是判断通知是否真正经过 RabbitMQ 的关键验收。

1. 停止应用（Ctrl+C），保持 RabbitMQ、MySQL、Redis 运行。
2. 在同一终端设置下列变量，重新启动应用：

~~~powershell
$env:PAICODING_MQ_CONSUMER_ENABLED = "false"
$env:PAICODING_MQ_PUBLISHER_ENABLED = "true"
mvn -Pdev -pl paicoding-web spring-boot:run "-Dspring-boot.run.fork=false"
~~~

3. 确认管理页面三个业务队列 Consumers=0。若不为 0，检查是否还有另一个应用实例在运行。
4. B 对一个新对象执行评论、点赞、收藏、关注；记录对应 eventKey。
5. 等待发送器运行：mq_outbox 变为 SENT；三个队列 Ready 增长；这些 eventKey 的 mq_inbox 仍为 0；notify_msg 中没有对应 last_event_key；A 刷新站内通知也看不到新增通知。
6. 停止应用，将 PAICODING_MQ_CONSUMER_ENABLED 改回 true 后启动。
7. Ready 下降到 0；每个 eventKey 出现三个 inbox 行；通知才落库并显示。

不要使用管理页面的 Get messages + “ack/remove”模式查看待消费内容，否则会手动移走消息。查看时选择重新入队模式，或直接从 mq_outbox.payload 查看原文。

## 5. 持久化与故障恢复

### 5.1 Broker 重启保留积压

保持消费者关闭，执行几次操作，记录队列 Ready 数后：

~~~powershell
docker compose -p paicoding-mq -f docker-compose.mq.yml restart rabbitmq
docker compose -p paicoding-mq -f docker-compose.mq.yml ps
~~~

等管理页面恢复，积压仍应存在。再启用消费者，通知应成功生成。可查看消息属性 delivery_mode=2。持久化保护正常容器/broker 重启，不等于单机磁盘损坏后的高可用保证。

### 5.2 Broker 停机期间业务提交不丢通知

~~~powershell
docker compose -p paicoding-mq -f docker-compose.mq.yml stop rabbitmq
~~~

执行一个新点赞/收藏/关注或评论。只要业务数据库等基础服务正常，操作应成功，mq_outbox 保留 PENDING（或短暂 SENDING），retry_count 增加，日志出现 MQ_PUBLISH_RETRY。此时不会通过 Spring 事件生成通知。

~~~powershell
docker compose -p paicoding-mq -f docker-compose.mq.yml start rabbitmq
~~~

恢复后等待重试，消息进入队列，消费者落库并 ACK。若再重启应用，PENDING 数据仍在；进程中断遗留的 SENDING 最多等待约 60 秒租约到期。

### 5.3 应用停机时保留未消费消息

先用第 4 节制造 Ready 积压，再退出应用并重新启动（消费者为 true）。消息继续处理；无需再次执行原业务操作。

## 6. 消费幂等：把同一业务消息重放 10 次

1. 在 mq_outbox 中选择一条已经成功消费的测试消息，完整复制 payload 字段原文，保存为项目根目录下 mq-replay.json（UTF-8，保存 JSON 对象本身，不要包 SQL 引号）。
2. 记下 event_key、对应 notify_msg 的 id、state、版本，以及三个 inbox 行。
3. 用接收账号打开该通知页，使它变为已读。
4. 在另一个设置好 MQ 账号密码的 PowerShell 终端执行：

~~~powershell
.\scripts\replay-interaction.ps1 -PayloadFile .\mq-replay.json -Consumer notification -Count 10
.\scripts\replay-interaction.ps1 -PayloadFile .\mq-replay.json -Consumer statistics -Count 10
.\scripts\replay-interaction.ps1 -PayloadFile .\mq-replay.json -Consumer activity -Count 10
~~~

脚本调用 RabbitMQ 管理 HTTP API，经默认交换机直投指定消费者队列，设置持久化模式并保留原 eventKey、message_id，x-attempt 设为 0。非默认端口/vhost可传 -ManagementUrl 和 -VirtualHost。

~~~sql
-- 替换为实际完整业务标识
SET @event_key='praise:article:100:actor:10:v1';
SELECT consumer_name,COUNT(*) FROM mq_inbox
WHERE event_key=@event_key GROUP BY consumer_name;

SELECT id,notification_key,visible,state,last_event_version
FROM notify_msg WHERE last_event_key=@event_key;
~~~

预期：每个消费者仍各一行；同一逻辑通知不增加行；已读状态不被重复消息重置；重复消息均可 ACK，队列不会一直积压。统计数和活跃积分在重放前后保持一致。不要修改 eventKey 来做“重复消息”测试，那会被视为不同版本或非法业务标识。

## 7. 消息乱序：取消先到，旧点赞后到

完整验证“取消先到”的步骤：

1. 关闭消费者并重启应用，选择一个从未操作的新对象。
2. 点赞、取消点赞，生成同一 aggregate_key 的 v1(active=true)、v2(active=false)。从 outbox 分别保存 payload。
3. 原始消息保持积压；在消费者仍关闭时，用管理界面调整测试队列顺序不方便。因此采用关闭**发布器**的方案重新选一个新对象：PAICODING_MQ_PUBLISHER_ENABLED=false，PAICODING_MQ_CONSUMER_ENABLED=true，重启应用。
4. 对新对象点赞再取消，outbox 中两条消息保持 PENDING。保存新对象的 v1/v2 payload，分别为 mq-v1.json 和 mq-v2.json。
5. 先重放 v2，再重放 v1 到 notification 队列：

~~~powershell
.\scripts\replay-interaction.ps1 -PayloadFile .\mq-v2.json -Consumer notification
.\scripts\replay-interaction.ps1 -PayloadFile .\mq-v1.json -Consumer notification
~~~

6. 预期：notify_msg 保留 visible=0，last_event_version=2；v1 不会复活通知。
7. 再点赞生成 v3，保存并重放，预期同一行 visible=1、版本=3。
8. 再重放旧 v2，通知仍保持版本=3、visible=1。
9. 恢复发布器为 true，重启应用，让所有待发消息及其他队列正常处理；通知最终状态保持正确。

若只验证“旧消息不会覆盖新状态”，可在正常消费完成 v2/v3 后直接重放旧 v1/v2，无需关闭发布器。

## 8. 重试、死信与人工恢复

### 8.1 无效消息进入死信

在 RabbitMQ 管理页面 Exchanges → amq.default → Publish message：
- routing key：paicoding.notification.queue
- payload：{"broken":true}
- delivery mode：2

预期：日志 MQ_CONSUME_FAILED、MQ_DEAD；mq_outbox 新增 dead:notification:invalid:... 任务，随后 SENT；死信队列 Ready 增加；不会写入 notify_msg 或 inbox。错误 payload 不应无限立即重新入队。

### 8.2 可恢复的消费异常

建议在第 10 节的独立环境测试；不要暂停共享的数据库或 Redis。可停止测试 Redis，触发统计/活跃消费失败，通知消费者仍能落库。可观察：
- mq_outbox 中 retry:statistics:... / retry:activity:...；
- 第 1、2、3 次重试约等待 5、30、120 秒，再加发送轮询与连接耗时；
- 超过 3 次进入死信；不会因一个统计错误而重复生成通知。

查询：

~~~sql
SELECT id,delivery_key,event_key,status,attempt,next_retry_time,last_error
FROM mq_outbox WHERE delivery_key LIKE 'retry:%' OR delivery_key LIKE 'dead:%'
ORDER BY id DESC LIMIT 30;
~~~

last_error 记录发送失败；消费失败原因在 MQ_CONSUME_FAILED 日志中。死信路由键表示原失败消费者，x-delivery-key 同样包含消费者名。

### 8.3 修复后重放

修复根因，从死信消息或对应 outbox 提取原始 payload，使用第 6 节脚本只重放到原失败消费者，保留 eventKey。脚本重置 x-attempt=0，给予新的重试机会。确认处理成功后再在管理界面清理对应测试死信；不要先清空整个死信队列。

重复的重试 delivery_key 在既有任务已 SENT 时可重新激活；PENDING/SENDING 任务保持原状，避免手工恢复遇到相同重试阶段时丢失补发。

## 9. 自动化测试及本次验证结果

~~~powershell
mvn -B -Pdev -pl paicoding-service -am test "-Dtest=NotificationTransactionTest,InteractionDeliveryTest,InteractionBrokerIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
~~~

默认不设置 PAICODING_MQ_INTEGRATION 时不连接外部中间件。2026-09-07 本次已设置该变量为 true，并使用 Java 8 连接独立的真实 MySQL、RabbitMQ、Redis 完成验证：
- 全项目 web 及依赖模块 install -DskipTests：成功。
- NotificationTransactionTest：8 项通过。
- InteractionDeliveryTest：8 项通过。
- InteractionBrokerIntegrationTest：2 项通过，没有跳过。覆盖真实投递、重复消费、不可路由消息，以及评论、回复、点赞、收藏、关注及其取消/删除。
- 合计 18 项测试，Failures=0、Errors=0、Skipped=0；构建成功。日志保存在项目根目录 mq-real-integration.log，JUnit 报告位于 paicoding-service/target/surefire-reports。
- scripts/test-mq-persistence.ps1 实际运行通过：创建独立 durable 队列、投递 delivery_mode=2 消息、重启测试 RabbitMQ，重启后成功取出并确认同一条消息。

事务测试使用 H2 MySQL 模式、真实 MyBatis 与 Spring 事务代理，覆盖五类通知/回复收件人、12 次并发重复消费、乱序取消、部分回复失败整体回滚、业务/outbox 一起回滚、强制业务事务、非法标识、重试再激活。
投递测试覆盖 ACK 时序、持久化重试交接、交接失败不 ACK、ACK 失败不重复安排重试、非法消息、delivery_mode/message_id、正向确认但消息被退回、定向重试与死信。

此前 Docker 引擎不可连接的问题已恢复。本轮使用 Docker Server 29.6.1，三个独立测试容器均健康。MySQL 实际执行新增迁移及历史收藏去重；RabbitMQ 实际完成投递和 ACK；Redis 实际更新计数。业务测试没有启动网页、没有模拟浏览器点击，WebSocket 提醒使用 Mock；本次通过的是后端通知链路集成验证。

## 10. 独立真实中间件集成测试

此环境与应用主环境使用不同端口：MySQL 3308、Redis 6380、RabbitMQ 5673 / 管理页面 15673。凭据 mq-test-only 仅用于绑定回环地址的测试容器。

~~~powershell
docker compose -p paicoding-mq-it -f docker-compose.mq-test.yml up -d --wait
$env:PAICODING_MQ_INTEGRATION = "true"
mvn -B -Pdev -pl paicoding-service -am test "-Dtest=InteractionBrokerIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
Remove-Item Env:PAICODING_MQ_INTEGRATION
~~~

测试连接专用 vhost mq-integration，创建随机 MySQL 库 mq_it_<随机值>，测试后只删除自己创建的库，不连接应用数据库。测试实际执行新增迁移 SQL，并校验历史收藏去重；使用真实 broker 发布持久化消息、confirm/return、拉取消息并调用实际消费者及手动 ACK；使用真实 Redis；重放完整事件验证 inbox、通知、统计不重复。WebSocket 推送服务使用 Mock，HTTP 页面与真实 WebSocket 不在此集成测试覆盖范围。

预期测试结果：2 项通过，0 skipped。若显示 skipped，检查同一个 PowerShell 窗口是否设置了 PAICODING_MQ_INTEGRATION=true。

等上述集成测试结束后，还可以实际重启测试 RabbitMQ 验证消息持久化：

~~~powershell
.\scripts\test-mq-persistence.ps1
~~~

脚本只重启 paicoding-mq-it 项目的 RabbitMQ，创建并最终删除自己的随机测试队列；成功时输出 PASS。请勿与正在运行的集成测试同时执行。

本轮测试环境保持运行，便于查看：管理页面 http://127.0.0.1:15673，账号 mqtest，密码 mq-test-only，vhost 为 mq-integration。正常业务队列在测试完成后已消费完毕；没有后台网页应用消费者，测试由实际 AMQP 拉取消息调用消费者处理。

测试完成可停止并移除此专用 Compose 环境：

~~~powershell
docker compose -p paicoding-mq-it -f docker-compose.mq-test.yml down -v
~~~

该命令针对独立测试项目，不能替换成主环境项目名。主环境持久化验收应使用 stop/start/restart，不能使用 down -v。

## 11. 数据兼容与运行边界

- 历史通知全部保留。能确定业务身份的历史收藏、关注：最新记录接入逻辑唯一键，旧重复行隐藏；旧记录初始版本为 0。
- 历史评论/回复及点赞记录缺少可靠的目标区分信息，不冒险将它们与新事件自动合并，使用 legacy:<id> 保留。因此旧版本遗留的此类通知不会随新消息自动撤销；上述幂等、取消测试应使用升级后的新业务。新评论、评论点赞都明确记录 comment_id/目标类型。
- 不要删除 mq_inbox、mq_interaction_state、通知的不可见记录或 Redis 去重账本来“清缓存”，这些是防重复、防乱序所需状态。outbox/inbox 的归档保留期应结合允许的重放窗口设计，本实现未自动删除它们。
- Redis 使用 mq:statistics:events 与 mq:activity:versions；应开启持久化、避免驱逐去重账本。Redis 数据整体丢失后的计数和排行重建属于单独运维流程，不能只删除 MySQL inbox 后盲目重放。
- 活跃积分按消息业务发生日计入：每条评论/回复 3 分；点赞、收藏、关注各 2 分；同一操作当日取消后对应贡献归零。新评论按 comment_id 区分，不按“同一篇文章当天只能评论计分一次”合并。
- 单机 durable classic 队列满足正常重启持久化；高可用需额外部署 RabbitMQ 集群与 quorum 队列。
- 初次部署请停止旧版本应用实例后统一升级，防止新旧代码混跑绕过统一版本锁。若以前手工创建过相同名称但不同属性的队列，启动会出现 PRECONDITION_FAILED，需先处理旧队列数据并安排迁移。

## 12. 故障定位速查

| 现象 | 检查 |
| --- | --- |
| 没有四个新队列 | 是否启动新 web 包；RabbitMQ 连接账号、vhost、权限；启动日志 |
| outbox 有数据但一直 PENDING | publisher-enabled、broker 连通性、last_error、MQ_PUBLISH_RETRY |
| outbox SENT、队列 Ready 不降 | consumer-enabled、Consumers 数、是否存在数据库错误 |
| notification 有 inbox，但页面看不到 | notify_user_id 是否当前账号；visible 是否为 1；是否查看正确通知类型 |
| 通知有了但统计未更新 | statistics/activity inbox、Redis 连接、重试/死信及日志 |
| 消息重放被拒绝 | payload 是否完整；业务 key/version 是否匹配；是否错误复制了 SQL 转义后的字符串 |
| 编译成功但运行还用旧行为 | 必须先根目录 -am install，再启动 web；停止其他旧应用实例 |
| CreateProcess error=206 | 使用 fork=false；不要附加依赖 fork 的 JVM 参数 |
| 本机没有 Docker Server | 启动 Docker Desktop，等引擎就绪；仅有 docker 客户端版本不代表 broker 已运行 |

建议验收至少留存四份证据：关闭消费者时的积压截图、恢复消费后的 inbox/notify_msg 查询、同一 eventKey 重放后的唯一行查询、broker 重启前后的积压记录。
