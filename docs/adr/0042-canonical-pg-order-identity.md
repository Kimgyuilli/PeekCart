# ADR-0042: Checkout부터 복구까지 유지하는 PG 주문 식별자

- 상태: Accepted
- 결정일: 2026-10-21
- 관련: D-064, ADR-0023, ADR-0034, ADR-0035

## Context

Payment의 내부 orderId는 숫자이고 현재 PG 승인과 운영 복구가 이를 문자열로 바꾼다. Toss의 주문 식별자는 6~64자이므로 초기 숫자 주문은 유효하지 않다. 서버에서 승인 순간 prefix를 붙이면 클라이언트가 결제 인증 때 사용한 값과 달라진다. 기존 외부 결제가 이미 어떤 숫자 식별자로 등록됐는지는 새 형식으로 소급 변경할 수 없다.

사용자는 선택지를 안내받고 “권장하는 방향으로 진행”을 승인했다. 신규 PG 식별자를 생성·저장하고 checkout 준비 응답과 승인 접수를 연결하는 계약을 별도 PR로 구현한다. 실제 PG 호출·배포·외부 checkout 운영 전환은 이 결정의 실행 범위에 포함하지 않는다.

## Decision

### 1. 내부 주문과 PG 주문 식별자를 구분한다

내부 orderId, 이벤트 payload, Kafka partition key, 승인 멱등키 `approve-<내부 orderId>`는 유지한다. 새 Payment 생성 시 `pc_`와 UUID 소문자 hex 32자를 이어 35자 pgOrderId를 생성하고 payments.pg_order_id에 저장한다. 같은 결제에서 값을 다시 생성하거나 수정하지 않는다. payments의 UNIQUE 인덱스가 충돌을 차단하며 충돌한 저장은 실패한다.

payments와 payment_approvals에 nullable VARCHAR(64) pg_order_id를 추가한다. 접수 트랜잭션은 payments의 값을 승인 원장에 snapshot으로 저장한다. dispatcher, 자동 조회와 운영 복구는 이 snapshot으로 동일 결제를 식별한다. 별도 서비스나 전역 ID 발급기는 도입하지 않는다.

### 2. 인증 전에 제공한 값을 그대로 승인한다

canonical pgOrderId가 있고 승인 원장이 없는 PENDING 결제의 인증된 소유자가 `POST /api/v1/payments/{orderId}/prepare`를 호출하면 서버 금액과 내부 orderId, pgOrderId를 반환한다. 준비 API는 결제 가능 상태·재고 준비·lease를 확인하지만 PG를 호출하거나 승인 fence를 생성하지 않는다. 반복 호출은 같은 저장 값을 돌려준다. 클라이언트는 이 pgOrderId를 Toss 결제 요청의 orderId로 사용하고 성공 callback의 같은 값을 confirm의 pgOrderId로 돌려준다.

confirm의 내부 orderId와 pgOrderId는 역할이 다르다. 서버는 소유권·금액과 함께 pgOrderId를 정확히 비교하고 승인 원장을 만들기 전에 거절한다. 준비 API 응답 자체를 재고 보장으로 취급하지 않으며 기존 접수 검증과 Product 보호 회신을 유지한다. 이미 접수한 주문은 기존 PAY-013 fence로 거절하고 새 승인을 만들지 않는다.

### 3. Legacy 식별자는 바꾸지 않는다

마이그레이션은 기존 값을 backfill하지 않는다. 기존 NULL은 종전 숫자 문자열 계약이다. 이미 pending/approved/복구 원장이 있는 행은 해당 숫자로 조회하며, 짧은 숫자였더라도 새 ID로 재승인하지 않는다.

새 prepare는 non-NULL canonical ID가 있는 결제만 허용한다. 기존 NULL payment는 숫자 길이와 무관하게 새 prepare를 거절하고 새 주문을 요청한다. 이미 인증된 기존 client의 callback을 위한 confirm은 숫자가 6~64자 규칙을 만족할 때만 유지한다. pgOrderId 생략은 이 NULL 행에서만 종전 숫자 값으로 해석한다. 짧은 숫자의 아직 미접수 결제는 confirm도 거절한다. 신규 non-NULL 행은 pgOrderId 생략·숫자 fallback·다른 값을 허용하지 않는다. 임의의 외부 식별자를 저장하는 전환 API는 만들지 않는다.

### 4. PG 응답도 같은 결제인지 확인한다

최초 승인 성공·자동 조회·운영 복구의 응답은 paymentKey, orderId, totalAmount가 원장의 값과 일치해야 한다. 필드 유형도 확인한다. 누락·불일치·파싱 실패는 UNRESOLVED 또는 운영 복구 증적 거절이며 성공이나 실패로 종결하지 않는다. 이 검사는 반환된 상태를 신뢰하기 전에 수행한다. 조회 결과로 최초 승인을 재호출하지 않는 원칙은 유지한다.

### 5. 배포는 접수 경계를 함께 전환한다

nullable additive migration은 기존 데이터의 보존을 위한 것이다. 새 필드를 모르는 구 바이너리의 안전한 혼합 실행을 보장하지 않는다. 구 confirm DTO는 새 필드를 무시할 수 있고 구 dispatcher는 숫자를 보낸다.

운영 전환 시 신규 승인 접수를 중지하고 schema를 적용한 뒤 모든 payment 생성자·접수기·dispatcher·조회기와 checkout 소비자를 새 계약으로 전환해야 한다. 구 인스턴스가 없어지고 prepare 응답과 callback/confirm 전달을 확인한 후 접수를 재개한다. 식별자를 새 형식으로 저장한 행이 생긴 뒤 구 바이너리로 단순 rollback하지 않는다. 문제 시 접수를 중지하고 새 식별자를 이해하는 버전에서 수정한다. 본 PR은 이 배포를 실행하거나 성공했다고 주장하지 않는다.

## Alternatives

- 승인 때만 숫자를 padding/prefix 처리: 인증 단계와 불일치하므로 기각.
- 기존 행을 일괄 UUID로 backfill: 이미 등록된 PG 식별자를 바꾸므로 기각.
- 외부 checkout이 임의 ID를 정하고 server가 받아 저장: 현재 신뢰 가능한 구현·증거가 없으며 주문 연결의 추가 계약이 필요하므로 선택하지 않음.
- 짧은 숫자 주문도 그대로 신규 승인: 공식 길이 계약을 계속 위반하므로 기각.

## Consequences

신규 client는 prepare를 먼저 호출하고 pgOrderId를 전달해야 한다. 기존 유효한 숫자 주문과 이미 접수한 원장은 유지된다. 오래된 짧은 숫자의 미접수 주문은 새 주문이 필요하다. UUID 생성 실패·충돌을 조용히 다른 값으로 재시도하지 않아 잘못된 checkout 연결을 만들지 않는다.

로컬 검증은 실제 MySQL의 migration·동시 준비·원장 원자성 및 HTTP fixture의 body/identity mismatch를 다룬다. 실제 PG와 외부 webhook 수신은 별도 증거가 필요하며 D-064 전체는 부분 완료로 유지한다.

## References

- [Toss 코어 API](https://docs.tosspayments.com/reference): orderId 허용 문자, 6~64자, 결제 전 생성·저장·불변 계약. 2026-10-21 확인.
