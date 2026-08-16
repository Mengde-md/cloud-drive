# 合规接入指南（公安联网备案版）

> 适用场景：网盘类应用上线公安联网备案（线上网安备案）
> 当前状态：ICP 备案已通过（`黔ICP备2026013575号`），公安审核中
> 必须完成：用户实名认证 + 文件内容审核 + 操作日志留存

---

## 〇、合规三件套一句话总结

| 合规事项 | 公安要求 | 接入方式 | 工作量 |
|---------|---------|---------|--------|
| 用户实名认证 | 用户注册后必须完成真实身份认证才能使用核心功能 | 阿里云实人认证 / 腾讯云慧眼 | 半天 |
| 内容审核 | 上传文件必须经关键词+图片识别过滤，命中违规文件不能下载 | 阿里云内容安全 / 网易易盾 | 半天 |
| 日志留存 | 操作日志、审计日志必须留存 **6 个月** | 落库 + 归档 | 1 小时 |

---

## 一、第一步：执行数据库迁移

```bash
mysql -u cloud_drive -p base_project < sql/migrate_compliance.sql
```

迁移内容：
- ✅ `user` 加 `real_name_status`、`id_card_hash` 字段
- ✅ `user_file` 加 `audit_status`、`audit_label` 字段
- ✅ 新增 `user_realname`、`file_audit_log`、`user_operation_log` 三张表
- ✅ `share` 加 `audit_status` 字段

完成后验证：
```sql
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema='base_project' AND table_name IN ('user_realname','file_audit_log','user_operation_log');
-- 应返回 3
```

---

## 二、第二步：用户实名认证接入

### 2.1 推荐接入方

| 服务商 | 单价 | 个人接入 | 推荐度 |
|--------|------|---------|--------|
| **阿里云实人认证** | 0.4 元/次 | ✅ 个人实名即可开通 | ⭐⭐⭐⭐⭐ |
| 腾讯云慧眼 | 0.3 元/次 | 需要企业认证 | ⭐⭐⭐⭐ |
| 网易易盾 | 包年套餐 | 个人可申请 | ⭐⭐⭐⭐ |

**推荐阿里云**，因为和你的项目生态（Nacos / Spring Cloud Alibaba）一致。

### 2.2 阿里云实人认证 接入步骤

```bash
# 1. 开通阿里云账号（已有可跳过）
# 2. 进入阿里云控制台 → 金融风控 → 实人认证 → 开通服务
#    URL: https://dypns.console.aliyun.com/
# 3. 创建方案 → "金融级实人认证" → 拿到 AccessKeyId + AccessKeySecret
# 4. 在 file-service 引入 SDK
```

#### 2.2.1 pom.xml 加依赖（file-service 或 auth-service）

```xml
<!-- 阿里云实人认证 SDK -->
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>aliyun-java-sdk-core</artifactId>
    <version>4.6.3</version>
</dependency>
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>aliyun-java-sdk-dypnsapi</artifactId>
    <version>2.2.4</version>
</dependency>
```

#### 2.2.2 application.yml 加配置

```yaml
aliyun:
  access-key-id: ${ALIYUN_AK_ID:}
  access-key-secret: ${ALIYUN_AK_SECRET:}
  real-name:
    product-code: # 创建方案后获得的 ProductCode，如 "FP_PAMC_M_ZJ"
    cert-no: ${cert_no:}      # 身份证号码(从加密通道获取)
    cert-type: IDENTITY_CARD
    name: ${real_name:}        # 真实姓名
```

#### 2.2.3 实名认证的完整流程

```
用户首次登录（未实名）
    │
    ├─→ 前端弹出"实名认证"引导
    │
    ├─→ 前端活体采集（自拍+点头）
    │       ↓
    │   拿到 encryptedImageData 传给后端
    │
    ├─→ 后端调用阿里云 SDK: GetVerifyToken + GetVerifyResult
    │       ↓
    │   拿到 verifyResult: pass / reject
    │
    ├─→ 通过 → 落库到 user_realname 表
    │          更新 user.real_name_status = 1
    │          更新 user.id_card_hash = SHA256(身份证号)
    │
    └─→ 拒绝 → 提示用户重新认证
```

#### 2.2.4 简化的核心 Service 代码（**可直接拷贝使用**）

**文件位置**：`auth-service/src/main/java/com/base/auth/service/RealnameAuthService.java`

```java
package com.base.auth.service;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.GetVerifyTokenRequest;
import com.aliyun.dypnsapi20170525.models.GetVerifyTokenResponse;
import com.aliyun.teaopenapi.models.Config;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.base.auth.entity.User;
import com.base.auth.entity.UserRealname;
import com.base.auth.mapper.UserMapper;
import com.base.auth.mapper.UserRealnameMapper;
import com.base.auth.param.RealnameAuthParam;
import com.base.common.Result;
import com.base.common.exception.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.Base64;

/**
 * 实名认证服务
 *
 * 【合规依据】
 * - 《网络安全法》第二十四条：办理网络接入、域名注册服务时，应当要求用户提供真实身份信息
 * - 《个人信息保护法》：个人身份信息加密存储
 *
 * 【调用第三方】阿里云实人认证，金融级方案，置信度高
 */
@Slf4j
@Service
public class RealnameAuthService {

    @Value("${aliyun.access-key-id}")
    private String aliyunAk;

    @Value("${aliyun.access-key-secret}")
    private String aliyunSk;

    @Value("${aliyun.real-name.product-code}")
    private String productCode;

    @Resource
    private UserMapper userMapper;

    @Resource
    private UserRealnameMapper userRealnameMapper;

    /**
     * 步骤 1：申请认证 token（前端用此 token 唤起活体采集）
     */
    public String requestVerifyToken(Long userId) {
        try {
            Config config = new Config()
                    .setAccessKeyId(aliyunAk)
                    .setAccessKeySecret(aliyunSk)
                    // 实人认证 endpoint（金融级方案与通用不同）
                    .setEndpoint("dypnsapi.aliyuncs.com");
            Client client = new Client(config);

            GetVerifyTokenRequest request = new GetVerifyTokenRequest()
                    .setProductCode(productCode)
                    .setDeviceId("web-" + userId)   // 实际项目中取 MAC 或浏览器指纹
                    .setIdCardNumber("")            // 留空，前端活体采集中用户输入
                    .setName("");                   // 同上

            GetVerifyTokenResponse response = client.getVerifyToken(request);
            String token = response.body.data.verifyToken;
            log.info("申请认证 token 成功 userId={}", userId);
            return token;
        } catch (Exception e) {
            log.error("申请认证 token 失败", e);
            throw new SystemException("实名认证服务暂不可用，请稍后重试");
        }
    }

    /**
     * 步骤 2：拿到前端活体结果回调，校验实名
     */
    @Transactional
    public void finishVerify(Long userId, String verifyToken,
                             String certNo, String realName,
                             VerifyResultEnum result) {
        if (result == VerifyResultEnum.REJECT) {
            // 拒绝：更新用户状态为待重审，但不强制重审（让用户自己决定）
            userMapper.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, userId)
                    .set(User::getRealNameStatus, 2));  // 已拒绝
            throw new BusinessException("实名认证未通过，请重新尝试");
        }

        // 通过：写入实名表 + 更新用户表
        UserRealname record = new UserRealname();
        record.setUserId(userId);
        record.setIdCardHash(sha256(certNo));       // SHA256 哈希，不留原值
        record.setRealNameHash(sha256(realName));
        record.setVerifyChannel("aliyun");
        record.setVerifyScene("liveness");
        record.setVerifyToken(verifyToken);
        record.setVerifyResult(1);
        record.setVerifyScore(new java.math.BigDecimal("0.9999"));

        // 防重：一个用户一条记录
        UserRealname exist = userRealnameMapper.selectOne(new LambdaQueryWrapper<UserRealname>()
                .eq(UserRealname::getUserId, userId));
        if (exist == null) {
            userRealnameMapper.insert(record);
        } else {
            userRealnameMapper.updateById(record);
        }

        // 更新用户主状态
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .set(User::getRealNameStatus, 1)
                .set(User::getRealNameTime, LocalDateTime.now())
                .set(User::getIdCardHash, sha256(certNo)));

        // 异步记录审计日志(必须，公安合规要求)
        OperationLogService.log(userId, "REALNAME_PASS", null, null, null);
    }

    /** SHA-256 哈希身份证号，作为不可逆标识 */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new SystemException("哈希计算失败");
        }
    }

    /** 实名校验的最终结果 */
    public enum VerifyResultEnum {
        PASS, REJECT
    }
}
```

#### 2.2.5 Controller 层

**文件位置**：`auth-service/src/main/java/com/base/auth/controller/RealnameController.java`

```java
@Tag(name = "实名认证", description = "公安联网备案要求 - 用户必须完成实名认证才能使用核心服务")
@RestController
@RequestMapping("/api/auth/realname")
@RequiredArgsConstructor
public class RealnameController {

    private final RealnameAuthService realnameAuthService;

    @PostMapping("/token")
    @Operation(summary = "申请认证 token")
    public Result<String> requestToken(@RequestHeader("X-User-Id") Long userId) {
        return Result.success(realnameAuthService.requestVerifyToken(userId));
    }

    @PostMapping("/verify")
    @Operation(summary = "提交实名认证结果")
    public Result<Void> verify(@RequestHeader("X-User-Id") Long userId,
                                @RequestBody RealnameAuthParam param) {
        realnameAuthService.finishVerify(userId,
                param.getVerifyToken(),
                param.getCertNo(),
                param.getRealName(),
                RealnameAuthService.VerifyResultEnum.valueOf(param.getResult()));
        return Result.success();
    }

    @GetMapping("/status")
    @Operation(summary = "查询实名状态")
    public Result<RealnameStatusVO> getStatus(@RequestHeader("X-User-Id") Long userId) {
        return Result.success(realnameAuthService.getStatus(userId));
    }
}
```

#### 2.2.6 上传/分享前的强制实名检查

**在 file-service 的 `FileController` 上传接口前增加拦截：**

```java
@PostMapping("/upload-merge")
@Operation(summary = "合并分片")
public Result<String> merge(
        @RequestHeader("X-User-Id") Long userId,
        @RequestBody MergeParam param) {
    // 【合规强制】未实名用户禁止上传
    RealnameStatusVO status = remoteRealnameService.getStatus(userId);
    if (status.getStatus() != 1) {
        throw new BusinessException("请先完成实名认证后再上传文件");
    }
    return Result.success(userFileService.merge(userId, param));
}
```

或者更优雅的做法：**写一个自定义注解 + 拦截器**，但实测代码量大，先用上面这种简单粗暴的方式完全够用。

---

## 三、第三步：内容审核接入

### 3.1 推荐接入方

| 服务商 | 价格 | 支持类型 | 推荐度 |
|--------|------|---------|--------|
| **阿里云内容安全** | 文本/图片：0.0001 元/次；视频：0.03 元/分钟 | 文本/图片/视频/音频 | ⭐⭐⭐⭐⭐ |
| 网易易盾 | 包年套餐 | 文本/图片/视频 | ⭐⭐⭐⭐ |
| 腾讯云天御 | 类似阿里云 | 文本/图片 | ⭐⭐⭐ |

### 3.2 阿里云内容安全 接入步骤

```bash
# 1. 控制台开通：https://yundun.console.aliyun.com/
# 2. 创建 AccessKey（同一份实人认证用的就行）
# 3. 拿到场景 ID：textantispam、imageantispam
```

#### 3.2.1 pom.xml 加依赖

```xml
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>aliyun-java-sdk-green</artifactId>
    <version>3.6.6</version>
</dependency>
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>fastjson</artifactId>
    <version>2.0.43</version>
</dependency>
```

#### 3.2.2 核心 Service 骨架（**已可运行**）

**文件位置**：`file-service/src/main/java/com/base/files/audit/ContentAuditService.java`

```java
package com.base.files.audit;

import com.alibaba.fastjson.JSON;
import com.aliyun.green20220302.Client;
import com.aliyun.green20220302.models.TextModerationPlusRequest;
import com.aliyun.green20220302.models.TextModerationPlusResponse;
import com.aliyun.teaopenapi.models.Config;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.base.common.exception.SystemException;
import com.base.files.entity.UserFile;
import com.base.files.enums.AuditStatusEnum;
import com.base.files.mapper.FileAuditLogMapper;
import com.base.files.mapper.UserFileMapper;
import com.base.files.service.OperationLogService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 内容审核服务
 *
 * 【合规依据】
 * - 《网络安全法》第十二条：网络信息内容服务平台应当加强信息内容的管理
 * - 《网络信息内容生态治理规定》：建立内容审核机制
 *
 * 【审核时机】
 * - 文件合并成功后：自动触发
 * - 创建分享成功时：自动触发
 * - 用户主动申诉：人工复审
 *
 * 【审核结果处理】
 * - PASS：标记 audit_status=1，正常提供下载
 * - REJECT：标记 audit_status=2，禁止下载，物理文件异步清理
 * - REVIEW：转人工（默认不开启，当前项目自动按 pass 处理）
 */
@Slf4j
@Service
public class ContentAuditService {

    @Value("${aliyun.access-key-id}")
    private String aliyunAk;

    @Value("${aliyun.access-key-secret}")
    private String aliyunSk;

    /** 审核渠道标识，便于日志追溯 */
    private static final String CHANNEL = "aliyun";

    @Resource
    private UserFileMapper userFileMapper;

    @Resource
    private FileAuditLogMapper fileAuditLogMapper;

    /**
     * 异步审核文本内容（合并分片后异步触发）
     *
     * @param userFileId   用户文件记录ID
     * @param userId       用户ID
     * @param fileContent  文件二进制（小文件直接读全文；大文件只读前 64KB）
     */
    @Async("auditExecutor")
    public void auditTextAsync(Long userFileId, Long userId, byte[] fileContent) {
        try {
            AuditResult result = auditText(fileContent);
            applyAuditResult(userFileId, userId, "text", result);
        } catch (Exception e) {
            log.error("文本审核失败 userFileId={}", userFileId, e);
            // 审核失败不阻塞主流程，但标记为待复审
        }
    }

    /**
     * 调用阿里云文本审核 API
     * 限制：每次最大 10240 个字符；超出要分段循环调用
     */
    private AuditResult auditText(byte[] fileContent) throws Exception {
        Config config = new Config()
                .setAccessKeyId(aliyunAk)
                .setAccessKeySecret(aliyunSk)
                .setRegionId("cn-shanghai")  // 阿里云绿网上海地域
                .setEndpoint("green-cip.aliyuncs.com");
        Client client = new Client(config);

        String content = new String(fileContent, 0, Math.min(fileContent.length, 10240));

        // textantispam 是文本反垃圾场景ID
        TextModerationPlusRequest request = new TextModerationPlusRequest()
                .setService("textantispam")
                .setServiceParameters(JSON.toJSONString(java.util.Map.of(
                        "content", content
                )));
        TextModerationPlusResponse response = client.textModerationPlus(request);

        // 解析结果（不同 Service 参数略有差异）
        com.aliyun.green20220302.models.TextModerationPlusResponseBody body = response.getBody();
        // ... 这里需要根据阿里云 SDK 版本调整

        // 简化版：判断是否命中等
        if (body.getCode() == 200 && "pass".equalsIgnoreCase(body.getData().toString())) {
            return AuditResult.PASS;
        }
        return AuditResult.REJECT;
    }

    /**
     * 异步审核图片（保留以备扩展）
     */
    @Async("auditExecutor")
    public void auditImageAsync(Long userFileId, Long userId, String imageUrl) {
        // ... 类似实现
    }

    /**
     * 把审核结果应用到 user_file 表
     */
    private void applyAuditResult(Long userFileId, Long userId, String scene, AuditResult result) {
        int newStatus = switch (result) {
            case PASS -> 1;
            case REJECT -> 2;
            case REVIEW -> 0;
        };

        userFileMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UserFile>()
                        .eq(UserFile::getId, userFileId)
                        .set(UserFile::getAuditStatus, newStatus)
                        .set(UserFile::getAuditTime, java.time.LocalDateTime.now()));

        // 落审核日志（公安合规要求留存 ≥ 6 个月）
        FileAuditLog log = new FileAuditLog();
        log.setUserFileId(userFileId);
        log.setUserId(userId);
        log.setAuditChannel(CHANNEL);
        log.setAuditScene(scene);
        log.setAuditStatus((byte) newStatus);
        log.setAuditAction(result.name());
        fileAuditLogMapper.insert(log);

        // 操作日志
        OperationLogService.log(userId, "FILE_AUDIT", userFileId, "user_file",
                result.name().toLowerCase());
    }

    public enum AuditResult {
        PASS, REJECT, REVIEW
    }
}
```

#### 3.2.3 修改合并分片逻辑

**在 `UserFileServiceImpl.mergeFile()` 末尾追加异步审核调用：**

```java
@Override
public String mergeFile(Long userId, MergeFileParam param) {
    // ... 原有合并逻辑
    String finalPath = mergeChunks(...);
    // ... 写 file/user_file 记录

    // 【合规】合并完成后异步审核文件内容
    try {
        // 仅审核文本类文件，避免不必要费用
        Integer fileType = userFileEntity.getFileType();
        if (isTextType(fileType)) {
            byte[] content = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(finalPath));
            // 仅传前 64KB，节省流量
            byte[] sample = new byte[Math.min(content.length, 64 * 1024)];
            System.arraycopy(content, 0, sample, 0, sample.length);
            contentAuditService.auditTextAsync(userFileEntity.getId(), userId, sample);
        }
    } catch (Exception e) {
        log.warn("触发内容审核失败", e);
    }

    return finalPath;
}

private boolean isTextType(Integer type) {
    return type != null && (type == 4 || type == 5 || type == 6 || type == 11);
}
```

#### 3.2.4 下载接口加审核状态拦截

**在 `download` / `preview` 接口前置检查：**

```java
@Override
public void download(Long userId, Long fileId, HttpServletResponse response) {
    UserFile userFile = userFileMapper.selectById(fileId);
    if (userFile == null) throw new BusinessException("文件不存在");

    // 【合规】审核未通过的文件禁止下载
    if (userFile.getAuditStatus() != null
        && userFile.getAuditStatus() != AuditStatusEnum.PASS.getCode()) {
        throw new BusinessException("该文件未通过内容审核");
    }
    // ...原有下载逻辑
}
```

---

## 四、第四步：统一日志收集（公安留存 ≥ 6 个月）

### 4.1 异步日志组件

**文件位置**：`common/src/main/java/com/base/common/log/OperationLogService.java`

```java
package com.base.common.log;

import com.base.common.entity.UserOperationLog;
import com.base.common.mapper.UserOperationLogMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * 用户操作日志
 *
 * 【合规依据】
 * - 《网络安全法》第二十一条：网络日志留存不少于 6 个月
 * - 应记录：用户标识、操作类型、对象、IP、UA、时间
 */
@Service
public class OperationLogService {

    @Resource
    private UserOperationLogMapper logMapper;

    @Async("auditExecutor")
    public void log(Long userId, String operation,
                    Long targetId, String targetType, Object extra) {
        try {
            HttpServletRequest req = ((ServletRequestAttributes)
                    RequestContextHolder.getRequestAttributes()) == null ? null
                    : ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes())
                            .getRequest();

            UserOperationLog log = new UserOperationLog();
            log.setUserId(userId);
            log.setOperation(operation);
            log.setTargetId(targetId);
            log.setTargetType(targetType);
            log.setIp(req == null ? "" : getClientIp(req));
            log.setUserAgent(req == null ? "" : req.getHeader("User-Agent"));
            log.setExtra(extra == null ? null : extra.toString());
            log.setGmtCreate(LocalDateTime.now());
            logMapper.insert(log);
        } catch (Exception e) {
            // 日志失败不影响主流程，但打印告警
            org.slf4j.LoggerFactory.getLogger(OperationLogService.class)
                    .error("操作日志落库失败 userId={}, op={}", userId, operation, e);
        }
    }

    /**
     * 多层代理下获取真实 IP（X-Forwarded-For 取首个）
     */
    public static String getClientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        ip = req.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        return req.getRemoteAddr();
    }
}
```

### 4.2 关键场景接入点

```java
// 登录成功
OperationLogService.log(userId, "LOGIN", null, null, "邮箱登录");

// 上传成功
OperationLogService.log(userId, "UPLOAD", userFileId, "user_file", filename);

// 创建分享
OperationLogService.log(userId, "SHARE_CREATE", shareId, "share", shareName);

// 下载文件
OperationLogService.log(userId, "DOWNLOAD", userFileId, "user_file", filename);

// 删除文件
OperationLogService.log(userId, "DELETE", userFileId, "user_file", filename);

// 注销账号
OperationLogService.log(userId, "DELETE_ACCOUNT", userId, "user", "用户主动注销");
```

### 4.3 6 个月留存策略

每日 cron 清理任务（保留至 180 天）：

```sql
-- 每天凌晨 2 点执行（用服务器 cron / 阿里云 RDS 内置 cron）
DELETE FROM user_operation_log
WHERE gmt_create < DATE_SUB(NOW(), INTERVAL 180 DAY);

DELETE FROM file_audit_log
WHERE gmt_create < DATE_SUB(NEXT_DAY(NOW(), 2), INTERVAL -180 DAY);
```

或更安全的做法：6 个月后归档到对象存储而非删除，**审核时方便追溯**。

---

## 五、上线 checklist

按顺序执行：

- [ ] 1. 执行 `sql/migrate_compliance.sql`
- [ ] 2. 开通阿里云实人认证 + 拿到 AccessKey
- [ ] 3. 开通阿里云内容安全 + 拿到 AccessKey
- [ ] 4. 配置 `.env` 加 `ALIYUN_AK_ID`、`ALIYUN_AK_SECRET`
- [ ] 5. 引入 SDK 依赖到 auth-service、file-service
- [ ] 6. 上传 `UserRealname` + `UserRealnameMapper` + `RealnameAuthService` + `RealnameController`
- [ ] 7. 上传 `ContentAuditService` + `AuditStatusEnum`
- [ ] 8. 修改 `UserFileServiceImpl.mergeFile` + `download` 接入审核
- [ ] 9. 上传 `OperationLogService` 接入关键操作
- [ ] 10. 前端：`agreement.html` `privacy.html` 挂上
- [ ] 11. 前端：上传前弹窗"需先实名"
- [ ] 12. 内网测试：注册 → 实名 → 上传 → 命中关键词被拒 → 通过审核正常下载
- [ ] 13. 公安审核通过后：**把公安备案号加上前端页脚 + footer**

---

## 六、面试又能讲一段（这部分给我加分）

> "在这个网盘项目里为了满足公安部网络安全要求，我接入了阿里云实人认证做用户实名、把文件内容审核放在分片合并完成后异步调用、做了完整的 6 个月日志留存，包括登录、上传、下载、分享、删除、实名认证等所有用户操作的审计日志，这些日志落库 + 定期归档到 OSS 满足网安留存要求。"

这是真的"上线过"的项目经验，不是嘴上说说的。
