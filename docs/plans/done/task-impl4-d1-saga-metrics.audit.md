# ④-d 계획 리뷰 audit (d-1 / d-2 공통 이력)

## 2026-08-26 16:26 — 라운드 1
- 항목: **12건 (P0:4, P1:7, P2:1)** · 반영 12 / 기각 0
- **뒤집힌 전제 (P0 4건, 전부 코드로 직접 확인)**:
  1. §4 E2E SQL 이 **실제 스키마와 불일치** — `inventories.quantity`(실제 `stock`, `V1__init_product.sql:35`) · `stock_reservations.refund_status`(실제 `refund_result`/`refund_resolved_at`/`refund_failure_code`, `V4:11-13`). **첫 줄에서 실패할 쿼리였다**
  2. **매트릭스 게이트가 논리적으로 불가능** — 매트릭스가 기대 행의 유일한 입력이면 행 삭제는 검사 대상만 줄여 통과한다. "행을 지우면 실패" 주장이 자기모순 → lint 내부 required-ID 정본 도입
  3. **`TossPaymentClient` 가 baseUrl 하드코딩**(`:36-44`) — PG 대역이 없어 환불 성공 체인 E2E 도달 불가 → 트리거 구간까지로 축소
  4. **CI 이미지가 PR 에 전달되지 않는다** — `images` 는 6개 별도 matrix 러너이고 artifact 업로드가 `if: github.event_name == 'push'`(`ci.yml:137-145`). `needs:` 는 순서만 보장 → PR 에서도 업로드 + e2e 잡에서 `docker load`
- 그 외 P1: §2.1 "메트릭 0건" 자기모순(환불 5종 인정하면서 제목이 0건) · order 보상 계측 지점 오지정(엔티티가 아니라 `OrderEventConsumer:227-238`) · **Counter 로는 alert 불가**(잔량 Gauge 필요) · §4 쿼리 미완성·기대 scalar 부재·`processed_events` 는 `(event_id, consumer_group)` 유니크 · CI lint 가 gradle build **보다 먼저** 실행돼 JUnit XML 부재 · 부모 P14 필수 행 미승계 · **alert 발화 검증은 수행 수단 없음**(ADR-0015:72-74 가 범위 밖 규정)
- 그 외 P2: "cross-service E2E 인프라 0건" 과장 — compose·smoke 하네스·Testcontainers 는 이미 있다. 정확한 공백은 "여러 서비스를 동시 기동해 하나의 saga 를 검증하는 하네스"
- raw: `.cache/codex-reviews/plan-d-r1.json`

## 2026-08-26 16:35 — 라운드 2
- 항목: **9건 (P0:2, P1:5, P2:2)** · 반영 9 / 기각 0
- **통과 확인**: 1R 에서 고친 스키마명·테이블·컬럼·consumer group 문자열이 마이그레이션 재대조에서 **전부 실제와 일치**
- **1라운드 수정이 만든 새 결함**:
  1. **(P0)** 트리거 구간 축소가 만든 구멍 — `RefundDispatcher` 는 `@Scheduled` 이고 **비활성화 프로퍼티가 없다**. fence 가 생기면 dispatcher 가 집어 **CI 러너가 `api.tosspayments.com` 실제 운영 API 로 나간다**. `REQUESTED 또는 CLAIMED` 단언도 플래키(4xx→`FAILED`, 타임아웃→`UNRESOLVED`, Order 원장은 `REFUND_FAILED` 로 경합)
  2. **(P0)** 증적 프로토콜과 CI 실행 순서 모순 — P9 가 모든 행의 실행 증적을 검사하는데 build 잡에는 E2E manifest 가 아직 없다
  3. JUnit XML 의 `testcase@name` 은 **메서드명이 아니라 `@DisplayName` 문자열**이다 — 제 프로토콜로는 실행된 테스트도 못 찾는다
  4. `observability-promql-lint` 의 라벨 invariant 는 **기존 UID 4종에만** 적용 — 신규 alert 는 라벨을 빼거나 삭제해도 통과한다
  5. **부모 P12 의 예약 실패 체인 누락** — 그러면서 ④ 완료를 선언했다
  6. `docker-compose.yml` 이 `container_name`·호스트 포트 고정 → project 이름만 바꿔도 격리 안 됨
  7. 시나리오 ② #4 에 `<producer>` placeholder 잔존 — "완전한 쿼리 정본" 선언과 어긋남
- **영역 분포**: E2E 하네스·시나리오 5건 · 매트릭스 게이트 3건 · **메트릭/alert 1건**
- raw: `.cache/codex-reviews/plan-d-r2.json`

## 2026-08-26 16:40 — 분할 결정
- 9건 중 **8건이 E2E + 게이트 절반**에 있고 메트릭(P11)은 1건뿐이다. P11 은 E2E 인프라에 의존하지 않는다.
- → **④-d-1**(P11 관측성, 즉시 착수) / **④-d-2**(P12 E2E · P14 게이트 · P15 종결) 로 분할.
- ④-d-2 가 흡수한 선결 과제: `RefundDispatcher` 비활성화 프로퍼티 · E2E 전용 compose(격리) · 예약 실패 체인 시나리오 · `observability-promql-lint` required-UID 확장(신규 alert 분) · 증적 프로토콜 3분기.
- **④ 종결은 ④-d-2 소관이다** — d-1 은 ④ 를 닫지 않는다.

## 2026-08-26 19:38 — diff 리뷰 라운드 1 (PR #91)
- 항목: **5건 (P0:0, P1:4, P2:1)** · 반영 5 / 기각 0
- **통과 확인**: Gauge 는 Micrometer 1.15.10 기본 weak reference 지만 Spring singleton repository 를 ApplicationContext 가 강하게 보유해 GC→NaN 위험 없음. DB 조회도 scrape 주기(15s)이고 `(status, occurred_at)`·`(status, detected_at)` 인덱스가 있어 부하 결함 아님. static `orderIdSeq` 도 병렬 실행 설정이 없고 매 테스트 DB 정리라 격리 문제 미재현
- **반영 내역**:
  1. **(P1) Counter 가 커밋 전에 증가** — 계측 지점이 전부 `@Transactional` 안이라 이후 flush/commit 실패 시 DB 는 롤백되고 카운터만 남는다. 내 계획 §4 의 "실제 전이가 일어났을 때만" 계약을 스스로 깬 것 → `CommitAwareMetrics`(관측성 모듈) 신설, 트랜잭션 활성 시 `afterCommit` 으로 이연
  2. **(P1) lint 우회 3경로** — ① prometheus entry 를 전부 지우면 검사 루프가 안 돌고 required 검사는 통과 ② 메트릭을 substring 으로 봐서 `잘못된식 + 0 * 계약메트릭` 우회 가능 ③ 부정 matcher(`application!=`)로 서비스 제외 가능 → entry 수 정확히 1 강제 · 메트릭 이름 집합 정확 일치(by/matcher 제거 후 추출) · 부정 matcher 금지. **self-test 4종 → 6종**
  3. **(P1) 누락 양성 테스트** — sweeper "3건 회수 시 +3" 과 product `saga.compensation.detected` 양성·중복 음성이 없었다. §6 의 "§4 전 행이 테스트로 고정됨" 이 false-green 이었다 → 둘 다 추가
  4. **(P1) 타임아웃 테스트가 실제 잡을 안 돌린다** — 메트릭 객체를 직접 호출해서, 스케줄러에서 계측을 지워도 통과했다. `OrderTimeoutSchedulerTest` 도 mock 만 넣고 verify 가 없었다 → 3개 잡 각각에 `verify` 추가(성공 건수만·경합/충돌 건 제외·사유 교차 금지) + **미확정 예약 잡 테스트 신설**(3사유 중 유일하게 없었다)
  5. **(P2) 레이어 역전** — `StockReservationService`(application)가 `infrastructure.metrics` 를 직접 import. CLAUDE.md 의존 방향 위반 → `application/port/SagaMetricsPort` 신설, infrastructure 가 구현(`SlackPort` 선례). order 는 계측 지점이 전부 infrastructure 라 해당 없음
- **부수 이동**: `CommitAwareMetrics` 는 `:common` 에 micrometer 의존이 없어 `:peekcart-common-observability`(ADR-0009 소유)로 배치, `spring-tx` 추가
- raw: `.cache/codex-reviews/diff-d1-r1.json`

## 2026-08-26 21:48 — diff 리뷰 라운드 2 (PR #91)
- 항목: **5건 (P0:0, P1:2, P2:3)** · 반영 5 / 기각 0
- **통과 확인**: `SagaMetricsPort` 의존 방향과 order 가 포트를 두지 않은 근거(계측 지점이 전부 infrastructure)는 타당. sweeper 의 `reclaimed` 를 클로저로 잡는 것도 단일 트랜잭션 커밋 후 증가라 의미가 맞음. 신규 통합테스트가 `@Transactional` 롤백 방식이 아니라 실제 커밋 후 값을 관측하는 것도 확인됨
- **1라운드 수정이 만든 새 결함**:
  1. **(P1) `afterCommit` 예외가 호출자에게 전파** — 그 시점엔 DB 가 이미 커밋돼 되돌릴 수 없는데 호출자는 커밋 실패로 받는다. `OrderEventConsumer` 같은 Kafka listener 안이면 **이미 커밋된 이벤트를 재처리**한다. 관측성 실패가 비즈니스 결과를 바꾸는 것 → try/catch 로 격리, 로그만 남기고 메트릭만 유실
  2. **(P1) `0 * metric` 우회** — 1R 에서 도입한 "메트릭 이름 집합 정확 일치" 를 그대로 통과하면서 Grafana 의 `$A > 0` 은 영원히 거짓이 된다. 이름·라벨 집합 검사로는 구조적으로 막을 수 없다 → **식 형태 자체를 정확 문자열로 고정**(AST 파서 대신, 우리가 소유한 alert 2종에 한해). self-test 6종 → 8종(`0 *` · status 필터 삭제)
  3. **(P2) 계획 P5 의 "상태 필터 검사" 미구현** — compensation alert 에 `status` 필터가 아예 없었다 → alert 에 `status=~"open|refund_failed"` 추가하고 lint 가 고정. 상태가 추가돼도 조용히 alert 대상에 들어오지 않는다
  4. **(P2) order `saga.compensation.detected` 가 테스트로 고정되지 않음** — 계획 §4 는 product·order 둘 다 요구하는데 order 는 원장 행만 봤다. consumer 에서 계측을 지워도 통과 → `OrderPaidButCancelledIntegrationTest` 에 신규 +1 · 재소비 +0 단언 추가
  5. **(P2) `CommitAwareMetrics` 자체 테스트 부재** — 1R 수정의 핵심인 "롤백이면 증가 0" 이 고정되지 않아 **즉시 증가 방식으로 되돌려도 전부 통과**했다 → 계약 테스트 7건 신설(무트랜잭션 즉시 증가 · 커밋 전 0/후 증가 · 롤백 0 · 예외 롤백 0 · REQUIRES_NEW 독립 · 증가량 0 무시 · 계측 예외 미전파)
- **실패 1건(수정)**: 추가한 카운터 단언이 절대값이었는데 `MeterRegistry` 가 컨텍스트 공유라 이전 테스트 값이 누적됐다(1.0 기대, 2.0 관측) → 델타 단언으로 교체
- raw: `.cache/codex-reviews/diff-d1-r2.json`

## 2026-08-26 22:35 — diff 리뷰 라운드 3 (PR #91, 상한)
- 항목: **3건 (P0:0, P1:1, P2:2)** · 반영 3 / 기각 0 · 라운드별 추세 **5 → 5 → 3**
- **통과 확인**: compensation alert 의 `status=~"open|refund_failed"` 가 `OrderSagaMetrics` 의 실제 Gauge 태그와 일치 · `catch(Exception)` 이 `OutOfMemoryError` 같은 `Error` 를 삼키지 않아 범위 적절 · `SagaMetricsPort` 의존 방향 · sweeper 의 `reclaimed` 클로저 의미
- **2라운드 수정이 남긴 것**:
  1. **(P1) 계약 강도 비대칭** — 2R 에서 `0 * metric` 우회를 막을 때 **신규 2종만** 식을 고정해, 같은 우회가 기존 4종(`high-error-rate`·`slow-response`·`target-down`·`scrape-absent`)에 그대로 남았다. 약한 쪽이 뚫리면 강한 쪽을 지킨 의미가 없다 → **모든 필수 alert 의 prometheus 식을 정본으로 고정**(`ALERT_EXPR_CONTRACTS`, scrape-absent 5종은 ground truth 에서 생성). self-test 8종 → 10종(기존 alert `0 *` · entry 삭제)
  2. **(P2) 유실 로그 정보 부족** — meter 이름만 남겨 어떤 태그의 몇 건이 유실됐는지 모른다. 이 메트릭들은 태그별 시계열이고 `amount` 가 사건 건수(sweeper 회수·타임아웃 취소)다 → `counter.getId()` 전체 + `amount` 기록
  3. **(P2) DLQ 메트릭 계약이 order 에만 고정** — 구현은 4서비스 복제인데 테스트가 order 뿐이라, 다른 3곳에서 Gauge 등록이나 조회 함수가 사라져도 아무 테스트도 실패하지 않았다 → 계약 테스트를 3서비스에 복제
- **루프 종료**: 계획 상한 3회 도달. **잔여 한계 명시** — 식 정확 일치는 문자열 비교이지 PromQL 의미 분석이 아니다. `0 *` 같은 알려진 패턴은 막지만, 새 무력화 패턴은 정본을 고치는 순간 함께 들어올 수 있다. 완전한 방어는 AST 기반 검사이며 본 PR 범위 밖이다.
- raw: `.cache/codex-reviews/diff-d1-r3.json`
