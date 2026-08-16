package com.base.ai.service;

import com.base.ai.config.AiProperties;
import com.base.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * AI 提供商服务 — 调用 OpenAI 兼容 API 进行对话生成
 * <p>
 * 【什么是 OpenAI 兼容 API？】
 * OpenAI 定义了一套 RESTful 接口标准（/chat/completions），
 * 许多国内外 AI 服务商都实现了这套标准，包括：
 * - 阿里通义千问（DashScope）
 * - 智谱 AI（GLM）
 * - 百度文心一言
 * - 月之暗面（Kimi）
 * - 零一万物（Yi）
 * - ...等等
 * <p>
 * 这意味着我们只需要写一套调用代码，通过切换 baseUrl 和 apiKey
 * 就可以对接不同的 AI 服务商，这就是"兼容"的价值。
 * <p>
 * 【API 请求格式】
 * POST {baseUrl}/chat/completions
 * <pre>
 * {
 *   "model": "qwen-plus",
 *   "messages": [
 *     {"role": "system", "content": "你是一个文档助手..."},
 *     {"role": "user",   "content": "请总结这份文档..."}
 *   ],
 *   "temperature": 0.3,
 *   "max_tokens": 2048
 * }
 * </pre>
 * <p>
 * 【API 响应格式】
 * <pre>
 * {
 *   "id": "chatcmpl-xxx",
 *   "choices": [
 *     {
 *       "index": 0,
 *       "message": {
 *         "role": "assistant",
 *         "content": "这份文档主要讲述了..."
 *       },
 *       "finish_reason": "stop"
 *     }
 *   ],
 *   "usage": {
 *     "prompt_tokens": 150,
 *     "completion_tokens": 80,
 *     "total_tokens": 230
 *   }
 * }
 * </pre>
 * <p>
 * 【messages 中的角色说明】
 * - system：系统提示词，定义 AI 的角色和行为准则（用户不可见）
 * - user：用户的输入消息
 * - assistant：AI 之前的回复（用于多轮对话上下文）
 */
@Slf4j
@Service
public class AiProviderService {

    /**
     * AI 配置属性（从 application.yml 自动注入）
     */
    private final AiProperties aiProperties;

    /**
     * RestTemplate：Spring 提供的 HTTP 客户端
     * <p>
     * 用于向 AI API 发送 POST 请求。
     * 在生产系统中，建议使用 WebClient（响应式）或者
     * 配置连接池的 HttpClient 来提升性能。
     * 这里为了学习目的使用 RestTemplate，更简单直观。
     */
    private final RestTemplate restTemplate;

    /**
     * 构造器注入
     * <p>
     * 【为什么手动创建 RestTemplate 而不是 @Bean？】
     * 因为 AI 请求可能需要特殊的超时配置（LLM 响应较慢），
     * 与项目中其他 HTTP 调用的超时策略不同。
     * 这里简单创建一个新的实例，实际项目中应通过 @Bean 配置。
     */
    public AiProviderService(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
        this.restTemplate = new RestTemplate();
    }

    /**
     * 基础对话方法 — 发送 system + user 消息给 LLM，获取回复
     * <p>
     * 【参数说明】
     * - systemPrompt：系统提示词，告诉 AI "你是谁，你应该怎么做"
     *   例如："你是一个专业的文档摘要助手，请用简洁的语言总结文档内容。"
     * - userMessage：用户消息，即具体的任务或问题
     *   例如："请总结以下内容：{文档文本}"
     *
     * @param systemPrompt 系统提示词（定义 AI 角色）
     * @param userMessage  用户消息（具体任务）
     * @return AI 生成的回复文本
     * @throws BusinessException 如果 API 调用失败
     */
    public String chat(String systemPrompt, String userMessage) {
        // ========== 第 1 步：构建请求 URL ==========
        // OpenAI 兼容 API 的聊天接口地址
        // 例如：https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
        String url = aiProperties.getProvider().getBaseUrl() + "/chat/completions";

        // ========== 第 2 步：构建请求头 ==========
        HttpHeaders headers = new HttpHeaders();
        // 请求体为 JSON 格式
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Bearer Token 认证：OpenAI 兼容 API 的标准认证方式
        // "Bearer" + 空格 + API Key
        headers.setBearerAuth(aiProperties.getProvider().getApiKey());

        // ========== 第 3 步：构建请求体 ==========
        // 按照 OpenAI 的标准格式组装请求 JSON
        Map<String, Object> requestBody = new LinkedHashMap<>();
        // 模型名称
        requestBody.put("model", aiProperties.getProvider().getChatModel());

        // messages 数组：包含系统提示词和用户消息
        // 使用 List<Map> 来表示 JSON 数组中的对象数组
        List<Map<String, String>> messages = new ArrayList<>();

        // 系统消息（system message）
        // 定义 AI 的角色和行为准则，AI 会始终遵循这些指令
        Map<String, String> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        // 用户消息（user message）
        // 这是 AI 需要回应或处理的具体内容
        Map<String, String> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        requestBody.put("messages", messages);
        // 温度参数：控制输出的随机性
        requestBody.put("temperature", aiProperties.getProvider().getTemperature());
        // 最大生成 Token 数
        requestBody.put("max_tokens", aiProperties.getProvider().getMaxTokens());

        // ========== 第 4 步：发送 HTTP POST 请求 ==========
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            log.info("正在调用 AI API: model={}, 消息数={}",
                    aiProperties.getProvider().getChatModel(), messages.size());

            // restTemplate.exchange() 发送 POST 请求并接收响应
            // 响应体自动反序列化为 Map
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            // ========== 第 5 步：解析响应 ==========
            return extractContent(response);

        } catch (Exception e) {
            log.error("AI API 调用失败: {}", e.getMessage(), e);
            throw new BusinessException("AI 服务调用失败，请检查 API 配置。错误：" + e.getMessage());
        }
    }

    /**
     * RAG 问答方法 — 将检索到的上下文和用户问题一起发给 LLM
     * <p>
     * 【RAG 问答 vs 普通对话的区别】
     * 普通对话：用户问什么，AI 根据自己的"记忆"（训练数据）来回答
     * RAG 问答：先从文档中检索相关内容，把内容塞进提示词，
     *          让 AI 基于这些真实资料来回答，大幅减少"幻觉"。
     * <p>
     * 【什么是 LLM 幻觉（Hallucination）？】
     * LLM 有时候会"一本正经地胡说八道"，编造不存在的事实。
     * RAG 通过提供真实上下文来约束 LLM，让它只能基于给定资料回答，
     * 如果资料中没有答案，AI 会说"根据提供的文档，无法找到答案"。
     *
     * @param context  从文档索引中检索到的相关文本（可能包含多个文本块拼接）
     * @param question 用户的问题
     * @return AI 基于上下文生成的回答
     */
    public String chatWithContext(String context, String question) {
        // ========== 构建 RAG 专用的系统提示词 ==========
        // 这个提示词非常关键，它告诉 AI：
        // 1. 你的角色是"文档问答助手"
        // 2. 你只能基于提供的参考资料回答
        // 3. 如果资料中没有答案，要诚实地说"不知道"
        // 4. 不要编造信息
        String systemPrompt = """
                你是一个专业的文档问答助手。你的任务是根据提供的参考资料来回答用户的问题。
                
                【重要规则】
                1. 只能基于参考资料中的内容来回答问题
                2. 如果参考资料中没有相关信息，请明确告知用户"根据提供的文档内容，未能找到相关答案"
                3. 不要编造或推测参考资料中没有的内容
                4. 回答要准确、简洁、有条理
                5. 如果答案涉及多个方面，请使用序号列表整理
                """;

        // ========== 构建包含上下文的用户消息 ==========
        // 将检索到的文档片段和用户问题拼接在一起
        // 使用清晰的分隔标记，让 AI 能区分"参考资料"和"用户问题"
        String userMessage = """
                【参考资料】
                %s
                
                【用户问题】
                %s
                """.formatted(context, question);

        // 调用基础 chat 方法
        return chat(systemPrompt, userMessage);
    }

    /**
     * 从 AI API 响应中提取回复内容
     * <p>
     * 【响应结构解析】
     * OpenAI 兼容 API 的响应是一个嵌套的 JSON 结构：
     * <pre>
     * response
     *   └── choices (数组)
     *        └── [0] (第一个候选回复，通常只有一个)
     *             └── message (对象)
     *                  └── content (字符串) ← 这就是我们要的 AI 回复文本
     * </pre>
     * <p>
     * 为什么 choices 是数组？
     * 因为可以设置 n 参数让 AI 一次生成多个候选回复（n>1），
     * 但通常我们只用 n=1（默认值），所以取 choices[0] 即可。
     *
     * @param response HTTP 响应（已反序列化为 Map）
     * @return AI 回复的文本内容
     */
    @SuppressWarnings("unchecked")
    private String extractContent(ResponseEntity<Map> response) {
        // 获取响应体
        Map<String, Object> body = response.getBody();
        if (body == null) {
            throw new BusinessException("AI API 返回空响应");
        }

        // 检查是否有错误信息
        if (body.containsKey("error")) {
            Map<String, Object> error = (Map<String, Object>) body.get("error");
            String errorMsg = error != null ? String.valueOf(error.get("message")) : "未知错误";
            throw new BusinessException("AI API 返回错误：" + errorMsg);
        }

        // 提取 choices 数组
        List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new BusinessException("AI API 未返回任何候选回复");
        }

        // 取第一个候选回复
        Map<String, Object> firstChoice = choices.get(0);

        // 提取 message 对象
        Map<String, String> message = (Map<String, String>) firstChoice.get("message");
        if (message == null) {
            throw new BusinessException("AI API 响应格式异常：缺少 message 字段");
        }

        // 提取 content 字段（AI 的实际回复文本）
        String content = message.get("content");
        if (content == null || content.isBlank()) {
            throw new BusinessException("AI API 返回的回复内容为空");
        }

        log.info("AI API 调用成功，回复长度: {} 字符", content.length());

        return content.trim();
    }
}
