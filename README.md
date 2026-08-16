# CloudDrive - 网盘后端系统

基于 **Spring Boot 3 + Spring Cloud** 微服务架构构建的云存储（网盘）后端系统，支持文件上传、下载、分享、回收站、AI 文档智能等完整的网盘核心功能。

---

## 目录

- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [模块说明](#模块说明)
- [核心功能](#核心功能)
- [关键设计](#关键设计)
- [快速开始](#快速开始)
- [配置参考](#配置参考)
- [API 接口](#api-接口)
- [项目结构](#项目结构)

---

## 技术栈

| 类别 | 技术 | 版本 | 说明 |
|------|------|------|------|
| 语言 | Java | 17 | LTS 版本，兼顾企业兼容性与 Spring Boot 3 生态 |
| 框架 | Spring Boot | 3.2.4 | 应用主框架 |
| 微服务 | Spring Cloud | 2023.0.1 | 微服务基础设施 |
| 微服务组件 | Spring Cloud Alibaba | 2023.0.1.0 | Nacos、Sentinel 等组件集成 |
| 注册中心/配置中心 | Nacos | - | 服务发现 + 配置管理，支持热更新 |
| 服务调用 | OpenFeign | - | 声明式 HTTP 客户端，微服务间调用 |
| 网关 | Spring Cloud Gateway | - | 基于 WebFlux 的 API 网关，路由/鉴权/跨域 |
| 认证框架 | SA-Token | 1.37.0 | 轻量级权限认证，Token 存 Redis，支持分布式 |
| ORM | MyBatis-Plus | 3.5.5 | 增强版 MyBatis，简化 CRUD |
| 数据库 | MySQL | 8.0+ | 持久化存储 |
| 缓存 | Redis + Caffeine | - | 两级缓存：Caffeine 本地缓存（纳秒级）+ Redis 分布式缓存 |
| 分布式组件 | Redisson | 3.24.3 | 分布式锁、限流器、延迟队列 |
| 对象映射 | MapStruct | 1.5.5 | 编译期 DTO 转换，比 BeanUtils 快且类型安全 |
| 文档解析 | Apache Tika | 2.9.2 | 解析 PDF/Word/Excel/TXT 等文档内容 |
| 密码加密 | BCrypt | - | Spring Security Crypto，60 字符哈希 |
| 限流/熔断 | Sentinel | - | 流量控制、熔断降级（依赖已就绪） |
| 连接池 | Druid | 1.2.20 | 数据库连接池，自带监控面板 |
| 工具库 | Hutool, Lombok, Jakarta Validation | - | 通用工具集、代码简化、参数校验 |
| 构建工具 | Maven | 3.8+ | 多模块聚合构建 |

---

## 系统架构

```
                          ┌──────────────────────────────────────────────────────────┐
                          │              微服务集群 (Spring Cloud + Nacos)             │
                          │                                                          │
                          │   ┌──────────────────┐                                   │
                          │   │  auth-service    │  认证服务 :8090                    │
                          │   │  注册/登录/登出   │  SA-Token + BCrypt + Redis        │
                          │   └────────┬─────────┘                                   │
                          │            │                                             │
┌────────┐   ┌────────────┤            │                                             │
│        │   │  Gateway   │   ┌────────┴─────────┐                                  │
│ Client ├──►│    :8080   │   │  user-service    │  用户服务 :8091                   │
│        │   │  路由/鉴权  │   │  查询/存储空间    │                                  │
└────────┘   └────────────┤   └────────┬─────────┘                                  │
     │                    │            │                                             │
     │                    │   ┌────────┴─────────┐   ┌──────────────────┐            │
     │                    │   │  file-service    │   │  ai-service      │            │
     │                    │   │  上传/下载/管理   │   │  AI 文档智能 :8093│            │
     │                    │   │  分片/秒传/分享   │   │  摘要/标签/RAG   │            │
     │                    │   │  回收站 :8092     │   └────────┬─────────┘            │
     │                    │   └────────┬─────────┘            │                      │
     │                    └──────────────────────────────────────────────────────────┘
     │                                 │                  │
     │         ┌───────────┬───────────┤                  │
     │         │           │           │                  │
     │    ┌────┴────┐ ┌────┴────┐ ┌───┴────┐       ┌─────┴─────┐
     │    │  MySQL  │ │  Redis  │ │  磁盘   │       │  AI API   │
     │    │ 持久存储 │ │ 缓存/锁 │ │ 文件存储│       │ (通义千问) │
     │    └─────────┘ └─────────┘ └────────┘       └───────────┘
     │
     └─── 所有请求经 Gateway 统一路由转发，SA-Token 鉴权过滤
```

**请求流转：**

```
Client ──► Gateway (8080) ──┬──► auth-service (8090)  -- 认证：注册 / 登录 / 登出
                            ├──► user-service (8091)  -- 用户：查询 / 更新存储空间
                            ├──► file-service (8092)  -- 文件：上传 / 下载 / 管理 / 分享 / 回收站
                            └──► ai-service  (8093)   -- AI：文档摘要 / 标签 / 索引 / RAG 问答
```

**架构要点：**

- **Gateway 统一入口**：所有请求经网关路由转发，基于路径断言匹配到对应微服务；SA-Token 过滤器校验 Token，通过后将用户 ID 注入请求头 `X-User-Id` 传递给下游
- **Nacos 服务发现**：网关通过 `lb://服务名` 从 Nacos 动态获取服务实例地址，支持负载均衡，服务增减无需改配置
- **Nacos 配置中心**：敏感配置（数据库密码、API Key 等）通过 Nacos 统一下发，支持热更新
- **OpenFeign 服务间调用**：微服务之间通过声明式接口调用，代码简洁，自动负载均衡
- **Redis 共享会话**：SA-Token 的 Token 存储在 Redis 中，网关与所有微服务共享同一份 Token 数据

---

## 模块说明

```
基础项目/
├── common              # 公共模块
├── gateway             # API 网关
├── auth-service        # 认证服务
├── user-service        # 用户服务
├── file-service        # 文件服务
├── ai-service          # AI 文档智能服务
├── sql/                # 数据库初始化脚本
└── pom.xml             # 父 POM
```

| 模块 | 端口 | 说明 |
|------|------|------|
| **common** | - | 公共基础模块。统一响应封装 `Result<T>`、全局异常处理器、分布式锁（注解 + AOP）、通用工具类 |
| **gateway** | 8080 | API 网关。基于 Spring Cloud Gateway (WebFlux)，负责路由转发、CORS 跨域配置、SA-Token 鉴权过滤器 |
| **auth-service** | 8090 | 认证服务。用户注册、登录、登出；BCrypt 密码加密；SA-Token 会话管理（Token 存 Redis） |
| **user-service** | 8091 | 用户服务。用户信息查询、存储空间管理 |
| **file-service** | 8092 | 文件服务。核心业务模块，涵盖文件上传下载、文件夹管理、搜索、分享、回收站等全部功能 |
| **ai-service** | 8093 | AI 文档智能服务。基于 Apache Tika 解析文档 + LLM (通义千问) 实现文档摘要、标签生成、RAG 问答 |

---

## 核心功能

### 文件上传

| 上传方式 | 说明 |
|----------|------|
| **普通上传** | 通过 `MultipartFile` 接收，适用于小文件 |
| **分片上传** | 大文件切片上传 + 断点续传，支持查询已上传分片、最终合并 |
| **秒传** | 基于文件 MD5 去重，若文件已存在于服务器则跳过上传，直接关联 |

### 文件管理

- **文件夹 CRUD** — 创建、重命名、删除文件夹
- **文件重命名** — 支持文件与文件夹重命名，自动处理名称冲突（追加 `(1)`、`(2)` ...）
- **批量删除** — 逻辑删除，删除的文件进入回收站
- **文件转移** — 将文件 / 文件夹移动至目标目录
- **文件复制** — 支持文件及文件夹递归复制
- **文件搜索** — 基于 MySQL `LIKE` 的文件名模糊搜索
- **面包屑导航** — 获取当前目录的层级路径
- **文件夹树** — 获取用户的完整文件夹树结构

### 文件下载与预览

- **文件下载** — 以二进制流形式输出
- **文件预览** — 以二进制流形式输出，前端根据 Content-Type 渲染

### 回收站

- 列出已逻辑删除的文件
- 恢复已删除文件
- 物理删除（不可恢复）

### 文件分享

- 创建分享链接（可设置 **提取码** 和 **过期时间**）
- 查看我的分享列表
- 分享详情 / 验证提取码
- 浏览分享文件列表 / 下载分享文件（支持匿名访问，无需登录）
- 取消分享

### 首页概览

- 文件类型统计
- 最近上传文件列表
- Redis 缓存加速

### AI 文档智能

| 功能 | 说明 |
|------|------|
| **文档摘要** | 上传文档（PDF/Word/Excel/TXT），AI 自动生成内容摘要 |
| **标签生成** | AI 自动提取文档关键词标签，用于分类和搜索 |
| **文档索引** | 上传文档并建立索引（Tika 解析 + 文本分块），为 RAG 问答做准备 |
| **RAG 问答** | 对已索引的文档提问，系统检索相关文本块作为上下文，LLM 基于文档内容精准回答 |

---

## 关键设计

### 统一响应封装

所有接口统一返回 `Result<T>` 结构：

```json
{
  "code": 200,
  "message": "success",
  "data": { }
}
```

### 全局异常处理

| 异常类型 | HTTP 状态码 | 说明 |
|----------|-------------|------|
| `BusinessException` | 400 | 业务逻辑异常（参数错误、资源不存在等） |
| `SystemException` | 500 | 系统内部异常 |
| `MethodArgumentNotValidException` | 400 | Jakarta Validation 校验失败 |

### 认证方案（SA-Token）

- 用户登录后，SA-Token 生成 UUID 格式的 Token 并存入 Redis
- 前端每次请求在 Header 中携带 `satoken: xxxxx`
- 网关 SA-Token 过滤器统一校验 Token，校验通过后注入 `X-User-Id` 到请求头
- 下游微服务直接从请求头获取用户 ID，无需关心认证逻辑
- 支持分布式部署，多个服务实例共享 Redis 中的 Token

### 两级缓存（Caffeine + Redis）

- **L1 Caffeine 本地缓存**：纳秒级访问，适用于高频热点数据（如首页概览、文件类型统计）
- **L2 Redis 分布式缓存**：毫秒级访问，适用于需要跨服务共享的缓存数据
- 读取顺序：先查 L1 -> 未命中查 L2 -> 未命中查数据库 -> 回填缓存

### 分布式锁

基于 Redisson 实现的分布式锁，通过自定义注解 `@DistributeLock` + AOP 切面使用：

- **加锁**：Redisson `tryLock`，支持超时和自动续期
- **释放**：Redisson 自动管理锁的生命周期，Lua 脚本保证原子性

### 逻辑删除

使用 `deleted` 字段实现软删除，回收站功能基于此实现，避免数据误删。

### 文件名冲突处理

当同一目录下存在同名文件或文件夹时，系统自动追加序号：`文件(1).txt`、`文件(2).txt` ...

### AI 文档处理流程（RAG）

```
上传文档 → Tika 解析为纯文本 → 文本分块（1000字符/块，200字符重叠）
    → 存入内存索引 → 用户提问 → 检索最相似的 Top-K 文本块
    → 拼接为 Prompt 发给 LLM → 返回基于文档内容的精准回答
```

### 配置外部化

数据库地址、Redis 地址、AI API Key 等敏感配置均通过 Nacos 配置中心或环境变量注入，支持不同环境灵活切换，避免硬编码。

---

## 快速开始

### 1. 环境要求

| 依赖 | 最低版本 | 说明 |
|------|----------|------|
| JDK | 17+ | 推荐使用 Eclipse Temurin 17 |
| Maven | 3.8+ | 多模块构建 |
| MySQL | 8.0+ | 持久化存储 |
| Redis | 6.0+ | 缓存 + SA-Token 会话 + 分布式锁 |
| Nacos | 2.x | 服务注册发现 + 配置中心 |

### 2. 启动 Nacos

```bash
# 下载 Nacos 2.x 并解压后，以单机模式启动
startup.cmd -m standalone    # Windows
# 或
sh startup.sh -m standalone  # Linux/Mac
```

> Nacos 控制台默认地址：`http://localhost:8848/nacos`（账号密码 nacos/nacos）

### 3. 初始化数据库

执行 `sql/init.sql` 脚本创建数据库与数据表：

```bash
mysql -u root -p < sql/init.sql
```

### 4. 配置连接信息

通过环境变量配置（推荐），或直接编辑各服务的 `application.yml` / Nacos 配置中心：

```bash
# 数据库
set DB_HOST=192.168.119.128
set DB_PORT=3306
set DB_NAME=base_project
set DB_USERNAME=root
set DB_PASSWORD=123456

# Redis
set REDIS_HOST=192.168.119.128
set REDIS_PORT=6379
set REDIS_PASSWORD=123456

# Nacos（可选，默认为 192.168.119.128:8848）
set NACOS_HOST=192.168.119.128
set NACOS_PORT=8848

# 文件存储
set FILE_STORAGE_PATH=D:/base-project/files
set CHUNK_STORAGE_PATH=D:/base-project/chunks

# AI 服务（可选，使用 AI 功能时配置）
set AI_API_KEY=sk-your-api-key-here
set AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
set AI_CHAT_MODEL=qwen-plus
```

### 5. 编译项目

```bash
mvn clean install -DskipTests
```

### 6. 启动服务

按以下顺序依次启动：

```bash
# 1. 启动网关
java -jar gateway/target/gateway-1.0-SNAPSHOT.jar

# 2. 启动认证服务
java -jar auth-service/target/auth-service-1.0-SNAPSHOT.jar

# 3. 启动用户服务
java -jar user-service/target/user-service-1.0-SNAPSHOT.jar

# 4. 启动文件服务
java -jar file-service/target/file-service-1.0-SNAPSHOT.jar

# 5. 启动 AI 服务（可选）
java -jar ai-service/target/ai-service-1.0-SNAPSHOT.jar
```

> 服务启动完成后，网关统一入口地址为 `http://localhost:8080`
> 可在 Nacos 控制台查看各服务的注册状态

---

## 配置参考

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `DB_HOST` | MySQL 地址 | `192.168.119.128` |
| `DB_PORT` | MySQL 端口 | `3306` |
| `DB_NAME` | 数据库名 | `base_project` |
| `DB_USERNAME` | 数据库用户名 | `root` |
| `DB_PASSWORD` | 数据库密码 | `123456` |
| `REDIS_HOST` | Redis 地址 | `192.168.119.128` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `REDIS_PASSWORD` | Redis 密码 | `123456` |
| `NACOS_HOST` | Nacos 地址 | `192.168.119.128` |
| `NACOS_PORT` | Nacos 端口 | `8848` |
| `NACOS_NAMESPACE` | Nacos 命名空间 | 空（public） |
| `FILE_STORAGE_PATH` | 文件存储路径 | `D:/base-project/files` |
| `CHUNK_STORAGE_PATH` | 分片存储路径 | `D:/base-project/chunks` |
| `AI_API_KEY` | AI 服务 API Key | - |
| `AI_BASE_URL` | AI API 地址 | 通义千问 DashScope |
| `AI_CHAT_MODEL` | AI 对话模型 | `qwen-plus` |

---

## API 接口

> 所有接口通过 Gateway (`http://localhost:8080`) 统一访问。
> 需要认证的接口请在请求头中携带 `satoken: <你的Token>`。

### 认证服务 (auth-service)

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| `POST` | `/api/auth/register` | 用户注册 | 否 |
| `POST` | `/api/auth/login` | 用户登录（返回 Token） | 否 |
| `GET` | `/api/auth/userinfo` | 获取当前用户信息 | 是 |
| `POST` | `/api/auth/logout` | 用户登出 | 是 |

### 用户服务 (user-service)

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| `GET` | `/api/user/{id}` | 查询用户信息 | 是 |
| `PUT` | `/api/user/{id}/space` | 更新存储空间 | 是 |

### 文件服务 (file-service)

#### 首页与概览

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/files/home/overview` | 首页概览（文件统计 + 最近文件） |

#### 文件夹与文件管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/files/folders-files` | 获取文件列表 |
| `POST` | `/api/files/folder` | 创建文件夹 |
| `PUT` | `/api/files/file` | 文件 / 文件夹重命名 |
| `DELETE` | `/api/files/file` | 删除文件（逻辑删除，进回收站） |
| `GET` | `/api/files/file/folder/tree` | 获取文件夹树 |
| `GET` | `/api/files/file/breadcrumbs` | 获取面包屑导航路径 |
| `POST` | `/api/files/file/search` | 文件搜索 |

#### 文件上传

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/files/file/sec-upload` | 秒传（MD5 去重） |
| `POST` | `/api/files/file/upload` | 普通文件上传 |
| `POST` | `/api/files/file/chunk-upload` | 分片上传（单片） |
| `GET` | `/api/files/file/chunk-upload` | 查询已上传分片 |
| `POST` | `/api/files/file/merge` | 合并分片 |

#### 文件下载与预览

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/files/file/download` | 文件下载 |
| `GET` | `/api/files/file/preview` | 文件预览 |

#### 文件转移与复制

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/files/file/transfer` | 文件转移 |
| `POST` | `/api/files/file/copy` | 文件复制（支持文件夹递归复制） |

#### 回收站

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/files/recycle/list` | 回收站文件列表 |
| `PUT` | `/api/files/recycle/restore` | 恢复已删除文件 |
| `DELETE` | `/api/files/recycle/delete` | 物理删除（不可恢复） |

### 分享服务 (file-service)

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| `POST` | `/api/shares` | 创建分享链接 | 是 |
| `GET` | `/api/shares/list` | 我的分享列表 | 是 |
| `GET` | `/api/shares/{shareId}` | 分享详情 | 视情况 |
| `GET` | `/api/shares/{shareId}/code` | 验证提取码 | 否 |
| `GET` | `/api/shares/{shareId}/files` | 分享文件列表（匿名可访问） | 否 |
| `GET` | `/api/shares/{shareId}/download` | 下载分享文件 | 否 |
| `DELETE` | `/api/shares/{shareId}` | 取消分享 | 是 |

### AI 文档智能服务 (ai-service)

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/ai/files/summarize` | 文档摘要（上传文档，AI 生成摘要） |
| `POST` | `/api/ai/files/tags` | 标签生成（上传文档，AI 提取关键词标签） |
| `POST` | `/api/ai/files/index` | 文档索引（上传文档建立索引，为 RAG 做准备） |
| `POST` | `/api/ai/files/question` | RAG 问答（对已索引文档提问，基于内容精准回答） |

---

## 项目结构

```
基础项目/
├── pom.xml                         # 父 POM（依赖管理 + 模块聚合）
├── sql/
│   └── init.sql                    # 数据库初始化脚本
│
├── common/                         # 公共模块
│   ├── pom.xml
│   └── src/main/java/
│       ├── result/                 # Result<T> 统一响应封装
│       ├── exception/              # 全局异常处理
│       │   ├── BusinessException
│       │   ├── SystemException
│       │   └── GlobalExceptionHandler
│       ├── lock/                   # 分布式锁
│       │   ├── DistributeLock      # 自定义注解
│       │   └── LockAspect          # AOP 切面
│       └── utils/                  # 工具类
│
├── gateway/                        # API 网关
│   ├── pom.xml
│   └── src/main/
│       ├── java/
│       │   ├── GatewayApplication
│       │   └── filter/             # 网关过滤器（SA-Token 鉴权 + 注入 X-User-Id）
│       └── resources/
│           ├── bootstrap.yml       # Nacos 连接配置
│           └── application.yml     # 路由规则 + CORS 配置 + SA-Token
│
├── auth-service/                   # 认证服务
│   ├── pom.xml
│   └── src/main/
│       ├── java/
│       │   ├── controller/         # 注册 / 登录 / 登出接口
│       │   ├── service/            # 认证业务逻辑（SA-Token 登录/注销）
│       │   ├── entity/             # 用户实体
│       │   └── mapper/             # MyBatis-Plus Mapper
│       └── resources/
│           ├── bootstrap.yml       # Nacos 连接配置
│           └── application.yml     # 数据源 + SA-Token 配置
│
├── user-service/                   # 用户服务
│   ├── pom.xml
│   └── src/main/
│       ├── java/
│       │   ├── controller/         # 用户查询 / 存储空间接口
│       │   ├── service/
│       │   ├── entity/
│       │   └── mapper/
│       └── resources/
│           ├── bootstrap.yml
│           └── application.yml
│
├── file-service/                   # 文件服务（核心业务）
│   ├── pom.xml
│   └── src/main/
│       ├── java/
│       │   ├── controller/         # 文件管理 / 上传 / 下载 / 回收站 / 分享
│       │   ├── service/            # 核心业务逻辑
│       │   ├── entity/             # 文件 / 文件夹 / 分享 / 分享文件实体
│       │   └── mapper/             # MyBatis-Plus Mapper
│       └── resources/
│           ├── bootstrap.yml
│           └── application.yml
│
└── ai-service/                     # AI 文档智能服务
    ├── pom.xml
    └── src/main/
        ├── java/
        │   ├── controller/         # 摘要 / 标签 / 索引 / RAG 问答接口
        │   ├── service/            # AI 编排层 + Tika 解析 + 文本分块 + RAG 检索
        │   ├── config/             # AI 配置属性类
        │   └── vo/                 # 请求参数 VO
        └── resources/
            ├── bootstrap.yml
            └── application.yml     # AI 提供商配置 + 索引参数
```

---

## 许可证

本项目仅供学习参考使用。
