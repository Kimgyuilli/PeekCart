# ADR-0034: 비동기 결제 승인과 재고 예약 fence

- **Status**: Accepted
- **Date**: 2026-10-14
- **Deciders**: 프로젝트 오너 (접수 후 비동기 승인 선택)
- **관련 Phase**: Phase 5, D-060

## Context

`PaymentApprovalService.beginApproval`은 로컬 lease 마진만 검사한다. `payment.requested`가 전달되기 전에 PG를 호출하므로 Order 취소와 Product sweeper/release가 재고를 복구한 뒤 과금이 성립할 수 있다. ADR-0023의 승인 원장은 중복 승인과 crash를 다루지만 재고 회수를 막지는 않는다. 서비스 직접 호출 없이 Product의 회수 CAS와 경쟁하는 영속 fence가 필요하다.

## Decision

승인 API는 접수(HTTP 202, PENDING)를 반환한다. Product가 예약을 보호했다는 이벤트를 커밋한 뒤에만 Payment dispatcher가 PG 승인을 시작한다. 결과는 기존 결제 조회 API로 확인한다.

### 상태와 이벤트

1. 접수 트랜잭션은 실제 paymentKey, `payment_approvals.WAITING_STOCK`, `dispatch_deadline` 및 기존 `payment.requested` Outbox를 함께 커밋한다. 같은 주문의 중복 접수는 기존 PAY-013이다. deadline은 수신한 예약 만료 시각이며 구 lease-null 요청은 접수 시각 + 기존 마진으로 제한한다.
2. Product는 `payment.requested`를 추가 구독한다. 미만료 `RESERVED`를 `APPROVAL_HELD`로 원자 CAS한다. `approval_fenced=true`를 남기고 `stock.approval.result(orderId, granted)`를 같은 트랜잭션에서 발행한다. 예약이 없거나 lease-null/만료/회수/실패이면 거절한다. 중복 요청은 HELD에서만 성공 회신한다. 새 시도/예약 재생성은 지원하지 않는다(orderId당 승인 원장 하나).
3. Payment는 WAITING_STOCK에서만 결과를 적용한다. 허가는 READY, 거절은 FAILED + payment.failed. dispatcher는 READY 행을 잠그고 취소 및 deadline을 검사한 뒤 CLAIMED를 커밋하고 PG를 트랜잭션 밖에서 한 번 호출한다. WAITING_STOCK의 deadline 초과도 PG 없이 FAILED + payment.failed로 닫는다. 동일 행 잠금이 회신/만료/dispatcher 경쟁을 직렬화한다.
4. CLAIMED 이후 crash/timeout은 기존 조회 전용 reconciliation과 generation fencing을 유지한다. 늦은 회신은 terminal/CLAIMED를 되돌리지 않는다. 소비 트랜잭션에서 PG를 호출하지 않는다.

### 재고 해제 권한

- 주문 취소와 sweeper는 RESERVED만 회수한다. HELD는 절대 시간만으로 해제하지 않는다.
- 확정된 승인 실패(`payment.failed`)만 RESERVED/HELD를 RELEASED로 CAS하고 재고를 한 번 복구한다. 취소된 Payment에서 실패가 확정돼도 이 회신은 필요하다.
- 성공(`payment.completed`)은 HELD 또는 기존 RESERVED를 CONFIRMED로 전이한다. 주문 취소와 승인 성공의 경합은 기존 자동 환불로 수렴한다.
- 새 fence를 거친 HELD/CONFIRMED는 `payment.refunded=SUCCEEDED`에서만 RELEASED로 CAS하고 재고를 복구한다. 환불 FAILED/결과 불명과 운영자의 단순 수동 종결은 해제 근거가 아니다. legacy CONFIRMED는 이번 계약으로 복구하지 않는다.
- 환불 회신과 completed의 역전은 성공 환불 marker와 RELEASED 상태로 흡수한다. 조회/marker 기록/해제/confirm을 같은 예약 행 잠금으로 직렬화하여 환불 상태가 유실되지 않게 한다.
- 승인 원장이 미해결이면 재고도 보유한다. 자동 상한 후에는 기존 승인 알림과 운영 조회를 사용한다. 안전성을 위해 가용 재고가 오래 묶일 수 있다.

### 계약 및 배포

ADR-0023 D1/D2/D4/D8의 동기 접수/직접 CLAIMED/즉시 응답 부분을 대체한다. 조회 기반 reconciliation과 PG 안정 멱등키는 유지한다. ADR-0012 D3의 예약 전이에 HELD를 추가한다.

새 토픽의 발행 소유자는 Product, 소비자는 Payment이고 key는 orderId이다. `DlqTopology`, replay 정책(새 두 구독은 deny), 구독 계약과 Saga 매트릭스를 함께 갱신한다. DB는 Product V11(approval_fenced), Payment V12(dispatch_deadline)로 확장한다.

배포는 승인 접수를 중단하고 진행 중 CLAIMED/UNRESOLVED 및 기존 이벤트를 drain한 뒤 스키마 → Product/Payment 호환 버전 → 접수 재개 순서다. 혼합 버전의 구 Payment는 fence 없이 과금하므로 안전한 rolling 전환을 주장하지 않는다. 롤백도 접수 중지 후 WAITING_STOCK/READY/CLAIMED/UNRESOLVED 및 HELD가 해소된 뒤 수행한다. 외부 배포는 이번 작업에서 실행하지 않는다.

## Alternatives Considered

- 마진/TTL 증가: 검사 이후 회수 창이 남으므로 기각.
- Payment→Product 동기 RPC: 응답 계약 보존에 유리하지만 서비스 직접 호출 금지와 충돌하고 별도 인증/네트워크 표면이 필요하다.
- 요청 안에서 이벤트 회신 대기: 제한 시간 후 처리 중 응답과 내구적 작업 처리가 여전히 필요하다. 오너가 비동기 접수 방식을 선택했다.
- HELD에 별도 TTL: 지연된 PG 성공과 회수 경합을 다시 만들므로 기각.

## Consequences

PG 전에 실제 재고 소유자의 회수와 경쟁하는 CAS가 완료된다. 회수가 이기면 PG 호출은 0회이고, fence가 이기면 PG 결과가 밝혀질 때까지 재판매가 불가능하다. API 호출자는 202 이후 조회해야 하며 broker/dispatcher 지연이 결제 지연에 더해진다. 미확정 과금은 재고를 묶으므로 기존 승인 미해결 운영 처리가 중요하다.

## References

- ADR-0012, ADR-0018, ADR-0023, ADR-0032
- `docs/plans/D-060.md`
