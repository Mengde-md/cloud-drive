package com.base.ai.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块服务 — 将长文本切分成适合 LLM 处理的小块
 * <p>
 * 【为什么需要文本分块（Chunking）？】
 * <p>
 * 1. LLM 上下文窗口限制：
 *    - GPT-3.5: 4096 tokens（约 3000 汉字）
 *    - GPT-4: 8192 / 32768 / 128000 tokens
 *    - 通义千问: 8192 tokens
 *    一篇 50 页的 PDF 可能有 5 万字，远超窗口大小，必须分块。
 * <p>
 * 2. 检索精度：
 *    RAG 的核心是"找到最相关的内容"。如果把整篇文档作为一个块，
 *    检索系统只能判断"这篇文档是否相关"，而无法定位到具体段落。
 *    分块后，可以精确定位到"第 3 章第 2 节"这样细粒度的内容。
 * <p>
 * 3. Token 成本：
 *    LLM API 按 Token 计费。把 5 万字全部塞给 LLM 不仅可能超出限制，
 *    还会产生高昂费用。分块后只发送最相关的几千字，大幅降低成本。
 * <p>
 * 【分块策略简介】
 * 常见的分块策略有：
 * - 固定大小分块（本模块使用）：按字符数切分，简单高效
 * - 按段落分块：以 \n\n 为分隔符，保持段落完整性
 * - 按语义分块：使用 NLP 模型识别语义边界（如话题转换点）
 * - 递归分块：先按大标题分，再按小标题分，层层细化
 * <p>
 * 本模块使用最简单的"固定大小 + 重叠"策略，便于理解核心概念。
 */
@Service
public class TextChunkingService {

    /**
     * 文本块记录（Record）
     * <p>
     * Java 14+ 引入的 Record 类型，相当于一个不可变的数据载体类。
     * 自动生成 constructor、getter、equals、hashCode、toString。
     * <p>
     * 字段说明：
     * - index：块在原文中的顺序（从 0 开始），用于追踪和排序
     * - content：块的文本内容
     *
     * @param index   块的序号（0, 1, 2, ...）
     * @param content 块的文本内容
     */
    public record TextChunk(int index, String content) {
    }

    /**
     * 将长文本切分成固定大小的重叠块
     * <p>
     * 【算法说明】
     * 采用"滑动窗口"方式切分文本：
     * <pre>
     * 原文：|---------- 文本内容 ----------|
     *       |← chunkSize →|
     *                  |← overlap →|
     *                  |← chunkSize →|
     *                             |← overlap →|
     *                             |← chunkSize →|
     * </pre>
     * <p>
     * 例如 chunkSize=1000, overlap=200：
     * - 块 0: 字符 [0, 999]
     * - 块 1: 字符 [800, 1799]
     * - 块 2: 字符 [1600, 2599]
     * - ...以此类推
     * <p>
     * 步长（step）= chunkSize - overlap = 1000 - 200 = 800
     * 即每次窗口向前滑动 800 个字符。
     *
     * @param text      待分块的原始文本（由 DocumentParserService 解析得到）
     * @param chunkSize 每个块的大小（字符数），如 1000
     * @param overlap   相邻块之间的重叠字符数，如 200
     * @return 文本块列表，按顺序排列
     */
    public List<TextChunk> chunk(String text, int chunkSize, int overlap) {
        // ========== 边界情况处理 ==========
        // 空文本直接返回空列表
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        // 如果文本长度小于等于块大小，不需要分块，整段作为一个块返回
        if (text.length() <= chunkSize) {
            return List.of(new TextChunk(0, text));
        }

        // 参数校验：重叠不能大于等于块大小（否则窗口不会前进，死循环）
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("重叠大小（" + overlap
                    + "）必须小于分块大小（" + chunkSize + "）");
        }

        // ========== 核心分块逻辑 ==========
        List<TextChunk> chunks = new ArrayList<>();

        // 计算步长：每次窗口前进的距离
        // 步长 = 块大小 - 重叠大小
        // 例如：chunkSize=1000, overlap=200 → step=800
        int step = chunkSize - overlap;

        // 块的序号计数器
        int index = 0;

        // 用滑动窗口遍历整个文本
        // start 是当前块的起始字符位置
        for (int start = 0; start < text.length(); start += step) {
            // 计算当前块的结束位置（不超过文本总长度）
            int end = Math.min(start + chunkSize, text.length());

            // 截取子串作为当前块的内容
            String chunkContent = text.substring(start, end);

            // 创建 TextChunk 记录并添加到列表
            chunks.add(new TextChunk(index, chunkContent));

            // 递增块序号
            index++;

            // 如果已经到达文本末尾，退出循环
            // （防止最后一块不足 chunkSize 时继续循环）
            if (end >= text.length()) {
                break;
            }
        }

        return chunks;
    }
}
