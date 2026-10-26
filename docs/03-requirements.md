## 6. 기능적 요구사항

### 6-1. 회원 (User)

- 회원가입 / 로그인 (JWT Access + Refresh Token)
- 회원 정보 조회 및 수정
- 관리자 / 일반 사용자 역할 분리 (RBAC)

### 6-2. 상품 (Product)

- 상품 등록 / 수정 / 삭제 (관리자 전용)
- 상품 목록 조회 (페이징, 카테고리 필터)
  - 기본 정렬 id ASC. `sort=id|name|price,asc|desc` 지원, id가 없으면 id ASC 동률 기준 추가.
  - 미지원/중복 정렬 필드는 SYS-003 400. 정규화된 정렬별 캐시 분리(see ADR-0037).
  - 캐시 miss의 동시 DB 진입 상한을 넘으면 PRD-005 503. cache hit은 이 제한을 통과하지 않음.
- 상품 상세 조회
- 재고 수량 관리

### 6-3. 주문 (Order)

- 장바구니 담기 / 수정 / 삭제
- 주문 생성 → Outbox 테이블 저장 → Kafka 이벤트 발행 (Phase 2 이후)
- 주문 내역 조회 (페이징)
- 주문 취소 (재고 롤백 포함)

### 6-4. 결제 (Payment)

- Toss Payments 결제 위젯 연동 (가상 결제)
- 결제 승인 / 실패 처리
- 결제 실패 시 주문 롤백 (Phase 1: `@TransactionalEventListener` / Phase 4: Choreography Saga)
- 결제 내역 조회
- 웹훅(Webhook) 수신 처리
- **자동 환불 보상 (Phase 4)** — "결제는 승인됐는데 재고가 확정되지 않았거나 주문이 취소된" saga 최악 경로에서
  사람의 개입 없이 환불을 실행하고 결과를 원장에 종결 상태로 남깁니다. 확정할 수 없는 구간(외부 PG 결과 불명)은
  없애는 것이 아니라 **관측 가능한 미해결 상태**(`UNRESOLVED` + backlog/age 게이지)로 만들고, 24시간 내
  미확정 시 감사 필드를 요구하는 수동 종결 경로로 넘어갑니다 (see ADR-0018)

### 6-5. 알림 (Notification)

- **Phase 2+**: Kafka Consumer로 이벤트 수신 → Slack Webhook으로 알림 발송
- 알림 내역 DB 저장 및 조회
- 환불 완료 알림 — **성공(`payment.refunded(SUCCEEDED)`)만** 발송합니다. 실패·미확정은 내부 미결 상태이며
  사용자에게 전가하지 않고, 운영이 해소한 뒤 성공 알림으로 수렴합니다 (see ADR-0018 §D6)

### 6-6. 핵심 API 경로 목록

> URL 규칙: `/api/v1/{도메인}/...` — 상세 명세는 Swagger(springdoc)로 자동 생성합니다.
>

| 메서드 | 경로 | 설명 | 인증 |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/signup` | 회원가입 | X |
| POST | `/api/v1/auth/login` | 로그인 | X |
| POST | `/api/v1/auth/refresh` | 토큰 재발급 | X |
| POST | `/api/v1/auth/logout` | 로그아웃 | O |
| GET | `/api/v1/users/me` | 내 정보 조회 | O |
| PUT | `/api/v1/users/me` | 내 정보 수정 | O |
| GET | `/api/v1/products` | 상품 목록 조회 | X |
| GET | `/api/v1/products/{id}` | 상품 상세 조회 | X |
| POST | `/api/v1/admin/products` | 상품 등록 (관리자) | O (ADMIN) |
| PUT | `/api/v1/admin/products/{id}` | 상품 수정 (관리자) | O (ADMIN) |
| DELETE | `/api/v1/admin/products/{id}` | 상품 삭제 (관리자) | O (ADMIN) |
| GET | `/api/v1/cart` | 장바구니 조회 | O |
| POST | `/api/v1/cart/items` | 장바구니 담기 | O |
| PUT | `/api/v1/cart/items/{id}` | 장바구니 수량 수정 | O |
| DELETE | `/api/v1/cart/items/{id}` | 장바구니 항목 삭제 | O |
| POST | `/api/v1/orders` | 주문 생성 | O |
| GET | `/api/v1/orders` | 주문 내역 조회 (**커서 페이지네이션** — `cursor`(불투명 문자열, 생략 시 첫 페이지) · `size`(1~100, 기본 20). 응답 `{content, nextCursor, hasNext}`. **breaking change (구현 ⑥)**: `page`/`sort`/`offset` 은 400 `ORD-012`, `totalElements`/`totalPages` 미제공) | O |
| GET | `/api/v1/orders/{id}` | 주문 상세 조회 | O |
| POST | `/api/v1/orders/{id}/cancel` | 주문 취소 | O |
| POST | `/api/v1/payments/{orderId}/prepare` | 소유자의 신규 결제 PG 주문 ID와 서버 금액 조회 | O |
| POST | `/api/v1/payments/confirm` | 비동기 결제 승인 접수 (202) | O |
| GET | `/api/v1/payments/{orderId}` | 결제 내역 조회 | O |
| POST | `/api/v1/payments/webhook` | 일반 결제 상태 알림의 제한된 조회 가속 접수 | X (비신뢰 신호, ADR-0041) |
| GET | `/api/v1/notifications` | 알림 내역 조회 | O |

---

## 7. 비기능적 요구사항

### 7-1. 성능

> 아래 수치는 일반적인 이커머스 레퍼런스 기준의 초기 목표값입니다. 최종 목표값은 Phase 3 Task 3-4 부하 테스트의 baseline 측정 결과로 확정합니다.
>
> **측정 환경**: GCP GKE (asia-northeast3-a, e2-standard-4), 부하 발생기는 같은 zone 의 별도 Compute Engine VM. 측정 방침 및 수치 해석은 `docs/04-design-deep-dive.md` §10-7, 환경 선택 근거는 ADR-0004 참고. 환경 요약은 `docs/01-project-overview.md` §4.
>

| 항목 | 목표 수치 | 측정 방법 |
| --- | --- | --- |
| 상품 목록 API 응답시간 | p99 기준 100ms 이하 | nGrinder 부하 테스트 |
| Redis 캐싱 개선 효과 | 캐시 미적용 대비 TPS 3배 이상 | 캐싱 전/후 TPS 비교 (동일 GKE 환경 내) |
| 동시 주문 처리 | 1,000 VUser 동시 주문 정합성 100% | k6 동시성 시나리오 |
| 주문 이벤트 처리 | Kafka Consumer Lag 0 유지 (정상 구간) | Prometheus 모니터링 |
| K8s HPA 스케일아웃 | 부하 급증 시 Pod 자동 증설 검증 | nGrinder + Grafana 연계 |

### 7-2. 안정성

- **이벤트 처리 (Phase별 구분)**
    - Phase 2~: Outbox 패턴 + Kafka로 전환, 이벤트 유실 방지
- **Outbox 발행 실패 처리**: `retry_count` 증가 → 최대 재시도 횟수 초과 시 `FAILED` 상태로 전환, 별도 알림
- **Saga 패턴 (Phase별 구분)** (Phase 4 경계는 see ADR-0010)
    - Phase 4: Choreography-based Saga — `order.created` → Product 재고 예약 → `stock.reservation.result` → 결제, `payment.failed`/`order.cancelled` → **Product 재고(예약) 복구** (재고 소유자가 Product). 재고 예약/차감 경계·토픽 매트릭스 확정은 ADR-0012 §D3/§D4 (see ADR-0012). **보상 수렴**: 환불이 필요한 상태를 감지한 서비스가 요청 토픽(`stock.compensation.requested` · `order.compensation.requested`)을 발행하고, Payment 가 `payment_refunds.order_id` UNIQUE fence 로 합류시켜 실행한 뒤 `payment.refunded` 로 각 원장을 닫습니다 (see ADR-0018)
- **재고 동시성 제어**
    - `Inventory.@Version`과 DB 트랜잭션 원자성으로 보호하며 재고 경로에는 Redis 분산 락을 사용하지 않습니다 (see ADR-0025).
    - Product Kafka 소비 중 낙관락 충돌은 jitter를 적용해 최대 3회 재시도하고, 소진하면 DLQ로 보냅니다. HTTP 경로의 낙관락 충돌은 `PRD-004`/409로 응답합니다.
- **JWT Refresh Token 전략**
    - `refresh_tokens` 테이블(DB)에 토큰 저장 → 영속성 보장, 발급 이력 관리
    - Redis는 로그아웃된 토큰 블랙리스트 (+ Phase 4: family/session deny — 탈취 즉시 차단, see ADR-0013) 용도 → 역할 분리(저장은 DB, 즉시 차단/deny enforcement는 Redis)
    - Refresh Token Rotation 적용 (재발급 시 기존 토큰 즉시 무효화). Phase 4: `family_id` 이력 모델 기반 **Reuse Detection**(재사용 감지 시 family 전체 무효화), RS256 + Gateway 검증 (see ADR-0013)
- **Kafka Consumer 멱등성**: `processed_events` 테이블로 중복 이벤트 체크 (at-least-once + 멱등성 처리)
- **재고 차감 시점**: 주문 생성 시 즉시 차감 (전략 A) — 결제 타임아웃 15분 초과 시 자동 취소 + 재고 복구 스케줄러 적용
- Kafka Consumer 장애 시 메시지 유실 방지 (at-least-once 보장)
- K8s Liveness / Readiness Probe로 비정상 Pod 자동 재시작

### 7-3. 모니터링

- kube-prometheus-stack으로 클러스터 + 애플리케이션 메트릭 통합 수집
- 실시간 대시보드: API 응답시간, 에러율, Kafka Consumer Lag, JVM 힙 메모리, Pod CPU/메모리, HPA 스케일 이벤트
- 임계치 초과 시 Grafana Alert 설정
- Actuator + Micrometer 연동으로 Spring 내부 메트릭 자동 수집

### 7-4. 확장성

- K8s HPA 기반 Order Service 자동 수평 확장
- MSA 분리 시 비동기 통신(Kafka 이벤트)을 기본으로 하되, 필요 시 로컬 캐시(CQRS)로 동기 호출을 대체 (상세: 섹션 9-13)
- 환경 변수 기반 설정 분리 (`application-dev` / `prod` profile)
- 모노레포(Gradle 멀티모듈)로 전체 구조 관리, `common` 모듈 공유

### 7-5. 보안

- **입력값 검증**: Bean Validation(`@NotBlank`, `@Min`, `@Email` 등) 활용, Controller DTO 레벨에서 유효성 검사
- **Rate Limiting**: Spring Cloud Gateway에서 API별 요청 제한 (기본: 분당 60회, 로그인 시도: 분당 10회)
- **일반 결제 웹훅**: 16KiB 이하의 `PAYMENT_STATUS_CHANGED` 중첩 `data.paymentKey`와 알려진 status만 접수한다. 알려진 UNRESOLVED 승인에 원장당 한 번의 조회 가속을 허용하며, 중복·미등록·진행 중·종결 건은 아무 상태도 바꾸지 않는다. payload는 결제 증빙이 아니고 확정은 서버 PG 조회가 담당한다(see ADR-0041).

### 7-6. 로깅 및 분산 트레이싱

- **Correlation ID 전파**: Micrometer Tracing을 활용하여 요청별 Trace ID를 전체 서비스 체인에 전파
- **구조화된 로깅**: JSON 포맷 로그 출력, MDC(Mapped Diagnostic Context)에 traceId · userId · orderId 포함
- **Phase별 적용**: Phase 1에서 MDC + JSON 로깅 적용, Phase 4에서 서비스 간 Correlation ID 전파 구현

### 비동기 승인 응답 (see ADR-0034)

`POST /api/v1/payments/confirm`은 소유자·금액·예약 게이트를 검증하고 승인 접수를 영속한 뒤 **202 Accepted**, 조회 URL을 담은 `Location: /api/v1/payments/{orderId}` 및 결제 상태를 반환한다. 보통 `PENDING`이며 결과는 `GET /api/v1/payments/{orderId}`로 조회한다. 비동기 실행이 먼저 완료되면 접수 응답에도 최신 상태가 담길 수 있다. 기존 접수 검증 오류와 중복 접수 PAY-013은 유지한다. `PENDING`은 과금 실패를 뜻하지 않으므로 새 주문/재결제로 자동 전환하지 않는다.

Product의 재고 보호 확인을 받은 승인만 PG를 호출한다. 접수 기한 초과/보호 거절/실행 전 취소는 과금 없이 실패 회신하며, PG 결과가 불명확하면 조회로 확정할 때까지 재고를 보유한다. 주문 취소와 승인이 경합하면 기존 자동 환불 절차로 수렴한다.

### 보호 승인 미결의 조회 (see ADR-0035)

결제 조회 응답의 `status`는 결제 상태, nullable `approvalStatus`는 PG 승인 원장 상태다. 원장이 없으면 `approvalStatus=null`이다. `CANCELLED + UNRESOLVED`는 미과금 확정을 뜻하지 않는다.
보호된 승인의 운영자·사유 기록은 과금 부재의 증명이 아니므로 조회 복구와 재고 보호를 유지한다. 근거 없는 강제 종결 API는 제공하지 않는다.


### 보호 승인 대사 복구 (see ADR-0040, 서버 직접 확정 조회 범위 Accepted)

운영 복구는 서버가 PG에서 직접 확인한 결제키·주문·금액 일치와 확정 무과금 결과, Product 예약 snapshot, 필요 시 전액 환불 원장 대사를 요구한다. 기존 서명 내부 토큰의 ADMIN principal 중 서로 겹치지 않는 승인자·실행자 목록만 접근한다. 기본 목록은 비어 있으며 요청의 `resolvedBy`나 평문 사용자 헤더는 신원 증거가 아니다.

아래 상대 경로의 prefix는 `/api/v1/payments/approval-recoveries`다. 쓰기는 `reason`(1~500자)을 필수로 받고, 응답은 `ApiResponse` 안의 id/orderId/status/generation만 제공한다. PG 원문과 paymentKey는 반환하지 않는다.

| 메서드·경로 | 역할 | 계약 |
|---|---|---|
| POST `/orders/{orderId}` | 승인자 | PG 직접 조회 후 예약 대사 요청. 활성/완료 요청 재전송은 기존 id 반환 |
| GET `/{id}` | 승인자 또는 실행자 | 복구 상태 조회 |
| POST `/{id}/approve` | 승인자 | 수신한 예약 snapshot 검토 뒤 승인 |
| POST `/{id}/execute` | 실행자 | PG 재확인·generation 재검증 후 실행 요청. 진행/완료 중복은 동일 결과와 감사만 반환 |
| POST `/{id}/resume` | 승인자 | 명시적인 Product 거절 뒤 새 PG 대사·generation·snapshot·재승인 절차 개시 |
| POST `/{id}/retry` | 실행자 | 응답 유실 시 PG 재확인 뒤 같은 id/generation/version 실행만 재전달 |

실행 요청은 완료가 아니며 Product 해제 확인 뒤에만 COMPLETED다. 예약 변경 시 REVIEW_REQUIRED와 승인 RECOVERY_PENDING을 유지한다. 이전 generation이나 reservation version은 새 실행에 사용할 수 없다. 늦은 PG DONE은 해제하지 않고 검증된 성공 증거를 기존 승인/고아 환불 경로에 전달한다. 404·불명·부분 취소·증빙 모순·PG 결과 변경은 해제 근거가 아니다. 영구 404의 외부 PG 증빙 경로는 미지원이며 D-084 전체는 열린 상태다. 상세 운영 계약은 [복구 runbook](runbooks/approval-recovery.md)을 따른다.


### PG 주문 식별자와 checkout (see ADR-0042)

신규 결제는 내부 숫자 `orderId`와 별도 `pgOrderId`를 가진다. 소유자는 먼저 `POST /api/v1/payments/{orderId}/prepare`로 `{orderId, pgOrderId, amount}`를 받는다. 클라이언트는 `pgOrderId`를 Toss 인증 요청의 `orderId`로 사용하고, 성공 callback의 같은 값을 `{paymentKey, orderId: 내부 숫자, pgOrderId, amount}`로 confirm에 전달한다. prepare는 반복해도 같은 ID를 반환하고 PG를 호출하지 않는다. prepare 이후에도 승인 접수의 재고·lease 검증은 다시 수행한다.

새 행의 누락·불일치 PG ID는 PAY-014로 거절한다. legacy NULL 행은 prepare를 허용하지 않으며 PAY-015로 새 주문을 요청한다. 이미 인증된 기존 callback의 confirm은 기존 숫자 ID가 6~64자일 때만 유지하고 생략된 pgOrderId도 그 숫자로만 해석한다. 기존 승인 원장의 식별자는 재작성하지 않는다. 결제 조회 응답에도 유효 식별자를 `pgOrderId`로 제공한다. 이미 접수한 원장은 prepare를 거절하며 중복 confirm은 PAY-013을 유지한다.

운영 전환은 신규 접수를 중지한 상태에서 schema, 전체 payment writer/dispatcher와 checkout 소비자를 같은 계약으로 전환한 후 재개한다. 구 바이너리는 새 필드를 무시할 수 있어 혼합 실행과 단순 rollback을 허용하지 않는다. 실제 전환·PG 연동 검증은 별도 실행 항목이다.
