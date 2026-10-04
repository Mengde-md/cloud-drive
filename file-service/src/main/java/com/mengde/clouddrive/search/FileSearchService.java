package com.mengde.clouddrive.search;

import com.mengde.clouddrive.persistence.FileSearchMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class FileSearchService {
    private final FileSearchMapper mapper;
    private final FileSearchIndex index;
    private final TransactionTemplate transaction;
    private final TransactionTemplate fallback;
    private final MeterRegistry meters;

    public FileSearchService(FileSearchMapper mapper, FileSearchIndex index, PlatformTransactionManager manager, MeterRegistry meters) {
        this.mapper = mapper;
        this.index = index;
        this.meters = meters;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        fallback = new TransactionTemplate(manager);
        fallback.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        fallback.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        fallback.setReadOnly(true);
    }

    public SearchPage search(long owner, SearchQuery query) {
        long start = System.nanoTime();
        String outcome = "ERROR";
        try {
            var page = searchPage(owner, query);
            outcome = page.source();
            return page;
        } finally {
            long elapsed = System.nanoTime() - start;
            meters.timer("clouddrive.search.calls", "outcome", outcome)
                    .record(elapsed, java.util.concurrent.TimeUnit.NANOSECONDS);
            LoggerFactory.getLogger(FileSearchService.class).info(
                    "event=file_search traceId={} outcome={} durationMs={}",
                    MDC.get("traceId"), outcome, elapsed / 1_000_000);
        }
    }

    private SearchPage searchPage(long owner, SearchQuery query) {
        try {
            var state = synchronize(owner, false);
            var hits = index.search(owner, state.generation(), query);
            // A fresh locking read, not a repeatable-read snapshot taken before the ES call.
            return transaction.execute(status -> {
                if (!state.equals(mapper.lock(owner))) throw new SearchUnavailableException("CATALOG_CHANGED");
                var items = new ArrayList<SearchItem>();
                for (long id : hits.ids()) {
                    var row = mapper.owned(owner, id);
                    if (row == null) throw new SearchUnavailableException("CATALOG_CHANGED");
                    items.add(SearchItem.from(row));
                }
                return new SearchPage(items, hits.total(), query.page(), query.size(), "ELASTICSEARCH", false, null);
            });
        } catch (SearchUnavailableException unavailable) {
            return fallback.execute(status -> new SearchPage(
                    mapper.page(owner, query.q(), query.offset(), query.size()).stream().map(SearchItem::from).toList(),
                    mapper.count(owner, query.q()), query.page(), query.size(), "MYSQL", true, unavailable.getMessage()));
        }
    }

    public FileSearchMapper.State reindex(long owner) { return synchronize(owner, true); }

    private FileSearchMapper.State synchronize(long owner, boolean force) {
        var previous = new String[1];
        var state = transaction.execute(status -> {
            mapper.ensure(owner);
            var locked = mapper.lock(owner);
            if (!force && locked.ready()) return locked;
            var rows = mapper.snapshot(owner);
            if (rows.size() > 1000) throw new SearchUnavailableException("SNAPSHOT_LIMIT");
            String generation = UUID.randomUUID().toString();
            index.replace(owner, generation, rows);
            mapper.publish(owner, locked.revision(), generation);
            previous[0] = locked.generation();
            return new FileSearchMapper.State(locked.revision(), locked.revision(), generation);
        });
        // Delete only the exact old generation after DB commit; timeout must never delete a future snapshot.
        if (previous[0] != null) {
            try { index.removeGeneration(owner, previous[0]); }
            catch (SearchUnavailableException ignored) { /* Stale generations are never queried. */ }
        }
        return state;
    }

    public record SearchItem(long id, Long parentId, String displayName, long byteSize) {
        static SearchItem from(SearchDocument row) {
            return new SearchItem(row.fileId(), row.parentId(), row.displayName(), row.byteSize());
        }
    }
    public record SearchPage(List<SearchItem> items, long total, int page, int size,
                             String source, boolean degraded, String reason) { }
}
