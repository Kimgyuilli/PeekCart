---
grade: L
---
# D-060 followup — PR #175 기반 보호 승인 복구 보강

## 1. 명제
#175 계약을 유지하며 보호된 승인의 운영 감사가 조회 복구를 끊지 않고, 회수와 보호의 실제 DB 경합 및 crash 복구가 검증되어야 한다. 수동 기록 후 FAILED가 되거나 실제 잠금 대기 없이 경합 통과를 주장하면 완료가 아니다.

## 2. 배경 — 코드 검증
| 전제 | 확인 결과 |
|---|---|
| 비동기 fence | ADR-0034/WAITING_STOCK/APPROVAL_HELD/payment.requested/stock.approval.result 이미 구현, 교체하지 않음 |
| 단일 시도 | payment_approvals.order_id UNIQUE, 예약 재생성 없음. 별도 UUID 불필요 |
| 신규/legacy 구별 | V12 dispatch_deadline은 신규 접수에 항상 저장, 기존 행 NULL. migration 수정 불필요 |
| 운영 수동 처리 | PaymentApproval.resolveManually가 모두 FAILED로 닫아 보호 건의 이후 query 복구를 차단 |
| 조회 | PaymentQueryService/DTO는 payment.status만 노출, CANCELLED와 PG 미결 구별 불가 |
| 경합/crash | 기존 dispatcher 테스트는 동시 호출/수동 성공 outcome. 실제 data_lock_waits 및 HTTP success 저장누락 증거 보강 |

B9: approval generation은 기존 worker 소유권, orderId는 유일 논리 시도. B12: Product 보호/회수 CAS의 실제 잠금 대기와 양쪽 승자 검증. inbound DTO 생성자/운영 controller 문구/테스트/알림/lint도 함께 확인한다. ADR-0035로 수동 처리/API 보강만 기록하며 ADR-0034 역사 보존.

## 3. 작업 항목
- [x] P1. 신규 보호 승인 수동 감사 후 UNRESOLVED/조회 유지 + API approvalStatus
- [x] P2. 실제 MySQL 두 승자 경합 및 PG 호출 전/성공 후 저장 전 중단 복구 검증
- [x] P3. 장기 미결 경보, 관련 계약 lint 및 cross-service/재시작 검증
- [x] P4. 전체 테스트·통합 검증과 공통 문서 인계

## 검증 방법
신규 manual 인수 후 조회 성공 수렴/legacy manual 유지/GET CANCELLED+UNRESOLVED를 단언. MySQL performance_schema.data_lock_waits가 발생한 뒤 승자 commit. PG HTTP confirm 성공 뒤 finalize 생략하고 조회로 복구, claim 뒤 PG 전 crash의404는 보호 유지. 최신 #175+보강 이미지에서 cross-service 증거 실행. 관련 lint와 ./gradlew test는 메인과 슬롯 조율.

## 완료 조건
실제 실행된 검증만 체크한다. 이전 worktree 결과는 이번 코드 검증으로 재사용하지 않는다. 기존 migration/토픽/replay DENY는 보존한다. 영구404 보호 운영 강제해제 API는 범위 밖이며 무기한 보호 비용을 명시한다. 별도 Codex 리뷰 미호출.

## 최신 main 기준 최종 검증 (2026-10-17)
cb8056d 기반 전체 test/:build/4서비스 bootJar 성공(8m22s, 67 tasks executed). Location 보강 후 전체 재실행도 성공(1m26s, 12 executed/55 up-to-date), JUnit 1,292건 실패·오류·skip 0. 아키텍처 가드 5개, JVM 및 E2E 매트릭스 각 34행 통과. 최종 runtime E2E a~e와 Payment 실제 재시작, PG 조회·보호 유지·실패 해제·정상 승인 확정 성공. 증거 `.cache/e2e/d060-followup-location-final-20261017`. 관련 lint 및 self-test 통과. 최초 E2E Location 실패는 audit에 보존하고 최종 실행과 구분한다. 별도 Codex 리뷰 미호출.
