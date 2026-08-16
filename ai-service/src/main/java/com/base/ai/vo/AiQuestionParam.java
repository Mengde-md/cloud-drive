package com.base.ai.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * AI 问答请求参数
 * <p>
 * 用于 POST /api/ai/files/question 接口的请求体。
 * 用户需要指定：
 * 1. fileId — 要提问的文档 ID（文档必须已经被索引）
 * 2. question — 用户的问题（自然语言）
 * <p>
 * 【参数校验注解说明】
 * - @NotNull：字段值不能为 null（适用于对象类型如 Long）
 * - @NotBlank：字段值不能为 null、空字符串或纯空白（适用于 String 类型）
 * <p>
 * 当 Controller 方法参数标注了 @Valid 时，Spring 会自动执行这些校验，
 * 校验失败会触发 MethodArgumentNotValidException，
 * 被 GlobalExceptionHandler 捕获并返回 400 错误。
 */
@Data
public class AiQuestionParam {

    /**
     * 文档 ID
     * <p>
     * 指向已上传并被索引的文档。
     * 系统会根据这个 ID 从内存索引中查找该文档的文本块，
     * 然后从中检索与 question 最相关的内容。
     */
    @NotNull(message = "文档ID不能为空")
    private Long fileId;

    /**
     * 用户的问题
     * <p>
     * 自然语言描述的问题，例如：
     * - "这份合同的违约条款是什么？"
     * - "这份报告的主要结论有哪些？"
     * - "文档中提到的技术方案是什么？"
     * <p>
     * 系统会将这个问题与文档的文本块进行匹配，
     * 找到最相关的块，然后让 LLM 基于这些内容来回答。
     */
    @NotBlank(message = "问题不能为空")
    private String question;
}
