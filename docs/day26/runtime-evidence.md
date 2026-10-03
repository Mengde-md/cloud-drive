# Day26 Runtime Evidence

Date: 2026-10-03. Implementation: `b4acef5`.

Final audit exited 0 and stopped only audit-owned Java processes. It started auth 8081, user 8083, file 8082, gateway 9000 and first verified that monolith 8080 was not listening. Default static HTTP URIs were used, not Nacos.

Raw local evidence directory:

`C:\Users\惠普暗影精灵9\Documents\ChatGPT\清理\day26-runtime-20261003-105649`

Runner: sibling `day26-runtime-audit.ps1`; fixtures: `day26-upload.txt`, `day25-redis-local.yml`. These are local audit assets, not required runtime dependencies of the repository. The runner used compiled production classes with dependency classpath from Surefire XML, removing test-classes; it did not use Maven spring-boot:run.

Database settings came from the existing dedicated learning-test configuration, never printed. JWT/internal tokens were generated for this run and held in process environment. No authentication headers were included in access logs. Redis was the existing passwordless local instance, overridden with a separate passwordless Redisson YAML rather than changing the server.

Root Flyway V1-V9 had run successfully before this audit; user V1 had initialized its own history. Objects/chunks used a run-specific directory, not the root build-output storage. This proves the initialized shared-schema deployment, not bootstrap of an empty database by only the four services.

## Original Check Summary

```text
PASS: auth ready
PASS: user ready
PASS: file ready
PASS: gateway ready
PASS: two users registered through standalone auth
PASS: anonymous file request is rejected
PASS: entries reach file-service with monolith stopped
PASS: multipart upload through Feign quota succeeds
PASS: range download returns 206
PASS: another owner cannot download file
PASS: instant upload preserves owner and quota boundary
PASS: chunk session starts in file-service
PASS: chunk 0 accepted
PASS: chunk 1 accepted
PASS: repeated chunk is idempotent
PASS: chunk completion reserves quota and persists metadata
PASS: share creation succeeds
PASS: anonymous share download succeeds with passcode
PASS: soft delete succeeds
PASS: restore rechecks quota
PASS: OpenAPI is served by file process
PASS: quota provider outage returns 503
PASS: read remains available during quota outage
PASS: next write reconciles pending release and succeeds
PASS: file access log proves upload arrived at independent process
PASS: user access log proves actual reserve and commit calls
AUDIT PASSED: 26 checks
Stopped only audit-owned Java processes.
```

The range check asserts status 206, not all possible range boundaries. Instant-upload checks assert the positive HTTP result; ownership negatives are covered by other tests and download isolation, not every deduplication adversarial case. The chunk completion check also asserts the merged byte size is twice the fixture size.

The provider outage is a real stop/restart of the audit-owned user process. It proves write 503/read 200 and successful lazy reconciliation after restart. Fine-grained lost-reply/rollback/old-holder cases are verified by the separate integration tests, not by this smoke script.

## Automated Runs

| Maven project | Tests | Failures | Errors | Skipped | Result |
|---|---:|---:|---:|---:|---|
| root | 108 | 0 | 0 | 0 | BUILD SUCCESS |
| auth-service | 19 | 0 | 0 | 0 | BUILD SUCCESS |
| gateway | 20 | 0 | 0 | 0 | BUILD SUCCESS |
| user-service | 11 | 0 | 0 | 0 | BUILD SUCCESS |
| file-service | 17 | 0 | 0 | 0 | BUILD SUCCESS |

All five clean-test processes completed with exit code 0. Root final pre-commit run ended 10:55:15, file final run 10:55:51, user 10:47:13, auth 10:49:08, gateway 10:48:42, local Asia/Shanghai time. Later original acceptance-gate output is recorded separately after the documentation commit.

Remaining external limitation: Day25 Docker Desktop backend failed while renaming `sailor-ingest.sock` to `.stale`; real Nacos registration and lb forwarding remain unverified. This audit did not retry destructive Docker repair or change existing data.
