# Day34 Runtime Evidence

Date: 2026-10-06. Platform: Windows native single-host equivalent deployment.
No production class, POM, DDL, protected handbook/roadmap/gate or prior Final was changed.

## Six Modules

| Module | JUnit | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| root | 111 | 0 | 0 | 0 |
| auth-service | 22 | 0 | 0 | 0 |
| gateway | 24 | 0 | 0 | 0 |
| user-service | 15 | 0 | 0 | 0 |
| file-service | 49 | 0 | 0 | 0 |
| ai-service | 184 | 0 | 0 | 0 |
| Unique total | 405 | 0 | 0 | 0 |

All six clean verify runs succeeded; AI was retested after each probe repair.
184 AI cases = prior 176 + 8 Day34 evidence-helper contracts.
Repeated AI/root reruns are not added to the unique total.
Logs: day34-six-modules.log and day34-*-clean-verify.log in the calling workspace.
Final AI source was compiled/tested in day34-ai-restart-fix-test.log.

## Executable Full Chain

Actual report is versioned as full-chain.json.
SHA256: 2C04EA9BA8827FC6BD1E81B5BC2872A93259167F3131D5CC15A00E44203A6056.
Owned output directory: C:\Users\惠普暗影精灵9\Documents\ChatGPT\清理\day34-full-chain-76ec91aa986f4fa79b2be1555f765fa2\full-chain.json.
source=protocol_fixture; realModelCalled=false; humanLearningAccepted=false.

| Value | Actual |
| --- | --- |
| fileId | 1194 |
| database owner | 32 |
| file size | 185 raw UTF-8 bytes |
| upload/download/restart SHA256 | 795369418111c9024389a0f7c839a1d422357eaf853add9092129963e1685ed4 |
| RAG documentId | c6338e02-ebcc-4d77-9ef1-2fb83c2c2bf6 |
| citationId | c6338e02-ebcc-4d77-9ef1-2fb83c2c2bf6:0 |

The raw file bytes were explicitly imported by the same authenticated client after download.
There is no automatic private-path ingestion, background file sync or Agent file mutation.
The thirty-day statement is example document content, not an audited service retention policy.

| Executed assertion | Contract | Result |
| --- | --- | --- |
| 1 | AI executable JAR healthy | PASS |
| 2 | real auth registration owner | PASS |
| 3 | real auth login owner | PASS |
| 4 | real auth registration other | PASS |
| 5 | real auth login other | PASS |
| 6 | anonymous file request rejected at edge | PASS |
| 7 | gateway upload uses signed owner despite spoofed identity header | PASS |
| 8 | same owner's download equals uploaded bytes | PASS |
| 9 | different registered owner cannot download | PASS |
| 10 | gateway Range has exact bytes and Content-Range | PASS |
| 11 | business restart preserves database metadata and object bytes | PASS |
| 12 | explicit import through gateway uses same downloaded SHA256 | PASS |
| 13 | real ONNX retrieval remains routed and references imported document | PASS |
| 14 | missing consent rejects before provider | PASS |
| 15 | other registered owner refuses without provider call | PASS |
| 16 | real SDK Agent answer cites same imported bytes | PASS |
| 17 | retrieved instruction cannot authorize document deletion | PASS |
| 18 | forged citation rejected | PASS |
| 19 | upstream error is redacted | PASS |
| 20 | actual MCP STDIO lists exactly two read-only tools | PASS |
| 21 | MCP JWT reaches same gateway RAG document | PASS |
| 22 | AI executable JAR healthy | PASS |
| 23 | AI restart loses in-memory RAG and refuses with zero provider calls | PASS |
| 24 | AI restart does not delete persistent business file | PASS |
| 25 | AI logs do not disclose JWT signing material or tokens | PASS |

25 executed assertions include separate health checks before and after AI restart.
They are non-JUnit steps, not 25 unique semantic scenarios.

## Same AI JAR Prior Contracts

| Script | Executed non-JUnit checks | Scope |
| --- | --- | --- |
| day34-full-chain-smoke | 25 | auth/file/restart/RAG/Agent/MCP |
| day33-packaged-smoke | 12 | telemetry, injection proposal, deadline, bounded MCP body |
| day32-packaged-smoke | 14 | Agent/MCP SDK and gateway boundaries |
| day31-packaged-smoke | 12 | ONNX/RAG/citation/deletion/restart |
| day30-packaged-smoke | 7 | summary/SSE and gateway contracts |
| Total | 70 | separate from JUnit |

All use the AI JAR SHA256 E0BAF3D3CD3EB0A91D6828FD775765993DF826BC6C687C77C36E5D5E0ECBD50A.
28 deployment configuration contracts passed; secret-hygiene check passed.
Seven runtime artifacts were inspected: six existing root/business JARs plus the AI JAR.
Repeated nested AI/RAG/Agent archive checks are not counted as new artifacts or JUnit.
No Nacos/Elasticsearch server, Docker/Linux runtime, hosted CI or real DeepSeek was started in this run.
MCP SDK close emitted Windows child code 1 warnings; successful protocol calls are not claimed as child exit 0.

## Artifact Hashes

| Relative artifact | SHA256 |
| --- | --- |
| build-output/cloud-drive-learning-0.1.0-SNAPSHOT.jar | 051BABF0590402B6046F8FCEF1F095C347E72311D03D15AAF2EE7B317E2A8121 |
| build-output/cloud-drive-learning-0.1.0-SNAPSHOT-migration.jar | 1DCF2DCCD5384F7FD44E42E72C9FF937E5BFDF5976AB605777F7D107675B62A6 |
| auth-service/build-output/cloud-drive-auth-service-0.1.0-SNAPSHOT.jar | 40325C27FD49A39E69C1B70557D8B0D438AA17A6553C369774FCCF2E108065BB |
| gateway/build-output/cloud-drive-gateway-0.1.0-SNAPSHOT.jar | CB53627DC34A49097E2A72AB475AD0C368C05B9CE1C5052B89F9885657B1B236 |
| user-service/build-output/cloud-drive-user-service-0.1.0-SNAPSHOT.jar | 0BE6C15934E5835C14EBB142B9180D0A18FC0A23F1CD9058C8D1E9CB0F840622 |
| file-service/build-output/cloud-drive-file-service-0.1.0-SNAPSHOT.jar | F5F18641DF8B3D3BAD35D6BAA5FE2E366013A7BEA2B5787871DCB2493EFECE87 |
| ai-service/build-output/cloud-drive-ai-service-0.1.0-SNAPSHOT.jar | E0BAF3D3CD3EB0A91D6828FD775765993DF826BC6C687C77C36E5D5E0ECBD50A |

## Actual Failures, Not Hidden

1. First attempt: business launch ready, probe main waited in Thread.join; drain thread waited in FileInputStream.readBytes/transferTo. Native jcmd output established this wait point. Only the verified owned probe JVM was terminated; the wrapper stopped recorded business PIDs.
2. The first launcher receipt decoded badly as UTF-8. A child PowerShell UTF-8 adapter and direct file redirection replaced the pipe/drainer. No path-authorization failure is invented: it had not reached that assertion.
3. Second attempt: 21 checks passed, then an extra ServerSocket probe bind failed before AI restart. full-chain.json remained FAIL. Cold start now checks LISTEN; the real AI JVM performs its own bind. No reuse flag or foreign-process kill was used.
4. Static self-review corrected the actual identity-header names, strengthened owner equality, fixed the architecture quota arrow and documented Windows-only support.
5. Only the final 25-check report is accepted; previous reports/logs are retained as failure evidence.

## SQL And Algorithms

Readonly disposable-schema audit: root Flyway version 10/success 1; user version 1/success 1.
The active-name unique key columns are owner_id/active_parent_scope_id/active_display_name.
Final demo row is file 1194, owner 32, 185 bytes; the earlier failed-run row 1193/owner30 remains in the disposable schema.
No DDL, personal database operation, EXPLAIN timing or performance improvement is claimed.

AI-only seeded draw: 20261006 -> LC704 and LC98 from delivered Day01-33 topics.
Standalone AI review: 1221 binary-search comparisons plus 9 BST boundaries.
This is not user closed-book/D+7 completion and is not included in JUnit counts.

## Gate And Human Status

Original verify_day.ps1 -DayNN 34 is executed after scoped implementation, material audit and frozen Final commits.
Its actual result, ownership cleanup and preservation audit are recorded separately in acceptance.md.
Original root clean removes two packaged root artifacts; only byte-identical verified backups may restore missing artifacts.
No repackage is substituted for verified bytes.

Textbook and evidence materials have been reread; Postman UI execution and human oral recording are not claimed.
Closed-book explanation, independent replica, error log, algorithm history, from-zero startup, API/failure drill,
architecture sketch, resume recording and suites C/D/E remain pending human evidence.
No Day35 or learning-complete milestone is created.
