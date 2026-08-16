package com.base.ai.service;

import com.base.common.exception.BusinessException;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文档解析服务 — 使用 Apache Tika 提取文档文本内容
 * <p>
 * 【什么是 Apache Tika？】
 * Apache Tika 是一个开源的文档内容检测与提取工具包，它能识别并解析
 * 上千种文件格式（PDF、Word、Excel、PPT、HTML、TXT、Markdown 等），
 * 将文档内容统一提取为纯文本（plain text）。
 * <p>
 * 【Tika 的工作原理】
 * 1. 检测文件 MIME 类型（如 application/pdf、application/vnd.openxmlformats...）
 * 2. 根据 MIME 类型选择合适的 Parser（解析器）
 *    - PDF → PDFParser（底层用 PDFBox）
 *    - DOCX → OOXMLParser（底层用 POI）
 *    - XLSX → OOXMLParser
 *    - TXT → TXTParser
 * 3. Parser 将文档内容提取为纯文本
 * <p>
 * 【为什么选择 Tika 而不是直接用 PDFBox / POI？】
 * - 统一接口：不管什么格式，都是一行 tika.parseToString() 搞定
 * - 自动检测：不需要手动判断文件类型，Tika 会自动识别
 * - 格式丰富：支持上千种格式，不需要为每种格式写单独的解析代码
 * <p>
 * 【在 RAG 中的角色】
 * 文档解析是 RAG 流程的第一步：
 * 用户上传文档 → [Tika 解析] → 纯文本 → [分块] → [索引] → [检索] → [LLM 生成]
 */
@Service
public class DocumentParserService {

    /**
     * Tika 实例：核心解析引擎
     * <p>
     * Tika 是线程安全的，所以可以在单例 Service 中安全地共享一个实例。
     * 它内部会根据文件的 MIME 类型自动选择最合适的解析器。
     */
    private final Tika tika = new Tika();

    /**
     * 解析上传的文档，提取纯文本内容
     * <p>
     * 【方法说明】
     * 接收 Spring 的 MultipartFile（HTTP 文件上传的封装），
     * 通过 Tika 自动检测文件类型并提取文本内容。
     * <p>
     * 【支持的格式】
     * - PDF（.pdf）
     * - Word（.doc, .docx）
     * - Excel（.xls, .xlsx）
     * - PPT（.ppt, .pptx）
     * - 纯文本（.txt）
     * - HTML（.html）
     * - Markdown（.md）
     * - 以及 Tika 支持的其他上千种格式
     * <p>
     * 【注意事项】
     * 1. 图片文件会返回空字符串（Tika 无法从图片中提取文本，除非有 OCR）
     * 2. 加密/密码保护的文档会抛出异常
     * 3. 扫描版 PDF（图片型 PDF）可能提取不到文字，需要 OCR 支持
     *
     * @param file 用户上传的文件（通过 @RequestParam("file") MultipartFile 接收）
     * @return 提取到的纯文本内容
     * @throws BusinessException 如果文件为空或解析失败
     */
    public String parse(MultipartFile file) {
        // ========== 第 1 步：参数校验 ==========
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }

        // 获取原始文件名，用于日志和错误提示
        String filename = file.getOriginalFilename();

        try {
            // ========== 第 2 步：使用 Tika 解析文档 ==========
            // getInputStream() 获取文件的输入流，避免将整个文件加载到内存
            // Tika 会从输入流中：
            //   1. 读取文件头（magic bytes）来检测 MIME 类型
            //   2. 选择合适的 Parser 进行解析
            //   3. 将所有文本内容提取为 String
            try (InputStream inputStream = file.getInputStream()) {
                String text = tika.parseToString(inputStream);

                // ========== 第 3 步：结果校验 ==========
                // 去除首尾空白后检查是否为空
                if (text == null || text.trim().isEmpty()) {
                    throw new BusinessException("文档内容为空，无法解析。"
                            + "可能原因：1) 文件是扫描版图片 2) 文件加密 3) 文件格式不支持");
                }

                // 去除首尾空白，保留内部格式
                return text.trim();
            }

        } catch (IOException e) {
            // IO 异常：通常是文件读取失败（磁盘错误、网络中断等）
            throw new BusinessException("文件读取失败：" + filename + "，原因：" + e.getMessage());
        } catch (TikaException e) {
            // Tika 异常：文件格式无法识别或解析过程中出错
            throw new BusinessException("文档解析失败：" + filename + "，原因：" + e.getMessage());
        }
    }
}
