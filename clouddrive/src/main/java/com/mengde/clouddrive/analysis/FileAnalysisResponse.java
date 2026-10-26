package com.mengde.clouddrive.analysis;

import java.time.LocalDateTime;

public record FileAnalysisResponse(long entryId, String fileName, String category, String source,
                                   String status, String summary, String reviewStatus, String reviewSummary,
                                   String reviewReason, Long reviewedBy, LocalDateTime reviewedAt,
                                   LocalDateTime analyzedAt) {
}
