package com.mengde.clouddrive.persistence;

import com.mengde.clouddrive.error.EntryNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 级联删除的子树收集逻辑（V8）。
 *
 * <p>这部分是纯遍历逻辑，用 mock 就能把边界钉死，不需要真实数据库：
 * 多层子树是否都收到、环形数据会不会把程序转死、不存在的条目是否 404。</p>
 */
class DriveEntryRepositoryCascadeTest {

    private final DriveEntryMapper mapper = mock(DriveEntryMapper.class);
    private final DriveEntryRepository repository = new DriveEntryRepository(mapper);

    private static final long OWNER = 7L;

    @Test
    void collectsEveryDescendantBeforeMarkingDeleted() {
        // 目录树：1 ├─ 2 ─ 4
        //           └─ 3
        existingDirectory(1L);
        when(mapper.findActiveChildIds(OWNER, 1L)).thenReturn(List.of(2L, 3L));
        when(mapper.findActiveChildIds(OWNER, 2L)).thenReturn(List.of(4L));
        when(mapper.softDeleteSubtree(anyLong(), anyList(), anyLong())).thenReturn(4);

        int affected = repository.deleteSubtreeByOwnerAndId(OWNER, 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).softDeleteSubtree(eq(OWNER), captor.capture(), eq(1L));

        List<Long> ids = captor.getValue();
        assertAll(
                () -> assertEquals(4, affected),
                () -> assertEquals(4, ids.size()),
                // 根自身必须在内，否则目录自己不会进回收站
                () -> assertTrue(ids.containsAll(List.of(1L, 2L, 3L, 4L))));
    }

    @Test
    void singleFileHasNoChildrenAndStillGetsDeleted() {
        existingDirectory(9L);
        when(mapper.softDeleteSubtree(anyLong(), anyList(), anyLong())).thenReturn(1);

        assertEquals(1, repository.deleteSubtreeByOwnerAndId(OWNER, 9L));
    }

    @Test
    void cyclicDataDoesNotLoopForever() {
        // 异常数据：1 的子是 2，2 的子又是 1。
        // 没有去重保护的话这里会无限循环，测试会超时而不是失败——所以这条一定要有。
        existingDirectory(1L);
        when(mapper.findActiveChildIds(OWNER, 1L)).thenReturn(List.of(2L));
        when(mapper.findActiveChildIds(OWNER, 2L)).thenReturn(List.of(1L));
        when(mapper.softDeleteSubtree(anyLong(), anyList(), anyLong())).thenReturn(2);

        int affected = repository.deleteSubtreeByOwnerAndId(OWNER, 1L);

        assertEquals(2, affected);
    }

    @Test
    void missingEntryIsReportedAsNotFoundAndNothingIsDeleted() {
        when(mapper.findByOwnerAndId(OWNER, 404L)).thenReturn(Optional.empty());

        assertThrows(EntryNotFoundException.class,
                () -> repository.deleteSubtreeByOwnerAndId(OWNER, 404L));
        verify(mapper, never()).softDeleteSubtree(anyLong(), anyList(), anyLong());
    }

    @Test
    void restoreReportsNotFoundWhenNothingMatchesTheBatch() {
        when(mapper.restoreSubtree(OWNER, 55L)).thenReturn(0);

        assertThrows(EntryNotFoundException.class,
                () -> repository.restoreSubtreeByOwnerAndId(OWNER, 55L));
    }

    private void existingDirectory(long id) {
        when(mapper.findByOwnerAndId(OWNER, id)).thenReturn(Optional.of(
                new DriveEntryRow(id, OWNER, null, "dir-" + id, "DIRECTORY", null)));
    }
}
