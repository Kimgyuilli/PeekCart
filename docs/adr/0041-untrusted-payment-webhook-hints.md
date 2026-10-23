# ADR-0041: 일반 결제 웹훅의 제한된 비신뢰 신호 접수

- **Status**: Accepted
- **Date**: 2026-10-21
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 5, D-064
- **대체 범위**: ADR-0023 C4/D7의 일반 결제 웹훅 HMAC 서명 가정 및 무제한 수신 로그. 서버 PG 조회로만 확정하고 webhook 안에서는 PG를 호출하지 않는 규칙은 유지한다.

## Context

Toss의 일반 PAYMENT_STATUS_CHANGED는 eventType과 중첩 data Payment 객체를 보낸다. 서명 헤더는 payout.changed/seller.changed에만 존재한다. 기존 수신기는 사용자 정의 Toss-Signature HMAC과 평면 paymentKey를 요구하여 정상 결제 알림을 받을 수 없었다.

서명 요구만 제거하면 임의 Idempotency-Key마다 로그를 적재하고 원장 lease를 계속 해제할 수 있다. 입력에 나타난 상태는 발신자를 인증하지 않으며, 상태 확정에 사용하면 위조 알림으로 과금 사실을 바꿀 수 있다.

## Decision

일반 결제 웹훅을 인증된 결제 증빙이 아닌, 알려진 미확정 승인에 대한 최대 한 번의 조회 가속 신호로 받는다.

1. HTTP 원본 스트림에서 최대 16KiB+1만 읽어 크기 초과를 413으로 거절한다. Content-Length가 없어도 상한을 적용한다. JSON은 PAYMENT_STATUS_CHANGED, 중첩 data.paymentKey(문자열 1~200자), 알려진 Payment status를 요구한다. 잘못된 입력은 400이다. 다른 유형은 별도 계약 없이 처리하지 않는다.
2. payments.payment_key의 기존 unique index로 로컬 결제를 찾고, 해당 approval 행을 잠근다. 키가 일치하며 UNRESOLVED인 원장만 적격이다. unknown/진행 중 CLAIMED/종결/승인 전 원장은 200 no-op이다.
3. 외부 transmission-id, Idempotency-Key, 상태·시각의 변화가 새 작업을 만들지 못하도록 서버가 paymentKey+고정 이벤트로 dedup 키를 만든다. existing webhook_logs에 INSERT IGNORE하고 실제 삽입 성공만 nudge한다. 동일 approval lock은 상태 검사부터 nudge 커밋까지 유지한다. exists/save의 REPEATABLE READ snapshot 경쟁에 기대지 않는다.
4. 로그에는 신뢰하지 않는 상태와 이벤트 종류의 최소 메타데이터만 저장한다. 전체 Payment 객체, 고객 정보와 외부 식별자는 저장하지 않는다. 로그와 nudge는 한 트랜잭션이다. 실패 시 함께 rollback한다.
5. webhook은 payment/approval status, generation, 재고, outbox를 확정하지 않으며 PG 요청도 만들지 않는다. 기존 scheduler가 별도로 원장을 claim하고 서버 PG 조회로만 결과를 확정한다. 직접 PG 조회의 일시 장애는 미결을 유지한다.
6. 승인 POST의 정확한 HTTP 409 + IDEMPOTENT_REQUEST_PROCESSING 조합은 transient다. 최초 confirm 한 번과 안정된 멱등키를 유지하고, 이후에는 조회로만 확인한다. 처리 중 응답을 확정 실패로 바꾸지 않는다.

## Alternatives Considered

- 가상의 일반 결제 HMAC을 유지하면 정상 외부 요청을 받을 수 없어 기각한다.
- 서명 없이 payload의 DONE/금액을 신뢰하면 공격자가 결제 결과를 확정하므로 기각한다.
- 외부 transmission-id별 dedup은 공격자가 키를 바꾸어 무한 적재·가속할 수 있어 기각한다.
- 새 cooldown 테이블/컬럼은 반복 힌트를 받을 수 있으나 새로운 시계·보관 정책이 필요하다. 기존 주기 조회가 복구의 정본이므로 원장당 한 번의 힌트로 한정한다.

## Consequences

실제 PG 서명 없는 일반 알림 형식이 도달할 수 있고, 위조된 알려진 키도 원장당 로그 한 건과 lease 해제 한 번까지만 추가할 수 있다. 중복·동시 요청은 같은 영속 fence를 공유한다. 기존 gateway fail-closed rate limiter와 서비스 스트림 상한을 유지한다.

알림이 16KiB보다 크거나 첫 힌트가 위조되어 뒤의 실제 힌트가 억제될 수 있다. 이 힌트는 정확한 결제 데이터 전체를 받는 통로가 아니며, 기존 주기 reconciliation은 힌트와 무관하게 계속 조회한다. 따라서 상한/중복 억제는 즉시성만 제한하며 확정 가능성을 외부 알림에 의존시키지 않는다. 일반 인터넷 요청량 전체를 완전히 차단한다는 보장은 하지 않는다.

새 merchant-order ID 계약은 후속 ADR(예약번호 0042)의 별도 작업이다. 이 결정은 기존 인증된 식별자를 바꾸지 않는다. 실제 PG 호출·외부 웹훅 수신은 준비된 테스트 거래와 수신 환경을 통해 따로 검증해야 한다.

## References

- [Toss webhook events](https://docs.tosspayments.com/reference/using-api/webhook-events)
- [Toss webhook verification](https://docs.tosspayments.com/guides/v2/get-started/llms-quick-reference#72-webhooks)
- [Toss idempotency status](https://docs.tosspayments.com/reference/using-api/authorization)
- [ADR-0023](./0023-payment-approval-reconciliation.md)
- [계획](../plans/done/D-064-pg-contract.md)
