package com.base.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 服务配置属性类
 * <p>
 * 【@ConfigurationProperties 的作用】
 * 将 application.yml 中以 "com.base.ai" 开头的配置项，自动映射到这个 Java 类的字段上。
 * 这样在代码中就可以通过 aiProperties.getProvider().getApiKey() 来读取配置，
 * 而不需要硬编码或者手动解析 YAML。
 * <p>
 * 【嵌套类的设计】
 * 配置项有层级关系，所以用嵌套类 Provider 和 Index 来对应：
 * - Provider：AI 模型提供商的连接参数（API 地址、密钥、模型名等）
 * - Index：文档索引的参数（分块大小、重叠度、检索数量等）
 * <p>
 * 对应的 YAML 配置结构：
 * <pre>
 * com:
 *   base:
 *     ai:
 *       provider:
 *         base-url: https://...
 *         api-key: sk-xxx
 *         ...
 *       index:
 *         chunk-size: 1000
 *         ...
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "com.base.ai")
public class AiProperties {

    /**
     * AI 模型提供商配置
     * <p>
     * 这里的"提供商"指的是提供 OpenAI 兼容 API 的任何服务商：
     * - 阿里通义千问（DashScope）
     * - OpenAI（ChatGPT）
     * - 智谱 AI（GLM）
     * - 百度文心一言
     * - ...等等
     * 它们都实现了 /chat/completions 这个标准接口，所以可以用同一套代码对接。
     */
    private Provider provider = new Provider();

    /**
     * 文档索引配置
     * <p>
     * 控制文档如何被切分成小块，以及检索时返回多少个最相关的块。
     */
    private Index index = new Index();

    /**
     * AI 模型提供商的嵌套配置类
     */
    @Data
    public static class Provider {

        /**
         * API 基础地址
         * <p>
         * OpenAI 兼容 API 的根 URL，例如：
         * - 阿里通义：https://dashscope.aliyuncs.com/compatible-mode/v1
         * - OpenAI：https://api.openai.com/v1
         * <p>
         * 实际的聊天接口会拼接 /chat/completions
         */
        private String baseUrl;

        /**
         * API 密钥
         * <p>
         * 每个 AI 服务商都会分配一个 API Key，用于身份认证和计费。
         * 通过环境变量 AI_API_KEY 注入，避免泄露到代码仓库中。
         */
        private String apiKey;

        /**
         * 聊天模型名称
         * <p>
         * 不同服务商有不同的模型名称：
         * - 通义千问：qwen-plus、qwen-max、qwen-turbo
         * - OpenAI：gpt-4、gpt-3.5-turbo
         * - 智谱：glm-4、glm-3-turbo
         */
        private String chatModel;

        /**
         * 温度参数（0.0 ~ 2.0）
         * <p>
         * 控制 AI 输出的"创造性"：
         * - 低温度（0.1~0.3）：输出确定性强，适合文档问答、代码生成
         * - 中温度（0.5~0.7）：平衡准确性和创造性，适合一般对话
         * - 高温度（0.8~1.5）：输出多样且随机，适合创意写作、头脑风暴
         */
        private double temperature = 0.3;

        /**
         * 最大生成 Token 数
         * <p>
         * Token 是 LLM 处理文本的最小单位，大约 1 个汉字 ≈ 1~2 个 Token。
         * 限制 max_tokens 可以：
         * 1. 防止 AI 生成过长内容浪费 Token 配额
         * 2. 控制响应时间（Token 越多，生成越慢）
         */
        private int maxTokens = 2048;
    }

    /**
     * 文档索引的嵌套配置类
     */
    @Data
    public static class Index {

        /**
         * 文本分块大小（字符数）
         * <p>
         * 每个文本块包含的字符数量。1000 字符大约等于 500~700 个汉字。
         * <p>
         * 【选择分块大小的考量】
         * - 太小（如 200 字符）：语义不完整，检索到的片段可能缺少上下文
         * - 太大（如 5000 字符）：占用过多 Token，检索精度下降
         * - 适中（800~1500 字符）：既能保持完整语义，又不浪费 Token
         */
        private int chunkSize = 1000;

        /**
         * 分块重叠大小（字符数）
         * <p>
         * 相邻两个块之间重叠的字符数。
         * 假设 chunkSize=1000, overlap=200：
         * - 第 1 块：字符 0~999
         * - 第 2 块：字符 800~1799（与第 1 块重叠 200 字符）
         * - 第 3 块：字符 1600~2599（与第 2 块重叠 200 字符）
         * <p>
         * 重叠的目的是防止关键信息恰好被切断在两个块的边界上。
         */
        private int chunkOverlap = 200;

        /**
         * RAG 检索时返回的最相似块数量
         * <p>
         * 当用户提问时，系统会从索引中找出与问题最相关的 topK 个文本块，
         * 把它们拼接成上下文，一起发给 LLM。
         * <p>
         * topK 的选择：
         * - 太小（如 1）：可能遗漏重要信息
         * - 太大（如 10）：上下文太长，消耗大量 Token，且可能引入无关内容
         * - 适中（3~5）：通常足够覆盖一个问题的答案
         */
        private int retrievalTopK = 3;
    }
}
