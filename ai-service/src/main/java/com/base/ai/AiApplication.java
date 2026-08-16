package com.base.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI 文档智能服务 — 启动类
 * <p>
 * 【模块介绍】
 * 本模块是一个简化版的 AI 文档智能服务，用于学习 RAG（检索增强生成）的核心概念。
 * <p>
 * 【什么是 RAG？】
 * RAG = Retrieval Augmented Generation（检索增强生成）
 * 核心思想：先从文档中检索相关内容，再把检索到的内容作为上下文喂给 LLM，
 * 让 LLM 基于这些真实资料来回答问题，而不是"凭空编造"。
 * <p>
 * RAG 的完整流程：
 * 1. 文档解析（Parsing）   — 把 PDF/Word/Excel 等转成纯文本
 * 2. 文本分块（Chunking）  — 把长文本切成小段，方便后续检索
 * 3. 向量化（Embedding）   — 把文本块转成向量（本模块用关键词匹配简化替代）
 * 4. 存储索引（Indexing）  — 把向量存入向量数据库（本模块用内存 Map 简化替代）
 * 5. 检索（Retrieval）     — 用户提问时，找到最相关的文本块
 * 6. 生成（Generation）    — 把检索到的文本块 + 用户问题一起发给 LLM 生成回答
 * <p>
 * 【本模块 vs 生产系统】
 * 生产系统中步骤 3-4 会使用 Embedding 模型 + pgvector/Milvus 等向量数据库，
 * 本模块用简单的关键词计数来模拟检索过程，重点在于理解整体流程。
 */
@SpringBootApplication
public class AiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
