# Day14 Final：Redis 基础与受限 Cache Aside 实验

## 当日范围与禁止项

- **唯一输入**：Day13 的 Spring 装配/代理边界、Day12 的 HTTP → Service → Repository → MySQL 链路，以及本机可达 Redis 6379。
- **唯一增量**：Spring Data Redis、环境变量化连接配置、仅限 `clouddrive:day14:lab:*` 的 `RedisCacheAsideLab`，和三项真实 Redis 测试。
- **禁止提前**：不得缓存真实 `DriveEntry`、目录、用户、权限或 HTTP 返回；不得加 `@Cacheable`/`@CacheEvict`、Redisson、分布式锁、布隆过滤器、认证或上传功能。真实 metadata 缓存从 Day20 开始。

## Checkpoint 0：实际起点

起点为 Day13 提交 `8b351b8` 后的干净代码库：已有 77 个回归测试，Redis 依赖、`docs/day14/Final.md`、Day14 教材均不存在。本机确认 `redis-server` 正在运行，`127.0.0.1:6379` 可达；没有读取、输出或写入 Redis 密码。

## Checkpoint 1：唯一增量

1. `pom.xml` 加入 `spring-boot-starter-data-redis`；`application.properties` 用 `CLOUDDRIVE_REDIS_HOST`、`PORT`、`PASSWORD` 外部化 host/port/password，并设 2 秒连接超时。
2. 新增 `RedisCacheAsideLab`：固定 Day14 实验 namespace、受限尾部 key、正值 TTL、独立短 TTL `:absent` marker、正值优先读取和双 key 显式失效。
3. 新增真实 Redis 数据结构测试（String/Hash/List/Set/TTL）与 Cache Aside 正值/空值测试。键均由 UUID 或 `nanoTime` 隔离，`@AfterEach` 仅删本测试精确创建的键。
4. 初版测试误启动完整 Web/MySQL/Flyway 上下文，导致无关 MySQL 凭据失败；已改为 `RedisLabTestConfiguration` 的最小 Spring Redis 上下文。它仍连接真实 Redis，但不会让无关数据库依赖掩盖本日结论。

## Checkpoint 2：Debug 与边界

教材记录九项可复现/可推演边界：密钥入库、缓存 miss 与连接故障混淆、TTL 秒级抖动、负标记顺序、负 TTL 过长、mock 不能替代真实 Redis、完整 SpringBootTest 引入 MySQL、`synchronized` 不等于分布式防击穿、延迟连接与超时预算。

本日只验证短期空值标记缓解穿透；没有验证或宣称解决热点击穿、TTL 雪崩、Redis 高可用、缓存降级、真实 DB 一致性或用户权限隔离。

## Final 验收记录

- `mvn clean test` 使用 JDK 17、Maven 3.9.10、`C:\Environment\m2repo` 与 Java socket 临时目录运行，Surefire XML 汇总为 **80 tests / 0 failures / 0 errors / 0 skipped**。新增 Redis 套件为 2 + 1 测试；Day01–13 的 77 项回归仍通过。
- `git diff --check` 通过；提交前仅暂存 Day14 的 POM、配置、Redis 服务、三份 Redis 测试/测试配置和本 Final，未使用 `git add -A`。
- 教材包含前日衔接、真实问题、项目源码走读、项目测试走读、九个 Debug、独立复刻、算法/SQL/Java/网络题、十道面试题和明日输入。它反复声明 Day14 不是实际网盘缓存接入。
- **教材自审（Day02/Day05 对照）**：Day02 的“问题→抽象→源码→测试”结构在第 1、4、7、8、10、15 节保留；Day05 的“容量/故障不能只背定义”思路在击穿、雪崩、超时预算和最小上下文 Debug 中保留。教材不拿未发生的压测、认证或业务缓存作证据。

## 独立复刻任务

在临时目录写一个仅允许 `lab:*` key 的 cache-aside 函数：第一次正值回源、第二次命中、删除后第三次回源；再写独立 absent key 的短 TTL 空值路径。说明为什么不能直接迁移到当前真实 `DriveEntry` 查询。

## 面试题与追问

1. Cache Aside 的读写顺序是什么？追问：写库成功而删缓存失败如何处理？
2. 穿透、击穿、雪崩分别是什么？Day14 覆盖了什么、没有覆盖什么？
3. 为什么空值 cache TTL 通常短于正值？
4. StringRedisTemplate 与对象序列化有什么边界？
5. `@Cacheable` 为什么不等于不需要设计 key 和失效？追问：self-invocation 有什么风险？
6. Redis 测试为何采用最小 Spring 上下文？
7. Redis 键空间为什么也是一种接口合同？
8. 为什么今天绝不能缓存真实用户目录？

## 明日唯一输入

输入是本日 Redis 基础、键/TTL/Cache Aside 边界和干净提交。Day15 才引入认证与身份传播；在身份与授权形成前，禁止将用户私有 metadata 放入 Redis。
