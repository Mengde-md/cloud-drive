# Day24 Final 网关路由、Nacos 服务发现与配置隔离

## Checkpoint 0 昨日真实资产与限制

Day23 交付的是**纸上的边界**：`docs/architecture/day23-service-boundaries.md` 写死了
auth / user / file / gateway 四个服务的数据归属与契约，`deploy/docker-compose.yml` 冻结了
MySQL 8.0 + Redis 7.2 的可复现运行环境，11 个测试文件的数据库凭据改为环境变量占位。
Day23 的工程门在 2026-09-22 由用户在本机跑通并取得 12/12 PASS，其中第 6 项曾因测试清理
未按外键顺序删除 `file_shares`/`upload_sessions` 而失败，修复提交 `ec1c747`，
复跑 `Tests run: 90, Failures: 0, Errors: 0`。

Day23 明确没有做、也禁止做的事，正是 Day24 的起点：没有任何独立部署单元，
没有引入 Nacos / Feign / Spring Cloud Gateway，没有任何跨进程调用，仓库是单一 Maven 模块。
调用方直连 `127.0.0.1:8080`，后端地址写死在调用方；没有统一入口，就没有地方放鉴权、
限流、traceId 这些每个服务都要做的横切逻辑；一旦 Day25 拆出第二个进程，
配置会立刻出现多份副本。这三条是 Day24 要处理的入口治理债务。

## Checkpoint 1 唯一增量、真实问题与禁止项

- **真实问题**：
  1. 后端地址写死在调用方——Day25 拆出 auth-service 后，每拆一个服务就要改一次所有调用方；
  2. 没有统一入口——鉴权/限流/链路标识没有统一落点，拆分后必然每个服务各写一份且口径不一；
  3. 配置随进程复制——单体只有一份 `application.properties`，四个进程就是四份副本，
     改一处漏三处是必然结果。

- **唯一增量**：
  1. `gateway/`：独立的 Spring Cloud Gateway 进程（pom、启动类、`application.yml` 路由表、
     `application-nacos.yml` 真实形态、8 项路由表契约测试）。路由 id 按 Day23 定下的未来服务名
     命名而非按今天的进程数命名，今天四条业务路由指向同一个单体地址。
  2. 单体进程接入 Nacos 服务发现与配置中心客户端，**默认全部关闭**：
     `application.properties` 新增 `spring.application.name` 与三行 Nacos 开关（均为 false），
     新增 `application-nacos.properties` 作为真实形态；新增
     `NacosDefaultsOffContractTest` 用断言把"默认关闭"这个契约钉死。
  3. `deploy/docker-compose.yml` 新增 `nacos` 服务（standalone、开启鉴权、8848+9848 双端口），
     `deploy/.env.example` 补齐 Nacos 与服务名/网关相关变量，全部是占位符。
  4. 引入 `springdoc-openapi-starter-webmvc-ui`，`/v3/api-docs` 由代码实时生成并经网关
     `api-docs-route` 暴露；`docs/day22/openapi.yaml` 降级为历史快照。
  5. `docs/architecture/day24-gateway-and-config.md`：路由表与归属映射、服务发现两种形态、
     配置隔离三层（namespace/group/dataId）、热更新边界、本日已知局限。

- **禁止**（对齐总纲第 1 节 Day24 行与《目标架构与34天增量蓝图》Day24 行"明确不提前"）：
  本日不新建 auth-service / user-service / file-service 任何业务服务模块（Day25/26 的增量）；
  不在网关做 JWT 验签与身份透传（Day25）；不引入 Feign、不做任何服务间调用（Day26）；
  不做熔断、灰度、重试策略；不引入 Elasticsearch 与任何 AI/Agent 能力。
  后端在本日结束后仍然只有单体一个业务进程。

## 跨天改动清单（对齐总纲第 4 节第 5 条）

本日修改了此前已提交的文件，逐条记录改动与原因：

| 文件 | 改动 | 原因 |
|---|---|---|
| `pom.xml` | 新增 `spring-cloud-dependencies` 2023.0.1 与 `spring-cloud-alibaba-dependencies` 2023.0.1.0 两个 BOM 导入；新增 nacos-discovery、nacos-config、springdoc 三个依赖 | 服务发现/配置中心客户端与实时 API 文档是本日唯一增量的前提。版本必须与 Spring Boot 3.2.x 对齐，错配的表现是启动期兼容性校验抛异常而非编译失败 |
| `src/main/resources/application.properties` | 新增 `spring.application.name`；新增三行 Nacos 开关（discovery/config/import-check 全部 false）；新增两行 springdoc 路径 | 依赖进来后必须显式关闭，否则 `spring-cloud-starter-alibaba-nacos-config` 在 Spring Boot 3 下强制要求 `spring.config.import`，缺失即启动失败，会让全部 Spring 上下文测试在启动阶段挂掉 |
| `deploy/docker-compose.yml` | 新增 `nacos` 服务与 `clouddrive-nacos-data` 卷 | 真实形态需要一个可复现的注册/配置中心；沿用 Day23 的凭据纪律，全部走 `${VAR:?}` |
| `deploy/.env.example` | 新增 Nacos 与服务名/网关段落 | 与上一条同源，且保证 compose 与应用读同一组变量名 |
| `src/test/resources/application.properties` | 新增 `spring.application.name` 与三行 Nacos 开关（与主配置一致） | **本日第一次构建失败后追加的修复**。该文件自 Day15 起存在（仅一行测试签名密钥），与主配置**同名**；测试 classpath 上 `test-classes` 先于 `classes`，`classpath:/application.properties` 只取第一个匹配，因此主配置在测试中从未生效。详见下文"本日真实构建失败与修复" |

未改动 `SecurityConfiguration`：其 `anyRequest().permitAll()` 已放行 `/v3/api-docs`、`/swagger-ui`，
本日不需要为 API 文档动安全配置，这一点经读码确认而非推测。

## 实现证据

- `gateway/src/main/resources/application.yml` 定义 6 条路由。`auth-service-route`（`/api/auth/**`）、
  `user-service-route`（`/api/entries/**`）、`file-service-route`（`/api/files/**`、
  `/api/upload-sessions/**`）、`share-service-route`（`/api/shares/**`）与
  `docs/architecture/day23-service-boundaries.md` 第 2 节的四服务归属表逐条对应；
  `/api/shares/**` 单独成一条的原因是它是唯一允许匿名访问的业务路径
  （`SecurityConfiguration` 中 `/api/shares/*/download` 为 `permitAll`），将来限流策略与其余不同。
  `monolith-health-route` 用 `/backend-actuator/**` + `StripPrefix=1`，避免与网关自身
  `/actuator` 端点冲突。
- 默认形态下三个 `clouddrive.gateway.*-uri` 属性解析为 `http://127.0.0.1:8080`；
  nacos 形态下同样的路由表解析为 `lb://cloud-drive-app`。路由定义本身一行不变，
  变的只是地址来源，这是"服务发现替代硬编码地址"的最小可验证形态。
- `gateway/src/test/java/.../GatewayRouteTableTest.java` 8 项断言覆盖：路由 id 集合恰好等于
  预期 6 条；四条业务路由今天指向同一地址；默认形态不出现 `lb` 协议；每条路由声明的
  Path 与其未来服务的归属一致；路由表中无重复 Path（重复会导致先匹配者生效，配置文件里肉眼极难发现）；
  默认过滤器包含统一响应头；health 路由带 `StripPrefix=1`；断言只出现 `Path` 一种谓词名。
- `src/test/java/.../NacosDefaultsOffContractTest.java` 5 项断言覆盖：classpath 上**每一份**
  同名 `application.properties` 副本的三个 Nacos 开关均为 false（失败消息带该副本的 URL）；
  测试实际生效的那一份（第一份）同时具备开关与服务名；所有声明了服务名的副本都与网关
  `lb://` 目标名一致；nacos profile 存在且 `spring.config.import` 带 `optional:` 前缀；
  nacos profile 中的用户名/口令/地址全部是 `${}` 占位符而非真实值。该测试读 classpath 上的
  配置文件本身，不启动 Spring 上下文，因此不依赖 MySQL/Redis/Nacos。
  用 `getResources`（复数）遍历全部副本而非 `getResourceAsStream`（单数）只读第一份，
  是本日真实故障之后的修正，原因见下节。

## 本日真实构建失败与修复（首次 `mvn clean test` 未通过，如实记录）

首次执行 `mvn -f pom.xml clean test` 的结果是 `Tests run: 94, Failures: 2, Errors: 43`，
BUILD FAILURE。按总纲第 4 节第 4 条"构建失败先自证（环境问题不是第一解释）"做代码级定位：

- **现象**：43 项为 `Failed to load ApplicationContext`，控制台被 `WebMergedContextConfiguration`
  转储刷屏，根因行不可见；另有 2 项为本日新增契约测试的断言失败，消息是
  `expected: <false> but was: <null>`。
- **取证**：读 `build-output/surefire-reports/*.txt`（Maven 报错末尾已指明该目录），
  拿到完整 `Caused by`：
  `org.springframework.cloud.commons.ConfigDataMissingEnvironmentPostProcessor$ImportException: No spring.config.import set`，
  发生在 `SpringApplication.prepareEnvironment` 阶段，早于任何 Bean 创建。
- **关键反证**：契约测试读 classpath 上的 `application.properties` 得到 `null`，
  而 `src/main/resources/application.properties` 里那三行开关确实存在。说明测试读到的
  不是这一份文件。
- **根因**：`src/test/resources/application.properties` 自 Day15 起就存在（内容只有一行
  测试用 JWT 签名密钥），与主配置**同名**。测试 classpath 上 `build-output/test-classes`
  排在 `build-output/classes` 之前，`classpath:/application.properties` 只命中第一个匹配，
  因此**主配置在测试中从未生效**，且整个过程没有任何警告。Day24 新增的三行 Nacos 禁用开关
  只写进了主配置，测试环境读不到，`ConfigDataMissingEnvironmentPostProcessor` 遂按默认值
  （启用）执行检查并抛异常，导致全部需要 Spring 上下文的测试在启动阶段失败。
- **修复**：（1）把 `spring.application.name` 与三行 Nacos 开关同步写入
  `src/test/resources/application.properties`，并在文件头注明同名遮蔽这一陷阱；
  （2）把 `NacosDefaultsOffContractTest` 从"读第一份"改为"用 `getResources` 遍历
  classpath 上全部同名副本逐一断言"，失败消息附带副本 URL，使"某一份漏了配置"
  这类问题直接指出是哪个文件，而不是表现为几十条无法定位的上下文加载失败。
- **附带发现（本日不处理，仅记录）**：由于同一遮蔽机制，主配置中的
  `mybatis.configuration.map-underscore-to-camel-case`、`spring.data.redis.*`、
  `management.*` 等设置在测试中同样从未生效；既有测试之所以通过，是因为各自用
  `@SpringBootTest(properties = {...})` 显式传入了所需值。改变这一现状会影响全部既有测试，
  不属于 Day24 唯一增量，留待后续单独评估。

## 测试与验收（诚实披露：本 Day 编译/回归执行环境限制）

本次 Day24 的代码与文档改动是在**没有 JDK 17 / Maven / Docker 的沙箱环境**中完成的，
沙箱无法执行任何构建或容器命令，**因此本 Day 未能在当前会话中执行 `mvn clean test`、
`mvn -f gateway/pom.xml clean test`、`docker compose up` 或 `verify_day.ps1 -DayNN 24`**。

与 Day23 不同的是，本日新增了两个外部依赖（Spring Cloud / Spring Cloud Alibaba BOM 与
springdoc），这些构件此前不在 `C:\Environment\m2repo` 中，首次构建需要联网下载；
并且本日新增了一个独立 Maven 工程，其构建**不在**根 pom 的 `mvn clean test` 范围内。
因此本日必须由用户在其 Windows 机器上执行下列命令并留存真实输出：

1. `mvn -f <repo>\pom.xml -Dmaven.repo.local=C:\Environment\m2repo clean test`
   —— 验证新增依赖与三行 Nacos 开关没有破坏原有 90 项回归（预期变为 95 项：新增
   `NacosDefaultsOffContractTest` 的 5 项）。
2. `mvn -f <repo>\gateway\pom.xml -Dmaven.repo.local=C:\Environment\m2repo clean test`
   —— 验证网关工程可编译，且 8 项路由表契约测试全绿。
3. `powershell -ExecutionPolicy Bypass -File verify_day.ps1 -DayNN 24` —— 需全部 12 项 PASS。
4. 手工验收（真实转发与真实注册，自动化测试不覆盖）：
   起 `docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d`，
   分别以默认形态与 `--spring.profiles.active=nacos` 启动单体与网关，
   用 `curl -i http://127.0.0.1:9000/api/auth/login` 验证响应头出现 `X-CloudDrive-Gateway: day24`，
   在 Nacos 控制台确认 `cloud-drive-app` 与 `cloud-drive-gateway` 两个服务出现在
   指定 namespace/group 下。具体步骤见教材第 12 节。

在收到第 1–3 项的真实命令输出并且全部通过之前，Day24 的工程交付**不能**被认定为已验收，
这与项目"绝不凭 claim 认定完成"的红线一致；本文档在此明确不做超出实际验证范围的断言。
第 4 项属于手工证据，未完成前不得声称"网关真的转发成功"或"服务真的注册成功"。

## 面试题与真实问题映射

本 Day 教材第 13/14 节收录与本日真实产出直接对应的面试题，均可用本仓库的路由表、
两份 profile 与 `docs/architecture/day24-gateway-and-config.md` 原文作答，不是脱离项目的背诵题：
"网关路由表为什么按未来服务命名而不是按当前进程命名"、"服务发现相比配置里写死地址
到底解决了什么"、"Nacos 的 namespace、group、dataId 各自隔离什么，填错会怎样"、
"配置中心的热更新有哪些边界，为什么改数据源密码不生效"、"为什么网关的超时可以配、
重试不能随便配"、"为什么把 Nacos 默认关闭反而是工程上更负责的做法"。

## 教材自审 Day02 Day05 对照

| 审计项 | 真实教材证据 | 结论 |
|---|---|---|
| 前日衔接与问题 | 教材第 0-1 节从 Day23"边界只在纸上、调用方直连 8080"推导出地址硬编码、无统一横切点、配置随进程复制三个真实问题 | 通过 |
| 项目源码走读 | 教材逐段走读本仓库 `gateway/application.yml` 六条路由、`application-nacos.yml` 与 `application-nacos.properties` 两份 profile、`pom.xml` 的 BOM 版本对齐、`SecurityConfiguration` 中与 api-docs 放行相关的 `anyRequest().permitAll()` | 通过 |
| 项目测试走读 | 教材逐条走读 `GatewayRouteTableTest` 8 项与 `NacosDefaultsOffContractTest` 4 项断言，解释每一条断言防的是哪一类真实故障 | 通过 |
| Debug/故障排障 | 教材设 5 个独立 Debug 小节：import-check 导致全量测试启动失败、9848 端口未映射导致注册超时、namespace 填名称而非 id 导致配置静默不生效、StripPrefix 缺失导致 actuator 404、热更新改数据源密码不生效 | 通过 |
| 独立复刻/实操 | 教材要求不看答案独立写出一条新路由（含 Path 谓词与 StripPrefix）并补一条对应断言，再独立画出 namespace/group/dataId 三层隔离图 | 通过 |
| 面试与简历 | 教材第 13/14 节收录本日对应面试题清单与简历讲法示例 | 通过 |
| 阶段题覆盖 | 教材含算法、SQL、Java 三类阶段巩固题，与本日入口治理主题结合命题 | 通过 |

本表不代表用户已学会；用户仍需口述、闭卷复刻、记录错因并完成题单，且必须先在其 Windows
机器上跑通上述第 1–3 项命令全部通过，工程交付才能被认定完成。
