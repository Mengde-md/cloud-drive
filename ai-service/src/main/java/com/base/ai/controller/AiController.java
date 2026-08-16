package com.base.ai.controller;

import com.base.ai.service.AiApplicationService;
import com.base.ai.vo.AiQuestionParam;
import com.base.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * AI 文档智能控制器 — 提供文档摘要、标签生成、索引、问答等 REST 接口
 * <p>
 * 【接口设计概览】
 * <pre>
 * POST /api/ai/files/summarize  — 上传文档，AI 生成摘要
 * POST /api/ai/files/tags       — 上传文档，AI 生成标签
 * POST /api/ai/files/index      — 上传文档，建立索引（为 RAG 问答做准备）
 * POST /api/ai/files/question   — 对已索引的文档进行 RAG 问答
 * </pre>
 * <p>
 * 【认证方式】
 * 与 file-service 保持一致，用户 ID 通过网关注入到请求头 X-User-Id 中。
 * 下游服务（本服务）无需关心认证逻辑，直接使用 userId。
 * <p>
 * 【调用流程示意】
 * <pre>
 * 用户请求 → 网关（鉴权 + 注入 userId） → ai-service → AI API（通义千问等）
 *                                        ↓
 *                                  Tika 文档解析
 *                                  文本分块
 *                                  内存索引
 *                                  RAG 检索
 * </pre>
 * <p>
 * 【@RestController vs @Controller】
 * - @RestController = @Controller + @ResponseBody
 * - 所有方法的返回值自动序列化为 JSON（不会被当作视图名解析）
 * <p>
 * 【@RequiredArgsConstructor】
 * Lombok 注解，自动为所有 final 字段生成构造器。
 * Spring 会通过构造器注入依赖的 Bean，不需要写 @Autowired。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    /**
     * AI 应用服务（编排层）
     * Controller 只负责接收请求和返回响应，
     * 所有业务逻辑都委托给 AiApplicationService 处理。
     */
    private final AiApplicationService aiApplicationService;

    // ==================== 文档智能接口 ====================

    /**
     * 文档摘要接口
     * <p>
     * 用户上传一个文档（PDF/Word/Excel/TXT 等），AI 自动生成摘要。
     * <p>
     * 【请求格式】multipart/form-data
     * - file: 文档文件（必填）
     * - X-User-Id: 用户 ID（请求头，由网关注入）
     * <p>
     * 【响应格式】
     * <pre>
     * {
     *   "code": 200,
     *   "message": "success",
     *   "data": "## 文档摘要\n\n1. 核心主题：...\n2. 关键信息：..."
     * }
     * </pre>
     * <p>
     * 【内部处理流程】
     * 1. Tika 解析文档 → 纯文本
     * 2. 文本分块（如果文档过长）
     * 3. 将文本发给 LLM 生成摘要
     * 4. 返回摘要文本
     *
     * @param userId 用户 ID（网关从 Token 解析后注入到请求头）
     * @param file   上传的文档文件
     * @return AI 生成的文档摘要
     */
    @PostMapping("/files/summarize")
    public Result<String> summarizeDocument(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam("file") MultipartFile file) {

        log.info("用户 {} 请求生成文档摘要: {}", userId, file.getOriginalFilename());

        String summary = aiApplicationService.summarizeDocument(file);

        return Result.success(summary);
    }

    /**
     * 文档标签生成接口
     * <p>
     * 用户上传一个文档，AI 自动提取关键词标签。
     * <p>
     * 【应用场景】
     * - 文件分类管理
     * - 智能搜索
     * - 知识图谱构建
     * <p>
     * 【示例】
     * 输入：一份关于机器学习的论文
     * 输出："机器学习，深度学习，卷积神经网络，图像识别，Python，TensorFlow"
     *
     * @param userId 用户 ID
     * @param file   上传的文档文件
     * @return AI 生成的标签（逗号分隔的字符串）
     */
    @PostMapping("/files/tags")
    public Result<String> generateTags(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam("file") MultipartFile file) {

        log.info("用户 {} 请求生成文档标签: {}", userId, file.getOriginalFilename());

        String tags = aiApplicationService.generateTags(file);

        return Result.success(tags);
    }

    /**
     * 文档索引接口
     * <p>
     * 上传文档并建立索引，为后续的 RAG 问答做准备。
     * <p>
     * 【为什么要单独建索引？】
     * 文档解析和分块是耗时操作。如果每次问答都重新解析文档，
     * 用户体验会很差。所以先把文档索引好，后续问答时直接检索。
     * <p>
     * 【典型使用流程】
     * 1. 调用 POST /api/ai/files/index 索引文档（一次性操作）
     * 2. 多次调用 POST /api/ai/files/question 进行问答
     * <p>
     * 【请求格式】multipart/form-data
     * - fileId: 文件 ID（关联 file-service 中的文件记录）
     * - file: 文档文件
     *
     * @param userId 用户 ID
     * @param fileId 文件 ID（来自 file-service 的数据库主键）
     * @param file   上传的文档文件
     * @return 索引结果描述
     */
    @PostMapping("/files/index")
    public Result<String> indexDocument(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam("fileId") Long fileId,
            @RequestParam("file") MultipartFile file) {

        log.info("用户 {} 请求索引文档: fileId={}, filename={}",
                userId, fileId, file.getOriginalFilename());

        String result = aiApplicationService.indexDocument(userId, fileId, file);

        return Result.success(result);
    }

    /**
     * RAG 文档问答接口
     * <p>
     * 对已索引的文档提问，系统会：
     * 1. 从索引中检索与问题最相关的文本块
     * 2. 将文本块作为上下文发给 AI
     * 3. AI 基于上下文生成回答
     * <p>
     * 【这就是 RAG 的核心价值】
     * 用户问"合同的违约条款是什么？"
     * 系统检索到合同第七章的内容，发给 AI
     * AI 基于第七章的具体文本回答问题，而不是凭空编造
     * <p>
     * 【请求格式】application/json
     * <pre>
     * {
     *   "fileId": 123,
     *   "question": "合同的违约条款是什么？"
     * }
     * </pre>
     * <p>
     * 【@Valid 参数校验】
     * 配合 AiQuestionParam 中的 @NotNull 和 @NotBlank 注解，
     * Spring 会自动校验请求参数：
     * - fileId 不能为 null
     * - question 不能为空字符串
     * 校验失败会返回 400 错误和具体的错误信息。
     *
     * @param userId 用户 ID
     * @param param  问答请求参数（fileId + question）
     * @return AI 基于文档内容生成的回答
     */
    @PostMapping("/files/question")
    public Result<String> askQuestion(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody AiQuestionParam param) {

        log.info("用户 {} 提问: fileId={}, question='{}'",
                userId, param.getFileId(), param.getQuestion());

        String answer = aiApplicationService.askQuestion(userId, param.getFileId(), param.getQuestion());

        return Result.success(answer);
    }
}
