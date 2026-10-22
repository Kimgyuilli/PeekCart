# D-078 retry experiment audit

## Planning

별도 Codex 리뷰 미호출. Read current listener, reservation/inventory path, actual handler/backoff, existing policy and lock-boundary tests, shared container/cleanup rules, ADR0025/0027. Base8f0d2ee. Actual cached Spring Kafka3.3.14 javap verified supported backoff-function/RetryListener APIs and non-null reset requirement. No execution or performance result yet; P1-P4 remain open. Shared TASKS/progress and historical ADR bodies untouched.

## Draft implementation, execution pending

Added only StockRetryLoadIntegrationTest and this plan/audit. Four parameterized cases, one observation per mode/scenario. Uses actual configured BackOff and unchanged real BackOffHandler/sleeper; persistent inter-attempt monotonic intervals must also reflect the returned delays (10ms allowance for wall-clock sleep granularity). Three actual child consumers must each hold one partition before publishing. Actual first reads must show three distinct threads, version0, one barrier generation; actual optimistic failures must occur. Persistent failures are explicitly injected after a real read, not represented as natural contention.

Local integration-test-container lint, docs-preservation against base8f0d2ee, writing lint, consistency precheck and diff whitespace checks passed. No local Gradle/Docker or runtime result. Ship preflight blocked on unverified P1-P4 as expected; draft/Actions path is authorized. Separate Codex review remains uncalled. All completion boxes remain open pending actual XML/raw evidence. Shared completion documents are Root-owned and untouched.

## First Actions failure, before experiment

PR221 headf2ffe2e, general run39627945741, product job119318107525, artifact12356789882, checkout117cbe6 (merge into8f0d2ee), seed1792597335136. Compilation succeeded. All four new cases failed at dependency injection before setup: CommonErrorHandler matched both kafkaErrorHandler and deadLetterErrorHandler. Target XML contains zero D078_RETRY rows. No conflict/backoff/DLQ result was measured. Original target XML and job log are preserved as gzip with raw hashes in evidence/D-078-retry-load/first-failure.json; no nonfixture credentials identified. Add explicit kafkaErrorHandler qualifier and assert it is the selected real container's handler. No retry/stock/timing criterion is relaxed. New Actions required.

## Second Actions failure, invalid fixture event IDs

PR221 head1881ddd, run39628723409, product119321327775, artifact12357673900, checkoutbf1b7ef into8f0d2ee, seed1792597702177. Four cases failed; target XML contains108 diagnostic rows. Transient modes never logged inventory arrivals and stock stayed100 rather than97. Persistent modes delivered four times with actual fixed delays1000/5000/30000ms and jitter1168/5865/42650ms; failures were all classified nonoptimistic, so these are real transport/delay observations but not optimistic-conflict/DLQ correctness success. Exact originals and hashes are in second-failure.json.

The generated fixture eventId was44characters (`retry-` plus UUID plus suffix); actual processed_events.event_id and stock_reservations.source_event_id are VARCHAR36. This is a verified invalid input and a likely cause of the pre-inventory failure; the old diagnostic output lacks the exception chain, so its precise runtime cause is not retrospectively asserted. Correct only the fixture IDs to canonical UUID36, emit the first unexpected exception stack, and retain all original optimistic/DB/retry/timing/DLQ assertions. No production code or schema change. Re-run Actions; P1-P4 remain unverified. PR: https://github.com/Kimgyuilli/PeekCart/pull/221.

## Third product observation, scoped success

Sourceb933806, run39630546332, product119327788981, artifact12359776352, actual checkoutb09fd148271c5ea7f57a9fd334e655bee4dbb9cb into7320ffdd4a72a1bdde77bc9424c8d11ecff18ba3, seed1792598420536. Actual product shard SUCCESS:47suites/240cases/0fail/0error/0skip. Target4/0,81 diagnostic rows. Original XMLs and job log, member/file/source hashes and selected observations are in evidence/D-078-retry-load. No local heavy execution.

Both transient modes established three real first-read participants/version0/one barrier generation and converged. Fixed3 versus jitter2 optimistic failure callbacks over6 versus5 original deliveries; three additional duplicate deliveries per mode had no second effects. Both persistent injected controls produced4attempts/oneDLQ with exact original coordinates and stock100/zero reservation,outbox,processed rows. Actual returned sleeps and monotonic inter-attempt intervals are preserved; no sleeper substitution. All four cleanup rows say stopped=true. Database/header values are passing executed assertions, not an independent DB/header dump. Total case elapsed includes unequal startup. One observation per mode cannot establish improvement or production rates. P1-P3 are complete only for this finite fixture; P4/fullCI/exact-head independent review remain open. Root owns shared completion records; D078⑤ sustained-load/policy inputs remain incomplete.

This final evidence/doc update leaves the measured Java source byte-identical to b933806; no new product measurement is attributed to the documentation commit. Current whole CI still pending at collection time. Separate Codex review uncalled.
