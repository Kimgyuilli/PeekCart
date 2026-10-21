---
grade: M
---
# D-078: finite Kafka inventory retry experiment

## 명제

Actual optimistic conflicts, transactional convergence, and exhausted-record DLQ coordinates must be observed through the configured listener and real broker/database. Policy-only calls, a substituted sleeper, or a jitter improvement asserted without measurement leave this work incomplete. This is a finite fixture, not the D-078 production load/sizing completion claim.

## 코드 전제

Base: `8f0d2ee091bc2ce08a4c19e2ca3678d406559d34`.

| Source | Verified premise |
| --- | --- |
| `StockReservationConsumer.handleOrderCreated` | Real `@KafkaListener` and outer transaction, idempotency plus reservation and stock/outbox effects. |
| `StockReservationService.reserve` / `InventoryService` | Precheck and mutation each read inventory. Synchronize the first real read in each of three consumer threads before any update; do not gate after IDENTITY reservation insertion. |
| `ProductKafkaConfig` | Actual common handler, three jittered delays (1s/5s/30s, ratio0.5), real sleeper, DLQ recoverer. Default listener concurrency is one. |
| `OrderKafkaConfig` | Producer-owned `order.created` has three partitions; DLQ has one. Fixture provisions these and changes only the selected stopped test container to concurrency3. |
| `JitteredSequenceBackOff` | Uses ThreadLocalRandom; testSeed controls class order, not jitter or thread scheduling. |
| `KafkaRetryContract` | Replaces sleep; cannot establish real retry timing or load behavior. |
| Spring Kafka3.3.14 actual cached jar | `setBackOffFunction` and RetryListener are available. Null backoff function is rejected; restore the configured BackOff through a non-null function after stopping the container. |
| SharedContainers / AbstractIntegrationTest | Reuse module singleton containers, assignment wait, DB/topic cleanup; manual listener start requires DirtiesContext. Slack is fixture-only. |

M is retained conservatively for a real asynchronous broker/database experiment; only product test code and its own documentation change. No shared API, production policy, schema, dependency, or architecture decision changes; no new ADR.

## 작업 항목

- [ ] P1. Add a finite same-workload comparison: three partition-specific orders for one product, actual first-read gate, actual configured jitter versus fixed1s/5s/30s with unchanged retry count and real sleep. Record arrivals, optimistic failures, attempts, issued delays and elapsed time. Require real optimistic conflict and full transactional convergence for both; do not require jitter to win.
- [ ] P2. Run one persistent injected optimistic failure per mode through the same real listener/handler. Require four deliveries, three actual delays, one matching DLQ record with original topic/partition/offset/key/value, no stock/reservation/outbox/idempotency effects. Observe the DLQ partition to the broker end after consumer commit before claiming exactly one.
- [ ] P3. Publish duplicate successful events and prove no second stock/reservation/outbox effect. Verify bounded gate release, assignment of all three partitions, listener stop and DB/topic cleanup, and emit raw diagnostic rows on failures too.
- [ ] P4. Run the actual product Actions shard/full CI, inspect XML/raw rows, preserve exact source/run evidence, then report measured populations and limits for independent review.

## 검증 방법

The transient gate is a controlled contention stimulus after actual managed inventory reads; its three distinct participants and one generation must be recorded. Missing arrival or unrelated exception fails, rather than being counted as an optimistic conflict. Persistent injection is a transport/budget/DLQ control, not a measured natural conflict. Unchanged real sleeper and fixed counterfactual expose retry removal; four-delivery/coordinate/effect checks expose early recovery, duplicate DLQ and partial commit. Duplicates expose missing idempotency. No local Gradle/Docker; formal execution uses existing Actions product shard and its XML/log artifacts. Source/lint checks locally only.

## 미해결 / limits

One transient burst (three originals plus three duplicates) and one persistent record per mode, four parameterized cases total. Persistent cases retain real sleeps, with maximum configured delay sum54s (jitter) or36s (fixed), each commit wait bounded90s; the first-read gate is bounded15s. This is one observation per mode/workload, not repeated sampling. One finite burst per mode cannot estimate an improvement percentage, production conflict/DLQ rate, sustained capacity, acceptable TPS or the ADR-0025 pessimistic-lock promotion threshold. Test-only concurrency3 is an explicit stimulus, not current production concurrency or replica topology. Initial contention is controlled; later retry collisions are observed, not forced. Persistent failures are injected. Original barrier1Timeout+4Broken remains causally unresolved; PR218/PR220 have separate evidence. Scheduler pool4 adequacy, GKE CPU/requests/node contention, Billing and manifest apply remain outside this PR. ADR-0025 historical causal statements are not re-proven here.
