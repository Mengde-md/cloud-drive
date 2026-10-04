# Day27 Runtime Evidence

Implementation checkpoint: `8fe065f`; final normalized-query fix: `e883c9b`. Initial full runs completed on 2026-10-03. File-service 35 tests, live ES 5 tests and all 25 four-process checks were rerun successfully after the fix on 2026-10-04. Unchanged auth/gateway/user results remain dated 2026-10-03, not relabeled as new runs.

## Automated Results

| Command target | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| Root clean test | 108 | 0 | 0 | 0 |
| auth-service clean test | 19 | 0 | 0 | 0 |
| gateway clean test | 20 | 0 | 0 | 0 |
| user-service clean test | 11 | 0 | 0 | 0 |
| file-service clean test | 35 | 0 | 0 | 0 |
| file-service test -Dtest=ElasticSearchLiveIT | 5 | 0 | 0 | 0 |

All six commands exited 0 with BUILD SUCCESS. The explicit IT is not selected by default Surefire naming. Final XML aggregates contain 40 file-service cases because both its standard run and separate IT run remain present. Do not label 40 as the default clean-test output. No code tests were skipped.

Raw logs in the local audit workspace `C:\Users\惠普暗影精灵9\Documents\ChatGPT\清理`: day27-root-tests.log, day27-auth-tests.log, day27-gateway-tests.log, day27-user-tests.log, day27-file-tests-final.log, day27-es-live-tests-final.log. Initial file/live logs without the -final suffix are retained separately. These logs are local evidence, not portable prerequisites; the textbook contains repeatable commands.

## Actual Elasticsearch

Official Windows Elasticsearch 8.10.4 ZIP, verified against official SHA512:

```text
85F4A534CE9B07E6AA324386B35BEB1977652B79132D59FF9FF0FAAFC374FB30A516E30EE2A220C19D491325508239D9C49B3CEE81957C8563D6917846CF176A
```

Dedicated cluster `clouddrive-day27-audit`, HTTP 127.0.0.1:19200, transport 127.0.0.1:19300, bundled JDK, 512 MB heap, single node, security/ML disabled only for this disposable loopback audit. No Windows service installed. This archived version is a compatibility baseline, not a current production recommendation.

Startup initially failed with the known UnixDomainSockets/PipeImpl error. Actual startup output showed that ES ignored JAVA_TOOL_OPTIONS; passing the same verified writable socket directory in ES_JAVA_OPTS fixed it. No global Java configuration changed.

The first live test run failed because the C drive had approximately 18.4 GB free (4.9%), crossing default percentage disk watermarks. The cluster log showed RED/unallocated shards. Only after checking this dedicated cluster's identity, transient low/high/flood thresholds were changed to 5 GB/4 GB/3 GB free space. Disk protection stayed enabled; no user files were deleted. The five real tests then passed without changing production timeouts or relaxing their assertions.

The 10-04 owned restart passed the same disk thresholds as process-level -E flags. It did not modify another cluster or disable the protection. NUL/null validation assertions were added within existing test methods, so the ordinary count stayed 35.

Live tests cover actual mapping, standard English/Chinese token behavior, owner filtering and stable paging; rename/delete/restore/purge; index deletion and explicit rebuild; real connection refusal and recovery against the healthy endpoint; incompatible mapping with real Bulk item failure. Random day27-it-* indices and their scoped DB fixtures were cleaned by the tests.

## Four-Process Check

Final raw run: `C:\Users\惠普暗影精灵9\Documents\ChatGPT\清理\day27-runtime-20261004-150902\checks.txt`; shell output: day27-http-runtime-final.log in its parent workspace. Exit 0, AUDIT PASSED: 25 checks. The original 10-03 run and day27-http-runtime.log remain separate evidence.

Production main classes plus Maven-resolved dependency classpaths were used, with test-classes removed. Processes: auth 8081, user 8083, file 8082, gateway 9000. Historical monolith 8080 stayed stopped. Real JWTs were issued through auth via gateway. DB credentials came from the existing local learning configuration and were not printed. Dedicated object/chunk directories prevented root clean from deleting runtime fixtures. Local Redis used a separate Redisson override; no server password changed.

```text
PASS: auth ready
PASS: user ready
PASS: file ready
PASS: gateway ready
PASS: two real JWT users registered
PASS: anonymous search is 401
PASS: upload commits metadata and search revision
PASS: instant upload commits revision
PASS: chunk session starts
PASS: chunk accepted
PASS: chunk complete commits revision
PASS: other owner creates same query terms
PASS: actual ES search has exactly own three uploads
PASS: stable pagination includes final older upload once
PASS: another owner sees only their catalog
PASS: rename invalidates search generation
PASS: soft delete succeeds
PASS: deleted filename no longer searchable
PASS: restore succeeds
PASS: restored filename is searchable
PASS: large page is rejected without overflow
PASS: owned ES process stopped for real outage
PASS: outage returns correct MYSQL degraded page
PASS: explicit reindex during outage is 503
PASS: file write remains available with ES stopped
```

The audit verified the ES Java executable path before stopping its owned PID. It then stopped only the four application PIDs it launched. Port inspection found no listeners on 8080/8081/8082/8083/9000/19200/19300 after completion. Disposable HTTP accounts/files remain in the learning DB and dedicated local audit directory, like earlier runtime audits; no broad data cleanup was attempted.

The HTTP script did not restart ES after stopping it. Recovery is separately proven by the real IT. No real Nacos discovery/lb, production TLS/auth, replicas, throughput benchmark, PIT pagination, scheduler or Day28 monitoring deployment was claimed.
