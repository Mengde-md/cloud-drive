# 审计问题修复报告

## 修复概览

从审计报告的 60+ 个问题中，筛选出对**面试展示有实际价值**的问题进行修复。
原则：修了能讲、能展示技术深度的优先，纯运维/生产级的不动。

---

## 已修复问题清单

### 严重（CRITICAL）

| # | 问题 | 修复方式 | 面试讲点 |
|---|------|---------|---------|
| 1 | 密码默认值 123456 | 4 个 yml 中 `${DB_PASSWORD:123456}` → `${DB_PASSWORD}`（无默认值，不设环境变量启动直接报错） | 配置安全：敏感信息强制外部化，不在代码中留默认值 |
| 3 | CORS `*` + credentials | CorsConfig 改为精确白名单（mengdecode.com + localhost），application.yml 同步修改 | CORS 安全：`*` + `credentials=true` 是经典安全漏洞，面试常问 |
| 4 | @TableLogic 未配置 | 5 个实体类 deleted 字段加 `@TableLogic`，3 个 yml 加 `logic-delete-field` 全局配置 | MyBatis-Plus 逻辑删除：框架自动过滤已删除数据，避免手动 `.eq("deleted", 0)` 遗漏 |

### 高危（HIGH）

| # | 问题 | 修复方式 | 面试讲点 |
|---|------|---------|---------|
| 7 | 分享 URL 仅 32 位熵 | UUID 截断 8→16 字符（128 位熵），加冲突重试（最多 3 次） | 安全设计：短链熵值不足可被暴力枚举，16 字符 + 冲突检测 |
| 8 | 文件合并 OOM | `Files.readAllBytes()` → `Files.copy(chunkPath, out)` 流式拷贝 | 大文件处理：避免将整个分片加载到 JVM 堆，用流式传输替代 |
| 9 | 分享列表 N+1 查询 | 循环 `selectCount` → `GROUP BY` 批量查询 + Map 映射 | SQL 性能优化：N+1 问题是面试必问，用批量查询 + Map 映射解决 |
| 10 | 文件名去重无限循环 | do-while 加上限 100 次，超限抛 BusinessException | 防御性编程：防止恶意构造大量同名文件拖垮数据库 |
| 12 | 搜索无分页可能雪崩 | `wrapper.last("LIMIT 200")` 限制返回条数 | 数据库保护：全量返回在数据量大时会导致内存溢出和慢查询 |

### 中危（MEDIUM）

| # | 问题 | 修复方式 | 面试讲点 |
|---|------|---------|---------|
| 21 | e.printStackTrace() | GlobalExceptionHandler 改用 `@Slf4j` + `log.error()` | 日志规范：用 SLF4J 而非直接 printStackTrace，支持日志级别控制和文件输出 |
| 23 | 密码哈希泄露给前端 | User 实体 passwordHash 加 `@JsonIgnore`，移除手动 setPasswordHash(null) | API 安全：用注解声明式隐藏敏感字段，比手动置空更可靠 |
| 24 | 缺少 @Transactional | transfer、restoreFromRecycleBin 加 `@Transactional(rollbackFor = Exception.class)` | 事务管理：批量操作必须事务保护，保证原子性 |
| 26 | RuntimeException 未统一处理 | ShareServiceImpl.downloadShareFile 的 RuntimeException → BusinessException | 异常体系：业务异常用 BusinessException，由全局处理器统一返回 |
| 31 | MergeFlagEnum 描述误导 | "不需要合并" → "分片未就绪"，"需要合并" → "分片已就绪，可合并" | 代码可读性：枚举描述应准确反映语义 |
| 32 | Random → SecureRandom | 提取码生成改用 `SecureRandom` | 安全随机数：普通 Random 可预测，安全场景必须用 SecureRandom |
| 33 | SQL 日志输出 stdout | `StdOutImpl` → `Slf4jImpl`（auth-service），user-service 和 file-service 补 `log-impl: Slf4jImpl` | 日志框架：stdout 绕过日志框架，无法控制级别和输出目标 |
| 37 | 路径穿越风险 | CreateFolderParam、UpdateFilenameParam 加 `@Pattern` 校验禁止 `/\:*?"<>|` | 输入校验：防止恶意文件名穿越目录 |
| 46 | TODO 注释留在代码中 | StpInterfaceImpl 移除 TODO 注释 | 代码整洁：TODO 在面试代码审查中会被追问 |

### 低危（LOW）

| # | 问题 | 修复方式 |
|---|------|---------|
| 34 | 死代码 HOME_OVERVIEW_TTL | 移除未使用的常量和 Duration import |

### SQL 索引优化

| # | 问题 | 修复方式 |
|---|------|---------|
| 18 | file_chunk 缺复合索引 | `idx_identifier` → `idx_identifier_expiration`（identifier + expiration_time） |
| 19 | user_file 缺复合索引 | 新增 `idx_user_folder_deleted`（user_id + folder_flag + deleted） |
| 41 | share_file 缺复合索引 | `idx_share_id` → `idx_share_id_deleted`（share_id + deleted） |

---

## 未修复的问题（及原因）

| # | 问题 | 不修原因 |
|---|------|---------|
| 2 | useSSL=false | 本地开发环境和面试演示不需要 SSL，且 MySQL SSL 配置较复杂 |
| 5 | 实体与迁移脚本字段不匹配 | 迁移脚本（migrate_compliance.sql）是备案预留的，当前项目未执行该脚本，不影响运行 |
| 6 | ~~分享接口无限流~~ | **已在第二轮修复**：ShareController.createShare 加 @RateLimit |
| 13 | 重复代码（User 实体两份） | 这是微服务架构的常见权衡，拆 common entity 会引入循环依赖，当前规模不值得 |
| 14 | 环境隔离缺失 | 已通过 .env.production 环境变量实现基本隔离，Nacos namespace 对面试项目过度设计 |
| 15 | 健康检查缺失 | Actuator 对 4G 服务器有额外内存开销，面试演示不需要 |
| 16 | Sentinel 未接入 | 声明了但未用，面试时可讲"知道 Sentinel 但项目规模未到限流阈值" |
| 17 | 无环境 Profile | 已通过环境变量实现配置切换，application-dev/prod.yml 对面试项目过度 |
| 20 | 无全文索引 | MySQL FULLTEXT 对中文支持不好，面试项目数据量小 LIKE 足够 |
| 27 | 无外键约束 | 微服务架构通常不用物理外键，用应用层保证一致性 |
| 28 | file_type INT → TINYINT | 已有数据在跑，改字段类型需要迁移，不影响功能 |
| 39 | getBreadcrumbs 全量查询 | 文件夹数量通常很少（几十个），全量查+内存构建比递归查询更高效 |
| 40 | SELECT * | MyBatis-Plus 默认查全字段，已通过 wrapper.select() 优化了搜索接口 |

---

## 面试讲点总结

修复后的项目可以讲以下技术亮点：

1. **MyBatis-Plus 逻辑删除**：`@TableLogic` 注解 + 全局配置，框架自动过滤已删除数据
2. **N+1 查询优化**：分享列表用 `GROUP BY` 批量查询替代循环 selectCount
3. **大文件流式处理**：文件合并用 `Files.copy()` 流式传输，避免 OOM
4. **安全设计**：CORS 白名单、SecureRandom 提取码、分享 URL 128 位熵、路径穿越防护
5. **事务管理**：批量操作加 `@Transactional`，保证原子性
6. **日志规范**：SLF4J 替代 printStackTrace，SQL 日志走 Slf4jImpl
7. **API 安全**：`@JsonIgnore` 隐藏密码哈希，`@Pattern` 校验文件名
8. **数据库索引**：复合索引优化高频查询场景

---

## 重新打包部署

修改完成后需要重新打包上传：

```bash
# 本地打包
mvn clean package -DskipTests

# 上传 4 个 jar 到服务器 /data/cloud-drive/
# auth-service-1.0.0.jar
# user-service-1.0.0.jar
# file-service-1.0.0.jar
# gateway-1.0.0.jar

# 服务器上重启
cd /data/cloud-drive
bash stop-all.sh
bash start-all.sh
```

注意：数据库需要重新执行 init.sql 中新增的索引（如果已有数据，用 ALTER TABLE 添加索引）。

---

## 第二轮修复（逐条核对补充）

对审计报告 60+ 条问题逐一核对后，追加修复 5 条：

| # | 问题 | 修复方式 | 面试讲点 |
|---|------|---------|---------|
| 33（补） | user-service 和 file-service 缺 log-impl | 两个 yml 补 `log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl` | 日志一致性：三个服务统一走 SLF4J |
| 62 | ShareFileMapper `${shareIds}` SQL 注入风险 | `@Select` + `<script><foreach>` 预编译参数绑定，调用方改传 `List<Long>` | SQL 注入防护：`${}` 是字符串拼接，`#{}` 是预编译参数绑定 |
| 6 | 分享接口无限流 | ShareController.createShare 加 `@RateLimit(key="'share:'+#userId", limit=5, windowSize=60)` | 限流保护：上传和分享都按用户维度限流 |
| 22 | 缺 Swagger/OpenAPI 文档 | 集成 SpringDoc OpenAPI 2.3.0：4 个服务加依赖 + yml 配置 + OpenApiConfig 类 | API 文档：面试展示时 Swagger UI 让接口一目了然 |
| 25 | 文件上传无类型校验 | upload 和 mergeFile 加 `FORBIDDEN_SUFFIXES` 黑名单校验（.exe/.bat/.sh/.jar 等） | 上传安全：拒绝可执行文件和脚本文件 |

### 修改文件清单（第二轮）

| 文件 | 改动 |
|------|------|
| user-service/application.yml | 补 log-impl |
| file-service/application.yml | 补 log-impl + springdoc 配置 |
| auth-service/application.yml | 补 springdoc 配置 |
| ai-service/application.yml | 补 springdoc 配置 |
| ShareFileMapper.java | `${shareIds}` → `<foreach>` 预编译 |
| ShareServiceImpl.java | 调用方 String → List<Long> |
| ShareController.java | createShare 加 @RateLimit |
| UserFileServiceImpl.java | upload + mergeFile 加 FORBIDDEN_SUFFIXES 校验 |
| pom.xml（根） | 加 springdoc 版本管理 |
| auth/user/file/ai-service pom.xml | 加 springdoc 依赖 |
| SaTokenConfigure.java | 白名单放行 swagger 路径 |
| OpenApiConfig.java ×4 | 新建，各服务文档配置 |

### Swagger UI 访问地址

部署后通过各服务端口直接访问：
- 认证服务：http://localhost:8090/swagger-ui.html
- 用户服务：http://localhost:8091/swagger-ui.html
- 文件服务：http://localhost:8092/swagger-ui.html
- AI 服务：http://localhost:8093/swagger-ui.html
