---
grade: L
---
# D-064 무료 PG 계약 보정

## 1. 명제

공식 일반 결제 웹훅이 존재하지 않는 서명/평면 필드 요구로 거절되거나, 위조된 알림이 결제 상태를 확정하거나 무한한 영속 기록·조회 가속을 만들거나, 승인 409 처리 중 응답이 영구 실패로 확정되면 미완료다.

공개 입력 보안 계약을 변경하고 application/domain repository/infrastructure/presentation을 함께 되돌려야 하므로 L이다. 새 외부 의존성·유료 자원·운영 실행은 없다. ADR-0041을 선행한다.

## 2. 배경과 코드 검증

| 확인한 코드/공식 자료 | 사실 | 처분 |
| --- | --- | --- |
| PaymentController webhook / WebhookService | `Toss-Signature` HMAC 및 top-level paymentKey 요구, 임의 Idempotency-Key 저장 | nested data 파싱, 비신뢰 신호의 제한된 접수 |
| 공식 webhook events 및 quick reference 7.2 | 일반 결제에는 서명 헤더가 없고 PAYMENT_STATUS_CHANGED의 data가 Payment 객체 | 이 이벤트만 허용, 상태는 서버 PG 조회로만 확정 |
| PaymentApprovalService.nudge / native query | UNRESOLVED만 claimed_at=NULL, CLAIMED 불변 | 기존 경계를 유지하되 알려진 원장 행 lock과 영속 중복 방지로 한 번만 가속 |
| webhook_logs UK 및 기존 exists/save | 임의 키 무한 적재·동시 조회 후 저장 경쟁 가능 | 서버가 paymentKey+고정 이벤트에서 키 생성, approval 행 잠금 및 INSERT IGNORE 영향 행 수로 같은 원장 알림 직렬화 |
| payments.payment_key unique index | 알려진 paymentKey 조회에 쓸 수 있는 기존 인덱스 | repository 조회만 추가, 신규 스키마 없음 |
| gateway payment route | 공개 webhook 포함, fail-closed Redis rate limiter 40/s 및 익명 IP fallback | 유지. 서비스 자체 raw stream 16KiB 상한을 추가 |
| TossPaymentClient.toConfirmOutcome | 취소와 달리 409 IDEMPOTENT_REQUEST_PROCESSING 분기 없음 | 정확한 status/code 쌍을 transient로 분류 |
| ApprovalExecutor.execute / verify | 최초 승인 1회, transient는 미결, 이후 조회 전용 | 기존 경로 유지, 실제 어댑터·원장 상태로 회귀 증명 |
| ApprovalExecutor orderId.toString / 공식 reference | 주문번호는 6~64자, 작은 숫자 ID는 불일치 | 단독 formatter 교체 금지. checkout 인증 단계 ID 생성 계약과 함께 후속 설계 |

### 공식 근거 (2026-10-21 확인)

- [웹훅 이벤트](https://docs.tosspayments.com/reference/using-api/webhook-events): 일반 PAYMENT_STATUS_CHANGED의 중첩 data 형식. 서명은 payout.changed/seller.changed에 한정된다.
- [Quick reference 7.2](https://docs.tosspayments.com/guides/v2/get-started/llms-quick-reference#72-webhooks): 일반 결제 알림의 상태를 서버 조회로 검증한다.
- [멱등 헤더](https://docs.tosspayments.com/reference/using-api/authorization): 모든 POST의 409 IDEMPOTENT_REQUEST_PROCESSING은 이전 요청 처리 중이다.
- [Payment reference](https://docs.tosspayments.com/reference): merchant orderId는 허용 문자로 된 6~64자다.

### 구조·역참조 확인

모듈 이동/공유물 추출은 없다. B1/B1b 기준으로 WebhookService 생성자 및 processWebhook 호출자(controller·단위/슬라이스 테스트), webhook-secret 설정(base/k8s)과 부팅 smoke 설명, 공개 경로(gateway·payment security)를 확인했다. 기존 잘못된 서명 문서는 ADR-0041로 대체 관계를 명시하고 Layer1 현재 사실을 정정한다. B7의 조회 후 UK 경쟁은 동일 approval 행 잠금과 INSERT IGNORE 영향 행 수를 함께 사용해 해결한다. MySQL REPEATABLE READ snapshot의 exists 조회에 의존하지 않는다. B12의 검사-부작용 창은 nudge까지 같은 트랜잭션에서 잠금을 유지한다. PG 호출은 이 트랜잭션에 포함되지 않는다.

## 3. 작업 항목

- [x] P1. ADR-0041에 비신뢰 webhook 접수, 원장당 한 번의 가속·후속 알림 무시와 주기 조회의 지속, 기존 HMAC 가정 대체를 기록한다.
- [x] P2. 실제 raw stream의 16KiB 상한, eventType/data.paymentKey/status 타입·길이·허용 목록을 검증한다. malformed는 400, oversized는 413으로 거절한다. 전달 ID/서명 헤더는 신뢰하지 않는다.
- [x] P3. 알려진 UNRESOLVED 원장만 행 잠금 하에서 서버 유도 중복 키를 기록하고 nudge한다. unknown/CLAIMED/terminal/중복은 200 no-op이며 원장 생성·로그 적재·외부 호출이 없다. 저장 payload는 필요한 최소 메타데이터만 유지한다.
- [x] P4. 승인 409 처리 중 분류를 transient로 보정하고 미결 및 조회 전용 복구를 검증한다.
- [x] P5. 단위·HTTP 입력·실제 MySQL 동시성/rollback 통합 테스트와 relevant lint를 실행하고 전체 CI 대기 상태를 기록한다. 실행 환경과 실 PG 미검증을 분리해 기록한다.
- [x] P6. Layer1 03/06과 ADR index의 0041 행을 갱신한다. TASKS/master/PHASE5는 주 작업자가 관리한다.

## 4. 검증 방법

- 공식 형식의 서명 없는 요청으로 중첩 키가 전달되는지, 평면/다른 이벤트/잘못된 타입·길이·상태/과대 본문/Content-Length 없는 과대 스트림이 거절되는지 확인한다.
- 알 수 없는 키, WAITING_STOCK/READY/CLAIMED/종결 원장은 로그·claim 시각·결제 상태·outbox·PG 호출이 불변이어야 한다.
- 같은 알려진 UNRESOLVED payment에 여러 헤더/본문·동시 요청을 주입해 로그 한 건, lease 한 번 해제만 관측한다. nudge 후 claim/reconcile 미결을 다시 만들어도 반복 입력은 lease를 다시 지우지 못해야 한다.
- 실패를 주입해 로그 적재와 nudge가 함께 rollback하는지 실제 MySQL에서 확인한다.
- forged DONE/금액으로는 PENDING이 유지되고, 독립 mock HTTP PG 조회 응답을 거쳐서만 확정되는지 확인한다. 실제 PG라고 부르지 않는다.
- MockRestServiceServer 승인 409 정확한 코드/다른 코드/다른 HTTP status 대조 및 ApprovalExecutor의 stable idempotency key·추가 confirm 부재를 검사한다. 원장 통합에서 UNRESOLVED/PENDING/outbox 실패 회신 부재를 확인한다.
- `:payment-service:test`의 관련 선택 테스트 후 전체 `./gradlew test` 또는 전체 필수 CI를 검증한다. Docker/Gradle은 주 작업자와 자원을 조정한 뒤 실행한다.

## 5. 완료 조건과 미해결

위 부정 명제가 반증되고 각 P항목의 실제 검증이 통과해야 이 무료 계약 보정을 완료로 본다. D-064 전체를 완료로 바꾸지 않는다.

실 PG 승인·취소·조회, 외부 웹훅 수신, 실제 test paymentKey/수신 URL은 미검증이다. 자격증명 읽기나 실제 결제/환불/GKE 실행은 하지 않는다. 사용자는 새 canonical PG merchant-order ID 생성·저장·응답 계약을 승인했다(2026-10-21). 이 계약은 후속 ADR(예약번호 0042)와 별도 PR에서 checkout 생성·재시도·legacy 증빙 조회 호환성을 함께 구현한다. 이번 PR에서 백엔드 confirm 문자열만 바꾸지 않는다.

## 로컬 검증 및 제출 상태

- focused 3개 클래스 통과, 실제 MySQL WebhookAdmissionIntegrationTest 10/10 통과. integration-test-container lint/self-test 56개, migration-grant lint, diff 검사 통과.
- 전체 필수 CI는 draft PR에서 실행하며 성공 전 병합하지 않는다. 실제 PG와 외부 웹훅 수신은 미검증이다.

## 2026-10-21 무료 계약 범위 병합 후 확인

최종 head `184c19cfcc696c6758fee9bbde27568e50b80ce0`의 [PR193](https://github.com/Kimgyuilli/PeekCart/pull/193)은 [최신 CI39480119984](https://github.com/Kimgyuilli/PeekCart/actions/runs/39480119984)와 [독립 delta 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/193#issuecomment-6298901536)를 통과했다. 초기 head6886c19에서 직접 실행한 86개 테스트는 작성 patch 불변을 확인해 재사용했으며 최신 SHA에서 다시 실행했다고 주장하지 않는다. 최신 필수 CI는 별도로 전체 실행했다.

정상 병합 SHA `a7ffa11f24be2bd281d8952ac5fc5d351fb6119c`의 [main CI39483390397](https://github.com/Kimgyuilli/PeekCart/actions/runs/39483390397)는 모든 검사와 여섯 publish가 성공했다. 본 무료 계약 보정의 구현·검증·리뷰·병합·main 확인은 끝났지만, D-064 전체는 실 PG·외부 수신 미검증 및 후속 주문 ID 계약 검증 때문에 부분 완료다. TASKS 상태와 PHASE5 기록은 root 기록 PR에서 전달한다.
