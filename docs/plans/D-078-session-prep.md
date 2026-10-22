---
grade: M
---
# D-078 과금 세션 사전 준비 (부분 작업)

## 1. 명제와 코드 전제

D-078 의 GKE 측정은 한 과금 세션으로 묶는다. 세션 안에서 계측 공백을 발견하면 클러스터 시간이 곧
과금이므로 수치를 잃는다. 이 작업은 무료로 메울 수 있는 계측·조건 공백만 메운다. 측정 결과를
만들지 않으며 D-078 어느 항목도 닫지 않는다.

기준 코드 `5567108`.

| 전제 | 코드 대조 |
|---|---|
| ⑤ 충돌 수 출처 | `d002bc-gke-20260917.md` 의 충돌 7건은 로그로 셌다. `ProductKafkaConfig` 의 에러 핸들러에 RetryListener 가 없고, product 메트릭은 `saga.*` 와 `dlq.backlog` gauge 뿐이다. replicas=3 이면 Pod 로그를 cleanup 전에 모두 모아야 한다. |
| ⑥ 풀 관측 | `SchedulingConfig` 는 조건부 `@EnableScheduling` 만 둔다. Boot 3.5.12 `TaskExecutorMetricsAutoConfiguration` 은 `ThreadPoolTaskScheduler` 빈을 `ExecutorServiceMetrics` 로 바인딩하고 `name` 태그에 빈 이름을 쓴다(jar javap 확인). 이 가정을 고정한 테스트가 없다. |
| 측정 overlay | `gke-d002bc` 의 mysql patch 는 재현용으로 500m 이다. ADR-0027 이후 조건(2000m)의 주문·경합 측정에 쓸 이름 있는 overlay 가 없다. |
| 재시도 실험 테스트 | `StockRetryLoadIntegrationTest` 는 `setRetryListeners` 로 listener 를 교체하고 cleanup 에서 비운다. 운영 listener 를 추가하면 이 테스트가 그것을 지운다. |

등급 M: product 운영 코드에 메트릭을 추가하고 측정 overlay 를 하나 더 둔다. 정책·스키마·서비스 경계는
바꾸지 않아 새 ADR 은 없다.

## 2. 작업 항목

- [x] P1. `ProductKafkaRetryMetrics`(RetryListener)를 추가하고 에러 핸들러에 등록한다. `kafka.listener.delivery.failed`(topic, cause=optimistic_lock|other)와 `kafka.listener.recovered`(topic). cause 는 예외 클래스명 대신 두 값으로 묶어 cardinality 를 닫는다.
- [x] P1-T. 재시도 실험 테스트가 운영 listener 를 지우지 않고 옆에 덧붙이게 고친다. 운영 배선에 메트릭 listener 가 정확히 1개인지 확인하고, 실제 브로커·DB 에서 카운터 차분을 단언한다. 단위 테스트로 낙관락 판정과 topic 분리를 본다.
- [x] P2. 스케줄러 배선 통합 테스트에 executor 메트릭 검사를 추가한다. 실제 발화 뒤 `executor.completed` 증가와 `executor.pool.core = 4` 를 본다.
- [x] P5. `k8s/overlays/gke-d002bc-mysql-2000m` 을 추가하고 namespace lint·lint-health selftest 목록에 등록한다.
- [x] TASKS. D-078 에 ⑨(README 근거 캡처 기준)와 선택 병행 항목(D-065 ⑤, D-092)을 적는다.

## 3. 검증 방법

- P1: 단위 3건, `StockRetryLoadIntegrationTest` 4건(FIXED/JITTER × 일시/지속). 일시 충돌은 실패 카운터 차분 = 관측 충돌 수, recovered 0. 지속 충돌은 실패 4, recovered 1.
- P2: `StockSchedulerWiringIntegrationTest` 3건.
- P5: 상위 overlay 와 렌더 diff 가 MySQL `limits.cpu` 한 줄인지 확인. `kustomize-namespace-lint.sh`, `ci-lint-health-selftest.py`.
- 기존 `StockConflictRetryPolicyTest`, `ProductKafkaConfigTopicConfigContractTest` 회귀. integration-test-container·observability SSOT·PromQL lint.

## 4. 범위 밖

- 세션 대시보드와 캡처 체크리스트(⑨)와 세션 계획서는 후속 작업이다.
- 1,000 VU 동시 주문 재측정은 넣지 않는다. Phase 4 `d002bc` ①(MySQL 2000m, 실패율 0%, 9,599/9,599)과 경합 run(음수 재고 0)이 README 목적의 질문에 이미 답한다. 부하 모양이 다르다는 점은 README 에 조건으로 적는다.
- ⑧ 은 이 overlay 로 닫지 않는다. patch 로 2000m 을 명시하므로 base 경로가 아니다.
- 다른 서비스의 에러 핸들러에는 메트릭을 붙이지 않는다. ⑤ 의 대상은 product 재고 경로다.
