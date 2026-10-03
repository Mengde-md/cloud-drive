package com.mengde.clouddrive.userservice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class QuotaService {
    private final QuotaMapper mapper;
    private final InternalAuthClient auth;
    private final String serviceToken;
    private final long defaultQuota;

    QuotaService(QuotaMapper mapper, InternalAuthClient auth,
                 @Value("${clouddrive.internal.service-token}") String serviceToken,
                 @Value("${clouddrive.quota.default-bytes}") long defaultQuota) {
        this.mapper = mapper;
        this.auth = auth;
        this.serviceToken = serviceToken;
        this.defaultQuota = defaultQuota;
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public String reserve(long userId, ReserveRequest request) {
        if (userId <= 0 || request.amount() < 0 || request.usedBytes() < 0) {
            throw new IllegalArgumentException("invalid quota request");
        }
        String id = request.reservationId();
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("invalid reservation id");
        }
        if (mapper.findQuota(userId) == null) {
            var identity = auth.identity(userId, serviceToken);
            if (identity == null || identity.id() != userId) {
                throw new IllegalArgumentException("user identity mismatch");
            }
            mapper.initialize(userId, defaultQuota);
        }
        Long quota = mapper.lockQuota(userId);
        var existing = mapper.find(id);
        if (existing != null) {
            if (existing.userId() != userId || existing.amount() != request.amount() || "RELEASED".equals(existing.state())) {
                throw new IllegalStateException("reservation id conflict");
            }
            return id;
        }
        long active = mapper.activeAmount(userId);
        if (request.usedBytes() > quota || active > quota - request.usedBytes()
                || request.amount() > quota - request.usedBytes() - active) {
            throw new QuotaDeniedException();
        }
        mapper.reserve(id, userId, request.amount());
        return id;
    }

    @Transactional
    public void commit(long userId, String id) {
        transition(userId, id, "COMMITTED");
    }

    @Transactional
    public void release(long userId, String id) {
        if (userId <= 0 || id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("invalid reservation");
        }
        // A release may overtake a timed-out reserve. A tombstone prevents a late reserve from succeeding.
        mapper.initialize(userId, defaultQuota);
        mapper.lockQuota(userId);
        if (mapper.find(id) == null) {
            mapper.tombstone(id, userId);
            return;
        }
        transition(userId, id, "RELEASED");
    }

    private void transition(long userId, String id, String target) {
        if (mapper.lockQuota(userId) == null) throw new IllegalArgumentException("unknown profile");
        var reservation = mapper.find(id);
        if (reservation == null || reservation.userId() != userId) throw new IllegalArgumentException("unknown reservation");
        if (target.equals(reservation.state())) return;
        if (!"RESERVED".equals(reservation.state())) throw new IllegalStateException("reservation state conflict");
        mapper.transition(id, target);
    }

    record ReserveRequest(String reservationId, long amount, long usedBytes) { }
    static final class QuotaDeniedException extends RuntimeException { }
}
