# 보호 승인 복구 (ADR-0040)

서버가 기존 PG 조회 API에서 주문·결제키·금액이 일치하는 확정 결과를 받았을 때만 사용할 수 있다. 이 작업의 검증은 무료 로컬 Mock PG와 CI에서 수행하며 실 PG나 운영 복구는 실행하지 않는다. 영구 404, 업로드 파일, 파일 해시, 운영자 무과금 선언으로 재고를 해제하지 않는다. 이 경로는 D-084 전체 완료가 아니다.

## 운영 역할과 조회

`app.approval-recovery.approvers`와 `app.approval-recovery.executors`는 기존 내부 JWT의 ADMIN principal ID 목록이다. 기본값은 빈 목록으로 접근을 거절한다. 두 목록이 겹치면 시작을 거절한다. 요청의 `resolvedBy`, `X-User-Id` 같은 평문 주장으로 권한을 얻지 않는다.

- 승인자: `POST /api/v1/payments/approval-recoveries/orders/{orderId}`에 사유를 제출해 PG 직접 조회와 예약 대사를 요청한다.
- Product snapshot이 도착해 `READY_FOR_APPROVAL`이면 승인자가 `POST /{id}/approve`에 검토 사유를 제출한다.
- 별도 실행자가 `POST /{id}/execute`에 실행 사유를 제출한다. 서버가 PG를 다시 조회하며 오래된 generation·변경된 PG 결과는 실행을 거절한다.
- 두 역할은 `GET /{id}`로 상태를 조회한다. 응답은 id/orderId/status/generation만 제공하며 PG 원문과 paymentKey는 반환하지 않는다.

모든 경로의 `{id}` 앞 prefix는 `/api/v1/payments/approval-recoveries`다. 쓰기 본문은 `{"reason":"대사/승인/실행의 근거"}`이며 사유는 1~500자다.

## 완료와 미완료

`EXECUTION_REQUESTED`는 완료가 아니다. Product가 예약 version/fence 조건으로 재고를 한 번만 복구하고 결과를 회신한 뒤에만 `COMPLETED`로 기록한다. 반복 이벤트는 inbox와 recoveryId/예약 version으로 중복 복구를 막는다. 이미 정상 해제된 동일 snapshot은 재고를 더하지 않고 확인만 회신한다.

`REVIEW_REQUIRED`는 예약이 승인 당시와 달라 실행이 거절된 상태다. approval의 `RECOVERY_PENDING`을 유지한다. 상태를 SQL로 UNRESOLVED/FAILED로 바꾸거나 새 실행 이벤트를 임의 발행하지 않는다. 승인자가 `POST /{id}/resume`에 사유를 제출하면 PG를 새로 대사하고, 기존 generation을 폐기한 새 요청으로 예약 snapshot부터 다시 시작한다. 새 snapshot에 대한 새 승인과 별도 실행자가 다시 필요하다. 이전 실행의 명시적인 거절 결과가 없는 상태에서는 이 재개를 허용하지 않는다.

응답이 유실되거나 실행 전달이 DLQ에 멈추면 실행자가 `POST /{id}/retry`에 사유를 제출한다. PG를 다시 대사하고 동일 recoveryId/generation/reservation version의 실행만 새 outbox eventId로 재전달한다. PG가 DONE/불명/다른 상태이면 재전달을 거절하고 보호 상태를 유지한다. 네 신규 구독의 일반 DLQ replay 정책은 DENY이며 이 운영 진입점을 우회하지 않는다. 재전달된 실행은 Product CAS와 recoveryId로 재고를 다시 더하지 않는다.

`RECOVERY_PENDING`은 자동 approval reconciler 대상에서 제외한다. `payment_approval_backlog` 및 `payment_approval_oldest_age_seconds`의 `recovery_pending` 라벨은 기존 장기 미결 경보에 포함해 정체를 숨기지 않는다.

## 감사

PG 원문, SHA-256, 조회 대상 원장 generation, 승인자, 실행자, 사유, 상태 전이를 DB 감사 행에 보존한다. 유효 운영 principal의 거절은 업무 트랜잭션과 분리된 감사 트랜잭션에 기록한다. 감사 DB 자체가 실패한 경우 원래 실패를 유지하고 완료로 응답하지 않는다. 해시는 증거 식별용이며 PG 진위 검증의 대체물이 아니다. 원문/인증값/paymentKey는 새 API 응답이나 로그에 쓰지 않는다. 감사 테이블은 애플리케이션에 수정·삭제 진입점이 없다.

늦게 PG DONE이 확인되면 복구 해제 이벤트를 발행하지 않는다. 엄격하게 검증한 DONE 원문으로 기존 승인 성공/고아 승인 환불 절차를 호출한다. 두 번째 PG 조회 결과를 실패 해제로 바꾸지 않는다. 과거 `FAILED/MANUALLY_RESOLVED`도 dispatch deadline 표지가 있는 보호 승인만 이 경로로 다룬다.
