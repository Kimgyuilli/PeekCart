## 6. 기능적 요구사항

### 6-1. 회원 (User)

- 회원가입 / 로그인 (JWT Access + Refresh Token)
- 회원 정보 조회 및 수정
- 관리자 / 일반 사용자 역할 분리 (RBAC)

### 6-2. 상품 (Product)

- 상품 등록 / 수정 / 삭제 (관리자 전용)
- 상품 목록 조회 (페이징, 카테고리 필터)
- 상품 상세 조회
- 재고 수량 관리

### 6-3. 주문 (Order)

- 장바구니 담기 / 수정 / 삭제
- 주문 생성 → `@TransactionalEventListener`로 결제/알림 도메인에 이벤트 전달 (Phase 1)
- 주문 생성 → Outbox 테이블 저장 → Kafka 이벤트 발행 (Phase 2 이후)
- 주문 내역 조회 (페이징)
- 주문 취소 (재고 롤백 포함)

### 6-4. 결제 (Payment)

- Toss Payments 결제 위젯 연동 (가상 결제)
- 결제 승인 / 실패 처리
- 결제 실패 시 주문 롤백 (Phase 1: `@TransactionalEventListener` / Phase 4: Choreography Saga)
- 결제 내역 조회
- 웹훅(Webhook) 수신 처리

### 6-5. 알림 (Notification)

- **Phase 1**: `@TransactionalEventListener`로 이벤트 수신 → Slack Webhook으로 알림 발송
- **Phase 2+**: Kafka Consumer로 이벤트 수신 → Slack Webhook으로 알림 발송
- 알림 내역 DB 저장 및 조회

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
| GET | `/api/v1/orders` | 주문 내역 조회 | O |
| GET | `/api/v1/orders/{id}` | 주문 상세 조회 | O |
| POST | `/api/v1/orders/{id}/cancel` | 주문 취소 | O |
| POST | `/api/v1/payments/confirm` | 결제 승인 | O |
| GET | `/api/v1/payments/{orderId}` | 결제 내역 조회 | O |
| POST | `/api/v1/payments/webhook` | Toss 웹훅 수신 | X (서명 검증) |
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
    - Phase 1: `@TransactionalEventListener`로 도메인 간 이벤트 처리 (로컬 트랜잭션)
    - Phase 2~: Outbox 패턴 + Kafka로 전환, 이벤트 유실 방지
- **Outbox 발행 실패 처리**: `retry_count` 증가 → 최대 재시도 횟수 초과 시 `FAILED` 상태로 전환, 별도 알림
- **Saga 패턴 (Phase별 구분)** (Phase 4 경계는 see ADR-0010)
    - Phase 1: `@TransactionalEventListener`로 결제 실패 시 로컬 보상 트랜잭션 처리
    - Phase 4: Choreography-based Saga — `payment.failed` → Order Service 주문 취소 → `order.cancelled` → **Product Service 재고 복구** (재고 소유자가 Product 이므로 복구 주체도 Product). 재고 예약/차감 경계는 ADR-0010 §D3 F2 참조 (확정은 A3)
- **재고 동시성 제어**
    - 1차: Redis 분산 락으로 동시 요청 직렬화 (획득 실패 시 즉시 409 응답)
    - 2차: DB 낙관적 락 (`version` 컬럼) — Redis 장애/만료 시 최후 방어선
- **JWT Refresh Token 전략**
    - `refresh_tokens` 테이블(DB)에 토큰 저장 → 영속성 보장, 발급 이력 관리
    - Redis는 로그아웃된 토큰 블랙리스트 용도로만 사용 → 역할 분리, 중복 아님
    - Refresh Token Rotation 적용 (재발급 시 기존 토큰 즉시 무효화)
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
- **웹훅 서명 검증**: Toss Payments 웹훅 수신 시 요청 헤더의 서명을 Secret Key로 HMAC 검증 → 위변조 방지

### 7-6. 로깅 및 분산 트레이싱

- **Correlation ID 전파**: Micrometer Tracing을 활용하여 요청별 Trace ID를 전체 서비스 체인에 전파
- **구조화된 로깅**: JSON 포맷 로그 출력, MDC(Mapped Diagnostic Context)에 traceId · userId · orderId 포함
- **Phase별 적용**: Phase 1에서 MDC + JSON 로깅 적용, Phase 4에서 서비스 간 Correlation ID 전파 구현
