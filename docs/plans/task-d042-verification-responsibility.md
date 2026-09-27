---
grade: M
---
# task-d042-verification-responsibility

서비스 경계 계약과 실제 스택 e2e 의 검증 책임 판정 (D-042, 입력: ADR-0031 · `docs/06-testing-strategy.md` §13-1-b)

**범위는 판정까지다.** e2e 단언·대조군을 옮기거나 지우지 않는다. 판정 결과 옮길 항목이 있으면
후속 부채 행으로 연다(그 작업은 여러 모듈의 테스트·runner·매트릭스를 함께 바꾸므로 L 이다).

## 1. 명제

다음 중 하나라도 성립하면 미완이다.

- 실제 스택 e2e 가 단언하는 것(시나리오 A~D 의 단언, readiness 5항, 대조군 ①~⑥)이 한 표에 전부
  올라 있지 않다. 각 행에 `saga_e2e.py`/`saga-e2e-smoke.sh` 의 위치가 없다
- 표의 각 행이 배선·로직 두 축으로 판정돼 있지 않다 — 배선은 **스택 전용**, 로직은 **JVM 중복**
  (같은 결함을 이미 JVM 테스트가 잡는다) 또는 **JVM 공백**(e2e 만 본다)
- "JVM 중복" 판정 중 하나라도 **결함 주입으로 확인되지 않았다.** 테스트 이름이 비슷하다는 것은
  같은 결함을 잡는다는 증거가 아니다
- 이 판정과 그 근거(무엇이 스택에 남고 왜 남는가)가 ADR 로 결정되지 않았다
- PR 검증 시간의 "전" 기준선이 실측으로 남아 있지 않다
- e2e 단언·대조군이 하나라도 제거됐다 (이 PR 의 범위 밖이다)

### 코드 검증 (계획 작성 시점, 2026-09-28)

| 전제 | 확인 | 결과 |
|---|---|---|
| 이벤트 payload 는 생산자·소비자가 같은 타입을 쓴다 | `common/src/main/java/com/peekcart/global/outbox/dto/*Payload.java` 11개 | 성립. 같은 커밋 안에서 필드 불일치는 컴파일이 막는다. 남는 틈은 서비스별 ObjectMapper 설정·토픽 이름·group id |
| 애플리케이션 서비스끼리 HTTP 로 호출한다 | `RestClient\|WebClient\|FeignClient\|RestTemplate` grep (`*/src/main`) | **불성립.** HTTP 경계는 gateway 라우팅(`JwksKeyRegistry`)과 Toss(`TossPaymentClient`, e2e 는 pg-stub) 뿐. TASKS 행의 "HTTP 생산자·소비자 호환성" 은 이 둘로 좁혀진다 |
| 서비스 단위 계약 테스트가 이미 있다 | `*Contract*Test.java` grep | 있다. `OrderCancelledEventContractIntegrationTest`, `*KafkaConfigTopicConfigContractTest` ×3, `DlqTopologyContractTest`, `DlqListenerGroupContractTest`, `DeadLetterAdminRouteContractTest` 등 |
| JVM 테스트는 실제 `@KafkaListener` 를 거친다 | ADR-0029, `app.kafka.listener.enabled` 기본 off | **대부분 아니다.** 소비자 테스트는 핸들러를 envelope 로 직접 부른다. 실제 group id·토픽 구독 배선은 e2e readiness(5) 와 대조군 ④ 만 본다 — 스택 전용 후보 |
| 대조군은 시나리오와 독립이다 | `saga_e2e.py` `control_*` · ADR-0031 D1 | 아니다. 대조군은 시나리오 단언의 검출력을 검증한다(`wait_reserved()` 공유). **시나리오 단언이 남는 한 대조군도 남는다** — 판정 단위는 대조군이 아니라 단언이다 |
| 부모 요구 중 이미 끝난 것 | D-041(#151) | 대조군 목록·실패 지점 특정 완료. 이 PR 은 그 목록을 입력으로만 쓴다 |

범위 변화: TASKS 행은 "옮기는 방안 검토 → 적용 여부 ADR 판정" 이었다. 사용자 결정(2026-09-28)으로
**적용은 제외**하고 판정만 한다. 등급 L → M.

### 전 기준선 (실측)

main run [36338959413](https://github.com/Kimgyuilli/PeekCart/actions/runs/36338959413) (#151 머지, `0bf7965`).
gate 는 payment 테스트 격리 결함으로 실패했으나(#152 에서 수정) e2e 잡은 둘 다 성공했다.

| 잡/단계 | 소요 |
|---|---|
| `e2e (scenarios)` 잡 | 321초 |
| `e2e (negative-control)` 잡 | 481초 |
| └ 음성 대조군 단계 | 429초 (#151 직전 main 658초) |
| `images` 최장 | 241초 (#151 직전 run 기준) |

## 2. 작업 항목

- [x] P1. e2e 단언 전수 목록. 시나리오 A~D 의 단언(각 `raise AssertionError`·`wait_for`), readiness
      (1)~(5), 대조군 ①~⑥ 을 한 행씩. 열: id · 주장 · 위치(파일:줄) · 스택 필요 요소(이미지/네트워크/
      실제 listener 배선/서비스 간 수렴)
- [x] P2. 행마다 두 축으로 나눠 판정한다. **배선**(이 단언이 지나는 서비스 간 경로 — 토픽·group·
      HTTP·이미지 설정)과 **로직**(도착한 입력으로 올바른 상태를 만드는가). 배선은 스택 전용이다.
      로직은 대응 JVM 증적을 찾아 JVM 중복(`classname#method` 까지) 또는 JVM 공백으로 적는다.
      행 전체가 옮길 수 있으려면 로직이 JVM 중복이고 **그 배선을 다른 e2e 단언이 이미 지나야** 한다.
      매트릭스(`saga-contract-matrix.tsv`)의 jvm 행과 대조한다
      **(정정 2026-09-28)** 초안은 행 단위 3분류(스택 전용 / JVM 중복 / 이동 가능)였다. 작업 중 거의
      모든 시나리오 단언이 배선과 로직을 함께 싣는다는 것이 드러나, 행 단위로는 "JVM 중복" 과 "스택
      전용" 이 동시에 참이 돼 분류가 성립하지 않았다
- [x] P3. 로직 "JVM 중복" 판정마다 결함 주입 1회. 프로덕션 코드에 그 단언이 겨누는 결함을 넣고 지목한
      JVM 테스트가 실패하는지 본다. 실패하지 않으면 **JVM 공백** 으로 재분류한다. 같은 코드 지점을
      겨누는 단언이 여럿이면 주입 1회로 묶는다. 주입 diff 는 커밋하지 않고 결과(주입 내용·실패한
      테스트)만 §4 에 남긴다
- [x] P4. 옮길 수 있는 행(로직 JVM 중복 + 배선이 다른 e2e 단언과 겹침)마다 e2e 에서 줄어드는 시간을
      적는다. 같은 체인의 뒤 단언이 앞 단언을 기다리는 구조라면 줄지 않는다. JVM 공백은 옮길 대상이
      아니라 **JVM 에 추가할 대상**이다(e2e 단언은 배선 때문에 남는다) — 후속 부채 후보로 적는다
- [x] P5. ADR-0032 작성. 결정: 실제 스택이 맡는 계약의 기준과 P2 분류표 요약. 옮길 항목이 있으면
      후속 조건(무엇을 먼저 확보해야 하는가)을 적고, 없으면 "재평가했고 유지" 를 산출물로 남긴다
- [x] P6. 문서 반영. `docs/06-testing-strategy.md` §13-1-b 끝의 "D-042 가 판정하는 입력" 문장을
      판정 결과 참조로 바꾼다(see ADR-0032). 이동 항목이 있으면 `docs/TASKS.md` 에 후속 부채 행을
      연다. D-042 행 상태는 `/done` 에서 갱신한다

## 3. 검증

- **P3 가 곧 검증이다.** "JVM 중복" 은 주입 → 지목 테스트 실패로만 인정한다. 주입 없이 이름으로 붙인
  판정이 표에 남으면 미완이다(명제 3). 주입 후 `git diff --stat` 이 비어 있는지 확인해 주입이 새지
  않았음을 본다
- 분류표 완전성: `saga_e2e.py` 의 `raise AssertionError` · `wait_for(` 호출 수와 P1 표의 해당 행 수를
  대조한다. 수가 다르면 누락이다
- 스택 전용 판정은 반례 질문 하나로 확인한다 — "이 결함을 서비스 하나만 띄워서 재현할 수 있는가".
  재현할 수 있으면 스택 전용이 아니다
- 이 PR 은 테스트·스크립트를 바꾸지 않는다. `git diff --stat origin/main` 에 `docs/` 밖 파일이 없어야 한다

## 4. 기록

### 4-1. 분류표 (P1·P2)

위치는 `scripts/e2e/saga_e2e.py` 줄 번호(`4a4e51b` 기준). **배선** 열은 그 단언이 지나는 서비스 간
경로이고 전부 스택 전용이다. **로직** 열의 `M*` 는 §4-2 의 결함 주입 id 다.

| id | 주장 | 위치 | 배선 (스택 전용) | 로직 |
|---|---|---|---|---|
| R1 | 볼륨이 이번 실행 것이다(run marker) | 177-180 | compose volume | 해당 없음 |
| R2 | migration 버전 집합이 기대와 같다 | 190-197 | 이미지에 실린 migration | 해당 없음 (JVM 은 classpath 로 적용, 이미지 누락을 못 본다) |
| R3 | 앱 4개 health UP | 218 | 이미지 기동 + compose env | 해당 없음 |
| R4 | 업무 토픽 10 + `.dlq` | 228 | 스크립트 사전 생성 | 해당 없음 |
| R5 | 필수 group 이 member·partition ≥ 1 | 258 | 이미지의 `@KafkaListener` 배선 | 해당 없음 (JVM 은 listener 기본 off, ADR-0029) |
| A0 | 상품 생성 → 단가 캐시 적재 (전제) | 280-320 | product→order `product.updated` | 미주입 — JVM 중복 주장 안 함 |
| A2 | 예약 RESERVED | 322-329 | order poller → `order.created` → product | JVM 중복 (M1) |
| A3 | 결제 준비 `ready_for_payment` | 411 | `order.created`·`stock.reservation.result` → payment | JVM 중복 (M5) |
| A4a | PG 4xx → 실패 확정 | 415-426 | payment → pg-stub HTTP | JVM 중복 (M11) |
| A4b | 응답 코드가 PAY-005 | 425-426 | 〃 | **JVM 공백 (M12)** — `PaymentControllerTest` 는 400 만 본다 |
| A5 | payments FAILED | 429 | 없음 (payment 내부) | JVM 중복 (M6) |
| A6 | orders CANCELLED | 434 | payment → order `payment.failed` | JVM 중복 (M8) |
| A7 | `order.cancelled` PUBLISHED + reason=PAYMENT_FAILED | 440-445 | order poller | JVM 중복 (M8·M10·M23) |
| A8·A9 | 예약 RELEASED + 재고 원복 | 449-460 | `payment.failed`/`order.cancelled` → product | JVM 중복 (M4) |
| A10 | `payment.failed` PUBLISHED | 470-471 | payment poller | JVM 중복 (M7) |
| A11 | 소비자 3곳의 `processed_events` (eventId 키) | 480 | `payment.failed` → group 3개 | JVM 중복 (M22, order 사본만 주입) |
| A12 | PAYMENT_FAILED 알림 | 490 | `payment.failed` → notification | **JVM 공백 (M13)** — `handlePaymentFailed` 테스트 없음 |
| B1·B2 | CANCELLED + reason=RESERVATION_FAILED | 533-543 | product → order `stock.reservation.result` | JVM 중복 (M9) |
| B3 | ORDER_CANCELLED 알림 (예약 실패발) | 549 | `order.cancelled` → notification | **JVM 공백 (M14)** — reason 분기 테스트에 RESERVATION_FAILED 가 없다 |
| B4 | RESERVED 잔여 0 | 559-560 | 없음 | JVM 중복 (M2) |
| B5 | 재고 불변 | 566-567 | 없음 | JVM 중복 (M3) |
| C1 | 환불 원장 1행 (fence) | 665-670 | runner → 요청 토픽 2개 → payment | JVM 중복 (M19) |
| C2·C4 | 환불 SUCCEEDED + `payment.refunded` PUBLISHED | 673, 684 | 이미지의 dispatcher 스케줄러 + pg-stub + poller | JVM 중복 (M16) |
| C3 | payments REFUNDED | 679 | 없음 | JVM 중복 (M15) |
| C5 | 취소 POST 1회 | 692-693 | pg-stub HTTP | JVM 중복 (M17) |
| C6 | Idempotency-Key 전송 | 695 | pg-stub HTTP | JVM 중복 (M18) |
| D1 | DLQ 원장 1행 (order 소유분만) | 736-745 | poison → `payment.failed` → 역직렬화 실패 → `.dlq` → intake | JVM 중복 (M21) |
| D2 | attempt_count=1 | 747-748 | 없음 | JVM 중복 (M20) |
| D3 | 식별자 6컬럼 non-null | 750-752 | 없음 | 미주입 — JVM 중복 주장 안 함 |
| E1·E2 | envelope 키 `payload`·`eventId` | 340, 353 | 없음 | JVM 중복 (M23) |
| ① | 시작 이벤트는 실제 poller 를 지난다 | `control_poller_*` | A2 의 검출력 | A2 가 남으므로 남는다 |
| ② | 예약은 product-service 소비로만 성립 | `control_product_down_*` | A2 의 검출력 | 〃 |
| ④ | readiness 가 listener 부재를 잡는다 | `control_readiness_*` | R5 의 검출력 | R5 가 남으므로 남는다 |
| ③ | B 는 예약 실패 취소를 본다 | `control_sufficient_stock_*` | B 의 검출력 | B 가 남으므로 남는다 |
| ⑤·⑥ | project 병렬 기동 · egress 격리 | `saga-e2e-smoke.sh` 405-473 | compose 토폴로지 | 해당 없음 |

완전성 대조(§3): `raise AssertionError`·`wait_for(` 52건 중 시나리오·readiness 단언은 위 표에 모두
대응한다. 나머지는 헬퍼 정의(`wait_for` 본체·`wait_reserved`)와 대조군 함수 내부다.

### 4-2. 결함 주입 (P3)

주입은 스크래치 스크립트로 했다. 파일 원문을 바꾼 뒤 지목 테스트를 `--rerun` 으로 돌리고 원문을 다시
썼다. 끝난 뒤 `git status` 에 `docs/` 밖 변경 0.

| id | e2e 행 | 주입 | 결과 | 잡은 테스트 |
|---|---|---|---|---|
| M1 | A2 | 예약 시 `decreaseStock` 제거 | 잡힘 | `StockReservationSagaIntegrationTest` (예약 후 취소 외 3) |
| M2 | B4 | 재고 부족 시 원장을 RESERVED 로 저장 | 잡힘 | `StockReservationSagaIntegrationTest#all-or-nothing` |
| M3 | B5 | 재고 부족 시 첫 품목 1개 차감 | 잡힘 | 〃 |
| M4 | A8·A9 | release 의 `restoreStock` 제거 | 잡힘 | `StockReservationSagaIntegrationTest` (예약 후 취소·double-release·역순 race) |
| M5 | A3 | `markReadyForPayment` 제거 | 잡힘 | `PaymentEventConsumerTest` |
| M6 | A5 | 영구 실패 시 `payment.fail()` 제거 | 잡힘 | `PaymentApprovalServiceTest$FinalizeApproval#영구 실패` |
| M7 | A10 | 영구 실패 시 `publishPaymentFailed` 제거 | 잡힘 | 〃 |
| M8 | A6·A7 | payment.failed 취소 사유를 USER_REQUESTED 로 | 잡힘 | `OrderEventConsumerTest`, `OrderCancelledEventContractIntegrationTest` |
| M9 | B1·B2 | 예약 실패 취소 사유를 USER_REQUESTED 로 | 잡힘 | `OrderEventConsumerTest` |
| M10 | A7 | `order.cancelled` payload reason 을 null 로 | 잡힘 | `OrderOutboxEventPublisherTest`, `OrderCancelledEventContractIntegrationTest` |
| M11 | A4a | 승인 4xx 를 TRANSIENT 로 분류 | 잡힘 | `TossPaymentClientTest` |
| M12 | A4b | FAILED 응답 코드를 PAY-001 로 | **통과** (payment 모듈 전체) | — |
| M13 | A12 | payment.failed 알림 타입을 PAYMENT_COMPLETED 로 | **통과** (notification 모듈 전체) | — |
| M14 | B3 | RESERVATION_FAILED 취소도 알림 스킵 | **통과** (notification 모듈 전체) | — |
| M15 | C3 | 환불 성공 시 `markRefunded` 제거 | 잡힘 | `RefundLedgerIntegrationTest#성공 확정` |
| M16 | C2·C4 | 환불 성공 회신 발행 제거 | 잡힘 | 〃 |
| M17 | C5 | 취소 성공 후 한 번 더 호출 | 잡힘 | `RefundExecutorTest` |
| M18 | C6 | 취소 요청의 Idempotency-Key 헤더 제거 | 잡힘 | `TossPaymentClientTest` |
| M19 | C1 | `uk_payment_refunds_order` 제거 (V4) | 잡힘 | `RefundLedgerIntegrationTest`, `CompensationRequestConsumerIntegrationTest` |
| M20 | D2 | DLQ 최초 적재 attempt_count 를 0 으로 | 잡힘 | `DeadLetterLedgerIntegrationTest` |
| M21 | D1 | DLQ 소유권 검사 제거 | 잡힘 | `DeadLetterLedgerIntegrationTest#남의 group 실패분은 적재하지 않는다` |
| M22 | A11 | order `IdempotencyChecker` 의 save 제거 | 잡힘 | `OrderCancelledEventContractIntegrationTest` |
| M23 | E1·E2 | envelope `payload` 를 `data` 로 직렬화 | 잡힘 | `OrderCancelledEventContractIntegrationTest` |

### 4-3. 옮길 수 있는 행과 시간 (P4)

- **로직은 20건이 이미 JVM 에 있다.** 그러나 그 행들 거의 전부가 배선을 함께 싣는다. 배선이 없는 행
  (A5·B4·B5·C3·D2·E1·E2)만 로직 측면에서 e2e 에서 빼도 검출력이 줄지 않는다
- **그 행들을 빼도 시간이 줄지 않는다.** 모두 같은 체인의 다른 대기 뒤에 붙은 조회 1회다
  (B4·B5·D2·E1·E2 는 대기 없는 조회, A5·C3 는 뒤 단언 A6·C4 가 어차피 기다린다)
- **시간은 단언이 아니라 스택 기동에 있다.** run 36338959413 `durations.tsv`:

  | 구간 | scenarios 잡 | negative-control 잡 |
  |---|---|---|
  | infra-up | 36초 | 26초 |
  | topic-precreate | 49초 | 67초 |
  | app-up (4개 **순차**) | 72초 | 84초 |
  | readiness | 2초 | 2초 |
  | 시나리오 a·b·c·d / 대조군 | 82초 (18·14·12·38) | 234초 |

- **JVM 공백 3건(M12·M13·M14)은 옮길 대상이 아니라 JVM 에 추가할 대상이다.** e2e 단언은 배선 때문에
  그대로 남는다. 추가하면 결함을 PR 의 `test` 잡(병렬, 스택 불필요)에서 먼저 잡는다

## 미해결

- 배포 버전 스큐(서로 다른 커밋의 생산자·소비자 공존)는 e2e 도 JVM 도 보지 않는다. 같은 커밋의
  이미지로 스택을 띄우기 때문이다. 이 PR 범위 밖이며 ADR-0032 에 공백으로만 적는다
