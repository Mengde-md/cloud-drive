package com.mengde.clouddrive.schedule;

import com.mengde.clouddrive.application.TrashPurgeService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回收站定时清理的行为边界。
 */
class TrashCleanupJobTest {

    private final TrashPurgeService trashPurge = mock(TrashPurgeService.class);

    private TrashCleanupJob job(int retentionDays) {
        TrashProperties properties = new TrashProperties();
        properties.setRetentionDays(retentionDays);
        return new TrashCleanupJob(trashPurge, properties);
    }

    @Test
    void retentionDaysZeroDisablesCleanupEntirely() {
        job(0).cleanupExpiredTrash();

        // 关闭时连查询都不该发出去
        verify(trashPurge, never()).purgeExpired(any());
    }

    @Test
    void explicitPurgeDelegatesToTheSeparateTransactionalService() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        when(trashPurge.purgeExpired(cutoff)).thenReturn(2);

        int purged = job(30).purge(cutoff);

        assertEquals(2, purged);
        verify(trashPurge).purgeExpired(cutoff);
    }

    @Test
    void scheduledEntryCallsTheSeparateTransactionalService() {
        when(trashPurge.purgeExpired(any())).thenReturn(2);
        job(30).cleanupExpiredTrash();
        verify(trashPurge).purgeExpired(any());
    }
}
