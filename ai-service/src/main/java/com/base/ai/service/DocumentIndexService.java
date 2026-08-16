package com.base.ai.service;

import com.base.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 文档索引服务 — 存储和检索文档文本块
 * <p>
 * 【本服务在 RAG 流程中的位置】
 * 文档解析 → 文本分块 → [文档索引] → 检索相关块 → LLM 生成回答
 * <p>
 * 【本模块 vs 生产系统】
 * <p>
 * 本模块使用"关键词匹配"来实现简化版检索，目的是让你理解 RAG 的核心思想。
 * 下面详细说明简化版和生产版的区别：
 * <p>
 * === 本模块的简化方案 ===
 * - 存储：ConcurrentHashMap（内存 Map，服务重启后数据丢失）
 * - 检索：关键词计数（统计查询词在文本块中出现的次数，按次数排序）
 * - 优点：简单易懂，无需额外依赖
 * - 缺点：只能做字面匹配，无法理解语义
 * <p>
 * === 生产系统的方案 ===
 * - 存储：向量数据库（pgvector / Milvus / Pinecone / Weaviate）
 * - 索引过程：
 *   1. 使用 Embedding 模型（如 text-embedding-ada-002）将文本块转成向量
 *      例如："合同违约条款" → [0.12, -0.34, 0.56, ..., 0.78]（1536维浮点数数组）
 *   2. 将向量和原文本一起存入向量数据库
 * - 检索过程：
 *   1. 将用户问题也转成向量："合同的违约条款是什么？" → [0.11, -0.32, 0.55, ...]
 *   2. 在向量数据库中计算"余弦相似度"（Cosine Similarity）
 *      相似度越接近 1，表示语义越相似
 *   3. 返回最相似的 topK 个文本块
 * - 优点：能理解语义（"合同违约"和"违反协议"虽然字面不同，但语义相近）
 * - 缺点：需要额外的 Embedding 模型和向量数据库
 * <p>
 * 【余弦相似度（Cosine Similarity）简介】
 * 衡量两个向量之间夹角的余弦值：
 * - 值域：[-1, 1]
 * - 1 = 完全相同方向（语义完全一致）
 * - 0 = 正交（语义无关）
 * - -1 = 完全相反方向（语义相反）
 * 公式：cos(θ) = (A · B) / (|A| × |B|)
 */
@Slf4j
@Service
public class DocumentIndexService {

    /**
     * AI 配置属性
     */
    private final AiProperties aiProperties;

    /**
     * 文本分块服务
     */
    private final TextChunkingService textChunkingService;

    /**
     * 文档索引存储（内存版）
     * <p>
     * 【数据结构】
     * Map<Long, DocumentEntry>
     * - Key: 文件 ID（fileId）
     * - Value: 文档条目（包含文件名和分块后的文本列表）
     * <p>
     * 【为什么用 ConcurrentHashMap？】
     * 多线程环境下（多个用户同时索引/检索文档），
     * 普通 HashMap 会出现数据不一致的问题。
     * ConcurrentHashMap 是线程安全的，适合并发访问。
     * <p>
     * 【生产系统用什么？】
     * 生产系统会将文本块存入向量数据库（如 pgvector），
     * 并建立向量索引（IVF / HNSW），支持高效的近似最近邻（ANN）搜索。
     */
    /**
     * 用户维度的索引存储。fileId 仅在单个用户的文件空间内有业务意义，
     * 因此不能单独作为索引键，否则猜到 fileId 的用户可能读取他人文档内容。
     */
    private final ConcurrentHashMap<DocumentKey, DocumentEntry> documentStore = new ConcurrentHashMap<>();

    private record DocumentKey(Long userId, Long fileId) {
    }

    public DocumentIndexService(AiProperties aiProperties, TextChunkingService textChunkingService) {
        this.aiProperties = aiProperties;
        this.textChunkingService = textChunkingService;
    }

    /**
     * 文档条目 — 存储一个文档的元信息和文本块
     * <p>
     * 每个被索引的文档对应一个 DocumentEntry 实例。
     */
    private static class DocumentEntry {
        /** 文档原始文件名 */
        final String filename;
        /** 分块后的文本列表 */
        final List<String> chunks;

        DocumentEntry(String filename, List<String> chunks) {
            this.filename = filename;
            this.chunks = chunks;
        }
    }

    /**
     * 索引文档 — 将文档文本分块并存储到内存索引中
     * <p>
     * 【流程】
     * 1. 接收文档的纯文本内容（由 DocumentParserService 解析得到）
     * 2. 调用 TextChunkingService 将文本分成小块
     * 3. 将分块结果存入 ConcurrentHashMap
     * <p>
     * 后续用户提问时，searchRelevantChunks() 会从这些块中检索相关内容。
     *
     * @param fileId      文件 ID（由 file-service 分配的数据库主键）
     * @param filename    文件原始名称（用于日志和展示）
     * @param textContent 文档的纯文本内容（由 Tika 解析得到）
     */
    public void indexDocument(Long userId, Long fileId, String filename, String textContent) {
        // ========== 第 1 步：获取分块参数 ==========
        int chunkSize = aiProperties.getIndex().getChunkSize();
        int overlap = aiProperties.getIndex().getChunkOverlap();

        // ========== 第 2 步：文本分块 ==========
        // 调用 TextChunkingService 将长文本切成小块
        List<TextChunkingService.TextChunk> textChunks =
                textChunkingService.chunk(textContent, chunkSize, overlap);

        // 只保留文本内容（丢弃序号，序号在排序时已用过）
        List<String> chunkTexts = textChunks.stream()
                .map(TextChunkingService.TextChunk::content)
                .collect(Collectors.toList());

        // ========== 第 3 步：存入索引 ==========
        // put() 会覆盖同一 fileId 的旧数据，支持重复索引（文档更新场景）
        documentStore.put(new DocumentKey(userId, fileId), new DocumentEntry(filename, chunkTexts));

        log.info("文档索引完成: userId={}, fileId={}, filename={}, 分块数={}, 原文长度={}",
                userId, fileId, filename, chunkTexts.size(), textContent.length());
    }

    /**
     * 检索相关文本块 — 基于关键词匹配的简化版检索
     * <p>
     * 【算法说明】
     * 这是一个极其简化的检索算法，用于教学目的：
     * 1. 将用户问题按空格、标点拆分成关键词
     * 2. 统计每个文本块中包含多少个关键词（出现次数）
     * 3. 按关键词命中数从高到低排序
     * 4. 返回前 topK 个最相关的文本块
     * <p>
     * 【示例】
     * 用户问题："合同的违约条款是什么？"
     * 关键词：["合同", "的", "违约", "条款", "是", "什么"]
     * <p>
     * 文本块 A: "本合同约定了双方的权利义务..." → 命中 1 次（"合同"）
     * 文本块 B: "第七章 违约责任：如一方违反合同约定..." → 命中 3 次（"合同", "违约", "违反"）
     * 文本块 C: "附录：相关法规列表..." → 命中 0 次
     * <p>
     * 排序后：B(3) > A(1) > C(0)
     * 如果 topK=2，返回 [B, A]
     * <p>
     * 【这个简化方案的问题】
     * 1. 无法处理同义词："违约"和"违反协议"语义相近但字面不同
     * 2. 无法处理多义词："苹果"可以是水果也可以是公司
     * 3. 对分词质量依赖很高：中文没有空格分隔，分词本身就是一个难题
     * 4. 没有考虑词的权重：常用词（"的"、"是"）会干扰排序
     * <p>
     * 这些问题在生产系统中通过 Embedding 向量 + 语义检索来解决。
     *
     * @param fileId 文件 ID
     * @param query  用户的查询/问题
     * @param topK   返回的最相关块数量
     * @return 最相关的文本块内容列表
     */
    public List<String> searchRelevantChunks(Long userId, Long fileId, String query, int topK) {
        // ========== 第 1 步：查找文档 ==========
        DocumentEntry entry = documentStore.get(new DocumentKey(userId, fileId));
        if (entry == null) {
            return Collections.emptyList();
        }

        // ========== 第 2 步：提取关键词 ==========
        // 简单的中文分词：按常见分隔符拆分
        // 注意：这只是一个教学用的简化分词，生产环境应使用 IK / HanLP / jieba 等分词器
        // 过滤掉太短的词（单字通常没有实际意义）和常见停用词
        Set<String> stopwords = Set.of("的", "了", "是", "在", "和", "与", "或", "及",
                "把", "被", "从", "到", "对", "向", "让", "给",
                "吗", "呢", "吧", "啊", "哦", "嗯",
                "我", "你", "他", "她", "它", "们",
                "这", "那", "有", "不", "也", "都",
                "而", "但", "又", "且", "就", "才",
                "什么", "怎么", "如何", "哪些", "为什么");

        List<String> keywords = Arrays.stream(query.split("[\\s,，。？！、；：（）\\[\\]{}【】]+"))
                .map(String::trim)
                .filter(w -> w.length() >= 2)          // 过滤单字
                .filter(w -> !stopwords.contains(w))    // 过滤停用词
                .distinct()                             // 去重
                .collect(Collectors.toList());

        log.debug("检索关键词: {}", keywords);

        // ========== 第 3 步：计算每个块的关键词得分 ==========
        // 用一个内部记录来关联块内容和得分，方便排序
        record ScoredChunk(String content, int score) {}

        List<ScoredChunk> scoredChunks = new ArrayList<>();

        for (String chunk : entry.chunks) {
            int score = 0;
            // 统计每个关键词在这个块中出现的次数
            for (String keyword : keywords) {
                // 使用 indexOf 循环计数（统计关键词在块中出现了几次）
                int count = countOccurrences(chunk, keyword);
                score += count;
            }
            scoredChunks.add(new ScoredChunk(chunk, score));
        }

        // ========== 第 4 步：按得分排序，取前 topK 个 ==========
        List<String> results = scoredChunks.stream()
                .sorted((a, b) -> Integer.compare(b.score, a.score)) // 降序排列（得分高的在前）
                .filter(sc -> sc.score > 0)                          // 过滤得分为 0 的块（完全不相关）
                .limit(topK)                                         // 只取前 topK 个
                .map(ScoredChunk::content)                           // 只保留文本内容
                .collect(Collectors.toList());

        log.info("检索完成: userId={}, fileId={}, query='{}', 关键词数={}, 返回块数={}",
                userId, fileId, query, keywords.size(), results.size());

        return results;
    }

    /**
     * 统计子串在文本中出现的次数
     * <p>
     * 使用 indexOf 循环查找，每次从上一次匹配位置之后继续查找。
     * 例如：在 "合同...合同...合同" 中查找 "合同"，返回 3。
     *
     * @param text    被搜索的文本
     * @param keyword 要搜索的关键词
     * @return 关键词在文本中出现的次数
     */
    private int countOccurrences(String text, String keyword) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(keyword, index)) != -1) {
            count++;
            index += keyword.length(); // 移动到下一次的起始位置
        }
        return count;
    }
}
