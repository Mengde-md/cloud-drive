# Day06 Final：Spring MVC 与 HTTP 契约

## 当日范围与禁止项

Day06 的唯一增量是把 Day02–05 的内存对象/索引/快照能力暴露为 HTTP 接口：Spring MVC 控制器、统一响应与全局异常处理、Bean Validation 请求体校验、资源创建/列表/详情/重命名，以及 2xx/4xx/5xx 的真实端口契约测试。引入 Spring Boot 3.2.4（starter-web + starter-validation + starter-test），不引入 MySQL、Redis、鉴权、分片或文件上传。

## Checkpoint 0 实际起点

起点为 Day05 的 `f89c4b2`：内存条目模型（Day02）+ HashMap 索引（Day03）+ 文件快照（Day04）+ 有界异步快照执行器（Day05），回归基线 36 测试全绿。此前所有对象只存在于 JVM 与本地文件快照，没有 HTTP 入口；外部调用方无法创建、查询或重命名条目。

## Checkpoint 1 唯一增量

新增 `web` 包：`DriveEntryWebApplication`（Spring Boot 启动）、`DriveEntryController`（`/api/entries` 的 GET/POST/PUT-name）、`DriveEntryCatalog`（组合 List+Index 的粗粒度同步门面）、`EntryCreateRequest`/`RenameRequest`/`EntryResponse`（请求与响应契约）、`GlobalExceptionHandler` + `ApiError`（统一错误体）、`SnapshotController` 等（把 Day04 快照暴露为管理端点）。纠偏提交 `6f88ee2` 补齐 `spring-boot-starter-validation`、DTO 约束、Controller `@Valid`、校验异常翻译与对应合同测试，使当前代码与总纲明确要求一致。

范围边界：Controller 只做 HTTP 翻译；领域规则仍在对象构造器中（Day02）；重复键与缺失仍走 Day03 的业务异常并由 `GlobalExceptionHandler` 转为 4xx。请求 id 由客户端声明（与 Day03"重复键是业务异常"一致，服务端生成 id 属 Day07+ 数据库阶段）。

## Checkpoint 2 Debug 与边界

1. 非法 kind / 非正 id / 空白名称 → 4xx 统一错误体，不泄漏内部堆栈。
2. 重复 id 创建 → 409/业务异常；缺失 id 详情 → 404。
3. 重命名越权/非法名 → 4xx；目录无 byteSize、文档有 byteSize 的响应差异有断言。
4. 快照管理端点失败路径（损坏快照、IO 错误）→ 5xx/明确错误。
5. 缺少 `kind`、非正 id/ownerId/parentId、空白名称、负 byteSize 在进入业务调用前触发 Bean Validation；`MethodArgumentNotValidException` 统一翻译成 `ApiError(400, ...)`，领域构造器仍保留同类不变量，防止非 HTTP 入口绕过规则。

**已记录的稳定性事实（flaky）**：`DriveEntryHttpContractTest` 与 `SnapshotHttpFailureTest` 均为 `@SpringBootTest(RANDOM_PORT)`，共享 Spring 上下文中的 Catalog 单例。个别全量运行曾出现 `DriveEntryHttpContractTest.createDirectoryThenListReturns200WithBothKinds` 一次偶发失败（`byteSize` 取到 null），单独重跑与随后两次全量均为 52/52 全绿——判定为跨测试上下文状态残留导致的时序性不稳定，非确定性代码缺陷。已如实记录，不作为"通过"掩盖。

## Final 验收记录

原始 Day06 发布时，`mvn clean test`（默认 `.m2`）实测：**Tests run: 52, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS**（36 回归 + Day06 新增 16）。

2026-09-09 在当前 Day08 代码基线上完成范围纠偏：用户本机以 JDK 17.0.20、Maven 3.9.10、`C:\Environment\m2repo` 真实执行 `mvn clean test`，编译 30 个生产源和 18 个测试源，最终 **Tests run: 76, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS**。其中新增 `DriveEntryHttpContractTest.createMissingKindReturnsBeanValidation400WithUnifiedErrorBody` 和 `RequestValidationContractTest` 3 个约束测试；纠偏提交为 `6f88ee2 fix(day06): enforce bean validation at http boundary`。入口仍可通过 `mvn spring-boot:run` 启动，HTTP 契约由 RANDOM_PORT 真实验证。

## 独立复刻任务

用 Spring Boot 3 + MVC 复刻：一个资源 + 创建/列表/详情/改名四端点 + 全局异常转 4xx/5xx + 至少两组成功/失败 MockMvc 契约测试。验证：未知资源 404、重复键不覆盖、空白名被拒、错误体统一。

## 面试题与追问

1. `@RestController` 与 `@Controller` 的差别？响应体如何序列化？
2. 全局异常处理器的职责边界？为什么业务异常不直接返回 HTTP 状态？
3. 为什么请求 id 由客户端声明（而非服务端生成）？Day07 起为何改变？
4. `DriveEntryCatalog` 为什么用粗粒度 `synchronized`？吞吐优化为何推迟？
5. 目录无 byteSize、文档有 byteSize 的响应模型如何用 record 表达？
6. 追问：RANDOM_PORT 测试共享上下文为什么会偶发污染？如何隔离？
7. 追问：400 vs 404 vs 409 各自表示什么？错误体为何要统一？

## 明日唯一输入

历史上 Day07 的唯一输入是本日 HTTP 契约与 52 测试基线。当前在 Day08 HEAD 上补齐 Day06 Bean Validation 后，全仓回归基线更新为 76；该纠偏没有修改 Day07 持久化范围，也没有引入缓存、登录或鉴权。
