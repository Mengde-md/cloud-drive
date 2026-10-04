package com.mengde.clouddrive.search;

import com.mengde.clouddrive.domain.EntryKind;
import org.junit.jupiter.api.*;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FileSearchServiceTest {
    SearchDatabaseFixture db;
    FileSearchIndex index;
    FileSearchService service;
    @BeforeEach void setup() throws Exception {
        db = new SearchDatabaseFixture();
        index = mock(FileSearchIndex.class);
        service = new FileSearchService(db.mapper, index, db.manager);
        when(index.search(anyLong(), anyString(), any())).thenReturn(new FileSearchIndex.IndexPage(List.of(), 0));
    }
    @AfterEach void close() { if (db != null) db.close(); }

    @Test void allOwnedRepositoryMutationsBumpRevisionAndRollbackTogether() {
        long id = db.add("report");
        assertThat(db.state().revision()).isEqualTo(1);
        db.tx.executeWithoutResult(s -> db.entries.renameByOwner(db.owner, id, "renamed"));
        db.tx.executeWithoutResult(s -> db.entries.deleteByOwnerAndId(db.owner, id));
        db.tx.executeWithoutResult(s -> db.entries.restoreByOwnerAndId(db.owner, id));
        assertThat(db.state().revision()).isEqualTo(4);
        assertThatThrownBy(() -> db.tx.executeWithoutResult(s -> {
            db.entries.renameByOwner(db.owner, id, "rollback");
            throw new IllegalStateException("abort");
        })).hasMessage("abort");
        assertThat(db.entries.findByOwnerAndId(db.owner, id).displayName()).isEqualTo("renamed");
        assertThat(db.state().revision()).isEqualTo(4);
        db.tx.executeWithoutResult(s -> db.entries.saveStoredDocument(db.owner, null, "stored", 7, "test-key", "a".repeat(32)));
        assertThat(db.state().revision()).isEqualTo(5);
        db.tx.executeWithoutResult(s -> db.entries.deleteByOwnerAndId(db.owner, id));
        db.tx.executeWithoutResult(s -> db.entries.purgeTrashByOwner(db.owner));
        assertThat(db.state().revision()).isEqualTo(7);
        db.tx.executeWithoutResult(s -> db.entries.purgeTrashByOwner(db.owner));
        assertThat(db.state().revision()).isEqualTo(7);
    }

    @Test void unavailableIndexFallsBackWithLiteralCaseSensitiveOwnerScopedPagination() {
        db.add("report %_*");
        long newest = db.add("report %_* two");
        db.add("REPORT %_* upper");
        db.add(db.other, "report %_* secret", EntryKind.DOCUMENT);
        db.add(db.owner, "report %_* folder", EntryKind.DIRECTORY);
        long trashed = db.add("report %_* trash");
        db.tx.executeWithoutResult(s -> db.entries.deleteByOwnerAndId(db.owner, trashed));
        doThrow(new SearchUnavailableException("ES_UNAVAILABLE")).when(index).replace(anyLong(), anyString(), anyList());
        var page = service.search(db.owner, new SearchQuery("report %_*", 0, 1));
        assertThat(page.source()).isEqualTo("MYSQL");
        assertThat(page.degraded()).isTrue();
        assertThat(page.reason()).isEqualTo("ES_UNAVAILABLE");
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(FileSearchService.SearchItem::id).containsExactly(newest);
        assertThat(service.search(db.owner, new SearchQuery("report %_*", 2, 1)).items()).isEmpty();
        assertThat(db.state().indexedRevision()).isEqualTo(-1);
    }

    @Test void snapshotFiltersDirectoriesTrashAndOtherOwnersAndReusesPublishedGeneration() {
        long id = db.add("report");
        db.add(db.other, "report", EntryKind.DOCUMENT);
        db.add(db.owner, "folder", EntryKind.DIRECTORY);
        long gone = db.add("trash");
        db.tx.executeWithoutResult(s -> db.entries.deleteByOwnerAndId(db.owner, gone));
        when(index.search(eq(db.owner), anyString(), any())).thenReturn(new FileSearchIndex.IndexPage(List.of(id), 1));
        var first = service.search(db.owner, new SearchQuery("report", 0, 20));
        assertThat(first.source()).isEqualTo("ELASTICSEARCH");
        assertThat(first.degraded()).isFalse();
        assertThat(first.items()).extracting(FileSearchService.SearchItem::id).containsExactly(id);
        service.search(db.owner, new SearchQuery("report", 0, 20));
        verify(index, times(1)).replace(eq(db.owner), anyString(), argThat(rows -> rows.size() == 1 && rows.get(0).fileId() == id));
        assertThat(db.state().ready()).isTrue();
    }

    @Test void changedRevisionAfterEsQueryFallsBackAsAWholePage() {
        long id = db.add("before");
        when(index.search(eq(db.owner), anyString(), any())).thenAnswer(call -> {
            db.tx.executeWithoutResult(s -> db.entries.renameByOwner(db.owner, id, "after"));
            return new FileSearchIndex.IndexPage(List.of(id), 1);
        });
        var page = service.search(db.owner, new SearchQuery("before", 0, 20));
        assertThat(page.reason()).isEqualTo("CATALOG_CHANGED");
        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(db.state().ready()).isFalse();
    }

    @Test void unownedEsHitNeverLeaksThroughHydration() {
        db.add("report");
        long other = db.add(db.other, "secret", EntryKind.DOCUMENT);
        when(index.search(eq(db.owner), anyString(), any())).thenReturn(new FileSearchIndex.IndexPage(List.of(other), 99));
        var page = service.search(db.owner, new SearchQuery("report", 0, 20));
        assertThat(page.reason()).isEqualTo("CATALOG_CHANGED");
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(FileSearchService.SearchItem::displayName).containsExactly("report");
    }

    @Test void failedRebuildDoesNotReplacePublishedGenerationAndCleanupFailureDoesNotUndoSuccess() {
        db.add("first");
        var first = service.reindex(db.owner);
        db.add("second");
        doThrow(new SearchUnavailableException("INDEX_WRITE_FAILED")).when(index).replace(anyLong(), anyString(), anyList());
        assertThatThrownBy(() -> service.reindex(db.owner)).hasMessage("INDEX_WRITE_FAILED");
        assertThat(db.state().generation()).isEqualTo(first.generation());
        assertThat(db.state().indexedRevision()).isEqualTo(first.indexedRevision());
        doNothing().when(index).replace(anyLong(), anyString(), anyList());
        doThrow(new SearchUnavailableException("ES_UNAVAILABLE")).when(index).removeGeneration(anyLong(), anyString());
        var second = service.reindex(db.owner);
        assertThat(second.ready()).isTrue();
        assertThat(second.generation()).isNotEqualTo(first.generation());
        verify(index).removeGeneration(db.owner, first.generation());
    }

    @Test void snapshotLimitDoesNotPublishTruncatedCatalog() {
        db.tx.executeWithoutResult(s -> {
            for (int i = 0; i < 1000; i++) db.entries.saveGenerated(db.owner, null, "report-" + i, EntryKind.DOCUMENT, 1);
        });
        assertThat(service.reindex(db.owner).ready()).isTrue();
        verify(index).replace(eq(db.owner), anyString(), argThat(rows -> rows.size() == 1000));
        db.add("report-overflow");
        var page = service.search(db.owner, new SearchQuery("report", 0, 50));
        assertThat(page.reason()).isEqualTo("SNAPSHOT_LIMIT");
        assertThat(page.total()).isEqualTo(1001);
        assertThat(page.items()).hasSize(50);
        assertThat(db.state().ready()).isFalse();
    }

    @Test void writerDuringRebuildRemainsDirtyInsteadOfBeingLost() throws Exception {
        long id = db.add("before");
        var rebuilding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var updated = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        doAnswer(call -> {
            rebuilding.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(index).replace(anyLong(), anyString(), anyList());
        try {
            var search = pool.submit(() -> service.reindex(db.owner));
            assertThat(rebuilding.await(10, TimeUnit.SECONDS)).isTrue();
            var writer = pool.submit(() -> db.tx.executeWithoutResult(s -> {
                db.jdbc.update("UPDATE drive_entries SET display_name = 'after' WHERE id = ?", id);
                updated.countDown();
                db.mapper.changed(db.owner);
            }));
            assertThat(updated.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> writer.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(search.get(10, TimeUnit.SECONDS).revision()).isEqualTo(1);
            writer.get(10, TimeUnit.SECONDS);
            assertThat(db.state().revision()).isEqualTo(2);
            assertThat(db.state().indexedRevision()).isEqualTo(1);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void emptyOwnerPublishesEmptySnapshotAndInvalidQueryIsRejectedBeforeIo() {
        assertThat(service.reindex(db.owner).ready()).isTrue();
        verify(index).replace(eq(db.owner), anyString(), eq(List.of()));
        assertThatThrownBy(() -> new SearchQuery(" ", 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchQuery("\u0000", 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchQuery(null, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchQuery("x".repeat(101), 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchQuery("x", Integer.MAX_VALUE, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchQuery("x", -1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchQuery("x", 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchQuery("x", 0, 51)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SearchQuery(" x ", 199, 50).offset()).isEqualTo(9950);
    }
}
