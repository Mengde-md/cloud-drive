package com.mengde.clouddrive.remote;

import feign.FeignException;
import feign.RetryableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class QuotaGateway {
    private final AuthIdentityClient auth;
    private final UserQuotaClient quota;
    private final String token;

    QuotaGateway(AuthIdentityClient auth, UserQuotaClient quota,
                 @Value("${clouddrive.internal.service-token:}") String token) {
        if (token.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("CLOUDDRIVE_INTERNAL_SERVICE_TOKEN must be at least 32 bytes");
        }
        this.auth = auth;
        this.quota = quota;
        this.token = token;
    }

    public void reserve(Reservation reservation, long amount, long usedBytes) {
        try {
            var identity = auth.identity(reservation.ownerId(), token);
            if (identity == null || identity.id() != reservation.ownerId()) throw new QuotaUnavailableException();
            String returned = call(() -> quota.reserve(reservation.ownerId(),
                    new UserQuotaClient.ReserveRequest(reservation.id(), amount, usedBytes), token));
            if (!reservation.id().equals(returned)) throw new QuotaUnavailableException();
        } catch (FeignException.Conflict rejected) {
            throw new QuotaRejectedException();
        } catch (FeignException failure) {
            throw new QuotaUnavailableException(failure);
        }
    }

    public void commit(Reservation reservation) {
        try {
            call(() -> { quota.commit(reservation.ownerId(), reservation.id(), token); return null; });
        } catch (FeignException failure) {
            throw new QuotaUnavailableException(failure);
        }
    }

    public void release(Reservation reservation) {
        try {
            call(() -> { quota.release(reservation.ownerId(), reservation.id(), token); return null; });
        } catch (FeignException failure) {
            throw new QuotaUnavailableException(failure);
        }
    }

    private <T> T call(java.util.concurrent.Callable<T> request) {
        for (int attempt = 0; ; attempt++) {
            try {
                return request.call();
            } catch (RetryableException timeout) {
                if (attempt == 2) throw timeout;
                try { Thread.sleep(100L << attempt); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new QuotaUnavailableException(interrupted);
                }
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    public record Reservation(long ownerId, String id) { }
    public static final class QuotaRejectedException extends RuntimeException { }
    public static final class QuotaUnavailableException extends RuntimeException {
        public QuotaUnavailableException() { super("quota service unavailable"); }
        public QuotaUnavailableException(Throwable cause) { super("quota service unavailable", cause); }
    }
}
