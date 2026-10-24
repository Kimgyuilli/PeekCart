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

- [x] P1. Add a finite same-workload comparison: three partition-specific orders for one product, actual first-read gate, actual configured jitter versus fixed1s/5s/30s with unchanged retry count and real sleep. Record arrivals, optimistic failures, attempts, issued delays and elapsed time. Require real optimistic conflict and full transactional convergence for both; do not require jitter to win.
- [x] P2. Run one persistent injected optimistic failure per mode through the same real listener/handler. Require four deliveries, three actual delays, one matching DLQ record with original topic/partition/offset/key/value, no stock/reservation/outbox/idempotency effects. Observe the DLQ partition to the broker end after consumer commit before claiming exactly one.
- [x] P3. Publish duplicate successful events and prove no second stock/reservation/outbox effect. Verify bounded gate release, assignment of all three partitions, listener stop and DB/topic cleanup, and emit raw diagnostic rows on failures too.
- [ ] P4. Run the actual product Actions shard/full CI, inspect XML/raw rows, preserve exact source/run evidence, then report measured populations and limits for independent review.

## 검증 방법

The transient gate is a controlled contention stimulus after actual managed inventory reads; its three distinct participants and one generation must be recorded. Missing arrival or unrelated exception fails, rather than being counted as an optimistic conflict. Persistent injection is a transport/budget/DLQ control, not a measured natural conflict. Unchanged real sleeper and fixed counterfactual expose retry removal; four-delivery/coordinate/effect checks expose early recovery, duplicate DLQ and partial commit. Duplicates expose missing idempotency. No local Gradle/Docker; formal execution uses existing Actions product shard and its XML/log artifacts. Source/lint checks locally only.

## 미해결 / limits

One transient burst (three originals plus three duplicates) and one persistent record per mode, four parameterized cases total. Persistent cases retain real sleeps, with maximum configured delay sum54s (jitter) or36s (fixed), each commit wait bounded90s; the first-read gate is bounded15s. This is one observation per mode/workload, not repeated sampling. One finite burst per mode cannot estimate an improvement percentage, production conflict/DLQ rate, sustained capacity, acceptable TPS or the ADR-0025 pessimistic-lock promotion threshold. Test-only concurrency3 is an explicit stimulus, not current production concurrency or replica topology. Initial contention is controlled; later retry collisions are observed, not forced. Persistent failures are injected. Original barrier1Timeout+4Broken remains causally unresolved; PR218/PR220 have separate evidence. Scheduler pool4 adequacy, GKE CPU/requests/node contention, Billing and manifest apply remain outside this PR. ADR-0025 historical causal statements are not re-proven here.

## Scoped result

Product Actions39630546332 at sourceb933806 passed47 suites/240cases, including four finite cases. [Raw evidence](../evidence/D-078-retry-load/README.md) preserves both earlier failures separately. Actual fixed/jitter natural failure callbacks were3/2 on three original records each, with6/5 original deliveries and three additional duplicate deliveries per mode; this single observation does not establish an advantage. Persistent control reached4deliveries/oneDLQ with original coordinates and zeroeffects in both. Full matching-head CI and independent review remain required. D-078⑤ sustained-load rate/promotion input and the broader D-078 remain incomplete.

## 완료 기록

[PR221](https://github.com/Kimgyuilli/PeekCart/pull/221)은 head `0efc727`에서 [독립 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/221#issuecomment-6321057146) 차단 없음과 같은 head의 [CI39632204255](https://github.com/Kimgyuilli/PeekCart/actions/runs/39632204255) 필수 job 전부 성공을 받았다. 최신 main을 합친 최종 head `f8e662f`는 작성 patch의 patch-id가 `0efc727`과 같고 [PR CI39671458522](https://github.com/Kimgyuilli/PeekCart/actions/runs/39671458522)가 성공했다. 병합 `b3a7c60`의 [main CI39674425123](https://github.com/Kimgyuilli/PeekCart/actions/runs/39674425123)은 1차 시도가 `images (gateway)`의 `lombok-1.18.44.jar` 다운로드 실패로 끝났고, 재시도에서 전체 테스트·E2E·gate·여섯 publish가 성공했다(2026-10-22). 이로써 P4를 충족한다. 체크박스는 원문 그대로 둔다. 위 미해결/limits와 D-078 ⑤ 지속 부하 충돌률·정책 입력, D-078 전체는 이 완료에 포함하지 않는다.
