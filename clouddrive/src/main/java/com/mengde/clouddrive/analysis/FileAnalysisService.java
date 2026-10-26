package com.mengde.clouddrive.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

@Service
public class FileAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(FileAnalysisService.class);
    private final FileAnalysisRepository repository;
    private final AnalysisSettingsService settings;
    private final DeepSeekAnalysisClient deepseek;
    private final SafeTextExtractor extractor;
    private final Executor executor;
    private final TransactionTemplate transactions;

    public FileAnalysisService(FileAnalysisRepository repository, AnalysisSettingsService settings,
                               DeepSeekAnalysisClient deepseek, SafeTextExtractor extractor,
                               @Qualifier("fileAnalysisExecutor") Executor executor,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.settings = settings;
        this.deepseek = deepseek;
        this.extractor = extractor;
        this.executor = executor;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<FileAnalysisResponse> list(long ownerId, List<Long> ids) {
        if (ids != null && (ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id <= 0))) {
            throw new IllegalArgumentException("每次最多查询100个有效文件编号。");
        }
        return repository.list(ownerId, ids, settings.reviewEnabled());
    }

    public FileAnalysisResponse find(long ownerId, long entryId) {
        return repository.find(ownerId, entryId, settings.reviewEnabled());
    }

    public FileAnalysisResponse schedule(long ownerId, long entryId) {
        return schedule(ownerId, entryId, false);
    }

    FileAnalysisResponse schedule(long ownerId, long entryId, boolean force) {
        boolean enabled = settings.reviewEnabled();
        AnalysisJob job = transactions.execute(tx -> repository.start(ownerId, entryId, enabled, force));
        FileAnalysisResponse pending = repository.find(ownerId, entryId, enabled);
        if (job != null) {
            try { executor.execute(() -> analyze(job)); }
            catch (RuntimeException rejected) {
                save(job, AnalysisResult.fallback(job, "分析队列已满，已根据后缀分类；可稍后重新分析。"));
                return repository.find(ownerId, entryId, enabled);
            }
        }
        return pending;
    }

    private void analyze(AnalysisJob job) {
        try {
            if (!repository.isCurrentAndActive(job)) { return; }
            if (!deepseek.configured()) {
                save(job, AnalysisResult.fallback(job, "未配置 DeepSeek API Key，已根据后缀分类。"));
                return;
            }
            var text = extractor.extract(job);
            if (text.isEmpty()) {
                save(job, AnalysisResult.fallback(job, "此格式或编码暂不支持内容分析，已根据后缀分类（仅支持 UTF-8 文本和代码）。"));
                return;
            }
            save(job, deepseek.analyze(job, text.get()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            saveSafely(job, AnalysisResult.fallback(job, "内容分析已中断，已根据后缀分类；尚未完成辅助审核。"));
        } catch (IOException | RuntimeException failure) {
            // Do not log file content, the provider body, configured key or exception messages.
            int providerStatus = failure instanceof DeepSeekAnalysisClient.ApiException api ? api.status() : 0;
            log.warn("file analysis fell back entryId={} failureType={} providerStatus={}", job.entryId(), failure.getClass().getSimpleName(), providerStatus);
            saveSafely(job, AnalysisResult.fallback(job, failureSummary(providerStatus)));
        }
    }

    private void save(AnalysisJob job, AnalysisResult result) {
        transactions.executeWithoutResult(tx -> repository.complete(job, result));
    }

    private static String failureSummary(int status) {
        return switch (status) {
            case 401 -> "DeepSeek 密钥认证失败，请联系管理员检查配置；当前按后缀分类。";
            case 402 -> "DeepSeek 可用额度不足，请联系管理员检查账户；当前按后缀分类。";
            case 400, 422 -> "DeepSeek 请求参数未被接受，请联系管理员检查服务配置；当前按后缀分类。";
            case 403 -> "DeepSeek 拒绝了当前请求，请联系管理员检查服务权限；当前按后缀分类。";
            case 429 -> "DeepSeek 请求频率受限，请稍后重新分析；当前按后缀分类。";
            case 500, 503 -> "DeepSeek 服务暂时繁忙，请稍后重新分析；当前按后缀分类。";
            default -> "内容分析暂不可用，已根据后缀分类；尚未完成辅助审核。";
        };
    }

    private void saveSafely(AnalysisJob job, AnalysisResult result) {
        try { save(job, result); }
        catch (RuntimeException failure) { log.warn("unable to save analysis fallback entryId={}", job.entryId()); }
    }
}
