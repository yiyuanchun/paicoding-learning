
一个基于 Spring Boot、MyBatis-Plus、MySQL、Redis、ElasticSearch、MongoDB、Docker、RabbitMQ 等技术栈实现的社区系统。


## 一、项目介绍
### 组织结构

```
paicoding
├── paicoding-api -- 定义一些通用的枚举、实体类，定义 DO\DTO\VO 等
├── paicoding-core -- 核心工具/组件相关模块，如工具包 util， 通用的组件都放在这个模块（以包路径对模块功能进行拆分，如搜索、缓存、推荐等）
├── paicoding-service -- 服务模块，业务相关的主要逻辑，DB 的操作都在这里
├── paicoding-ui -- HTML 前端资源（包括 JavaScript、CSS、Thymeleaf 等）
├── paicoding-web -- Web模块、HTTP入口、项目启动入口，包括权限身份校验、全局异常处理等
```

#### 环境配置说明

资源配置都放在 `paicoding-web` 模块的资源路径下，通过maven的env进行环境选择切换

当前提供了四种开发环境

- resources-env/dev: 本地开发环境，也是默认环境
- resources-env/test: 测试环境
- resources-env/pre: 预发环境
- resources-env/prod: 生产环境

环境切换命令

```bash
# 如切换生产环境
mvn clean install -DskipTests=true -Pprod
```

#### 配置文件说明

- resources
  - application.yml: 主配置文件入口
  - application-config.yml: 全局的站点信息配置文件
  - logback-spring.xml: 日志打印相关配置文件
  - liquibase: 由liquibase进行数据库表结构管理
- resources-env
  - xxx/application-dal.yml: 定义数据库相关的配置信息
  - xxx/application-image.yml: 定义上传图片的相关配置信息
  - xxx/application-web.yml: 定义web相关的配置信息

#### `.env` 配置说明

项目支持在仓库根目录通过 `.env` / `.env.local` 管理本地或服务器上的敏感配置。

- 首次使用时，可复制 `.env.example` 为 `.env`
- Spring Boot 启动前会自动加载 `.env` / `.env.local`
- `launch.sh` 和 `deploy.sh` 启动 jar 时，也会自动 `source` 这两个文件
- `.env.local` 适合放机器本地专属配置，优先级高于 `.env`
- 进程环境变量和 `-D` 启动参数优先级仍然更高
- `.env`、`.env.local` 已加入 `.gitignore`，不会提交到仓库

示例：

```bash
cp .env.example .env
```

常见配置包括：

- `PAICODING_DB_PASSWORD`
- `PAICODING_OPENAI_API_KEY`
- `PAICODING_ZHIPU_API_SECRET_KEY`
- `PAICODING_XUNFEI_APP_ID`
- `PAICODING_XUNFEI_API_KEY`
- `PAICODING_XUNFEI_API_SECRET`
- `PAICODING_XUNFEI_API_PASSWORD`
- `PAICODING_DEEPSEEK_API_KEY`
- `PAICODING_DOUBAO_API_KEY`
- `PAICODING_DOUBAO_ENDPOINT`
- `PAICODING_WX_APP_SECRET`
- `PAICODING_OSS_AK`
- `PAICODING_OSS_SK`
- `PAICODING_OSS_ENDPOINT`
- `PAICODING_OSS_BUCKET`
- `PAICODING_OSS_HOST`

### 技术选型

后端技术栈

|         技术          | 说明                   | 官网                                                                                                 |
|:-------------------:|----------------------|----------------------------------------------------------------------------------------------------|
| Spring & SpringMVC  | Java全栈应用程序框架和WEB容器实现 | [https://spring.io/](https://spring.io/)                                                           |
|     SpringBoot      | Spring应用简化集成开发框架     | [https://spring.io/projects/spring-boot](https://spring.io/projects/spring-boot)                   |
|       mybatis       | 数据库orm框架             | [https://mybatis.org](https://mybatis.org)                                                       |
|    mybatis-plus     | 数据库orm框架             | [https://baomidou.com/](https://baomidou.com/)                                                     |
| mybatis PageHelper  | 数据库翻页插件              | [https://github.com/pagehelper/Mybatis-PageHelper](https://github.com/pagehelper/Mybatis-PageHelper) |
|    elasticsearch    | 近实时文本搜索              | [https://www.elastic.co/cn/elasticsearch/service](https://www.elastic.co/cn/elasticsearch/service) |
|        redis        | 内存数据存储               | [https://redis.io](https://redis.io)                                                               |
|      rabbitmq       | 消息队列                 | [https://www.rabbitmq.com](https://www.rabbitmq.com)                                               |
|       mongodb       | NoSql数据库             | [https://www.mongodb.com/](https://www.mongodb.com/)                                               |
|        nginx        | 服务器                  | [https://nginx.org](https://nginx.org)                                                             |
|       docker        | 应用容器引擎               | [https://www.docker.com](https://www.docker.com)                                                   |
|      hikariCP       | 数据库连接                | [https://github.com/brettwooldridge/HikariCP](https://github.com/brettwooldridge/HikariCP)         |
|         oss         | 对象存储                 | [https://help.aliyun.com/document_detail/31883.html](https://help.aliyun.com/document_detail/31883.html)                                               |
|        https        | 证书                   | [https://letsencrypt.org/](https://letsencrypt.org/)                                               |
|         jwt         | jwt登录                | [https://jwt.io](https://jwt.io)                                                                   |
|       lombok        | Java语言增强库            | [https://projectlombok.org](https://projectlombok.org)                                             |
|        guava        | google开源的java工具集     | [https://github.com/google/guava](https://github.com/google/guava)                                 |
|      thymeleaf      | html5模板引擎            | [https://www.thymeleaf.org](https://www.thymeleaf.org)                                             |
|       swagger       | API文档生成工具            | [https://swagger.io](https://swagger.io)                                                           |
| hibernate-validator | 验证框架                 | [hibernate.org/validator/](hibernate.org/validator/)                                               |
|     quick-media     | 多媒体处理                | [https://github.com/liuyueyi/quick-media](https://github.com/liuyueyi/quick-media)                 |
|      liquibase      | 数据库版本管理              | [https://www.liquibase.com](https://www.liquibase.com)                                             |
|       jackson       | json/xml处理           | [https://www.jackson.com](https://www.jackson.com)                                                 |
|      ip2region      | ip地址                 | [https://github.com/zoujingli/ip2region](https://github.com/zoujingli/ip2region)                   |
|      websocket      | 长连接                  | [https://docs.spring.io/spring/reference/web/websocket.html](https://docs.spring.io/spring/reference/web/websocket.html)                   |
|      sensitive-word      | 敏感词                  | [https://github.com/houbb/sensitive-word](https://github.com/houbb/sensitive-word)                   |
