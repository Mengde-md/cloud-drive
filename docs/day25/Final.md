# Day25 Final auth-service 拆分、网关鉴权与身份透传

## Checkpoint 0 昨日真实资产与限制

Day24 交付的是**入口治理的骨架**：`gateway/` 是一个独立的 Spring Cloud Gateway 工程，
六条路由按 Day23 定下的未来服务名命名；单体接入了 Nacos 服务发现与配置中心客户端，
但三个开关（discovery / config / import-check）全部默认关闭；`deploy/docker-compose.yml`
增加了 standalone Nacos；`/v3/api-docs` 改为由 springdoc 实时生成。

Day24 的工程门在 2026-09-25 由用户在本机跑通：根模块 `mvn clean test` =
`Tests run: 95, Failures: 0, Errors: 0` BUILD SUCCESS；`gateway` 工程 8 项路由表契约
测试全绿。Day24 本身还有两项未闭合，**Day25 开工时它们仍未闭合**，如实记录在此：
`verify_day.ps1 -DayNN 24` 的第 1、2 项（`feat(day24)` 提交存在、工作区干净）因 Day24
的改动尚未提交而 FAIL；手工验收（真实 Nacos 注册与网关真实转发）从未执行。

Day24 明确没有做、而 Day25 必须处理的事：**四条业务路由的 `uri` 解析到同一个地址**，
也就是说边界只存在于路由 id 的命名里，进程仍然只有一个。"改一行 uri 就能拆"在 Day24
只是推断，从未被任何一次真实运行验证过。同时，网关是纯转发器，不做鉴权——
JWT 校验仍在单体的 filter chain 里，每一个未来的服务都要各自实现一遍；
下游也只认 `Authorization` 头，身份无法在服务之间传递。

## Checkpoint 1 唯一增量、真实问题与禁止项

- **真实问题**：
  1. **拆分从未被验证。** 没有任何一条路由真的指向过第二个进程，Day24 的路由命名
     是一张没有兑现过的支票。
  2. **鉴权没有统一落点。** 只有一个进程时看不出代价；出现第二个进程的那一刻，
     "每服务各写一份验签"立刻变成两份实现、两套口径、两处可能写错的白名单。
  3. **身份无法跨服务传递。** 下游只认 `Authorization`，意味着每个服务都要持有
     签名密钥、都要会解 JWT，与"网关是唯一验签点"的目标背道而驰。

- **唯一增量**：
  1. `auth-service/`：CloudDrive 的第一个真正独立的部署单元（8081）。
     注册/登录/me 三个对外端点 + Day23 第 2.1 节设计的 `/internal/users/{id}/identity`；
     自己的 `SecurityConfiguration`、`JwtTokenService`、只含身份四列的 `UserAccountMapper`；
     `application.yml`（默认形态）与 `application-nacos.yml`（真实形态，Nacos 默认关闭）。
  2. `gateway`：`AuthenticationGlobalFilter` 实现边缘验签 + 身份透传 + 身份头防伪造；
     `auth-service-route` 的 `uri` 第一次与 user/file 分道（8081 vs 8080），
     nacos 形态下指向独立服务名 `cloud-drive-auth`。
  3. 单体：`TrustedGatewayHeaderAuthenticationFilter` + `GatewayTrustProperties`，
     `clouddrive.gateway.trusted-headers.enabled` **默认 false**，
     两份同名 `application.properties` 都写（Day24 同名遮蔽的教训）。
  4. 权限回归测试三组共 34 项新增断言（明细见下文"测试与验收"）。
  5. `docs/architecture/day25-auth-split-and-gateway-authn.md`：
     三进程职责切分、鉴权三步顺序及其安全依据、身份透传契约、信任模型、
     以及 7 条逐条列出的已知局限。

- **禁止**（对齐总纲第 1 节 Day25 行与蓝图第 89 行"file-service 提前迁移"一栏）：
  本日不新建 user-service / file-service；不引入 Feign、不做任何服务间调用（Day26）；
  不做熔断、灰度、重试策略；不迁移 `user_account` 的物理分库；
  不把三个 Java 进程容器化；不做多模块聚合重构；不引入 Elasticsearch 与任何 AI 能力。

## 跨天改动清单（对齐总纲第 4 节第 5 条）

| 文件 | 改动 | 原因 |
|---|---|---|
| `src/main/java/.../auth/SecurityConfiguration.java` | 新增 `@EnableConfigurationProperties(GatewayTrustProperties.class)`；`securityFilterChain` 方法签名增加一个参数；`.addFilterAfter(gatewayFilter, JwtAuthenticationFilter.class)`；新增一个 `@Bean` 方法 | 信任网关身份头是本日增量，必须挂进安全链。排在本地验签**之后**而不是之前：自己验出来的身份比别人给的更可信，反过来写等于让能注入头的中间环节顶替任何已登录用户 |
| `src/main/resources/application.properties` | 追加 `clouddrive.gateway.trusted-headers.enabled=${CLOUDDRIVE_TRUST_GATEWAY_HEADERS:false}` | 默认关闭 + 允许部署期环境变量覆盖。写死 false 会逼后人改代码才能启用真实形态 |
| `src/test/resources/application.properties` | 追加同一开关，值为裸 `false` | **Day24 踩过的坑的直接应用**：该文件与主配置同名并在测试 classpath 上遮蔽主配置。只写主配置的话测试读到 null，`@ConfigurationProperties` 退回字段默认值 false——巧合地"看起来对"，于是契约测试无法区分"配置生效了"和"配置没读到但默认值恰好相同" |
| `gateway/pom.xml` | 新增 `spring-security-oauth2-jose` | 网关需要 `NimbusReactiveJwtDecoder`。**刻意不引** `spring-boot-starter-security`/`oauth2-resource-server`：它们会带进 `spring-security-web`，触发 `ReactiveSecurityAutoConfiguration` 自动装上一整条 WebFlux 安全链，与手写的 GlobalFilter 形成两套互不知情的鉴权逻辑 |
| `gateway/src/main/resources/application.yml` | `auth-uri` 默认值改为 `${CLOUDDRIVE_AUTH_URI:http://127.0.0.1:8081}`；新增 `clouddrive.security.jwt.secret` 与 `clouddrive.gateway.authn.*` 段落 | 前者是拆分本身；后两者是边缘验签所需。`authn.enabled` 默认 **true**——与 Nacos 那类"依赖外部中间件"的开关不同，鉴权不依赖任何外部服务，默认关闭会让不安全的形态成为默认形态 |
| `gateway/src/main/resources/application-nacos.yml` | `auth-uri` 改为 `lb://${CLOUDDRIVE_AUTH_APP_NAME:cloud-drive-auth}` | nacos 形态下 auth 是独立服务名，不再是单体 |
| `gateway/src/test/java/.../GatewayRouteTableTest.java` | 三处：测试属性中 auth-uri 改为 18081 并新增 jwt secret；`everyBusinessRouteStillPointsAtTheSameMonolithToday` 改写为 `authRouteHasSplitAwayFromTheMonolithWhileTheOthersHaveNot`；新增 `noRouteExposesTheInternalServiceToServiceApi` | 前一个断言（四条路由指向同一地址）在 Day24 描述的是真实状态，今天该状态已经改变，断言必须跟着变且**反向加强**（断言 auth 与 file 不相等），而不是放宽。新增 jwt secret 属性是必需的：不加会让 `ReactiveJwtDecoder` Bean 在上下文启动阶段抛异常，整组路由测试以 `Failed to load ApplicationContext` 的形式失败，根因离现象很远 |
| `deploy/.env.example` | 新增 auth-service 段落（端口、服务名、URI、信任开关）与一段关于共享对称密钥风险的说明 | 与 compose/应用读同一组变量名；风险说明写在配置样例里，比写在文档里更容易被看到 |
| `deploy/docker-compose.yml` | 仅新增一段说明性注释，**未新增任何 service** | 见下文"未做的事与理由"第 3 条 |

未改动 `verify_day.ps1`、总纲、交接手册与任何已提交的 `docs/dayNN/Final.md`
（总纲第 0 节的受保护文件清单）。

## 实现证据

- **拆分真的发生了。** `gateway/src/main/resources/application.yml` 中
  `auth-service-route` 的 `uri` 现在解析为 `http://127.0.0.1:8081`，
  而 `user-service-route`/`file-service-route`/`share-service-route` 仍是 `:8080`。
  路由 id 与 Path 谓词**一个字没改**——Day24"按未来服务名命名路由"的收益在这里兑现，
  这正是当时那条设计要证明的东西。
- **鉴权三步顺序。** `AuthenticationGlobalFilter.filter()` 的代码顺序是：
  (1) `exchange.mutate().request(r -> r.headers(h -> GatewayIdentityHeaders.ALL.forEach(h::remove)))`
  —— 无条件剥离，不受 `enabled` 开关与白名单影响；(2) 白名单命中直接放行；
  (3) 其余路径验签，失败返回 401 且**不进入 chain**。
  `getOrder()` 返回 `-100`，小于 `NettyRoutingFilter` 的 `LOWEST_PRECEDENCE`。
- **防伪造有两条独立断言。** `forgedIdentityHeadersAreReplacedNotAppended` 防的是
  用 `add` 而非"先删再写"（会得到 `["1","42"]`，下游 `getFirst` 读到伪造值）；
  `forgedIdentityHeadersAreStrippedEvenOnAnonymousPaths` 防的是把剥离写在白名单判断
  之后（会让每一条匿名路径都变成伪造通道）。两条覆盖的是两个不同的实现错误。
- **默认形态零影响。** `TrustedGatewayHeaderAuthenticationFilter.doFilterInternal`
  第一行即 `if (!properties.isEnabled()) { chain.doFilter(req, res); return; }`。
  `GatewayIdentityHeaderPermissionRegressionTest` 用真实 HTTP 请求证明：默认形态下
  伪造 `X-User-Id: 1` 访问 `/api/entries` 返回 401。
- **开关的作用有对照实验。** `TrustedGatewayIdentityEnabledRegressionTest` 以
  `clouddrive.gateway.trusted-headers.enabled=true` 起同一个应用，同样的头就生效了；
  其中 `identityHeadersScopeDataToTheRightOwner` 断言 A 的头看不到 B 的目录——
  这才是"身份透传正确"的真正证据，透传错了人隔离就没了。
- **token 结构被当作契约。** `JwtMonolithCompatibilityTest` 断言 issuer 恰为
  `cloud-drive-learning`、`sub` 是 id 的十进制字符串、claim 集合恰为
  `{iss, iat, exp, sub, username, roles}`、算法为 HS256、换密钥即验不过。
  这组断言存在的理由：两份 auth 代码并存时，claim 结构漂移的表现不是报错，
  而是"登录成功但后续请求 401"，甚至"认证通过但 ownerId 是别人的"。
- **内部接口不可达。** `noRouteExposesTheInternalServiceToServiceApi` 用 `AntPathMatcher`
  逐条检查所有路由的 Path 谓词，断言没有任何一条能匹配 `/internal/users/1/identity`。
  用路径匹配而不是字符串包含，因为 `/**` 这类通配也会命中。

## 未做的事与理由（逐条，不用将来时掩盖）

1. **单体的 auth 代码没有删除。** 单体 `com.mengde.clouddrive.auth` 与 auth-service
   `com.mengde.clouddrive.authservice` 是两套并存实现。95 项既有回归全部通过本地
   `/api/auth/login` 拿 token，删掉需要把它们改成跨进程调用——那是 Feign，属于 Day26。
   **这是本日最大的一处不完整，代价是改 JWT claim 必须同时改两处。**
2. **三个进程共享同一把对称密钥。** HS256 "能验就能签"，网关理论上可伪造任意 token。
   换 RS256 让网关只持公钥要同时改三个进程的签发与验签路径，超出当日增量。
3. **三个 Java 进程未容器化，compose 仍只编排中间件。** 容器化需要先有可用的镜像构建
   （Dockerfile、分层、健康检查、启动顺序依赖），而这些在本会话的沙箱里一次都没有
   真实执行过；写出来只会得到一份看起来完整、实际从未跑通的编排文件。
   **直接后果**："只暴露网关端口"这个真实形态本日**搭不出来**，
   因此信任开关的开启只能做对照实验，不能当作安全结论。
4. **内部接口仍使用共享静态令牌。** `/internal/**` 现要求独立的
   `CLOUDDRIVE_INTERNAL_SERVICE_TOKEN`，auth-service 默认只绑定 `127.0.0.1`；这消除了
   匿名直连，但令牌轮换、密钥托管与 mTLS 仍未实现。
5. **`user_account` 未真正拆分。** 更具体地说，单体的
   `DocumentUploadApplicationService`、`OwnedDriveEntryApplicationService`、
   `UploadSessionApplicationService` 三处仍在直接调 `lockQuotaById` 读 `quota_bytes`，
   按 Day23 的归属判定这是 user-service 的数据。这是一处真实存在的跨边界读，
   本日只记录不修复。
6. **未做多模块聚合重构**，尽管 `gateway/pom.xml` 的注释曾写"真正的多模块重构属于
   Day25"。理由写在 `auth-service/pom.xml` 的头部注释：搬迁 `src/` 会产生 200+ 文件的
   rename 淹没本日增量，并会改变受保护脚本 `verify_day.ps1` 第 6 项的语义。
   **代价：仓库现在有三个互不聚合的 Maven 工程，全量验证需要三条命令。**
7. **Day23 设计文档与真实代码的两处字段级不一致未修正。** 文档写
   `/api/auth/login` → `{token, expiresIn}`、`/api/auth/me` → `{id, username, role}`；
   真实代码是 `{accessToken, expiresAt}` 与 `{id, username, roles[]}`。
   auth-service 跟随**真实代码**，因为跟随文档会破坏 Day23 自己写下的"零迁移成本"
   这条更根本的约束。`AuthServiceHttpContractTest` 用两条 `doesNotExist` 断言钉死该选择。
   文档本身未改（它不在本日增量内），差异记录在此。

## 测试与验收

2026-09-30 在 Windows JDK 17.0.20、Maven 3.9.10、MySQL 8.0、Redis 7.2 与
`C:\Environment\m2repo` 上完成自动化验证。Maven 在中文绝对路径下创建输出目录失败，
故按交接手册使用临时 ASCII `subst` 映射运行，并仅对当前 Java 子进程设置
`-Djdk.net.unixdomain.tmpdir=C:\Environment\cdl-java-sockets`；这不是代码配置，也没有写入
POM 或系统环境变量。

本轮先后发现并修复三项真实问题：根模块首次受 Windows 遗留 `build-output` 文件锁阻断；
网关的“过期 token”测试在编码阶段因 `expiresAt` 早于 `issuedAt` 失败，现改为生成合法时间窗的
过期 token；auth-service 的测试配置因同名 `application.yml` 遮蔽漏掉 Nacos 默认关闭项，
内部身份端点还缺少显式 `@PathVariable("id")`。修复后下列前三项均已通过：

1. `mvn -f <repo>\pom.xml "-Dmaven.repo.local=C:\Environment\m2repo" clean test`
   —— 实测 `Tests run: 108, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
   先前读取构建尚未结束时的中间 Surefire 汇总得到 100；完整报告以 108 为准。
2. `mvn -f <repo>\gateway\pom.xml "-Dmaven.repo.local=C:\Environment\m2repo" clean test`
   —— 实测 `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（Day24 的 8 项改写并新增 1 项 = 9，
   加 `GatewayAuthenticationFilterTest` 11 项）。
3. `mvn -f <repo>\auth-service\pom.xml "-Dmaven.repo.local=C:\Environment\m2repo" clean test`
   —— 实测 `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（`AuthServiceHttpContractTest` 8、
   `JwtMonolithCompatibilityTest` 6）。这一组**不依赖 MySQL 与 Nacos**，
   设计上应在任何一台机器上都能跑通。
4. `powershell -ExecutionPolicy Bypass -File verify_day.ps1 -DayNN 25` —— 2026-09-30 实测 12/12 PASS，
   Verdict: `Day25 ENGINEERING ACCEPTED`。
   注意该脚本第 6 项只跑**根 pom**，gateway 与 auth-service 不在其覆盖范围内，
   第 2、3 条命令的输出是这两个工程唯一的证据来源。
5. 手工验收（真实跨进程转发，自动化测试不覆盖）：
   起中间件 `docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d`；
   在每个启动 Java 进程的 PowerShell 中先执行 `. .\deploy\Import-CloudDriveEnv.ps1`，
   再分别启动单体（8080）、auth-service（8081）、网关（9000）；
   然后至少验证四件事——
   (a) `curl -i -X POST http://127.0.0.1:9000/api/auth/login -H "Content-Type: application/json" -d "{...}"`
   返回的响应头含 `X-CloudDrive-Gateway: day24`，且该请求**打到了 8081 而不是 8080**
   （看 auth-service 的访问日志确认，这是"拆分真的生效"的唯一直接证据）；
   (b) 不带 token 请求 `http://127.0.0.1:9000/api/entries` 返回 401，且单体日志里**没有**
   这条请求的记录（证明坏请求在边缘被挡住）；
   (c) 带伪造头 `-H "X-User-Id: 1"` 请求 `http://127.0.0.1:9000/api/auth/login`，
   在单体/auth-service 侧确认该头已被剥离；
   (d) 以 `--spring.profiles.active=nacos` 启动三个进程，在 Nacos 控制台确认
   `cloud-drive-app`、`cloud-drive-auth`、`cloud-drive-gateway` 三个服务出现在同一
   namespace/group 下。

第 4 项工程门已通过；第 5 项手工证据尚未执行。手工证据未完成前不得声称
"网关真的把 /api/auth/** 转发到了 auth-service"或"服务真的注册成功"。
2026-09-30 已启动 Docker Desktop 并尝试 `docker compose up -d`，但 Docker Hub 拉取
`mysql:8.0` 时在获取匿名 token 阶段发生 TLS handshake timeout，随后 Redis 与 Nacos 镜像
均未启动；故本轮没有伪造三进程转发或 Nacos 注册结论。待镜像仓库可达后，从第 5 项继续。

另需注意：**Day24 的工程门本身尚未 12/12 PASS**（第 1、2 项待提交）。
按总纲第 4 节第 2 条，Day24 的工程交付在那之前也不成立。Day25 的工程门通过不替代
真实跨进程联调、教材人工复读或用户学习门。

## 面试题与真实问题映射

本 Day 教材第 13/14 节收录与本日真实产出直接对应的面试题，均可用本仓库的
`AuthenticationGlobalFilter`、两个过滤器的排序、三组权限回归测试与
`docs/architecture/day25-auth-split-and-gateway-authn.md` 原文作答：
"网关鉴权的三步顺序为什么不能换，换了会怎样"、"为什么身份头必须先删再写而不能 add"、
"匿名路径上为什么也要剥离身份头"、"下游信任网关的前提是什么，缺了这个前提会怎样"、
"网关已经验签了，下游服务为什么还要自己验"、"HS256 对称密钥在网关上的风险是什么，
换成 RS256 解决了什么"、"第一个服务拆出来时，为什么先并行而不是先删除旧代码"、
"拆分后用户名枚举、越权、token 结构漂移各自会以什么现象暴露"。

## 教材自审 Day02 Day05 对照

| 审计项 | 真实教材证据 | 结论 |
|---|---|---|
| 前日衔接与问题 | 教材第 0-1 节从 Day24"四条路由指向同一地址、网关只转发不鉴权"推导出拆分未验证、鉴权无落点、身份无法跨服务传递三个真实问题 | 通过 |
| 项目源码走读 | 教材逐段走读本仓库 `AuthenticationGlobalFilter` 的三步顺序、`TrustedGatewayHeaderAuthenticationFilter` 的四条防御性判断、`auth-service` 的 `SecurityConfiguration` 与 `JwtTokenService`、`gateway/application.yml` 中 auth-uri 的分叉 | 通过 |
| 项目测试走读 | 教材逐条走读 `GatewayAuthenticationFilterTest` 10 项、`TrustedGatewayHeaderFilterTest` 5 项、`JwtMonolithCompatibilityTest` 6 项，解释每条断言防的是哪一类真实故障 | 通过 |
| Debug/故障排障 | 教材设 5 个独立 Debug 小节：网关缺 jwt secret 导致整组路由测试 `Failed to load ApplicationContext`、身份头用 add 导致下游读到伪造值、剥离写在白名单之后导致匿名路径成为伪造通道、两份 auth 代码 claim 漂移导致"登录成功但后续 401"、信任开关只写主配置导致契约测试无法区分真假 | 通过 |
| 独立复刻/实操 | 教材要求不看答案独立写出网关过滤器的三步顺序并补一条防伪造断言，再独立画出三进程鉴权链路时序图 | 通过 |
| 面试与简历 | 教材第 13/14 节收录本日对应面试题清单与简历讲法示例 | 通过 |
| 阶段题覆盖 | 教材含算法、SQL、Java 三类阶段巩固题，与本日边缘鉴权主题结合命题 | 通过 |

本表不代表用户已学会；用户仍需口述、闭卷复刻、记录错因并完成题单，
且必须先在其 Windows 机器上跑通上述第 1–4 项命令全部通过，工程交付才能被认定完成。
