# Day11 Final：IOC、AOP、Bean 生命周期与配置边界

## 当日范围与禁止项

Day11 是 Spring 原理复盘日。唯一增量是以现有 Web 启动类、Controller 构造器注入、`SnapshotConfiguration`、HTTP 测试替换 Bean 为事实来源，完成独立 Spring context 实验、教材、Final 与回执。

禁止修改 CloudDrive 生产代码、测试、pom、数据库迁移或任何业务契约；禁止把测试类上的 `@Transactional` 叙述为 Day12 的业务事务接线；禁止新增认证、缓存、上传、Profile 专用生产配置、Aspect 或 Service/Repository 空壳。

## Checkpoint 0 实际起点

输入是 Day10 `b54ab74` 与工程标签 `learning/day10-engineering`。Day10 已解释并发控制，但未解释 Spring 如何创建和装配 `DriveEntryController`、`DriveEntryCatalog`、`EntrySnapshotStore` 与 `SnapshotSink`。

源码事实：`DriveEntryWebApplication` 带 `@SpringBootApplication`；`DriveEntryCatalog` 带 `@Component`；`DriveEntryController` 通过构造器接收 Catalog；`SnapshotConfiguration` 用两个 `@Bean` 适配 Day04 快照，并以 `@Value("${clouddrive.snapshot.path:...}")` 接收配置；`SnapshotHttpFailureTest` 用 `@TestConfiguration`、`@Bean`、`@Primary` 替换 SnapshotSink 来验证 500 错误合同。

## Checkpoint 1 唯一增量

### 1. IOC 不是“Spring 自动 new 一切”

IOC 的本质是对象创建、依赖关系与生命周期由容器协调，而不是由业务对象在内部 `new` 其依赖。组件扫描会发现 `@Component`/`@RestController` 等候选；`@Configuration` 的 `@Bean` 方法显式声明对象构造方式；自动配置会根据 classpath、条件与属性补充基础设施。

`DriveEntryController(DriveEntryCatalog catalog)` 的构造器是依赖边界：控制器声明“我需要一个 Catalog”，却不知道它如何被实例化。Day12 会把该边界真正接到 application/MySQL 链路；Day11 不提前改变它。

### 2. Bean 生命周期的准确范围

简化顺序是：解析 BeanDefinition → 实例化 → 依赖注入 → BeanPostProcessor 前/后处理 → 初始化回调（如 `InitializingBean`/`@PostConstruct`）→ 容器关闭时 destroy 回调。不同 Bean、scope、代理与循环依赖会影响细节；不能把这条简图说成每一种对象绝对逐行相同。

独立 lab 创建 `AnnotationConfigApplicationContext`，启用 `lab` profile，注入一个属性，注册 `@Configuration`，并以 `InitializingBean`/`DisposableBean` 输出初始化/销毁事实。它不代表 CloudDrive 已有自定义生命周期逻辑，只证明本机依赖中的 Spring context 真实执行这些机制。

### 3. AOP 是代理拦截，不是“所有方法都会被增强”

AOP 通常由代理在调用边界织入横切行为，例如日志、鉴权、事务、指标。代理必须被调用方持有或从容器注入；同一个对象内部 `this.method()` 的自调用常绕过外部代理。Day11 lab 用 `ProxyFactory` 明确创建 GreetingPort 代理并记录 advice 次数，只证明代理可拦截接口调用。项目当前尚未增加生产 Aspect，也不把测试注解误称为已完成业务事务。

### 4. 配置绑定、@Value 与 Profile 的边界

当前 `SnapshotConfiguration` 只有单个 `@Value`，适合一个简单路径属性；多个关联且需要校验的配置才更适合后续 `@ConfigurationProperties`。Profile 是选择 Bean/配置集的环境边界，不是把密码、地址和环境判断散落进业务 if/else。Day11 lab 显式激活 `lab`，让 `@Profile("lab")` 的 GreetingPort 出现；生产环境要以部署配置确定 profile，不能从请求参数切换。

## Checkpoint 2 Debug 与边界

1. **NoSuchBeanDefinitionException。** 先检查候选类是否在扫描包、配置是否 register、Profile 是否激活、条件是否成立；不要先手工 new 绕过容器。
2. **NoUniqueBeanDefinitionException。** 多个同类型候选时，明确用 `@Primary` 或 `@Qualifier` 表达选择。现有失败快照测试的 `@Primary` 是测试替换证据，不应复制成生产默认规则。
3. **以为 AOP 没生效。** 先确认调用经过代理、切点匹配、代理类型和 self-invocation；不能仅凭目标方法执行过就宣称 advice 必经。
4. **Profile/属性看起来未生效。** 在启动时输出 active profiles 和 property source；区分默认值、环境变量、命令行与配置文件优先级，不记录或暴露密钥。

## Final 验收记录

- 独立 Spring context lab 放在文档仓库 `阶段一/Day11_验收证据`，不加入项目 `src`。它使用本机 Spring 6.1.5 jar 真实输出 `profileGreeting=hello-from-lab-profile`、`configuredLabel=configured-from-environment`、生命周期 init/destroy 为 true，且代理 advice 调用计数为1。第一次运行提示非 static `BeanPostProcessor` 工厂方法过晚注册；改为 static 后以退出码0重跑无该警告。
- 本日生产代码、项目测试、pom、迁移均保持不变；Maven 回归仍应为76项。真实 Maven、提交 hash 与机器闸门以发布回执为准。
- 面试证据覆盖 IOC 与 DI、Bean 生命周期、代理 AOP/self-invocation、`@Value`/`@ConfigurationProperties` 取舍、Profile 和测试 Bean 替换。

## 独立复刻任务

1. 用 `AnnotationConfigApplicationContext` 写一个 @Bean 依赖另一个 @Bean 的例子；解释为什么不是业务类自己 new。
2. 给两个同接口 Bean 制造注入歧义，再分别用 `@Primary` 与 `@Qualifier` 修复。
3. 用 ProxyFactory 或容器代理让一次接口调用经过 advice；再解释 self-invocation 为什么可能绕过代理。
4. 用一个 profile 和一个有默认值的属性启动 context，记录哪个来源最终生效。

## 面试题与追问

1. **IOC/DI 是什么？** 容器管理对象构造与依赖关系；Controller 构造器只声明依赖。追问：为什么构造器注入更利于测试？依赖显式且对象不可缺依赖地创建。
2. **Bean 生命周期？** BeanDefinition、实例化、注入、后处理、初始化、销毁；说明代理/作用域会影响细节。
3. **AOP 如何生效？** 代理拦截外部调用，切点/advice 匹配；self-invocation 可能绕过代理。
4. **@Value 与 @ConfigurationProperties 怎么选？** 单个简单值可用 Value；成组、可校验、可维护配置选后者。
5. **@Primary 与 @Qualifier？** 都解决多候选；Primary 是默认偏好，Qualifier 在注入点精确指定。
6. **Profile 能做什么？** 按环境选择 Bean/配置，不能作为运行中任意请求的权限开关。
7. **简历如何讲？** “我基于网盘 Web 入口梳理组件扫描、构造器注入和配置 Bean；通过独立 Spring context 验证 profile、属性注入、生命周期及代理拦截，并把测试依赖替换与生产装配边界区分开。”

## 教材自审（Day02/Day05 对照）

| 质量项 | Day11 教材位置 | 重新核对的真实证据 | 结论 |
|---|---|---|---|
| 前日衔接与真实问题 | 第 0、1 节 | Day10 的并发边界之后，Controller、Catalog、SnapshotConfiguration 与测试替换 Bean 的装配边界 | 通过 |
| 项目生产源码走读 | 第 2--6、27--28、31 节 | `DriveEntryWebApplication.java`、`DriveEntryController.java`、`SnapshotConfiguration.java`、`GlobalExceptionHandler.java` | 通过 |
| 项目测试走读与测试矩阵 | 第 7、29--30 节 | `DriveEntryHttpContractTest.java`、`RequestValidationContractTest.java`、`SnapshotHttpFailureTest.java`、`DriveEntryMapperTest.java` | 通过 |
| 原理回扣项目 | 第 16--24、27--31 节 | 组件扫描、构造器注入、MVC 映射、DTO 校验、异常协议、MyBatis 测试代理与资源关闭边界 | 通过 |
| 三类以上 Debug | 第 8--12、32 节 | Bean 缺失、404、校验未生效、测试污染、异步事务误判、重复 id 映射 | 通过 |
| 独立实验不冒充项目实现 | 第 25 节及 Spring context lab | lab 只验证 Profile、生命周期与代理；项目结论以 Controller、Handler 和 HTTP/Mapper 测试为准 | 通过 |
| 独立复刻、阶段题、面试表达 | 第 13--15、33 节 | MVC 分支图、fake Repository 构造器注入、测试分层、配置故障、两分钟答辩口述 | 通过 |
| 运行证据与范围边界 | Checkpoint 0/2、Final 验收记录 | 本日未改项目代码、测试、pom 或迁移；历史 76 项基线与 lab 输出分开陈述 | 通过 |

**审计结论**：Day11 的独立 Spring context 实验只作原理验证；完整教材已用当前 Boot 源码、HTTP 测试与 Mapper 测试闭环。人工学习门仍待用户自行完成，AI 不得代填。

## 明日唯一输入

输入是 Day11 Final、独立 Spring context 输出、干净提交与机器闸门。Day12 才首次引入 `@Transactional`、HTTP→application→MySQL 接线、合同测试隔离和 OpenAPI/Postman；不提前实现这些能力。
