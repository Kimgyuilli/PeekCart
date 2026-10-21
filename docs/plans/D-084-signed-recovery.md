---
grade: L
---

# D-084 — 실제 signed ADMIN 분산 복구 체인

## 1. 명제

실제 User 로그인과 Gateway 서명을 통과한 서로 다른 운영자가 증빙 수집·승인·실행을 수행해도 Kafka/outbox와 Product 예약 CAS, Payment ack 완료를 연결해 관측할 수 없거나, 중복·오래된 예약 snapshot·PG 불명 응답에서 재고가 잘못 증가하면 이 검증은 미완이다. fixture PG 성공을 실 PG 증빙이나 영구404 복구 권한으로 해석해도 미완이다.

## 2. 배경과 코드 전제

자기 worktree `/private/tmp/peakcart-worker-c-recovery`, branch `debt/d084-signed-recovery`, 시작 base `89797c9639e15fb7c362e6be49687e652f80d3b9`. PR210 최종 `0be5aa32ec9159cdbc86f351475443fa090aec7f`의 signed fixture를 선행으로 재사용한다. PR210은 root 승인으로 실제 main `7894dcc9cf0de3dd966f62777d1b463a1d9313dc`에 병합됐고 이 tree에 정상 통합했다(`8fd71ea`). PR212 실패 및 root가 지정한 PR189 후속 정렬이 우선이다.

| 실제 source | 확인한 전제와 처분 |
|---|---|
| ADR0040, D-084/PR181 | 서버 직접 PG 확정 조회, 분리된 운영 principal, generation/CAS/outbox/inbox 복구가 이미 구현·집중 검증됐다. 이를 재구현하거나 기존 단위 case를 다시 늘리지 않는다. 기존 A–E는 새 전체 복구 체인 증거가 아니다. |
| ApprovalRecoveryController / ApprovalRecoveryAccess | `/api/v1/payments/approval-recoveries`의 request/approve/execute/read/resume/retry가 존재한다. ADMIN ID allowlist는 승인자·실행자가 겹치면 부팅 거부한다. 요청의 actor를 신원으로 쓰지 않는다. |
| ApprovalRecoveryService | 서버 PG 조회 뒤 request outbox, snapshot 수신 뒤 READY_FOR_APPROVAL, 승인 후 실행 재조회 및 RECOVERY_PENDING, Product ack 뒤 COMPLETED/FAILED가 된다. 실행 중복은 업무 outbox를 추가하지 않는다. Product false는 REVIEW_REQUIRED다. |
| ApprovalRecoveryEvidenceVerifier / TossPaymentIdentity | key/orderId/amount가 같고 ABORTED/EXPIRED의 balanceAmount가 정수0이어야 한다. 404/불일치/미완 응답은 증빙이 아니다. DONE은 별도 성공 경로이며 실패 재고 해제 증빙으로 사용하지 않는다. |
| Product ApprovalRecoveryConsumer / StockReservationService | 네 recovery topic과 고유 inbox group이 이미 있다. snapshot version/fence/status를 실제 Product CAS가 다시 검사하고 결과를 발행한다. 이미 동일 recoveryId로 해제된 예약은 재고를 더하지 않는다. |
| PaymentApprovalJpaRepository | 자동 reconciler는 CLAIMED/UNRESOLVED의 claimed_at lease 조건을 검사한다. historical FAILED는 후보가 아니다. 전체 체인의 결정적 기준 fixture는 적격 MANUALLY_RESOLVED/dispatch_deadline 있는 과거 FAILED를 쓴다. UNRESOLVED 확인이 필요하면 해당 fixture의 명시 claimed_at 보호 구간과 실제 행을 기록하며 자동스케줄러 정책/한도를 바꾸지 않는다. |
| PR210 Lab/Compose/verify | 이미 실제 issuer key/JWKS/login/nonce cleanup/secret scan/image identity가 있다. 직접적인 기존 prepare/run/cleanup과 HTTP/SQL helper를 재사용하고 범용 새 실행 framework나 키설정 계층을 만들지 않는다. |
| 기존 pg-stub fence script | confirm으로 identity를 저장하고 resolve 뒤 ABORTED를 조회하지만 balanceAmount가 없어 recovery verifier에는 불충분하다. 기존 fence-resolved 응답에 명시0을 추가하고 해당 Handler 회귀를 검증한다. 새 외부 PG client/service는 만들지 않는다. |

등급 L은 네 앱과 기존 분산 계약을 관통하는 검증·공유 fixture 영향 때문이다. 제품 코드/API/이벤트/DDL/보안 정책 변경은 계획하지 않는다. 신규 ADR 결정은 없고 ADR0040 수용 범위를 검증한다. 제품 결함이 발견되면 근거를 먼저 보고하고 작은 별도 PR로 분리한다. 모듈 이동·새 경계가 없어 PLAN-BLINDSPOTS의 구조 이동 항목은 해당하지 않는다. 기존 helper/Compose/PG stub의 기존 호출자는 유지하고 현재 E2E와 signed210의 회귀를 함께 검사한다.

## 3. 작업 항목

- [x] P1. 기존 구현/통합 테스트/서명 fixture/PG stub 전제를 읽고 전체 체인만 추가하는 범위와 한계를 기록한다. root 설계 검토 뒤 구현한다.
- [x] P2. PR210을 명시 선행으로 연결하고 기존 signed Compose에 Product와 기존 PG stub을 더하는 작은 overlay, 직접 host orchestration/runner를 구성한다. 4개 앱(User/Gateway/Payment/Product)의 기존 Dockerfile/ci-image-identity와 기존 DB init, 내부망/nonce cleanup을 재사용한다.
- [x] P3. 실제 signup/login의 서로 다른 승인자·실행자 ADMIN, USER 및 allowlist 밖 ADMIN을 만들고 실제 Gateway 경유 권한 반례와 적격 과거 FAILED 복구를 수행한다. 자기 DB에서 만든 보호 예약/결제 원장의 초기 snapshot을 보존하며 준비 단계와 실제 요청 이후 변경을 구분한다.
- [x] P4. 실제 request/snapshot/approve/execute/result 네 event 흐름, Product CAS와 재고 정확히 한 번 증가, Payment ack 후 완료, 동시 execute/같은 eventId 재전달/새 eventId 동일 execute payload의 멱등성을 검증한다. Product를 자기 container에서 잠시 중지한 구간에는 Payment가 RECOVERY_PENDING/EXECUTION_REQUESTED를 유지하고 ack 전 완료하지 않는지 확인한다.
- [x] P5. 알 수 없는 PG key의 실제 stub404 및 identity 불일치, 승인자 execute 거부, 승인 후 Product 예약 version 변경의 실제 CAS 거부를 확인한다. 거부 감사와 업무 상태/outbox/재고 불변을 구분한다. 고정 사전조건의 stale snapshot 반례를 실제 동시 thread 경합이라고 부르지 않는다.
- [ ] P6. Actions에서 새 좁은 workflow와 일반 CI를 실행하고 실제 원자료/이미지 출처/마이그레이션 hash/상태/중복/cleanup·secret scan/누락 증거 음성을 보존한다. root 독립 검토와 순차 병합 조건을 충족한 뒤 제한된 전체 체인 검증만 기록한다.

## 검증 방법과 최소 구성

- 신규 파일은 `docker-compose.approval-recovery.yml`, `scripts/approval-recovery/`의 직접 runner·작은 독립 evidence 검사/자기검사, 별도 `.github/workflows/approval-recovery.yml`, 이 plan/audit/evidence를 우선한다. 기존 signed Lab의 prepare/run/cleanup/sanitize와 request/sql을 import하고 고유 생명주기를 복제하지 않는다. 기존 signed runner 이미지를 사용하며 Kafka CLI 주입은 host가 자기 broker immutable ID에만 수행해 새 Kafka client dependency를 만들지 않는다.
- Payment/Product만 실제 scheduling/listener를 켜 outbox poller와 consumer를 통과한다. User는 기존 closed fixture 설정을 유지한다. Gateway는 실제 User/Payment에 연결하고 Product는 기존 Kafka 계약으로만 참여한다. `replay.enabled=false`, 호스트 port 없음, 내부망, 로컬 PG stub 외 주소 없음은 유지한다. 신규 runtime 종류는 없고 필요한 Product 앱과 기존 PG stub만 추가한다.
- 승인자/실행자 allowlist는 fresh User fixture의 고정 예상 ID에 맞추고 실제 signup 결과·JWT principal을 대조한다. ADMIN 승격은 이 nonce의 User DB만 변경한다. token/private key/사용자 비밀번호는 artifact에 쓰지 않는다. HTTP5초/기존 DB3·5초/Compose 유한 wait를 유지하고 각 phase에 명시 deadline을 둔다.
- PG 준비는 기존 fence script에 fixture identity를 등록한 뒤 resolve한다. 이 준비 POST와 앱의 실제 증빙 GET 원장을 분리한다. recovery 실행 중 앱이 새로운 confirm/cancel을 발행하지 않았는지 stub 원장으로 검사한다. ABORTED balanceAmount0는 fixture 응답이며 실 PG 응답이라고 주장하지 않는다.
- 먼저 known held reservation/items/stock/payment/approval을 자기 DB에 seed하고 원형을 기록한다. 이후 실제 request부터는 서비스 코드가 만든 recovery/audit/outbox/inbox/result를 관측한다. Product의 snapshot/ack를 runner가 직접 서비스 메서드 호출하거나 성공 이벤트를 합성해 대신하지 않는다. 중복 검증에서만 실제 발행된 실행 envelope를 그대로, 이후 eventId만 바꿔 재전달한다.
- happy 완료 뒤 payment.failed의 후속 소비도 기다려 재고가 다시 늘지 않는지 확인한다. business outbox 건수, 고유 inbox eventId/group, generation/version/recoveryId, actor와 evidence digest를 교차 대조한다. 전체 snapshot에는 fixture paymentKey가 있을 수 있으나 실제 자격증명은 없다.
- stale snapshot은 승인 후 자기 fixture 예약 version을 바꿔 실제 Product CAS가 false를 반환하도록 만든다. RECOVERY_PENDING/REVIEW_REQUIRED, 실행 outbox와 실제 false result/inbox, 재고 불변을 확인한다. 이는 제품 CAS 경계 주입이며 운영 DB 변경 절차가 아니다.
- evidence 검사기는 독립 case inventory를 갖고 ack/실행 event/inbox/중복 결과/role/이미지 source/cleanup 누락과 잘못된 stock delta를 실제 원자료 복사 변이에서 거부해야 한다. empty/partial run은 green이 될 수 없다.
- 일반 test/기존 E2E/PG stub Handler 회귀와 새 Actions를 실행한다. 로컬 heavy는 하지 않는다. 전용 workflow는 pull_request와 수동 실행, contents:read, 유한 timeout, always owned cleanup·safe artifact upload만 가지며 publish/deploy/secrets 권한은 없다.

## 완료 조건과 남는 범위

최종 source에서 실제 signed HTTP, 직접 서버 PG 조회, 네 Kafka/outbox/inbox 전이, Product CAS, ack 완료와 중복/거부 반례가 모두 관측되어야 제한된 체인 검증을 완료한다. 실제 Linux Actions 증거 없는 코드는 완료로 표시하지 않는다. 신규 구현보다 기존 helper의 재사용을 우선하며 필요 없는 범용 분기/configuration/runtime를 추가하지 않는다.

영구404의 신뢰 가능한 PG 발급 증빙, 실제 운영 데이터·PG·배포·GKE·운영 승인은 여전히 범위 밖이다. 404 보호는 유지하며 D084 전체 완료로 표시하지 않는다. 초기 보호 상태 생성은 seed이므로 실제 checkout부터 장기미결 발생까지 증명하지 않는다. CANCELED 환불원장 경합, DONE 전환, resume/retry 모든 기존 분기의 전체 E2E 확장은 이 작은 첫 체인 PR에 묶지 않고 기존 PR181 검증과 구분해 후속 필요성을 판단한다. TASKS/progress/master는 root가 소유한다.

## 실제 실행 근거

수정 source `81fc5183db0c947ffe3149aba8d52e58411d8e7c`, Actions39605979637의 실제 PR merge checkout은 `5d54ab47377fc484ff95bd6cffdbd18003c70373`이다. 24개 관측(16/3/3/2), 네 outbox/inbox 전이, 실제 Kafka 중복 전달, ack 전 상태 유지, happy 재고3→5 한 번, stale/404/identity 불일치 재고3 유지, 8개 실제 원자료 변이 거절이 통과했다. 자기 container8/network1/volume1 정리와 secret scan(제거0)도 완료했다. 39개 raw 파일과 개별 hash는 evidence bundle에 보존한다. 이는 fixture PG와 seeded 초기 상태의 검증이며 최종 일반 CI/독립 검토/순차 병합인 P6는 미완이다.
