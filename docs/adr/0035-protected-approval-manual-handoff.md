# ADR-0035: 보호된 승인 미결의 운영 인수와 상태 조회

- **Status**: Accepted
- **Date**: 2026-10-17
- **관련**: D-060 followup, ADR-0034, ADR-0023 D5

## Context
#175의 비동기 fence는 이미 배포 기준이다. 그러나 기존 수동 종결은 신규 보호 승인도 FAILED로 닫고 재고는 APPROVAL_HELD로 남겨, 나중에 PG가 DONE을 반환해도 조회 복구할 수 없게 한다. 결제 CANCELLED만 조회하면 미확정 과금을 구분할 수도 없다.

## Decision
신규 원장의 dispatch_deadline(non-null)을 비동기 fence 접수 표지로 재사용한다. 기존 데이터는 NULL이며 신규 접수는 deadline을 필수 저장한다. 새 UUID나 migration은 추가하지 않는다. orderId당 단일 승인/예약 시도 계약과 generation worker fencing을 유지한다.

신규 보호 승인의 resolve-manually는 운영자/사유만 감사 기록하고 UNRESOLVED/resolved_at=NULL을 유지한다. 이후 PG 조회 확정이 가능하다. legacy(NULL deadline)의 기존 수동 FAILED 종결은 유지한다. 현재 운영 처리는 application service 진입점이며 별도 공개 HTTP 수동 API는 없다. 로그도 수동 감사와 종결을 구별한다.

POST 승인 접수의 202 응답은 Location: /api/v1/payments/{orderId}로 조회 URL을 제공한다. 응답 시점의 상태가 이미 APPROVED/FAILED여도 같은 Location을 반환한다. GET 결제 조회는 nullable approvalStatus를 추가한다. status는 결제 도메인 상태, approvalStatus는 PG 승인 원장 상태다. CANCELLED+UNRESOLVED는 미과금 확정이 아니다. 원장 없는 결제는 null이다.

장기 미결은 WAITING_STOCK/READY/CLAIMED/UNRESOLVED 요청시각 age >900초, for=0s 경보로 감지하며 나이만으로 해제하거나 PG를 재호출하지 않는다.

## Limits and operations
CLAIMED 후 PG 호출 전 crash의 영구404는 UNRESOLVED/APPROVAL_HELD를 무기한 유지할 수 있다. 조회가 DONE 또는 기존 확정 부정 상태를 반환할 때만 자동 종결한다. 운영 대사로 '요청 없음'을 확인한 증거를 제출해 강제 종결/재고 해제하는 API는 구현하지 않는다. 안전을 위한 가용성 비용이며 완결된 운영 해제 경로를 제공한다고 주장하지 않는다.

신규 두 구독 replay DENY를 유지한다. WAITING_STOCK/READY는 dispatch_deadline 뒤 PG 없이 실패 이벤트로 닫고 HELD를 해제할 수 있다. DLQ의 직접 발행/SQL 우회는 지원하지 않는다. CLAIMED/UNRESOLVED는 이 deadline 경로로 닫히지 않는다.

## Compatibility
ADR-0034의 상태/토픽/DDL/배포 순서 그대로 유지. ADR-0023 D5의 수동 terminal 동작은 legacy에 한정한다. ADR-0034의 '운영 단순 종결은 해제 근거가 아님'을 조회 가능성까지 보강한다. nullable 응답 필드 추가이며 migration은 없다. 신규 미결이 있는 동안 구 수동 종결 코드로 롤백하면 복구 경로가 끊기므로 운영 수동 처리 호출을 차단해야 한다.

## Rollout diagnostics
이미 #175에서 수동 종결된 신규 보호 원장은 이번 코드 변경으로 자동 재개되지 않는다. 배포 전 다음 read-only 조회로 후보를 찾는다:

```sql
SELECT order_id, status, failure_code, requested_at, resolved_at, resolved_by
FROM payment_approvals
WHERE dispatch_deadline IS NOT NULL
  AND status = 'FAILED'
  AND failure_code = 'MANUALLY_RESOLVED'
  AND resolved_by IS NOT NULL;
```

후보마다 PG 대사 결과와 Product 예약 상태/환불 원장을 함께 확인해야 한다. 이것은 결함 영향 후보 진단이며 과금 없음이나 재고 해제 허가가 아니다. 이미 terminal인 행을 자동으로 UNRESOLVED로 바꾸거나 직접 SQL로 재개하는 절차는 제공하지 않는다. 확정 negative와 이벤트 배선에 미치는 영향까지 검증한 별도 복구 작업이 필요하다. 이 변경은 이후 수동 인수가 조회 경로를 끊지 않도록 예방한다.
