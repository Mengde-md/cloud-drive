package com.base.ai.service;

import com.base.ai.config.AiProperties;
import com.base.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * AI 应用服务 — 编排层，组合各个基础服务完成业务功能
 * <p>
 * 【编排层（Orchestration Layer）的设计思想】
 * 本服务不直接做文档解析、分块、检索、AI 调用等具体工作，
 * 而是"编排"（组合调用）各个专职服务来完成更高层的业务功能。
 * <p>
 * 这种设计遵循了"单一职责原则"：
 * - DocumentParserService：只负责文档解析
 * - TextChunkingService：只负责文本分块
 * - AiProviderService：只负责 AI API 调用
 * - DocumentIndexService：只负责索引和检索
 * - AiApplicationService（本类）：组合上述服务，实现完整业务
 * <p>
 * 【本服务提供的功能】
 * 1. 文档摘要（summarizeDocument）：上传文档 → AI 生成摘要
 * 2. 标签生成（generateTags）：上传文档 → AI 生成标签
 * 3. 文档索引（indexDocument）：上传文档 → 解析分块 → 存入索引
 * 4. 文档问答（askQuestion）：RAG 流程 — 检索相关块 → 构造提示词 → AI 回答
 */
@Slf4j
@Service
public class AiApplicationService {

    private final DocumentParserService documentParserService;
    private final TextChunkingService textChunkingService;
    private final AiProviderService aiProviderService;
    private final DocumentIndexService documentIndexService;
    private final AiProperties aiProperties;

    /**
     * 构造器注入所有依赖
     * <p>
     * 【依赖注入的方式选择】
     * Spring 支持三种注入方式：
     * 1. 字段注入（@Autowired）— 最简单但不推荐，无法使用 final
     * 2. Setter 注入 — 适合可选依赖
     * 3. 构造器注入（本类使用）— 官方推荐，依赖明确，支持 final
     */
    public AiApplicationService(DocumentParserService documentParserService,
                                TextChunkingService textChunkingService,
                                AiProviderService aiProviderService,
                                DocumentIndexService documentIndexService,
                                AiProperties aiProperties) {
        this.documentParserService = documentParserService;
        this.textChunkingService = textChunkingService;
        this.aiProviderService = aiProviderService;
        this.documentIndexService = documentIndexService;
        this.aiProperties = aiProperties;
    }

    /**
     * 文档摘要 — 上传文档后由 AI 自动生成摘要
     * <p>
     * 【完整流程】
     * 1. 文档解析（Tika）：PDF/Word/Excel → 纯文本
     * 2. 文本分块：长文本 → 多个小块
     * 3. 拼接内容：将所有块合并为一段（截断到合适长度）
     * 4. AI 生成摘要：将文本发给 LLM，让 LLM 总结要点
     * <p>
     * 【注意】
     * 摘要功能不需要 RAG 检索，因为我们是让 AI 总结整篇文档，
     * 而不是回答关于文档某个细节的问题。
     * 但如果文档特别长（超过 LLM 上下文窗口），就需要：
     * - Map-Reduce 策略：先对每个块分别生成摘要，再合并成总摘要
     * - 或使用长上下文模型（如 GPT-4-128K）
     *
     * @param file 用户上传的文档文件
     * @return AI 生成的文档摘要
     */
    public String summarizeDocument(MultipartFile file) {
        log.info("开始生成文档摘要: {}", file.getOriginalFilename());

        // ========== 第 1 步：解析文档 ==========
        String text = documentParserService.parse(file);
        log.info("文档解析完成，文本长度: {} 字符", text.length());

        // ========== 第 2 步：准备发给 AI 的内容 ==========
        // 对于摘要功能，我们尽量把全文发给 AI
        // 如果文本太长，取前 N 个字符（保证不超过 LLM 上下文窗口）
        // 实际生产中应该用 Map-Reduce 策略来处理超长文档
        String contentForAI = truncateForLLM(text);

        // ========== 第 3 步：调用 AI 生成摘要 ==========
        String systemPrompt = """
                你是一个专业的文档摘要助手。你的任务是阅读文档内容，并生成一份结构清晰的摘要。
                
                【摘要要求】
                1. 提取文档的核心主题和主要观点
                2. 列出关键信息（如重要数据、日期、人名、结论等）
                3. 使用简洁的语言，避免冗余
                4. 摘要长度控制在原文的 10%-20%
                5. 使用 Markdown 格式输出，包含标题和要点列表
                """;

        String userMessage = "请为以下文档内容生成摘要：\n\n" + contentForAI;

        String summary = aiProviderService.chat(systemPrompt, userMessage);
        log.info("文档摘要生成完成，摘要长度: {} 字符", summary.length());

        return summary;
    }

    /**
     * 标签生成 — 上传文档后由 AI 自动提取关键词标签
     * <p>
     * 【应用场景】
     * 在网盘系统中，给文件打标签可以帮助用户：
     * - 快速分类和搜索文件
     * - 发现文件之间的关联
     * - 构建知识图谱
     * <p>
     * 传统方法是用 TF-IDF 或 TextRank 算法提取关键词，
     * 而 LLM 可以理解语义，生成的标签更加准确和有意义。
     *
     * @param file 用户上传的文档文件
     * @return AI 生成的标签列表（以逗号分隔的字符串）
     */
    public String generateTags(MultipartFile file) {
        log.info("开始生成文档标签: {}", file.getOriginalFilename());

        // ========== 第 1 步：解析文档 ==========
        String text = documentParserService.parse(file);

        // ========== 第 2 步：准备内容 ==========
        // 标签生成不需要全文，取前 3000 字符足够提取关键信息
        String contentForAI = text.length() > 3000 ? text.substring(0, 3000) : text;

        // ========== 第 3 步：调用 AI 生成标签 ==========
        String systemPrompt = """
                你是一个文档标签生成专家。你的任务是从文档内容中提取最有代表性的标签。
                
                【标签要求】
                1. 生成 5-10 个标签
                2. 标签应该是具体的关键词或短语（2-6 个字）
                3. 标签应涵盖文档的主题、领域、关键概念
                4. 避免过于宽泛的标签（如"文档"、"内容"）
                5. 用逗号分隔所有标签
                
                【输出格式】
                直接输出标签，用中文逗号（，）分隔，不要添加任何额外说明。
                示例：人工智能，深度学习，自然语言处理，神经网络，Transformer
                """;

        String userMessage = "请为以下文档内容生成标签：\n\n" + contentForAI;

        String tags = aiProviderService.chat(systemPrompt, userMessage);
        log.info("文档标签生成完成: {}", tags);

        return tags;
    }

    /**
     * 索引文档 — 解析文档并存入内存索引，为后续的 RAG 问答做准备
     * <p>
     * 【这是 RAG 的"离线"步骤】
     * RAG 分为两个阶段：
     * 1. 离线阶段（Indexing）：提前把文档解析、分块、建立索引
     *    → 本方法负责这一步
     * 2. 在线阶段（Querying）：用户提问时实时检索和生成
     *    → askQuestion() 方法负责这一步
     * <p>
     * 类比搜索引擎：
     * - 离线阶段 ≈ 爬虫爬取网页 + 建立倒排索引
     * - 在线阶段 ≈ 用户搜索 + 返回结果
     *
     * @param fileId 文件 ID（关联 file-service 中的文件记录）
     * @param file   用户上传的文档文件
     * @return 索引结果的描述信息
     */
    public String indexDocument(Long userId, Long fileId, MultipartFile file) {
        log.info("开始索引文档: fileId={}, filename={}", fileId, file.getOriginalFilename());

        // ========== 第 1 步：解析文档 ==========
        String text = documentParserService.parse(file);
        log.info("文档解析完成，文本长度: {} 字符", text.length());

        // ========== 第 2 步：分块并存储索引 ==========
        // indexDocument 内部会调用 TextChunkingService 进行分块
        documentIndexService.indexDocument(userId, fileId, file.getOriginalFilename(), text);

        // 计算分块数量用于返回给调用者
        int chunkSize = aiProperties.getIndex().getChunkSize();
        int overlap = aiProperties.getIndex().getChunkOverlap();
        int chunkCount = textChunkingService.chunk(text, chunkSize, overlap).size();

        String result = String.format("文档索引成功！文件名: %s, 原文长度: %d 字符, 分块数: %d",
                file.getOriginalFilename(), text.length(), chunkCount);

        log.info(result);
        return result;
    }

    /**
     * RAG 文档问答 — 基于已索引的文档回答用户问题
     * <p>
     * 【这是 RAG 的"在线"步骤，也是最核心的方法】
     * <p>
     * 完整流程：
     * 1. 从内存索引中检索与问题最相关的文本块
     * 2. 将检索到的文本块拼接为上下文
     * 3. 构造 RAG 提示词（系统提示词 + 上下文 + 用户问题）
     * 4. 调用 LLM 生成回答
     * 5. 返回回答结果
     * <p>
     * 【RAG 提示词的关键设计】
     * 系统提示词必须明确告诉 AI：
     * - "你只能基于提供的参考资料回答"
     * - "如果资料中没有答案，请说不知道"
     * 这样可以有效减少 LLM 的"幻觉"（编造不存在的信息）。
     *
     * @param fileId   文件 ID（指向已索引的文档）
     * @param question 用户的问题（自然语言）
     * @return AI 基于文档内容生成的回答
     */
    public String askQuestion(Long userId, Long fileId, String question) {
        log.info("RAG 问答: fileId={}, question='{}'", fileId, question);

        // ========== 第 1 步：检索相关文本块 ==========
        int topK = aiProperties.getIndex().getRetrievalTopK();
        List<String> relevantChunks = documentIndexService.searchRelevantChunks(userId, fileId, question, topK);

        // 检查是否检索到内容
        if (relevantChunks.isEmpty()) {
            throw new BusinessException("未能从文档中检索到相关内容。"
                    + "可能原因：1) 文档尚未被索引 2) 问题与文档内容无关");
        }

        log.info("检索到 {} 个相关文本块", relevantChunks.size());

        // ========== 第 2 步：拼接上下文 ==========
        // 将多个相关文本块拼接成一段完整的上下文
        // 用分隔线区分不同的块，方便 AI 理解来源
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < relevantChunks.size(); i++) {
            contextBuilder.append("【参考资料 ").append(i + 1).append("】\n");
            contextBuilder.append(relevantChunks.get(i));
            contextBuilder.append("\n\n");
        }
        String context = contextBuilder.toString().trim();

        log.debug("拼接的上下文长度: {} 字符", context.length());

        // ========== 第 3 步：调用 AI 生成回答（RAG） ==========
        // chatWithContext 内部会构造 RAG 专用的提示词模板
        String answer = aiProviderService.chatWithContext(context, question);

        log.info("RAG 问答完成，回答长度: {} 字符", answer.length());

        return answer;
    }

    /**
     * 截断文本以适应 LLM 上下文窗口
     * <p>
     * 【为什么需要截断？】
     * LLM 有上下文窗口限制（如 4096 tokens ≈ 3000 汉字）。
     * 如果文档有 5 万字，全部发给 LLM 会：
     * 1. 超出窗口限制，API 报错
     * 2. 即使不报错，也会消耗大量 Token（钱）
     * <p>
     * 【简单截断 vs Map-Reduce】
     * 本方法使用简单的"取前 N 字符"策略。
     * 生产系统应使用 Map-Reduce 策略处理超长文档：
     * 1. Map 阶段：对每个文本块分别生成小摘要
     * 2. Reduce 阶段：将所有小摘要合并，再生成总摘要
     * 这样可以覆盖整篇文档的内容，不会遗漏后半部分。
     *
     * @param text 原始文本
     * @return 截断后的文本
     */
    private String truncateForLLM(String text) {
        // 保守估计：1 个汉字 ≈ 2 个 Token
        // maxTokens = 2048（AI 回复的 Token 上限）
        // 上下文窗口 = 8192 tokens（假设）
        // 留给输入的空间 ≈ 8192 - 2048 = 6144 tokens ≈ 3000 汉字
        // 但为了安全，这里设为 6000 字符
        int maxLength = 6000;

        if (text.length() <= maxLength) {
            return text;
        }

        // 截断时添加提示，让 AI 知道内容被截断了
        return text.substring(0, maxLength) + "\n\n...（文档内容过长，已截断）";
    }
}
