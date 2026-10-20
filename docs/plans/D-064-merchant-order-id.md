---
grade: L
---
# D-064 canonical PG 주문 식별자

## 1. 명제

신규 결제에서 checkout에 제공한 PG 주문 식별자와 승인·조회·복구가 사용하는 값이 다르거나, 기존 승인 식별자가 배포 후 바뀌거나, 다른 주문의 PG 응답으로 결제를 종결할 수 있으면 미완이다.

## 2. 배경과 코드 검증

사용자는 PG merchant order ID 선택 질문에 “권장하는 방향으로 진행”을 승인했다. 신규 canonical ID의 생성·저장·checkout 응답·confirm 검증 계약을 함께 도입한다. 웹훅/409 보정 PR #193과 분리한다. 공개 API와 DB 계약 및 외부 checkout 소비자 전환이 필요하므로 L이다. ADR-0042를 구현 전에 작성한다.

| 전제 | 확인한 코드 | 처분 |
|---|---|---|
| 주문 숫자가 그대로 PG orderId가 된다 | ApprovalExecutor.execute, ApprovalRecoveryEvidenceVerifier.verify | 승인 원장의 영속 pgOrderId로 전환, NULL만 기존 숫자 fallback |
| checkout 준비 API와 PG ID 필드가 없다 | PaymentController, ConfirmPaymentRequest, PaymentDetailDto, PaymentResponse | 인증된 prepare 응답과 confirm의 pgOrderId 필드 추가 |
| Payment는 order.created 수신 시 생성된다 | PaymentEventConsumer.handleOrderCreated, Payment.create | 생성 시 UUID 기반 ID를 한 번 저장, 반복 prepare는 조회 |
| 승인 fence는 별도 독립 트랜잭션 native INSERT다 | PaymentApprovalService.beginApproval, PaymentApprovalJpaRepository | 검증 후 같은 INSERT에 PG ID snapshot 저장 |
| 일반 PG 조회는 status만 판정한다 | ApprovalExecutor.verifyByQuery | paymentKey/orderId/amount의 엄격한 일치를 확인한 응답만 판정 |
| 기존 운영 복구는 identity를 비교한다 | ApprovalRecoveryEvidenceVerifier | 새 snapshot/기존 숫자 fallback을 동일 적용 |
| payment migration은 현재 V13까지다 | payment-service db/migration | 기존 파일 수정 없이 nullable V14 추가, backfill 없음 |

PLAN-BLINDSPOTS B1/B4/B7/B10/B12를 확인했다. beginApproval/insertWaitingIfAbsent/DTO 생성의 테스트 인바운드를 함께 갱신한다. 다른 서비스 이벤트의 숫자 orderId와 partition key는 유지한다. 새 ID의 UNIQUE는 payments만 소유하고 원장은 snapshot이다. MySQL 통합 테스트는 SharedContainers 및 Flyway opt-in을 사용한다. prepare는 재고 보호 fence가 아니며 승인 시 기존 검사를 다시 수행하고 Product 보호 회신을 기다린다.

### 호환성과 배포 경계

- 신규 Payment: `pc_` + UUID의 소문자 hex 32자로 35자 ID를 생성·저장한다. payments의 UNIQUE가 최종 충돌 방어다. 저장 실패를 새 값으로 조용히 재시도하지 않는다.
- 기존 payments/approval의 NULL은 기존 숫자 문자열 계약을 의미한다. 마이그레이션이 채우거나 다시 포맷하지 않는다. 기존 pending/approved/recovery 원장은 숫자 식별자로 계속 조회한다.
- 신규 prepare는 non-NULL canonical ID 및 승인 원장이 없는 PENDING 결제만 허용한다. 기존 NULL 행은 길이와 무관하게 새 prepare를 거절한다. 기존 인증 callback을 위한 confirm만 유효한 6~64자 숫자 ID에 허용한다. 짧은 숫자 ID의 미접수 주문은 새 주문을 요청하도록 거절한다. 이미 접수된 짧은 legacy 원장은 재승인 없이 조회한다.
- confirm은 숫자 `orderId`와 별도 `pgOrderId`를 받는다. 신규 행은 pgOrderId 필수·정확 일치다. 기존 NULL 행만 필드 생략을 기존 숫자 문자열로 해석한다. 임의 prefix/fallback mapping은 없다.
- additive schema만으로 구 바이너리와 신 API의 혼합 배포를 안전하게 만들 수 없다. 구 바이너리는 새 필드를 무시하고 숫자를 PG로 전송한다. 운영 전환은 신규 접수를 멈추고 schema·payment writer/dispatcher·checkout 소비자를 함께 전환해야 한다. 본 작업은 배포하지 않으며 이 제약을 API/ADR에 기록한다.

## 3. 작업 항목

- [x] P1. ADR-0042에 사용자 결정, 생성·snapshot·legacy·배포 계약과 대안을 기록한다.
- [x] P2. nullable V14 migration, Payment immutable pgOrderId, PaymentApproval snapshot 및 native insert를 구현한다.
- [x] P3. owner 검증 prepare API와 pgOrderId 응답·confirm 정확 일치를 구현한다. 기존 금액·상태·재고·중복 fence를 유지한다.
- [x] P4. 최초 confirm·정기 조회·운영 복구에 동일 snapshot을 사용하고 PG identity mismatch를 UNRESOLVED/증적 거절로 처리한다.
- [x] P5. 실제 MySQL에서 migration legacy 유지, 신규/반복/동시 prepare, 중복 승인 및 불일치 rollback을 검증한다. HTTP fixture로 동일 ID 전달과 mismatched 응답의 비종결을 검증한다.
- [x] P6. API·데이터·결제 테스트 문서와 감사 기록을 갱신하고 lint·분류 커밋·초안 PR 본문을 준비한다. 전체 CI와 최종 커밋 독립 검토 후에만 주 작업자가 merge한다.

- [x] P7. 저장소 E2E A/E의 준비·승인 호출과 PG stub의 영속 identity 응답을 새 계약으로 전환하고 양음 self-test 및 실제 E2E를 검증한다.

- [x] P8. 기존 product 동시 예약 CI 실패의 원본 XML·seed를 보존하고 worker 예외·트랜잭션·save/barrier phase 진단을 추가한다. 원래 기준을 유지한 재현 결과와 미확정 원인을 기록하고 명시적 음성 대조로 진단·cleanup을 검증한다. 최종 CI·독립 검토는 merge 전 별도 필수다.

## 4. 검증 방법

| 작업 | 실패 주입과 판정 |
|---|---|
| P2 | V13 상태에 legacy pending/approved/approval을 심고 V14를 적용해 ID NULL과 기존 값을 유지하는지 확인; 신규 최소 숫자 orderId에도 35자 ID와 UNIQUE 충돌 차단 확인 |
| P3 | 다른 사용자·다른 PG ID·누락·잘못된 길이·금액·재고 만료 요청에서 승인 행/키/outbox 불변; 반복/동시 prepare가 같은 값 반환; 중복 confirm이 추가 원장/PG 호출을 만들지 않음 |
| P4 | 최초 POST body 및 query/recovery 기대값이 prepare와 동일; DONE/ABORTED/CANCELED의 다른 paymentKey/orderId/amount 또는 잘못된 JSON 유형은 종결하지 않음; legacy 숫자 일치는 허용 |
| P5 | 실제 MySQL SharedContainers와 MockRestServiceServer만 사용; 테스트 중 임의 PG 네트워크 없음; root의 heavy slot 승인 후 집중 Gradle 실행 |
| P7 | prepare/PG identity 양음 HTTP fixture와 네 서비스 migration 파일 목록 누락·추가·대체 대조; 같은 commit의 이미지로 실제 Compose A/E 검증 |
| P6 | migration-grant, integration-container, consistency, writing, diff 검사 및 필수 전체 CI; 미실행은 감사에서 명시 |

## 5. 완료 조건과 미해결

P1~P8 로컬 구현 및 검증을 완료하고 최종 SHA/PR을 전달한다. 실제 PG 승인·취소·조회, 외부 webhook 수신, 운영 checkout 소비자의 전환 및 혼합 배포 차단은 수행하지 않는다. 따라서 이 변경만으로 D-064 전체 완료를 주장하지 않는다. TASKS·PHASE5·마스터 계획은 주 작업자가 소유한다. docs/06은 13-3만 수정하며 다른 작업자의 E2E 13-1을 건드리지 않는다.

공식 계약: [Toss 코어 API](https://docs.tosspayments.com/reference)의 orderId는 허용 문자로 된 6~64자 문자열이며 결제 요청에서 생성한 값을 저장하고 유지한다. 2026-10-21 직접 확인했다.


## 로컬 검증 결과

집중 6개 클래스 58개, 실제 MySQL 44개를 통과했다. PR #193의 고정 head `6886c19`를 merge한 뒤 controller 28개, 실제 MySQL webhook 10개와 merchant 6개를 다시 통과했다. migration은 세션 임시 테이블의 대표 legacy 행으로 비재작성을 확인했고 전체 application schema는 Flyway 기동으로 검증했다. 실제 PG·운영 전환은 미수행이며 최종 head의 전체 CI·독립 검토는 대기다. #193 실제 merge인 main `a7ffa11`을 `aa33234`로 반영했다. 기존 merchant authored patch는 byte-identical이고 이후 product 테스트 진단만 별도 보완했다.

독립 검토의 E2E P1 보완 후 소스 `bb944c3`에서 smoke 59개 및 PG stub 41개(9+32)를 통과했다. 같은 commit에서 정상 Dockerfile로 만든 4개 이미지로 실제 Compose A/E가 통과했다. E는 runner 298.7초로 기존 300초 예산에 근접했고 전체 A~F/음성 대조 및 최종 head 검토는 CI에서 계속 확인한다.


기존 product CI 실패는 같은 seed 전체 suite에서 barrier timeout으로 재현했지만 SQL/Java 대기 원인은 확정하지 못했다. 확장 진단의 단독 및 전체 product 234개는 통과했다. 참여자 한 명을 억제한 음성 대조는 기존 30초 기준에서 실패하며 5초 snapshot·원인 출력·cleanup을 검증했고 원본 파일 hash로 복구했다. 이 결과는 merchant 결함을 고쳤다는 증거가 아니며, 원인 분석 잔여는 D-078 관련 후속으로 추적한다. 최종 CI와 독립 검토가 이 잔여의 처분을 판단한다.


독립 검토 P2에 따라 진단 DB 연결·읽기·SQL 실행도 유한 timeout으로 바꾸고 관찰 오류가 worker 판정을 가리지 않도록 했다. 실제 무응답 소켓·느린 SQL을 포함한 4개 집중 검사 및 진단 SQL 실패/참여자 누락 음성 대조를 검증했다. 원 seed 전체 product는 236개 통과했다. 이후 실제 main `ec677b3`을 `dc30479`로 통합했고 전체 authored patch와 해당 test 파일의 byte-identical을 확인했다. 최종 CI·재검토는 대기이며 최초 비주입 timeout 원인 해소를 주장하지 않는다.
