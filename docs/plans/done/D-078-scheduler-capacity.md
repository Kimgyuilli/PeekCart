---
grade: M
---
# D-078 실제 Boot scheduler의 유한 capacity 검사

## 1. 명제와 코드 전제

실제 Boot scheduler/제품 ConfigData 대신 수동 executor만 검사하거나, 유한 probe 성공을 실제7잡의 부하 적정성으로 해석하면 미완이다. pool4의3개 점유 중 남은 slot 진행,4개 점유 중 정체와 release 회복, pool1 음성을 bounded 신호로 구별한다.

| 전제 | 직접 확인 |
|---|---|
| base | main189 7915f26에서 own isolated scheduler-capacity tree 생성. PR218/retry-load tree 변경 없음 |
| 설정 | product-service application.yml의 spring.task.scheduling.pool.size=4, common SchedulingConfig가 조건부 @EnableScheduling을 등록한다. 수동 TaskScheduler 빈은 새로 만들지 않는다. |
| 기존 검사 | StockSchedulerWiringIntegrationTest는 실제 sweeper의 만료/미만료 규칙과 발화를 검사하나 pool 점유 실험은 아니다. 기존 테스트는 수정하지 않는다. |
| 현재 범위 | product/common/messaging source에서 @Scheduled7개를 찾았다. 실제 payload/DB/Kafka 비용이나 동시경합은 이 작은 context에서 재현하지 않는다. 등록 수/빈 검사는 오직 이 fixture의 probe에 한정한다. |
| seed | product testSeed는 JUnit class 순서만 고정하며 thread/task scheduling을 고정하지 않는다. 새 검사는 latch로 시작/점유/release를 통제한다. |

Root가 실제 product ConfigData/production SchedulingConfig/Boot auto-configuration + 테스트 probe만의 최소 범위를 승인했다. M은 공유 scheduler 설정 계약을 확인하는 CI 검사다. 제품/정책/ADR 변경은 없다. Boot3.5.12의 실제 test/autoconfigure jar에서 ConfigDataApplicationContextInitializer/TaskSchedulingAutoConfiguration API 존재도 확인했다.

## 2. 작업

- [ ] P1. 새 product 단위 테스트만 추가한다. ConfigData로 실제 product application.yml을 읽고 actual Boot taskScheduler의 configured core size4와 실제 등록 probe/메서드를 확인한다. 수동 executor/DB/Kafka/Testcontainers/새 workflow는 없다.
- [ ] P2.3blockers 시작을 전부 확인한 동안 due task와 @Scheduled probe가 남은 slot에서 진행한다.4blockers가 전부 시작된 동안 due task/probe가 정체하고 release 후 회복한다. pool1+1blocker도 같은 음성/회복을 확인한다. watchdog/latch/실패전파/finally release와 Future 완료를 검증해 우연 timing PASS를 막는다.
- [ ] P3. 기존 일반 Actions product shard에서 실제3case/XML/log를 확인하고 raw/환경/판정범위만 보존한다. 로컬 Gradle/Docker/full suite는 실행하지 않는다. 원 config/Java·workflow 보존과 필요한 경량 source 확인만 수행한다.

## 3. 해석과 후속

결과는 해당 fixture의 유한 capacity 경계다. 실제7잡 비용/동시 scheduler contention/운영 적정성/허용 TPS나 지연 기준은 미측정이다. D078⑥ 전체해결이나 GKE/노드/Billing 결론이 아니다. 실제 sweeper DB/락/회수 계약은 기존 검사와 구분하고, jitter/DLQ 측정은 별도 PR로 남긴다. root 소유 TASKS/PHASE/master는 수정하지 않는다. 별도 Codex 리뷰 미호출.

## 첫 Actions와 좁은 등록 검사 교정

2a3b2fe/run39622466849의target3case는모두line75의Runnable타입가정에서실패했다. 실제wrapper는Task.OutcomeTrackingRunnable이며기존config/core/단일빈/등록수assertion까지는통과했다. 점유검사/D078_SCHEDULER marker는0으로capacity결과는미측정이다. 원XML/log/API metadata/hash를보존한다.

Root 승인교정은공개Runnable.run으로등록작업이정확probe.tick을호출하는지검사하는것이다. 현재검사thread만별도counter에세어다른schedulerthread의자동tick으로target검사가오탐하지않게한다. 다른probe/빈등록음성은거부하고, 직접검사이후새자동tick을따로확인한다. fragilewrapper타입/private delegate검사는없다. 기존3/4blocker/pool1/release기준은불변이며새Actions3case실측전P1–P3미완유지다.

## 완료 기록

교정 source의 [run39625382103](https://github.com/Kimgyuilli/PeekCart/actions/runs/39625382103)(head `de18d00`, 실제 checkout `a07da44`)에서 product shard의 `SchedulerPoolCapacityTest` 3 case가 모두 통과했다(실패·오류·skip 0). marker는 pool4/blocker3 `stalled=false`, pool4/blocker4와 pool1/blocker1 `stalled=true`다. 이 artifact와 checkout을 직접 확인한 [독립 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/220#issuecomment-6319781812)는 차단 없음이었다. 2026-10-24에 이 run의 target XML·job log·API metadata를 [증거 bundle](../../evidence/D-078-scheduler-capacity/README.md)에 원바이트 gzip으로 보존했다.

최신 main을 합친 최종 head `a7f7b25`는 작성 patch의 patch-id가 `de18d00`과 같고 [PR CI39674443598](https://github.com/Kimgyuilli/PeekCart/actions/runs/39674443598)이 성공했다. 병합 `8fbd658`의 [main CI39679620260](https://github.com/Kimgyuilli/PeekCart/actions/runs/39679620260)은 전체 테스트·E2E·gate·여섯 publish가 성공했다(2026-10-22). 이로써 P1–P3을 충족한다. 체크박스는 원문 그대로 둔다. §3의 미측정 범위(실제 7잡 비용·동시 경합·운영 적정성·TPS/GKE)와 D-078 ⑥ 전체는 이 완료에 포함하지 않는다.
