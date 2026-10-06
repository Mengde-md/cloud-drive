# Day33 Runtime Evidence (2026-10-06)

Implementation: 7a75657, 14 exact implementation files (10 new, 4 necessary differences). Code and material gates are separate. This file records executed facts before the original gate, not a forecast of its verdict.

## Commands Actually Executed

- AI focused first: Maven testCompile failed because current SimpleMeterRegistry is not AutoCloseable; zero JUnit executed.
- Focused second: offline test of AgentTelemetryTest,McpBoundedBodyTest,McpFailureContractTest,AgentEvaluationHttpTest; 38/0/0/0, BUILD SUCCESS, exit0.
- AI clean verify first full and final rerun after probe UTF-8 fix: 176/0/0/0 each, BUILD SUCCESS, exit0. Final log day33-ai-refinal.log; dependency:build-classpath then exit0.
- Existing day29-ci.ps1 against owned disposable MySQL/Redis: root/auth/gateway/user/file sequential clean verify, config 28, secret check and six JAR contracts, exit0.
- day32-verify-agent-jar.ps1 on final artifact: old AI/RAG/SDK/Jackson/ONNX and Agent/MCP checks PASS.
- Initial new package probe: first six checks passed, Files.readString(ai.out.log) failed with MalformedInputException. Probe child now uses -Dfile.encoding=UTF-8; strict backend decoder unchanged.
- Final serial probes: Day33=12, old Day32 Agent/MCP=14, Day31 RAG=12 including real restart, Day30 summary/SSE=7. All four scripts exit0 and share the final AI bytes; 45 non-JUnit assertions, not added to JUnit count.
- SQL first: history queries returned10/1 and1/1 but EXPLAIN name failed1054; final query used display_name after V1/V7 read, exit0, ref and Using where; Using filesort. Read only, no DDL.
- Java17 LC84 standalone AI demonstration:9 examples +5461 exhaustive arrays +2 input guards, actual compile/run PASS, not user practice.
- Git diff --check for implementation passed; material diff blank context whitespace normalized before its staging, no production code change.

Environment: Microsoft JDK17.0.20, Maven3.9.10, Git2.54.0.windows.1, PowerShell7. Dependencies: existing Boot3.2.4/LC4j1.21.0/ONNX1.21.0-beta31/AI-only Jackson2.22.1/MCPSDK2.0.1 unchanged. Disposable MySQL8.0.42/13306 and Redis3.0.504/16379 used random Day33 datadir and generated credentials. Personal MySQL80/Redis not stopped.

## Unique Surefire Results

| Project | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| root |111|0|0|0|
| auth-service |22|0|0|0|
| gateway |24|0|0|0|
| user-service |15|0|0|0|
| file-service |49|0|0|0|
| ai-service |176|0|0|0|
| Unique total |397|0|0|0|

Parsed each actual POM build directory and TEST-*.xml; all six use build-output. AI176=old138+new38; new classes5/6/6/21. Focused rerun, original root rerun, config28 and package45 are not accumulated as new JUnit cases.

## Frozen Evaluation Actual Report

Source file: ai-service/build-output/day33-agent-evaluation-report.json. Structured JSON parsed independently after final clean verify: executed20, source=protocol_fixture, realModelCalled=false, semanticQualityMeasured=false. Each actual tuple equals its frozen expected tuple. Status/code/model/tool assertions and live corpus count assertions are real; the proposals and usage are synthetic.

| ID | Actual HTTP | Actual code/result | Actual model requests | Actual successful tools |
|---|---:|---|---:|---:|
|E01|200|ANSWERED|2|1|
|E02|200|ANSWERED|3|2|
|E03|200|MODEL_REFUSED|1|0|
|E04|200|NO_EVIDENCE|0|0|
|E05|200|NO_EVIDENCE|0|0|
|E06|400|AI_INPUT_INVALID|0|0|
|E07|400|AI_INPUT_INVALID|0|0|
|E08|502|AGENT_TOOL_INVALID|1|0|
|E09|502|AGENT_TOOL_INVALID|1|0|
|E10|502|AGENT_TOOL_INVALID|1|0|
|E11|502|AGENT_OUTPUT_INVALID|1|0|
|E12|502|AGENT_LIMIT_EXCEEDED|3|2|
|E13|502|RAG_OUTPUT_INVALID|2|1|
|E14|502|AI_USAGE_INVALID|1|0|
|E15|502|AI_UPSTREAM_FAILED|1|0|
|E16|502|AGENT_TOOL_INVALID|1|0|
|E17|502|AGENT_TOOL_INVALID|1|0|
|E18|502|AGENT_TOOL_INVALID|2|1|
|E19|502|AGENT_TOOL_INVALID|2|1|
|E20|403|FORBIDDEN|0|0|

E18/E19 additionally asserted the imported malicious document canary actually appeared in SDK tool messages. Illegal delete/owner-change did not execute and owned documents remained. E07/E06/E20 reject before service: no Agent request meter; this is intentional measurement coverage, not all-HTTP observability.

## Final Verified Artifact Hashes

| Actual artifact | SHA256 |
|---|---|
|build-output/cloud-drive-learning-0.1.0-SNAPSHOT.jar|8E1999DB2EA650AB6B3D9DD7FD20FA853173E487F70A9C27A07847BB68C42976|
|build-output/cloud-drive-learning-0.1.0-SNAPSHOT-migration.jar|72AA409D9BD13A17FAB777BB1CDB0346A585538D22BF2CD53055D80EC9D85512|
|auth-service/build-output/cloud-drive-auth-service-0.1.0-SNAPSHOT.jar|0050FFA0CBA7D3AB55048702F485C82AF96FD8D8B57833D75EDB53AE581C7668|
|gateway/build-output/cloud-drive-gateway-0.1.0-SNAPSHOT.jar|8040BA030D36E14877D5B5630FFDEDE84BBC84F19D395EB72FF184E069557E7F|
|user-service/build-output/cloud-drive-user-service-0.1.0-SNAPSHOT.jar|53CB6C431E59907CF4949EA7FCDD5818F4DAE6C66817A5B7C97B0C6EF9FF6E3C|
|file-service/build-output/cloud-drive-file-service-0.1.0-SNAPSHOT.jar|3EB4F576729BE37F147F66AD6703C9A51181D599ACACACE04A49BF26021654E7|
|ai-service/build-output/cloud-drive-ai-service-0.1.0-SNAPSHOT.jar|6C10E7B9CCDF5FB6B0EF1226C48345905B89BBFB2E9AC4CA790333AA7F5101D7|

Captured copies of the seven verified bytes before the original gate; root clean will delete two root JARs. Only missing artifacts may be restored from these hash-checked copies; final consistency belongs in acceptance. Ignored old target data is untouched.

## Runtime Ownership and Honest Limits

Final new probe directory: day33-packaged-a3b05557d0184a3c8abfa1c3c6c2bb6c; old Agent directory: day32-packaged-b179422fa1774f96a3d243bddc1c4873; RAG: day31-packaged-4e7f61844f9e45438ad25678e78366e6; summary: day30-packaged-a123e19891e04838bddf60cd9cddc040. These are retained runtime logs under the local temporary directory, not source assets.

Official MCP negotiated protocol2025-11-25 with tools and SDK built-in logging capability. Both new/old successful probes logged Windows STDIO shutdown code1 warnings after the protocol checks. Do not claim child shutdown exit0; final native ownership/port scan separately verifies no leftover owned child.

No real DeepSeek/key/model planning/semantic quality/billing was run. Metrics remain internal (USER403/anonymous401), new logs omit content/secrets, existing usage ownerId logs remain. Input/output checks and owner/allowlist constrain actions, not universal language-level injection detection. Memory index/restart loss, exact substring quote not entailment, revision cannot retract sent content, best-effort native/network cancellation and load-sensitive test deadlines remain.

Original verify_day.ps1 -DayNN33 has not yet run at this file's freeze; do not infer PASS from git clean or this evidence. User learning gate remains pending. Acceptance records the later true original result and cleanup.
