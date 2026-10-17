# ADR-0040: 보호 승인 복구의 증빙과 역할 분리

- **Status**: Accepted
- **수용 범위**: 서버 직접 확정 조회 복구에 한정. 영구404 외부 증빙 경로는 미해결이다.
- **Date**: 2026-10-17
- **관련**: D-084, ADR-0034, ADR-0035 (서버 직접 조회 복구 승인; 영구 404 외부 증빙 미확보)
- 배경: ADR-0035는 근거 없는 강제 FAILED를 막고 보호 재고를 유지한다. 과거 MANUALLY_RESOLVED/FAILED 및 확정 PG 결과에 대한 운영 복구는 추가 프로토콜이 필요하다.

## 결정

1. **신뢰 원천**: 서버가 기존 Toss 클라이언트로 직접 조회한 응답만 자동 검증 대상으로 삼는다. paymentKey/orderId/totalAmount가 원장과 일치해야 한다. ABORTED/EXPIRED는 잔여 과금 0, CANCELED는 전액 취소 금액과 로컬 환불 원장의 일치까지 요구한다. PARTIAL_CANCELED/잔여 balance/모순/404/5xx는 보류한다. 파일 업로드·해시는 진위 검증이 아니다. 사용자는 서버 직접 확정 조회만 허용하는 권장안을 승인했다. 영구 404의 PG 발급 증빙 경로는 외부 신뢰 원천/형식 미확보로 미구현이며 D-084 전체를 열린 상태로 유지한다.
2. **역할**: signed internal token의 authenticated ADMIN principal ID가 서로 겹치지 않는 승인자/실행자 allowlist에 있어야 한다. 요청의 resolvedBy는 신원 근거로 사용하지 않는다. 증빙 수집, 승인, 실행, 거부 사유를 별도 불변 감사 행으로 남긴다. 같은 사람이 승인과 실행할 수 없다.
3. **예약 대사**: Payment가 Product를 동기 호출하거나 DB를 직접 읽지 않는다. 기존 outbox/inbox 패턴으로 recovery.requested를 발행하고 Product가 orderId/recoveryId/approvalGeneration과 예약 status/version/fence snapshot을 회신한다. 응답이 요청 generation과 다르거나 원장과 모순되면 실행하지 않는다.
4. **최종 경합 방어**: snapshot만으로 재고를 해제하지 않는다. 실행은 approval row lock+generation+현재 상태 조건으로 한 번만 인정하고, Product는 실행 이벤트가 지시한 reservation version/fence에 대해 최종 CAS를 적용한다. CAS 불일치는 수동 검토 상태로 남기며 다른 예약을 해제하지 않는다. reconciler와 경합하면 먼저 확정된 generation만 승리한다. historical FAILED도 별도 허용된 원인/dispatch deadline 검사 없이 일반 terminal을 재개하지 않는다.
5. **감사 및 트랜잭션**: 증빙 원문/조회 시각/원장 식별자/해시/승인 principal/실행 principal/상태 전이/generation을 보존한다. 메시지 eventId는 기존 outbox/inbox 원장에 보존한다. 상태 변경과 실행 outbox는 같은 DB 트랜잭션이다. 재요청은 recoveryId 멱등키로 동일 결과를 반환한다.
6. **배포/복구**: 먼저 양 서비스 additive DDL, 다음 소비자 및 새 inbox subscription의 replay DENY 정책, 마지막 운영 진입을 배포한다. 신규 메시지의 과거 임의 replay는 금지한다. 실패는 DLQ에서 원문과 감사 원장을 대조한다. 명시 false 결과는 새 generation과 재승인으로, 응답 유실은 같은 fence의 명시 재전달로 처리한다. 구버전은 새 운영 진입 비활성 상태에서 유지 가능해야 한다.

## 대안과 제한

동기 Product HTTP 및 교차 DB 조회는 서비스 경계와 일관성 문제 때문에 사용하지 않는다. 운영자 무과금 선언은 외부 과금 진실을 증명하지 못한다. 단순 timeout/TTL로 APPROVAL_HELD를 해제하지 않는다. 새 인프라나 외부 키 저장소를 도입하지 않는다.

## 필수 반례

동일 승인/실행자, 미서명 principal, 잘못된 역할, PG key/order/amount 불일치, 부분취소·잔여과금·404, 오래된 snapshot/version/generation, 늦은 reconciler 성공, 중복 실행 및 outbox 재전달, historical FAILED의 잘못된 원인, 이미 환불된 주문의 중복 재고 증가를 각각 검증한다. 실제 DB 경합에서 재고 증가는 정확히 한 번이어야 한다.

## 미해결

- 영구 404에 대한 신뢰 가능한 PG 발급 무과금 증빙 원천/형식.
- 제한된 서버 조회 복구는 PR #181로 병합되었고 PR/main 전체 gate·추가 반례·독립 검증을 통과했다. 영구404 증빙 복구는 이 수용 범위에 포함하지 않는다.


## 구현된 프로토콜

- Payment `approval_recoveries`는 UUID id, order_id, generation, pg_status, pg_evidence, evidence_digest, reservation_version, reservation_status, status, approved_by, executed_by, created_at, resolved_at를 가진다. 같은 주문의 활성 복구는 approval row lock 아래 한 건만 생성한다. `approval_recovery_audit`는 append-only 행으로 actor/action/reason/evidence digest를 보존한다. 원문은 DB에만 보존하고 응답·로그에는 paymentKey나 인증값을 노출하지 않는다.
- 상태는 AWAITING_RESERVATION → READY_FOR_APPROVAL → APPROVED → EXECUTION_REQUESTED → COMPLETED이며, snapshot 부적합은 REJECTED, 실행 뒤 Product 거절은 REVIEW_REQUIRED이다. COMPLETED는 Product의 해제 성공 또는 이미 동일 복구가 적용된 결과를 수신한 뒤에만 기록한다. 응답 유실은 미완료 상태를 유지하며 임의 완료하지 않는다.
- `payment.approval-recovery.requested`는 recoveryId/orderId/generation을 담는다. Product가 기존 예약 행을 잠그고 snapshot을 생성하며 `stock.approval-recovery.snapshot`으로 status/fence/refundResult와 예약 변경 token을 반환한다. 예약 상태 변경 token은 모든 관련 상태 변화에 증가하는 version이며 신규 컬럼/기존 CAS 갱신을 함께 수정한다.
- 승인 시에는 수집 증빙과 snapshot을 고정한다. 실행 시 PG를 다시 조회하고 같은 key/order/amount/무과금 결과인지 확인한다. 승인 이후 approval generation/status 또는 예약 token이 바뀌면 거부한다. 승인된 snapshot을 그대로 믿고 실행하지 않는다.
- 실행 시 Payment의 generation을 증가시켜 기존 reconciler를 fence하고 복구 실행 outbox를 쓴다. Product의 `payment.approval-recovery.execute` 소비는 snapshot token/fence/status에 대한 CAS 성공일 때만 재고를 반환하고 결과를 발행한다. 성공 결과의 Payment 소비가 final ledger/audit를 확정한다. 각 소비자는 고유 group과 inbox로 중복을 제거하며, Product CAS가 별도 eventId 중복도 차단한다.
- 실행 재조회가 DONE이면 failed/해제 이벤트를 발행하지 않는다. 기존 성공 승인/고아 승인 환불 경로로 전달하고 기존 복구 요청은 REJECTED로 기록한다. historical FAILED만 제한적으로 재개하는 domain 메서드는 dispatch_deadline과 MANUALLY_RESOLVED 원인을 모두 요구한다.
- 배포 순서: Payment additive V13 복구/감사 테이블, Product V12 예약 version/recovery_id → 새로운 소비자와 topic/replay DENY 등록 → 운영 진입. 네 이벤트의 필드는 common DTO로 정의했고 6개 native CAS와 entity @Version 경로를 실제 MySQL에서 검증했다.

### 경합 전제 추가

기존 reconciliation SQL은 CLAIMED/UNRESOLVED를 lease 기준으로 다시 claim하므로 generation 증가만으로 긴 비동기 실행을 보호하지 못한다. 복구 실행 중에는 별도 RECOVERY_PENDING 상태가 필요하며 scheduler 후보에서 제외한다. 수동 복구의 대기 상태와 기존 자동 승인 상태를 섞어 lease 만료 후 무효 실행이 되지 않도록 한다. PG 재조회가 DONE이면 RECOVERY_PENDING 진입 전 기존 승인 성공/환불 경로를 사용한다. Product 거절 결과는 자동 실패 해제로 취급하지 않고 감사와 운영 검토를 요구한다. 결과 유실 시 같은 recoveryId 실행 이벤트의 outbox/inbox 멱등 재전달로 회복하며 새로운 PG 승인 호출은 없다.


## 실행 전 독립 검토 반영

- DONE을 확인한 뒤 기존 `executor.verify`로 다시 조회하면 두 번째 부정 응답이 해제로 이어질 수 있다. 이 경로를 제거하고 엄격히 검증한 DONE 원문만 성공/고아 환불 outcome으로 매핑한다.
- 동일 주문의 요청 재전송은 기존 활성/완료 recoveryId를 반환한다. 승인·실행 중복 및 완료 뒤 중복은 감사만 추가하고 업무 outbox는 추가하지 않는다. 응답 유실로 id를 잃어도 같은 주문 요청으로 회수한다.
- 유효 운영자의 거절은 별도 REQUIRES_NEW 감사 트랜잭션으로 남긴다. 원문이 없는 거절은 빈 증거와 그 해시, generation=-1로 구분한다. 업무 변경 롤백이 거절 감사까지 없애지 않는다.
- 명시적인 Product false 결과인 REVIEW_REQUIRED만 `resume`으로 새 PG 대사·새 generation·새 snapshot·재승인 절차에 들어간다. approval은 계속 RECOVERY_PENDING으로 자동 claim을 차단한다. 기존 version은 증가하므로 오래된 명령이 새 예약 상태를 해제하지 못한다.
- 응답 유실은 `retry`가 동일 recoveryId/generation/version payload를 새 outbox로 재전달한다. 새로운 PG 대사 결과가 같은 확정 부정일 때만 허용한다. DONE/불명/모순이면 재전달 없이 보호를 유지한다. 이는 일반 DLQ replay DENY를 우회하는 임의 발행 기능이 아니다.

## Update Log

- 2026-10-17, PR #181 / `d405cfabfbe03ecf4b06a32ef2e6a400e7b29988`: 구현·PR/main CI38983400228/38984838008 및 독립 검증 완료에 따라 제한된 서버 직접 확정 조회 결정을 Accepted로 전환했다. 영구404 외부 신뢰 증빙은 미해결이며 D084 전체 완료를 뜻하지 않는다.
- 2026-10-17, 후속 완료 기록: 상태 파서가 읽는 Status는 표준 Accepted로 유지하고 수용 범위를 별도 필드로 분리했다. 제한 범위와 D084 미완 조건은 바뀌지 않는다.
