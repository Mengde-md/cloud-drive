package com.mengde.clouddrive.analysis;

import com.mengde.clouddrive.error.EntryNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class FileAnalysisRepository {
    private static final Set<String> REVIEW_STATUSES = Set.of("DISABLED", "UNREVIEWED", "PENDING_REVIEW", "APPROVED", "REJECTED");
    private static final String SELECT = """
            SELECT e.id AS entry_id, e.display_name AS file_name,
                a.category, a.source, a.status, a.summary, a.review_status, a.review_summary,
                a.review_reason, a.reviewed_by, a.reviewed_at, a.analyzed_at
            FROM drive_entries e LEFT JOIN file_analysis a ON a.entry_id = e.id AND a.owner_id = e.owner_id
            """;
    private final JdbcTemplate jdbc;

    public FileAnalysisRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<FileAnalysisResponse> list(long ownerId, List<Long> ids, boolean reviewEnabled) {
        List<Object> args = new ArrayList<>();
        args.add(ownerId);
        String filter = "";
        if (ids != null && !ids.isEmpty()) {
            filter = " AND e.id IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")";
            args.addAll(ids);
        }
        return jdbc.query(SELECT + " WHERE e.owner_id = ? AND e.deleted_at IS NULL AND e.kind = 'DOCUMENT'"
                + filter + " ORDER BY e.id DESC LIMIT 100", responseMapper(reviewEnabled), args.toArray());
    }

    public FileAnalysisResponse find(long ownerId, long entryId, boolean reviewEnabled) {
        return jdbc.query(SELECT + " WHERE e.owner_id = ? AND e.id = ? AND e.deleted_at IS NULL AND e.kind = 'DOCUMENT'",
                        responseMapper(reviewEnabled), ownerId, entryId).stream().findFirst()
                .orElseThrow(() -> new EntryNotFoundException(entryId));
    }

    /** Called in a short transaction. Locking the entry serializes initial INSERT and reanalysis. */
    public AnalysisJob start(long ownerId, long entryId, boolean reviewEnabled, boolean force) {
        AnalysisJob job = jdbc.query("""
                SELECT id, owner_id, display_name, storage_key, byte_size FROM drive_entries
                WHERE owner_id = ? AND id = ? AND deleted_at IS NULL AND kind = 'DOCUMENT' FOR UPDATE
                """, (rs, row) -> new AnalysisJob(rs.getLong("id"), rs.getLong("owner_id"),
                rs.getString("display_name"), rs.getString("storage_key"), rs.getLong("byte_size"),
                UUID.randomUUID().toString(), reviewEnabled), ownerId, entryId).stream().findFirst()
                .orElseThrow(() -> new EntryNotFoundException(entryId));
        if (job.storageKey() == null || job.storageKey().isBlank()) {
            throw new IllegalArgumentException("该条目没有已上传的文件内容，无法分析。");
        }
        // Repeated clicks reuse in-flight work. A stale request can be retried after a restart.
        boolean pending = !jdbc.query("""
                SELECT entry_id FROM file_analysis WHERE entry_id = ? AND status = 'PENDING' AND requested_at > ?
                """, (rs, row) -> rs.getLong(1), entryId, Timestamp.valueOf(LocalDateTime.now().minusMinutes(3))).isEmpty();
        if (pending && !force) { return null; }

        jdbc.update("""
                INSERT INTO file_analysis (entry_id, owner_id, generation, category, source, status, summary,
                    review_status, review_summary, requested_at)
                VALUES (?, ?, ?, ?, 'EXTENSION', 'PENDING', '正在分析文件。', ?, ?, CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE generation = VALUES(generation), category = VALUES(category),
                    source = 'EXTENSION', status = 'PENDING', summary = '正在分析文件。',
                    review_status = CASE WHEN review_status IN ('APPROVED', 'REJECTED') THEN review_status ELSE VALUES(review_status) END,
                    review_summary = CASE WHEN review_status IN ('APPROVED', 'REJECTED') THEN review_summary ELSE VALUES(review_summary) END,
                    analyzed_at = NULL, requested_at = CURRENT_TIMESTAMP(6)
                """, entryId, ownerId, job.generation(), FileCategory.fromName(job.fileName()).name(),
                reviewEnabled ? "UNREVIEWED" : "DISABLED", reviewEnabled ? "等待辅助分析；尚未人工审核。" : "辅助审核已关闭。");
        return job;
    }

    /** A late result cannot replace a newer generation or overwrite a human decision. */
    public int complete(AnalysisJob job, AnalysisResult result) {
        return jdbc.update("""
                UPDATE file_analysis SET category = ?, source = ?, status = ?, summary = ?,
                    review_status = CASE WHEN review_status IN ('APPROVED', 'REJECTED') THEN review_status ELSE ? END,
                    review_summary = CASE WHEN review_status IN ('APPROVED', 'REJECTED') THEN review_summary ELSE ? END,
                    analyzed_at = CURRENT_TIMESTAMP(6)
                WHERE entry_id = ? AND owner_id = ? AND generation = ?
                    AND EXISTS (SELECT 1 FROM drive_entries e WHERE e.id = file_analysis.entry_id
                        AND e.owner_id = file_analysis.owner_id AND e.deleted_at IS NULL AND e.display_name = ?)
                """, result.category().name(), result.source(), result.status(), result.summary(),
                result.reviewStatus(), result.reviewSummary(), job.entryId(), job.ownerId(), job.generation(), job.fileName());
    }

    public boolean isCurrentAndActive(AnalysisJob job) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM file_analysis a JOIN drive_entries e ON e.id = a.entry_id AND e.owner_id = a.owner_id
                WHERE a.entry_id = ? AND a.owner_id = ? AND a.generation = ? AND e.deleted_at IS NULL AND e.display_name = ?
                """, Long.class, job.entryId(), job.ownerId(), job.generation(), job.fileName());
        return count != null && count > 0;
    }

    public ReviewPage reviews(String status, int page, int size) {
        if (status != null && !status.isBlank() && !REVIEW_STATUSES.contains(status)) {
            throw new IllegalArgumentException("未知审核状态。");
        }
        String filter = status == null || status.isBlank() ? "" : " AND a.review_status = ?";
        String where = " WHERE e.deleted_at IS NULL AND a.entry_id IS NOT NULL" + filter;
        List<Object> args = new ArrayList<>();
        if (!filter.isEmpty()) { args.add(status); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM file_analysis a JOIN drive_entries e ON e.id = a.entry_id AND e.owner_id = a.owner_id"
                + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) page * size);
        List<FileAnalysisResponse> items = jdbc.query(SELECT + where + " ORDER BY a.requested_at DESC, e.id DESC LIMIT ? OFFSET ?",
                responseMapper(true), args.toArray());
        return new ReviewPage(items, total == null ? 0 : total, page, size);
    }

    public FileAnalysisResponse review(long entryId, long adminId, String decision, String reason) {
        int updated = jdbc.update("""
                UPDATE file_analysis SET review_status = ?, review_summary = ?, review_reason = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP(6)
                WHERE entry_id = ? AND status <> 'PENDING'
                    AND EXISTS (SELECT 1 FROM drive_entries e WHERE e.id = file_analysis.entry_id
                        AND e.owner_id = file_analysis.owner_id AND e.deleted_at IS NULL)
                """, decision, "APPROVED".equals(decision) ? "管理员已人工确认通过。" : "管理员已人工确认不通过。", reason, adminId, entryId);
        if (updated == 0) { throw new IllegalArgumentException("文件不存在、已进入回收站或正在分析，请刷新后重试。"); }
        return jdbc.query(SELECT + " WHERE e.id = ? AND e.deleted_at IS NULL", responseMapper(true), entryId)
                .stream().findFirst().orElseThrow(() -> new EntryNotFoundException(entryId));
    }

    private static RowMapper<FileAnalysisResponse> responseMapper(boolean reviewEnabled) {
        return (rs, row) -> {
            String fileName = rs.getString("file_name");
            String category = rs.getString("category");
            boolean missing = category == null;
            Number reviewedBy = (Number) rs.getObject("reviewed_by");
            return new FileAnalysisResponse(rs.getLong("entry_id"), fileName,
                    missing ? FileCategory.fromName(fileName).name() : category,
                    missing ? "EXTENSION" : rs.getString("source"),
                    missing ? "NOT_ANALYZED" : rs.getString("status"),
                    missing ? "尚未分析，当前类别根据后缀识别。" : rs.getString("summary"),
                    missing ? (reviewEnabled ? "UNREVIEWED" : "DISABLED") : rs.getString("review_status"),
                    missing ? (reviewEnabled ? "尚未进行内容辅助审核。" : "辅助审核已关闭。") : rs.getString("review_summary"),
                    rs.getString("review_reason"), reviewedBy == null ? null : reviewedBy.longValue(),
                    timestamp(rs, "reviewed_at"), timestamp(rs, "analyzed_at"));
        };
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    public record ReviewPage(List<FileAnalysisResponse> items, long total, int page, int size) { }
}
