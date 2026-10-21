# D-078 retry experiment audit

## Planning

별도 Codex 리뷰 미호출. Read current listener, reservation/inventory path, actual handler/backoff, existing policy and lock-boundary tests, shared container/cleanup rules, ADR0025/0027. Base8f0d2ee. Actual cached Spring Kafka3.3.14 javap verified supported backoff-function/RetryListener APIs and non-null reset requirement. No execution or performance result yet; P1-P4 remain open. Shared TASKS/progress and historical ADR bodies untouched.

## Draft implementation, execution pending

Added only StockRetryLoadIntegrationTest and this plan/audit. Four parameterized cases, one observation per mode/scenario. Uses actual configured BackOff and unchanged real BackOffHandler/sleeper; persistent inter-attempt monotonic intervals must also reflect the returned delays (10ms allowance for wall-clock sleep granularity). Three actual child consumers must each hold one partition before publishing. Actual first reads must show three distinct threads, version0, one barrier generation; actual optimistic failures must occur. Persistent failures are explicitly injected after a real read, not represented as natural contention.

Local integration-test-container lint, docs-preservation against base8f0d2ee, writing lint, consistency precheck and diff whitespace checks passed. No local Gradle/Docker or runtime result. Ship preflight blocked on unverified P1-P4 as expected; draft/Actions path is authorized. Separate Codex review remains uncalled. All completion boxes remain open pending actual XML/raw evidence. Shared completion documents are Root-owned and untouched.

## First Actions failure, before experiment

PR221 headf2ffe2e, general run39627945741, product job119318107525, artifact12356789882, checkout117cbe6 (merge into8f0d2ee), seed1792597335136. Compilation succeeded. All four new cases failed at dependency injection before setup: CommonErrorHandler matched both kafkaErrorHandler and deadLetterErrorHandler. Target XML contains zero D078_RETRY rows. No conflict/backoff/DLQ result was measured. Original target XML and job log are preserved as gzip with raw hashes in evidence/D-078-retry-load/first-failure.json; no nonfixture credentials identified. Add explicit kafkaErrorHandler qualifier and assert it is the selected real container's handler. No retry/stock/timing criterion is relaxed. New Actions required.
