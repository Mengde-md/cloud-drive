package com.mengde.clouddrive.analysis;

record AnalysisResult(FileCategory category, String source, String status, String summary,
                      String reviewStatus, String reviewSummary) {
    static AnalysisResult fallback(AnalysisJob job, String reason) {
        return new AnalysisResult(FileCategory.fromName(job.fileName()), "EXTENSION", "FALLBACK", reason,
                job.reviewEnabled() ? "UNREVIEWED" : "DISABLED",
                job.reviewEnabled() ? "未执行内容辅助审核；需要人工确认。" : "辅助审核已关闭。");
    }
}
