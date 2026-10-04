package com.mengde.clouddrive.observability;

import com.mengde.clouddrive.persistence.FileSearchMapper;
import com.mengde.clouddrive.search.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SearchTelemetryTest {
    @Test void degradedSuccessAndDatabaseFailureHaveDifferentBoundedOutcomes() {
        var meters = new SimpleMeterRegistry();
        var mapper = mock(FileSearchMapper.class);
        var index = mock(FileSearchIndex.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var state = new FileSearchMapper.State(1, 1, "generation");
        when(mapper.lock(9)).thenReturn(state);
        when(index.search(eq(9L), anyString(), any())).thenThrow(new SearchUnavailableException("ES_UNAVAILABLE"));
        when(mapper.page(eq(9L), anyString(), anyInt(), anyInt())).thenReturn(List.of());
        var service = new FileSearchService(mapper, index, manager, meters);
        assertThat(service.search(9, new SearchQuery("private-query", 0, 20)).degraded()).isTrue();
        assertThat(meters.get("clouddrive.search.calls").tag("outcome", "MYSQL").timer().count()).isEqualTo(1);
        when(mapper.page(eq(9L), anyString(), anyInt(), anyInt())).thenThrow(new IllegalStateException("database down"));
        assertThatThrownBy(() -> service.search(9, new SearchQuery("another-private-query", 0, 20)))
                .isInstanceOf(IllegalStateException.class).hasMessage("database down");
        assertThat(meters.get("clouddrive.search.calls").tag("outcome", "ERROR").timer().count()).isEqualTo(1);
        assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .extracting(tag -> tag.getKey()).containsExactly("outcome"));
        meters.close();
    }
    @Test void elasticsearchSuccessIsMeasuredWithoutChangingThePage() {
        var meters = new SimpleMeterRegistry();
        var mapper = mock(FileSearchMapper.class);
        var index = mock(FileSearchIndex.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mapper.lock(9)).thenReturn(new FileSearchMapper.State(1, 1, "generation"));
        when(index.search(eq(9L), anyString(), any())).thenReturn(new FileSearchIndex.IndexPage(List.of(), 0));
        assertThat(new FileSearchService(mapper, index, manager, meters).search(9, new SearchQuery("report", 0, 20)).source())
                .isEqualTo("ELASTICSEARCH");
        assertThat(meters.get("clouddrive.search.calls").tag("outcome", "ELASTICSEARCH").timer().count()).isEqualTo(1);
        meters.close();
    }
}
