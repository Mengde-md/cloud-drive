# Day26 File Service, Feign and Compensation

Implementation: `b4acef5`; verified on 2026-10-03. This is a shared-schema learning deployment, not a production distributed transaction system.

## Scope and Ownership

- Gateway `:9000` sends auth to `:8081`, entries/files/upload-sessions/shares/OpenAPI to file `:8082`.
- User quota provider `:8083` exposes internal reserve/commit/release only; no public gateway route.
- File owns file metadata, upload sessions/chunks, shares, object storage and `file_quota_operations`.
- User owns `user_profile.quota_bytes` and `quota_reservations`; file production code does not query either user table.
- Auth retains identity and JWT issuance. File verifies JWT itself and does not trust arbitrary identity headers.
- Legacy `monolith-health-route` is retained but is unavailable when the monolith is stopped. Its existence is not runtime evidence.

## Migration and Cutover Prerequisites

1. Back up any data that must be preserved and stop business writes before cutover.
2. Apply root Flyway migrations through **V9** to the shared schema. File has no Flyway dependency and cannot initialize an empty schema itself. Root tests do this only in the disposable learning database; tests are not a production migration procedure.
3. Start user-service, whose independent `flyway_schema_history_user` baselines at 0 and applies V1. Its one-time backfill requires existing `user_account`.
4. Start auth, file and gateway with consistent credentials/configuration. Auth may be started before user as in the runtime audit; user profile initialization needs auth at request time.
5. Keep the old monolith stopped during the four-process business workload. Do not mix legacy quota/file writers with the new writer group.
6. Set stable absolute `CLOUDDRIVE_STORAGE_ROOT` and `CLOUDDRIVE_CHUNKS_ROOT`. The learning defaults under root build-output are not durable deployment locations and root `mvn clean` can erase them.

V8 creates the journal; V9 adds RELEASE_PENDING to its CHECK constraint. V8 had already been applied during this work and was not rewritten. No Flyway repair was used.

## Contract

| Method/path under `/internal/users/{id}/quota` | Request | Successful response |
|---|---|---|
| POST `/reserve` | JSON reservationId, amount, usedBytes | 200 plain reservation ID |
| POST `/{reservationId}/commit` | no body | 200 empty |
| POST `/{reservationId}/release` | no body | 200 empty |

All requests require `X-CloudDrive-Internal-Token` (at least 32 bytes configured), not a user's Bearer token. User only permits exact health/info anonymously; all other paths fail closed. Commit/release explicitly name path variables.

User returns 400 for invalid arguments, 409 for quota/conflicting states, 503 for auth dependency failure. File maps quota rejection to 409 and quota availability failures to 503; downstream business exceptions are outside JWT validation catches.

## Quota Model and Day23 Design Delta

This is not the complete Day23 authoritative `used_bytes` ledger. File calculates active metadata usage while holding the owner Redis lock and passes it to user; user locks the profile row and adds active RESERVED amounts before accepting a request. All file mutations changing usage use this owner coordination, including restore and delete.

User reserve is READ_COMMITTED. An initial consistent read under default RR previously opened a stale snapshot before the profile lock; the real two-thread test then allowed both 60-byte requests against a 100-byte limit. READ_COMMITTED plus the retained profile row lock fixes that query pattern, not arbitrary unlocked quota code.

New profiles initialize lazily after auth identity verification on first reserve. Historical quotas are backfilled once. There is no ongoing dual-write from legacy quota_bytes. Registration does not synchronously create a profile.

Same ID and amount repeats are idempotent, different owner/amount conflicts. COMMITTED and RELEASED are terminal. Release of a not-yet-seen ID writes a RELEASED tombstone so late reserve cannot succeed. Expired RESERVED rows still count; elapsed time alone is not evidence that the file operation failed.

## Journal State Machine

```text
PREPARED --metadata + marker in one transaction--> COMMIT_PENDING
   |                                                   |
   | durable cancellation transaction                 | remote commit
   v                                                   v
RELEASE_PENDING --remote release + owned cleanup-----> DONE
```

The same reservation ID is persisted before remote reserve and reused for every retry. Metadata transactions lock the operation row and only accept PREPARED. Reconciliation rereads each candidate under row lock; state updates require the expected phase and one affected row.

Cancellation intent is committed in a separate transaction before remote release or object deletion. If cleanup or DONE commit fails, the state remains RELEASE_PENDING, never PREPARED. A resumed old Redis-lock holder cannot resurrect the cancelled operation.

COMMIT_PENDING means local metadata already committed. Failed remote confirmation is logged as `quota_finalize_pending`; the local successful result is preserved, no release or object deletion occurs. The next same-owner mutation retries commit using the same ID.

New multipart/merged objects carry cleanup ownership. Instant uploads and restore pass no cleanup key, so failure cannot delete an already shared object. Before journal preparation, failure cleans a newly written object when possible; process death between object write and journal creation can still leave an orphan.

## Transactions, Locks and Timeouts

- Owner Redis lock serializes usage-changing normal operations. Session Redis lock coordinates chunk manifest and completion.
- Chunk upload uses REQUIRES_NEW TransactionTemplate inside the held session lock. Commit/rollback precedes unlock, unlike method-level transaction proxy plus inner try-with-resources.
- Operation row locks protect cancellation versus metadata writes for the same operation, including a resumed holder. This is not owner-wide fencing.
- Feign connect/read timeouts: 1000/2000 ms. Feign's own Retryer is disabled. QuotaGateway tries RetryableException at most three times with 100/200 ms sleeps; 409 is not retried. Auth identity has no explicit retry loop.
- Gateway response timeout is 30 seconds. It is not an end-to-end deadline. Multiple old operations can make synchronous reconciliation exceed it.
- Reconciliation holds a database operation-row transaction during bounded RPC/cleanup. This costs connection/lock occupancy and is not a scalable task executor.

## Verified and Unverified

Verified: root 108, auth 19, gateway 20, user 11, file 17 tests; all failure/error/skip counts zero. Real MySQL tests cover reservation concurrency, local transaction visibility and compensation interleavings. Four separate default-profile processes passed 26 checks with monolith stopped; see `../day26/runtime-evidence.md`.

Not verified: real Nacos registration/config/lb, TLS/mTLS, network-partition owner fencing, production throughput, container deployment, physical database isolation, and production migration/rollback. Docker Desktop remains externally blocked by the Day25 backend socket startup failure; this work did not reset Docker or delete its data.

## Residual Risks

1. Owner lock loss can still expose stale usedBytes across different operations; same-operation row locking does not guarantee no oversell during Redis failures.
2. Chunk storage has no fencing token against an old session holder after watchdog expiration.
3. Recovery is lazy before the next mutation by the same owner, not scheduled. No further mutation means pending operations may persist indefinitely; expires_at does not automatically release them.
4. Direct multipart/complete requests do not have a client idempotency key. Response loss or post-commit cache/chunk cleanup failure can return an error after a successful metadata commit.
5. Filesystem and database commits are not atomic. Orphan objects/chunks and failed physical cleanup remain possible; do not manually delete referenced storage keys.
6. HS256 key sharing, static service tokens and shared database accounts remain transitional security limitations. Default loopback binding is not equivalent to production network isolation.
7. Legacy and migrated source copies coexist. Changes to shared behavior require explicit parity review rather than silently editing one copy.

No Day27 Elasticsearch, MQ, background scheduler, circuit-breaker platform or AI implementation is included.
