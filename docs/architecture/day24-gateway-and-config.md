# Day24 入口治理：网关路由表、服务发现与配置隔离

本文是 Day24 的设计依据，承接 Day23《day23-service-boundaries.md》定下的 auth / user / file / gateway
四服务边界。Day23 只把边界写在纸上，仓库仍是单体；Day24 第一次让"边界"在运行时有一个落点——
一个独立的网关进程，以及一套不再写死在配置文件里的后端地址。

以 Day24 提交 `2ef3ace` 为准，后端仍然只有一个单体进程，四条业务路由指向同一地址。路由按未来边界拆开，使后续服务迁移可以逐条调整目标地址；本文不把后续 Day25 工作写成 Day24 已完成的能力。

---

## 1 真实问题：为什么现在必须做入口层

Day22 交付单体验收时，调用方（前端、Postman、将来的 CI）直接访问 `127.0.0.1:8080`。
这带来三个到 Day25 就会爆炸的问题：

1. **后端地址写死在调用方**。Day25 把 `/api/auth/**` 拆到独立进程后，端口会变成第二个；
   如果调用方直接连后端，每拆一个服务就要改一次所有调用方的配置。
2. **缺少统一横切点**。鉴权、限流、链路标识和跨域等策略需要明确由入口还是后端承担；Day24 只建立网关入口，不声称已经实现这些策略。
3. **配置随进程增加**。拆分后多个进程各有配置，若缺少配置治理，地址、凭据和环境参数容易不一致；配置中心也不能代替每个服务的权限边界和凭据管理。

Day24 分别用"网关路由表""服务发现""配置中心 + namespace/group 隔离"回答这三个问题。

---

## 2 路由表：按未来边界命名，而不是按今天的进程数

路由定义在 `gateway/src/main/resources/application.yml`。

| 路由 id | 匹配路径 | 今天转发到 | Day23 契约里的归属 | 拆分后改哪里 |
|---|---|---|---|---|
| `auth-service-route` | `/api/auth/**` | 单体 | auth-service（`user_account` 身份子集） | Day25 改这一条的 uri |
| `user-service-route` | `/api/entries/**` | 单体 | user-service（配额）+ file-service（元数据） | Day26 再按 `/entries` 细分 |
| `file-service-route` | `/api/files/**`、`/api/upload-sessions/**` | 单体 | file-service（`drive_entries`/`upload_sessions`/`upload_chunks`） | Day26 改这一条 |
| `share-service-route` | `/api/shares/**` | 单体 | file-service（`file_shares`） | Day26 与上一条一起改 |
| `api-docs-route` | `/v3/api-docs/**`、`/swagger-ui/**` | 单体 | gateway 聚合 | Day25 起改为聚合多份文档 |
| `monolith-health-route` | `/backend-actuator/**`（`StripPrefix=1`） | 单体 `/actuator/**` | 运维 | 每加一个服务加一条 |

三个刻意的设计决定：

- **路由 id 用未来的服务名，不用今天的进程名**。如果今天写成 `monolith-route` 一条通配，
  Day25 就必须推翻重写，而且没人能从路由表看出边界在哪。
- **`/api/shares/**` 单独成一条**，虽然今天与 `file-service-route` 指向同一个地址。这条路径内同时有受保护的分享管理接口与允许匿名访问的 `/api/shares/*/download`；路由独立不意味着整条路径匿名，也不意味着 Day24 网关已实施限流。
- **actuator 走 `/backend-actuator` 前缀并 `StripPrefix=1`**。网关自己也有 `/actuator`，
  不加前缀会和网关自身端点冲突，表现是"查后端健康查到了网关的健康"，非常难发现。

### 2.1 今天不做的事

- 不在网关做 JWT 验签。Day23 的契约写明网关是唯一验签点，但那是 Day25 的增量；
  今天网关只转发 `Authorization` 头，鉴权仍然发生在单体内部的 `SecurityConfiguration` 过滤链。
- 不做灰度、熔断、重试。只配了连接超时 2s 与响应超时 10s，因为超时是转发的最低要求，
  而重试在没有幂等保证的接口上（例如 `POST /api/files/upload`）是有害的。
- 不引入 Feign、不做任何服务间调用。那是 Day26。

---

## 3 服务发现：地址从哪里来

默认形态与 nacos 形态的差别只有一处：

```
默认形态   uri: http://127.0.0.1:8080     地址写死在配置里
nacos 形态 uri: lb://cloud-drive-app      地址来自注册中心，由 LoadBalancer 选实例
```

路由表本身（几条路由、各自匹配什么路径、带什么过滤器）在两种形态下完全一致。
切换靠 `--spring.profiles.active=nacos`，不改任何一行路由定义。

**为什么默认关闭 Nacos**：工程门第 6 项要求 `mvn clean test` 全绿，而测试不应该要求
先手工启动 Nacos。默认关闭使路由表与配置契约测试不依赖 Nacos；单体的完整集成回归仍需满足 MySQL、Redis 等既有依赖，不能据此宣称在任意机器上开箱即过。真实注册发现另需联调证据。

这不是"把难的部分跳过"，而是把两类证据分开：
**配置被正确解析**（可自动化、每次构建都验证）与
**跨进程真的连通**（需要真实中间件，按步骤手工验证一次并记录）。

---

## 4 配置隔离：三层，从硬到软

| 层 | 机制 | 隔离什么 | 越界的后果 |
|---|---|---|---|
| 1 | Nacos `namespace` | 环境（dev / test / prod） | 跨 namespace 完全不可见，拿不到别的环境的配置 |
| 2 | Nacos `group` | 同一环境内的业务域（`CLOUDDRIVE_GROUP`） | group 不一致时找不到预期配置；若另一个 group 恰有同名 dataId，还可能读到非预期内容 |
| 3 | `dataId` = `${spring.application.name}.${file-extension}` | 单个服务 | 服务名写错就拉不到自己的配置 |

**非 public namespace 使用控制台给出的 ID，而不是展示名称。**填错时客户端可能找不到预期的配置或服务，具体表现取决于客户端与配置；不能断言一定静默退回 public。排障时对照实际生效的 namespace ID、group、dataId 与客户端日志。

### 4.1 热更新的边界

`refresh-enabled=true` 仅开启相关刷新能力，不保证任何业务值自动生效。还要确认远端配置确实被加载、变更通知到达、消费该值的 Bean 支持刷新，并用实际行为验证。`@ConfigurationProperties` 或 `@RefreshScope` 是可能用到的机制，不是单独的验收证据。下列启动期资源通常需要重启或专门的重建机制：

- `server.port`：启动期一次性读取并绑定端口；
- `spring.datasource.*`：连接池在启动时建好，之后不再回头看配置；
- `spring.cloud.nacos.*` 自身：客户端已经连上了，不会用新配置重连。

所以验证热更新应先增加一个无敏感信息的业务属性和可观察的读取点，再依次核对远端加载、通知与最终行为。不要改真实数据源密码做实验；本日尚未实现这个演示接口，不能声称热更新已经通过实测。

### 4.2 凭据纪律（延续 Day23）

`application-nacos.properties`、`gateway/application-nacos.yml`、`docker-compose.yml`
里全部是 `${ENV_VAR:default}` 或 `${VAR:?错误信息}` 占位符，没有任何真实地址口令组合；
Nacos 的鉴权 token 与 identity 由 `deploy/.env` 注入，`.env` 在 `.gitignore` 中。
`NacosDefaultsOffContractTest` 用断言把这条纪律钉死。

---

## 5 API 文档：从手工维护的快照变成代码生成

Day12/Day22 留下的 `docs/day22/openapi.yaml` 与两份 Postman collection 是手工维护的，
接口一改就过期，而且没有任何机制能发现它过期了。

Day24 引入 `springdoc-openapi-starter-webmvc-ui`，让 `/v3/api-docs` 由控制器上的注解与
方法签名实时生成，并通过 `api-docs-route` 经网关暴露。既有的 `openapi.yaml` 保留为
Day22 当时的快照证据，不再作为唯一事实来源。

局限性要说清楚：springdoc 生成的是**结构**（路径、方法、请求/响应模型），
不会自动生成业务语义说明；Day25 起如果要让文档可读，仍然需要在控制器上补注解。

---

## 6 本日已知局限（不得在 Final 或回执里回避）

1. **网关不在根 pom 的构建范围内**。Maven 不允许 `packaging=jar` 的 pom 声明 `<modules>`，
   而把现有全部源码搬进子模块的重构属于 Day25 拆分时的必要动作，不在本日唯一增量内。
   后果：根工程的 `clean test` 不编译网关，网关必须用 `-f gateway/pom.xml` 单独构建，两次结果都要留存。
2. **没有自动化的"真实转发"测试**。路由表被正确解析有断言保证；请求真的穿过网关到达后端，
   只有手工验收步骤能证明。
3. **Nacos 用 standalone + 内嵌 Derby 存储**，不是生产形态。生产必须换 MySQL 存储并集群化，
   否则容器重建即丢全部配置。
4. **网关尚未验签**。今天任何人都能把任意 `Authorization` 头透过网关送到后端，
   安全边界仍然完全由单体内部承担。这是 Day25 的增量，不是遗漏。
