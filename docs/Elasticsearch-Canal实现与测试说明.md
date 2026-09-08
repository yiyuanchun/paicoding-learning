> 本机部署已改为统一使用 `paicoding-mysql/pai_coding`。请优先阅读 [本机业务库启动与验收](本机业务库启动与验收.md)，统一运行 `scripts/start-local.ps1`。本文的独立测试容器与测试结果属于历史验证，不是当前业务部署。

# Elasticsearch + Canal 实现与测试说明

## 1. 本次实现

搜索链路：
用户输入 → 原搜索接口 → Elasticsearch 分词匹配、拼写容错、相关性排序和高亮 → 查询 MySQL 文章展示信息 → Thymeleaf 页面。

同步链路：
MySQL 事务提交 → Binlog → Canal Server → Java 消费者 → 持久化收件箱 → 持久化文章同步任务 → 查询 MySQL 最新完整文章 → Elasticsearch 外部版本写入 → 标记任务完成。

原有 RabbitMQ 通知功能保持独立。文章索引更新不再依赖 ArticleMsgEvent；原 ArticleSearchSyncListener 已移除。

### 搜索行为

- 保留 /search、/search/api/hint、/search/api/list 接口。
- 标题、短标题、摘要、正文通过 multi_match 检索，标题权重最高。
- 短语匹配加分；低权重 fuzzy 分支使用 AUTO、prefix_length=1、max_expansions=30。
- 输入 elastcsearch 可以命中 Elasticsearch；高亮的是实际匹配到的 Elasticsearch。
- 默认使用 ES 内置 cjk 分析器，无需下载 IK 插件。中文“消息队列”已纳入真实测试。
- 安装与 ES 版本匹配的 IK 后，可设置 PAICODING_ES_ANALYZER=ik_max_word 和 PAICODING_ES_SEARCH_ANALYZER=ik_smart；分析器变化须使用新索引，不能直接修改现有字段分析器。
- ES 正常返回零条结果时，页面显示零条，不回退 SQL、不按关键字补建索引。
- 启用 ES 后，连接失败会明确报搜索暂不可用；ES 开关关闭时保留原 SQL 搜索兼容路径。
- 页面分别显示 titleHighlight 和 contentHighlight；只允许受控 mark 标签，其余 HTML 转义。
- 公共索引不保存付费、登录限制、星球限制文章的正文，也不保存教程关联文章的正文。这些文章仍可按公开标题、摘要检索。
- 返回页面前再次检查 MySQL 发布、删除和正文访问类型，避免索引延迟泄露已受限正文。
- 当前分页限制为前 10000 条，单页最多 100 条。

### 同步覆盖

| 表 | 处理内容 |
|---|---|
| article | 新建、标题/摘要修改、发布、下架、阅读类型变化、逻辑/物理删除 |
| article_detail | 正文新增版本、修改、删除；重新读取最新有效版本 |
| user_info | 作者信息变化，按文章 ID 分页更新其文章 |
| column_article | 教程关系变化；同时处理变更前后的文章 ID |
| search_cdc_signal | 全量补建和定期校验的控制标记 |

其他同步元数据表不在订阅范围内。Canal 仍可能返回被过滤表的空事务头尾，客户端会丢弃这些空事务，不将它们写回收件箱，避免反馈循环。

## 2. 如何启动当前项目

### 2.1 准备 MySQL

当前开发配置使用已有 MySQL 的 localhost:3307，默认数据库 pai_coding。请在数据库客户端执行只读检查：

```sql
SELECT @@log_bin, @@binlog_format, @@binlog_row_image, @@server_id;
SHOW VARIABLES LIKE 'binlog_expire_logs_seconds';
```

要求：

- log_bin = 1；
- binlog_format = ROW；
- binlog_row_image = FULL；
- server_id 非零且与复制客户端区分；
- 日志保留时间覆盖预期最长故障时间，例如 7 天。

不满足时，在该 MySQL 实例自己的启动配置中添加：

```ini
server-id=1908
log-bin=mysql-bin
binlog-format=ROW
binlog-row-image=FULL
binlog-expire-logs-seconds=604800
```

按该实例现有部署方式重启。不要把测试环境的空数据库替换到现有项目上。

### 2.2 准备 Canal 数据库账号

由数据库管理员准备专用账号，连接来源限定为 MySQL 实际看到的 Canal 容器来源 IP，不使用任意来源通配符。

最小权限：

| 权限 | 范围 | 用途 |
|---|---|---|
| SELECT | 项目数据库 | 读取表结构和字段元数据 |
| REPLICATION SLAVE | MySQL 实例级 | 订阅 Binlog |
| REPLICATION CLIENT | MySQL 实例级 | 读取复制位点 |

MySQL 的两个复制权限是实例级权限。容器经过 Docker Desktop NAT 时，MySQL 看到的来源可能是网关 IP，应按实际来源限定。

本次没有自动修改现有 MySQL 的账号权限；启动脚本也不执行创建账号、授权或重置密码。

在项目根目录 .env.local 中加入：

```dotenv
PAICODING_CANAL_DB_USERNAME=你的专用复制账号
PAICODING_CANAL_DB_PASSWORD='该账号密码'
```

原有 .env 中的 PAICODING_DB_PASSWORD 仍用于应用自身连接数据库，两者不要混淆。.env.local 已被 Git 忽略。

### 2.3 启动

在项目根目录的 PowerShell 中运行：

```powershell
.\scripts\start-search.ps1
```

脚本会：

1. 读取现有环境配置和 Canal 账号。
2. 启动项目专用 Elasticsearch、Canal 容器。
3. 在 .env.local 中保存 ES 和 Canal 开关、地址等本地配置。
4. 使用 Java 8 编译安装项目模块，不执行 mvn clean。
5. 使用 Maven spring-boot:run 启动项目，沿用 Windows 的非 fork 运行配置，避免长 classpath 导致 error=206。

注意：本项目当前默认打包的是普通 JAR，脚本使用 Maven 启动，不使用 java -jar。

默认 Java 8 路径为本机此前准备的临时 JDK。路径不同时：

```powershell
.\scripts\start-search.ps1 -Java8Home '你的 JDK8 目录'
```

只准备中间件和本地配置，随后通过 IDEA 启动：

```powershell
.\scripts\start-search.ps1 -InfrastructureOnly
```

数据库名称不同时传入 -Database；MySQL 地址不同时传入 -MySqlAddress。已有应用占用 8080 时，先停止旧实例，或用 -Port 8082。

项目现有端口工具会优先复用 .dev-port 中记录的可用端口，实际访问地址以启动日志为准；本次完整应用 HTTP 验证实际使用 8080。使用 IDEA 且数据库名称不同时，也需要为应用设置 database.name。

### 2.4 端口与配置

| 服务 | 项目运行环境 | 独立测试环境 |
|---|---|---|
| MySQL | 已有 3307 | 3309 |
| Elasticsearch | 9201 | 9202 |
| Canal TCP | 11111 | 11112 |
| 数据库 | pai_coding，或脚本指定 | search_it |

项目专用 ES 使用本地 HTTP，不启用认证，仅绑定 127.0.0.1。此 Docker 配置用于本机开发，正式部署应按实际安全策略配置认证、TLS、副本和资源。

Java 客户端使用项目现有的 ES 7.17.4 依赖，测试服务端为 ES 8.10.4；Canal 客户端、协议和服务镜像为 1.1.7。

主要环境变量：

```dotenv
PAICODING_ES_OPEN=true
PAICODING_ES_HOSTS=127.0.0.1:9201
PAICODING_ES_SCHEME=http
PAICODING_ES_ARTICLE_INDEX=paicoding_article_v2
PAICODING_CANAL_ENABLED=true
PAICODING_CANAL_HOST=127.0.0.1
PAICODING_CANAL_PORT=11111
PAICODING_CANAL_DESTINATION=paicoding
```

PAICODING_CANAL_DB_* 是 Canal Server 连接 MySQL 的账号。
PAICODING_CANAL_USERNAME/PASSWORD 是 Java 客户端连接 Canal Server 的认证配置；本地 Canal TCP 默认不配置这组认证。

应用启动后 Liquibase 自动创建同步表。首次补建通过控制标记触发，必须等该标记进入 Binlog 消费流后才扫描文章。第一次启动可能需要等待几十秒。

## 3. 如何确认真的使用了 ES 和 Canal

### 3.1 查看服务

```powershell
docker compose --env-file .env.local -p paicoding-search -f docker-compose.search.yml ps
Invoke-RestMethod 'http://localhost:9201/_cat/indices/paicoding_article_v2?format=json'
```

管理员登录后访问：

```text
GET /api/admin/article/search/status
```

关键字段：

- canalConnected：当前进程是否连接到 Canal；多实例时只有持有租约的进程为 true。
- inboxPending：尚未完成路由的收件箱数量。
- pending：尚未追平当前请求版本的文章任务数。
- retrying：处于重试中的任务数。
- backfillEnqueued：全量扫描任务已生成，不代表 ES 已全部写完。
- lastError：最近捕获错误。
- lastReceivedAt：最近确认批次时间。

积压归零并经过 ES refresh 后，搜索结果应追平数据库。

### 3.2 页面搜索

使用一篇正常公开、未关联教程的文章：

- 标题：Elasticsearch 全文检索实践
- 正文包含：消息队列、canalverifytoken

依次搜索：

| 输入 | 预期 |
|---|---|
| Elasticsearch | 标题命中且高亮 |
| elastcsearch | 拼写容错命中，Elasticsearch 被高亮 |
| 消息队列 | 正文命中并展示命中片段 |
| canalverifytoken | 正文命中 |
| 一个确定不存在的词 | 空结果，不补建索引 |

高亮结果必须包含实际 mark 标签；原文章中的 script、img 事件属性不能作为可执行 HTML 输出。

### 3.3 直接改数据库

选择你自己的测试文章 ID，在数据库客户端执行：

```sql
UPDATE article_detail
SET content = CONCAT(content, '\ncanal_sql_probe_20260908')
WHERE article_id = 你的测试文章ID
  AND deleted = 0;
```

不访问发布接口，不重启应用，等待同步后搜索 canal_sql_probe_20260908。

同时查看：

```sql
SELECT article_id, requested_version, completed_version,
       retry_count, next_retry_time, last_error
FROM search_index_task
WHERE index_name = 'paicoding_article_v2'
ORDER BY updated_at DESC
LIMIT 20;
```

requested_version 与 completed_version 最终相等，且 ES 可查到新正文，证明更新经过 Binlog/Canal 链路。

正文存在多个历史版本时，投影读取最大有效 version；正式验证可只修改最新版本，或插入更高版本。

### 3.4 下架、删除和权限变化

分别验证：

1. 文章下架后不再出现在公共搜索结果中。
2. 逻辑删除、物理删除后不再命中。
3. 公开文章改为付费/登录限制后，其正文词不再可搜索，公开标题仍可搜索。
4. 作者改名后 ES 文档 authorName 更新。
5. 文章关联教程后，公共索引移除其正文。

ES 中删除文章保留 status=0、deleted=1 的版本占位文档。这是防止旧任务恢复已删除内容的设计，不是删除失败。

## 4. 故障恢复测试

### ES 停机

```powershell
docker compose --env-file .env.local -p paicoding-search -f docker-compose.search.yml stop elasticsearch
```

修改测试文章。观察收件箱和同步任务仍能持久化，任务未完成且产生重试信息。此时搜索应明确失败，不应静默回退 MySQL。

恢复：

```powershell
docker compose --env-file .env.local -p paicoding-search -f docker-compose.search.yml start elasticsearch
```

等待自动重试完成，再搜索新内容。重试采用指数退避，上限默认 300 秒。

### Canal 停机

停 Canal，修改文章，再启动 Canal。服务端从持久化位点续传，Java 客户端自动重新连接。无需再次编辑文章。

Canal 的确认位点和表结构缓存挂载在独立数据卷。不要为了重启服务删除该卷。

### 消费幂等和崩溃恢复

自动测试覆盖：

- 同一个 Binlog 行事件重复接收，不重复增加文章请求版本。
- 一个事务跨多个批次，到事务结束前不执行投影。
- 事务结束批次的 ACK 丢失后重放，不要求重新收到已确认的 BEGIN。
- ES 已写成功、任务尚未标记完成时模拟进程中断；重试同版本不会覆盖更新数据。
- 较旧版本任务不能覆盖新文档，也不能恢复删除占位文档。
- 同步事件持久化失败时不向 Canal ACK。
- 跨批次确认按 batchId 顺序逐个执行。

## 5. 可重复运行的自动测试

无需配置现有业务数据库复制账号，可以直接运行隔离测试：

```powershell
.\scripts\test-search.ps1
```

该脚本只使用 docker-compose.search-test.yml 创建的 search_it 数据库和测试服务。测试会暂时停止并恢复它自己的 ES 容器、重启它自己的 Canal 容器，不操作其他项目容器。

搜索核心测试：

| 测试类 | 数量 | 验证内容 |
|---|---:|---|
| SearchQueryTest | 4 | 高亮转义、受限正文保护、零结果不写索引、ES 故障不隐式降级 |
| CanalAcknowledgementTest | 3 | 持久化后顺序 ACK、忽略空事务、数据库失败不 ACK |
| SearchRealIntegrationTest | 8 | 真实全量补建、英文/中文/拼写容错、高亮、直接 SQL、幂等、故障恢复 |

真实页面模板测试：

```powershell
mvn -pl paicoding-web -am "-Dtest=SearchTemplateTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

该命令也必须使用 Java 8。SearchTemplateTest 的 2 项测试实际渲染项目 Thymeleaf 文章卡片，检查高亮标签和普通文章显示。

本机已完成：上述 17 项相关测试通过，0 失败；其中 8 项连接真实 Docker MySQL、Canal、Elasticsearch。完整多模块编译、页面资源打包通过。测试记录见 logs/search-test.log、logs/search-final-build.log 及各模块 target/surefire-reports。

此外，已在隔离的 search_ui 数据库启动完整 Spring Boot 应用，验证新 Liquibase 迁移和真实 HTTP 链路：直接通过 SQL 修改文章标题后，/search/api/list 自动返回新内容及 mark 标签，/search 页面返回 HTTP 200 并展示高亮。证据保存于 logs/search-http-proof.json。临时应用验证完成后关闭，未把该测试数据库替换成用户业务数据库。

## 6. 可靠性实现细节

### 持久化表

- search_cdc_receipt：对源实例、源代次、Binlog 文件、事件位置、事件类型和索引名生成 SHA-256 唯一标识，去重事务标记和行事件。
- search_cdc_inbox：保存受影响文章/作者的路由任务、事务标识、就绪标记和分页游标。
- search_index_task：按索引名 + 文章 ID 保存请求版本、完成版本、租约、重试时间及错误。
- search_cdc_checkpoint：记录事务状态、接收位置、全量补建状态和消费者租约。
- search_cdc_signal：在源 Binlog 流中标记补建起点。

Canal ACK 表示变更已可靠接收，不代表 Elasticsearch 已更新；任务完成状态以 completed_version 为准。

版本由数据库同步任务维护，不使用 article.update_time，因为正文版本、作者名变化不一定更新文章时间。

ES 写入使用 version_type=external。只有明确的版本冲突可视作已处理/已被较新版本取代，其余异常都保留任务重试。

### 初始化和修复

首次补建、管理员重建、周期校验都使用可恢复的分页任务。扫描包含数据库现存文章 ID 和历史同步任务的文章 ID，因此也会修复物理删除。

管理员重建接口：

```text
POST /api/admin/article/search/rebuild
```

需要管理员权限。返回成功表示控制标记已提交，完成情况通过状态接口判断。

本次实现采用非破坏式原索引重投影，不再删除索引。它没有实现新旧索引别名原子切换；分析器或映射不兼容变化应配置新索引名并完整补建。

### 运维边界

- 最终一致性依赖 Binlog 尚未过期、源配置正确且失败任务最终能重试成功。
- Binlog 被清理导致无法续传时，需要重设 Canal 起始位点并触发完整重投影，不能声称自动恢复已不存在的日志。
- MySQL 恢复或重置 Binlog 时更新 PAICODING_CANAL_SOURCE_EPOCH，普通重启不要修改。
- 同一 Canal destination 服务一个搜索索引配置；多应用实例应共享该配置，消费者通过数据库租约选主。
- 同步元数据和文章投影查询使用应用默认数据源，默认数据源必须指向 MySQL 主库；不能把默认数据源配置成延迟的只读副本。
- 当前客户端等待事务边界后确认，限制未确认缓存 10000 个 Entry；配套 Canal 使用 ITEMSIZE 和 16384 槽。大规模导入应拆分源事务；超限会保留未确认位点并报错，不静默丢弃。
- DDL 由部署配置过滤，表结构变更需同步修改投影 SQL/映射并执行重投影。
- 收件箱、事件去重记录、占位文档保留用于重放和排障。清理需覆盖 Binlog 保留及最大重试窗口，不能随意删除任务版本记录。
- 默认每日执行一次完整重投影修复遗漏；这不是双向同步，ES 不向 MySQL 回写文章内容。
