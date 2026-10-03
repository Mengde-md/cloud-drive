package com.mengde.clouddrive.userservice;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"clouddrive.internal.service-token=day26-test-only-service-token-over-32-bytes", "clouddrive.quota.default-bytes=100"})
class QuotaServiceIntegrationTest {
    private static final long OWNER = 900002601L;
    @Autowired QuotaService service;
    @Autowired JdbcTemplate jdbc;
    @MockBean InternalAuthClient auth;
    @BeforeEach void setup() {
        clean();
        when(auth.identity(anyLong(), anyString())).thenAnswer(c -> new InternalAuthClient.Identity(c.getArgument(0), "contract-user", "USER"));
    }
    @AfterEach void clean() {
        jdbc.update("DELETE FROM quota_reservations WHERE user_id IN (?, ?)", OWNER, OWNER + 1);
        jdbc.update("DELETE FROM user_profile WHERE user_id IN (?, ?)", OWNER, OWNER + 1);
    }
    private String id() { return UUID.randomUUID().toString(); }
    private String state(String id) { return jdbc.queryForObject("SELECT state FROM quota_reservations WHERE reservation_id = ?", String.class, id); }

    @Test void repeatedReserveAndCommitAreIdempotent() {
        String id = id();
        var request = new QuotaService.ReserveRequest(id, 60, 20);
        assertThat(service.reserve(OWNER, request)).isEqualTo(id);
        assertThat(service.reserve(OWNER, request)).isEqualTo(id);
        service.commit(OWNER, id);
        service.commit(OWNER, id);
        assertThat(state(id)).isEqualTo("COMMITTED");
        assertThatThrownBy(() -> service.release(OWNER, id)).isInstanceOf(IllegalStateException.class);
    }
    @Test void releaseTombstoneRejectsLateReserve() {
        String id = id();
        service.release(OWNER, id);
        service.release(OWNER, id);
        assertThatThrownBy(() -> service.reserve(OWNER, new QuotaService.ReserveRequest(id, 20, 0))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.commit(OWNER, id)).isInstanceOf(IllegalStateException.class);
        assertThat(state(id)).isEqualTo("RELEASED");
    }
    @Test void amountAndOwnerCannotChangeForExistingId() {
        String id = id();
        service.reserve(OWNER, new QuotaService.ReserveRequest(id, 10, 0));
        assertThatThrownBy(() -> service.reserve(OWNER, new QuotaService.ReserveRequest(id, 11, 0))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.reserve(OWNER + 1, new QuotaService.ReserveRequest(id, 10, 0))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.release(OWNER + 1, id)).isInstanceOf(IllegalArgumentException.class);
        assertThat(state(id)).isEqualTo("RESERVED");
    }
    @Test void overdueReservationIsNotSilentlyFreed() {
        String id = id();
        service.reserve(OWNER, new QuotaService.ReserveRequest(id, 80, 0));
        jdbc.update("UPDATE quota_reservations SET expires_at = DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY) WHERE reservation_id = ?", id);
        assertThatThrownBy(() -> service.reserve(OWNER, new QuotaService.ReserveRequest(id(), 30, 0))).isInstanceOf(QuotaService.QuotaDeniedException.class);
        service.release(OWNER, id);
        assertThat(service.reserve(OWNER, new QuotaService.ReserveRequest(id(), 100, 0))).isNotBlank();
    }
    @Test void twoConcurrentReservationsCannotExceedQuota() throws Exception {
        service.reserve(OWNER, new QuotaService.ReserveRequest(id(), 0, 0));
        var ready = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> attempt = () -> {
                ready.await();
                try { service.reserve(OWNER, new QuotaService.ReserveRequest(id(), 60, 0)); return true; }
                catch (QuotaService.QuotaDeniedException expected) { return false; }
            };
            var a = pool.submit(attempt); var b = pool.submit(attempt); ready.countDown();
            assertThat(java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally { pool.shutdownNow(); }
    }
    @Test void invalidNumbersAndUnknownCommitAreRejected() {
        assertThatThrownBy(() -> service.reserve(OWNER, new QuotaService.ReserveRequest(id(), -1, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reserve(OWNER, new QuotaService.ReserveRequest(id(), 1, Long.MAX_VALUE))).isInstanceOf(QuotaService.QuotaDeniedException.class);
        assertThatThrownBy(() -> service.commit(OWNER, id())).isInstanceOf(IllegalArgumentException.class);
    }
}
