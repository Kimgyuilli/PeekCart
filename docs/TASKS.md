# PeekCart — Task 관리

> Phase별 작업 항목과 현재 상태를 추적합니다.
> 상태: `🔲 대기` / `🔄 진행 중` / `✅ 완료` / `⏸ 보류`

---

## Phase 1 — 모놀리식 구현

**Phase 1 Exit Criteria** (`docs/07-roadmap-portfolio.md` 참고):
- [x] 모든 도메인 CRUD API 정상 동작 (Swagger UI 기준)
- [x] 주문 → 결제 → 알림 전체 플로우 정상 처리
- [x] 주문 상태 전이 검증 완료 (결제 성공/실패/타임아웃)
- [x] 결제 타임아웃 스케줄러 동작 확인

---

## Phase 1 Tasks

### Task 1-1: 프로젝트 초기 설정
**상태**: ✅ 완료
**목표**: 빌드 구성, 공통 구조, 로컬 개발 환경 세팅 완료

| 항목 | 상태 | 비고 |
|------|------|------|
| `settings.gradle` 생성 | ✅ | |
| `build.gradle` 작성 (의존성 포함) | ✅ | |
| `docker-compose.yml` 작성 (MySQL + Redis) | ✅ | |
| `application.yml` / `application-local.yml` | ✅ | |
| `V1__init_schema.sql` Flyway 초기 스키마 | ✅ | Phase 1 ERD 기준 |
| `PeekCartApplication.java` | ✅ | |
| `global/response/ApiResponse.java` | ✅ | 표준 응답 포맷 |
| `global/exception/ErrorCode.java` | ✅ | 도메인별 에러 코드 |
| `global/exception/BusinessException.java` | ✅ | 추상 예외 클래스 |
| `global/exception/GlobalExceptionHandler.java` | ✅ | |
| `global/config/SecurityConfig.java` | ✅ | |
| `global/config/RedisConfig.java` | ✅ | JWT 블랙리스트 전용 |
| `global/jwt/JwtProvider.java` | ✅ | |
| `global/jwt/JwtFilter.java` | ✅ | |
**완료 기준**: 애플리케이션 구동 + Swagger UI 접근 + Docker Compose 정상 실행

---

### Task 1-2: User 도메인
**상태**: ✅ 완료
**목표**: 회원가입/로그인, JWT 인증, RBAC 구현

| 항목 | 상태 | 비고 |
|------|------|------|
| `User` Entity + 비즈니스 로직 | ✅ | BaseEntity 상속, create/matchesPassword/updateProfile |
| `RefreshToken` Entity | ✅ | BaseTimeEntity 상속, DB 저장 |
| `Address` Entity | ✅ | |
| `UserRole` Enum (VO) | ✅ | |
| `UserRepository` 인터페이스 | ✅ | |
| `UserRepositoryImpl` + `UserJpaRepository` | ✅ | |
| `RefreshTokenRepository` 인터페이스 | ✅ | |
| `RefreshTokenRepositoryImpl` + `RefreshTokenJpaRepository` | ✅ | |
| `TokenBlacklistRepository` (Redis) | ✅ | 블랙리스트(bl:) + 그레이스 피리어드(gp:) |
| `AuthService` — 회원가입/로그인/로그아웃/토큰 재발급 | ✅ | Grace Period 포함 |
| `UserCommandService` / `UserQueryService` | ✅ | |
| `AuthController` / `UserController` | ✅ | |
| 단위 테스트 (Domain + Application) | ✅ | Domain 4건 + Application 14건, 전부 통과 |
| 슬라이스 테스트 (Presentation) | ✅ | AuthController 5건 + UserController 3건, 전부 통과 |

**완료 기준**: 회원가입 → 로그인 → 토큰 재발급 → 로그아웃 시나리오 정상 동작

---

### Task 1-3: Product 도메인
**상태**: ✅ 완료
**목표**: 상품 CRUD (관리자), 목록/상세 조회, 재고 관리

| 항목 | 상태 | 비고 |
|------|------|------|
| `Product` Entity | ✅ | BaseTimeEntity 상속, update/discontinue/isOnSale |
| `Category` Entity | ✅ | 자기 참조 (parent_id), @ManyToOne LAZY |
| `Inventory` Entity | ✅ | @Version 낙관적 락, decrease/restore 비즈니스 메서드 |
| `ProductStatus` Enum (VO) | ✅ | ON_SALE / SOLD_OUT / DISCONTINUED |
| Repository 계층 | ✅ | 인터페이스 3개 + JPA 3개 + Impl 3개 |
| `ProductCommandService` (관리자 CRUD) | ✅ | create(Product+Inventory 단일 트랜잭션), update, delete(soft) |
| `ProductQueryService` (목록/상세, 페이징) | ✅ | ON_SALE 필터, categoryId 옵션 |
| `InventoryService` (재고 차감/복구) | ✅ | Order 도메인 호출 대상 |
| `ProductController` / `AdminProductController` | ✅ | RBAC 적용, SecurityConfig 공개 URL 추가 |
| 단위 테스트 | ✅ | Domain 11건 + Application 16건 + Presentation 10건, 전부 통과 |

**완료 기준**: 상품 등록 → 목록 조회 (페이징/카테고리 필터) → 상세 조회 정상 동작

---

### Task 1-4: Order 도메인
**상태**: ✅ 완료
**목표**: 장바구니, 주문 생성 (재고 즉시 차감), 주문 상태 전이, 이벤트 발행

| 항목 | 상태 | 비고 |
|------|------|------|
| `Order` Entity (상태 전이 로직 포함) | ✅ | cancel(), transitionTo(), OrderItemData로 순환 의존 제거 |
| `OrderItem` Entity | ✅ | `unit_price` 스냅샷, 패키지 내부 생성자 |
| `OrderStatus` Enum (VO) | ✅ | 8개 상태 + canTransitionTo() 전이 규칙 캡슐화 |
| `Cart` / `CartItem` Entity | ✅ | addItem 중복 병합, get-or-create 패턴 |
| Repository 계층 | ✅ | 인터페이스 2개 + JPA 2개 + Impl 2개 |
| `OrderCommandService` — 주문 생성, 취소 | ✅ | 재고 즉시 차감 + 이벤트 발행 |
| `OrderQueryService` — 주문 내역 (페이징) | ✅ | |
| `CartService` — 장바구니 CRUD | ✅ | CartCommandService + CartQueryService 분리 |
| `OrderEventListener` (`@TransactionalEventListener`) | ✅ | payment.approved/failed 수신 → 주문 상태 전이 + 재고 복구 (Task 1-5에서 구현) |
| `OrderController` / `CartController` | ✅ | |
| 단위 테스트 | ✅ | Domain 58건 + Application 16건 + Presentation 15건 = 89건, 전부 통과 |

**완료 기준**: 장바구니 → 주문 생성 (재고 차감) → 주문 취소 (재고 복구) 정상 동작

---

### Task 1-5: Payment 도메인
**상태**: ✅ 완료
**목표**: Toss Payments 연동, 결제 승인/실패, 웹훅 수신

| 항목 | 상태 | 비고 |
|------|------|------|
| `Payment` Entity | ✅ | PENDING/APPROVED/FAILED 상태 전이, validateAmount, assignPaymentKey |
| `PaymentStatus` Enum (VO) | ✅ | canTransitionTo() 전이 규칙 캡슐화 |
| Repository 계층 | ✅ | 인터페이스 2개 + JPA 2개 + Impl 2개 (Payment, WebhookLog) |
| `PaymentCommandService` — 결제 승인/실패 | ✅ | userId 소유권 검증 + Toss API 연동 + 이벤트 발행 |
| `PaymentQueryService` | ✅ | userId 소유권 검증 포함 |
| `TossPaymentClient` — Toss API 연동 | ✅ | RestClient, Basic Auth |
| `PaymentEventListener` (`@TransactionalEventListener`) | ✅ | order.created 수신 → Payment(PENDING) 생성 |
| `OrderEventListener` (`@TransactionalEventListener`) | ✅ | payment.approved/failed 수신 → 주문 상태 전이 + 재고 복구 |
| `PaymentController` — 결제 승인, 조회, 웹훅 | ✅ | HMAC 서명 검증은 WebhookService에서 처리 |
| `webhook_logs` 저장 (멱등성 처리) | ✅ | `idempotency_key` UK, WebhookService에서 관리 |
| `OrderPort` + `OrderPortAdapter` | ✅ | Payment → Order 크로스 도메인 DIP |
| 단위 테스트 | ✅ | Domain 22건 + Application 12건 + Presentation 7건 = 41건, 전부 통과 |

**완료 기준**: 결제 승인 → PAYMENT_COMPLETED 상태 전이 + 웹훅 수신 정상 처리

---

### Task 1-6: Notification 도메인
**상태**: ✅ 완료
**목표**: 이벤트 수신 → Slack Webhook 알림 발송, 알림 내역 저장

| 항목 | 상태 | 비고 |
|------|------|------|
| `Notification` Entity | ✅ | NotificationType VO 포함, create |
| Repository 계층 | ✅ | 인터페이스 + JPA + Impl |
| `NotificationCommandService` | ✅ | 알림 생성 + Slack 발송 (SlackPort DIP) |
| `NotificationQueryService` | ✅ | 페이징 조회 |
| `NotificationEventListener` (`@TransactionalEventListener`) | ✅ | order.created, payment.completed/failed, order.cancelled 수신 |
| `SlackNotificationClient` — Slack Webhook 발송 | ✅ | SlackPort 구현체, RestClient, 실패 시 로그만 |
| `NotificationController` — 알림 내역 조회 | ✅ | GET /api/v1/notifications |
| 단위 테스트 | ✅ | Domain 4건 + Application 4건 + Presentation 2건 = 10건, 전부 통과 |

**완료 기준**: 주문 생성 → Slack 알림 수신 확인

---

### Task 1-7: 결제 타임아웃 처리
**상태**: ✅ 완료
**목표**: 15분 초과 주문 자동 취소 + 재고 복구 스케줄러

| 항목 | 상태 | 비고 |
|------|------|------|
| `OrderTimeoutScheduler` (`@Scheduled`) | ✅ | 60초 주기, 단일 인스턴스, ShedLock 없음 |
| `PAYMENT_REQUESTED` 상태 15분 초과 조회 | ✅ | JOIN FETCH JPQL, 인덱스: `idx_orders_status_ordered_at` |
| 자동 취소 + 재고 복구 트랜잭션 | ✅ | REQUIRES_NEW 건별 독립 트랜잭션, 실패 격리 |
| 단위 테스트 | ✅ | Service 2건 + Scheduler 3건 = 5건, 전부 통과 |

**완료 기준**: 15분 초과 주문이 CANCELLED로 자동 전이 + 재고 복구 확인

---

## Phase 2 — 성능 개선 (완료)

**Phase 2 Exit Criteria** (`docs/07-roadmap-portfolio.md` 참고):
- [x] Redis 캐싱 적용 후 통합 테스트에서 캐시 적중/무효화 동작 확인
- [x] 동시 주문 테스트 시 오버셀링 0건
- [x] Outbox → Kafka 이벤트 발행 정상 동작
- [x] DLQ 토픽으로 실패 메시지 라우팅 확인

---

## Phase 2 Tasks

### Task 2-1: Redis 캐싱
**상태**: ✅ 완료
**목표**: 상품 목록/상세 조회에 Cache Aside 패턴 적용, 응답시간 개선

| 항목 | 상태 | 비고 |
|------|------|------|
| `build.gradle` Redis 캐싱 의존성 추가 (spring-boot-starter-cache) | ✅ | |
| `CacheConfig` 설정 (RedisCacheManager, TTL, 직렬화) | ✅ | JSON 직렬화, product 30분 / products 10분 TTL |
| `ProductQueryService` 상품 상세 조회 캐싱 (`@Cacheable`) | ✅ | ProductCacheService 분리 (AOP 프록시), ProductInfoDto(재고 제외) 캐싱 |
| `ProductQueryService` 상품 목록 조회 캐싱 | ✅ | CachedPage 래퍼, ProductListDto, 페이징+카테고리 조건별 캐시 키 |
| `ProductCommandService` 상품 수정/삭제 시 캐시 무효화 (`@CacheEvict`) | ✅ | create→목록 evict, update/delete→상세+목록 evict |
| 통합 테스트 (캐시 적중/무효화 검증) | ✅ | Testcontainers Redis + MySQL, 캐시 적중/무효화 5건 |

> **캐시와 재고 분리**: 캐시에 재고를 포함하지 않습니다. 재고는 차감/복구마다 변경되어 캐시 무효화가 빈번하고, PK 단건 조회(~1ms)로 충분합니다. `@CacheEvict`는 상품 수정/삭제 시에만 동작하며, 재고 변경과 캐시가 결합되지 않아 구현이 단순합니다. 대안(재고 포함 + TTL 30초)의 트레이드오프도 인지합니다.

**완료 기준**: 캐시 적중 시 DB 조회 없이 응답, 상품 변경 시 캐시 즉시 무효화

---

### Task 2-2: Redis 분산 락 (재고 동시성 제어)
**상태**: ✅ 완료
**목표**: Redisson 분산 락 + DB 낙관적 락 이중 방어로 오버셀링 방지

| 항목 | 상태 | 비고 |
|------|------|------|
| `build.gradle` Redisson 의존성 추가 | ✅ | `org.redisson:redisson:3.27.0` |
| `RedissonConfig` 설정 | ✅ | `RedisConnectionDetails` 주입, Testcontainers `@ServiceConnection` 호환 |
| `DistributedLockManager` 구현 (Redisson RLock) | ✅ | 키: `inventory-lock:{productId}`, waitTime 3s / leaseTime 5s, Redis 장애 시 fallback |
| `InventoryService` 분산 락 적용 (재고 차감) | ✅ | 락 획득 실패 → PRD-004(409), `ProductPortAdapter` → `InventoryService` 위임으로 통합 |
| 동시성 통합 테스트 (멀티스레드 오버셀링 검증) | ✅ | Testcontainers Redis, 50스레드 동시 차감, 오버셀링 0건 |

> **데드락 방지**: 다중 상품 주문 시 productId 오름차순 정렬 후 순차 락 획득 (global ordering).
> **Redis 장애 fallback**: `DistributedLockManager`에서 Redis 연결 예외 catch → 락 없이 진행, `@Version` 낙관적 락이 최후 방어선 (설계 9-1). 동시 요청 시 낙관적 락 충돌률 증가를 감수하는 트레이드오프.

**완료 기준**: 동시 주문 테스트 시 오버셀링 0건, Redis 장애 시 DB 낙관적 락 fallback 동작

---

### Task 2-3: Kafka + Outbox 도입
**상태**: ✅ 완료
**목표**: `@TransactionalEventListener` → Outbox 패턴 + Kafka 전환, 이벤트 유실 방지

| 항목 | 상태 | 비고 |
|------|------|------|
| `docker-compose.yml` Kafka (KRaft) 추가 | ✅ | apache/kafka:3.8.1, KRaft 모드 |
| `build.gradle` spring-kafka 의존성 추가 | ✅ | spring-kafka + spring-kafka-test + testcontainers:kafka |
| Flyway `V2__outbox_processed_events.sql` 스키마 추가 | ✅ | outbox_events + processed_events(복합 UK) + 인덱스 |
| `OutboxEvent` Entity (`global/outbox/`) | ✅ | PENDING/PUBLISHED/FAILED 상태, retry_count, 횡단 관심사 |
| `OutboxEventRepository` 계층 (`global/outbox/`) | ✅ | 단일 Repository — 도메인 구분은 aggregate_type 컬럼 |
| `OrderOutboxEventPublisher` / `PaymentOutboxEventPublisher` | ✅ | 도메인별 Publisher (infrastructure/outbox/), 기존 `ApplicationEventPublisher` 대체 |
| `OutboxPollingScheduler` (`global/outbox/`) | ✅ | 5초 주기, PENDING 조회 → Kafka 발행 → PUBLISHED, 실패 시 retry_count 증가 |
| Outbox FAILED 시 Slack 알림 발송 | ✅ | retry 초과(MAX_RETRY=5) → FAILED 상태 + SlackPort로 알림 |
| 이벤트 페이로드 DTO 정의 | ✅ | KafkaEventEnvelope 래핑 + 4개 Payload record (global/outbox/dto/) |
| `KafkaConfig` 설정 (Producer/Consumer/Topic) | ✅ | 파티션 키: orderId, Consumer Group 네이밍 규칙 적용 |
| 기존 `@TransactionalEventListener` → Kafka Consumer 전환 | ✅ | PaymentEventConsumer, OrderEventConsumer, NotificationConsumer |
| Kafka 토픽 생성 설정 | ✅ | 4개 토픽, 파티션 3, Replication 1 (KafkaConfig NewTopic Bean) |
| 통합 테스트 (Outbox → Kafka 발행 → Consumer 수신 검증) | ✅ | Testcontainers Kafka + MySQL + Redis, E2E 5건 |

> **Phase 2 이벤트 소비 경로**:
> - `order.created` → PaymentEventConsumer(결제 생성) + NotificationConsumer(알림)
> - `payment.completed` → OrderEventConsumer(주문 상태 전이) + NotificationConsumer(알림)
> - `payment.failed` → OrderEventConsumer(주문 취소 + 재고 복구 직접 호출) + NotificationConsumer(알림)
> - `order.cancelled` → NotificationConsumer만 소비 (Product Consumer 분리는 Phase 4)
>
> **Phase 2 재고 복구 경로**: 모놀리스이므로 `cancelOrder()` 내에서 `inventoryService.restoreStock()` 직접 호출을 유지합니다. Product 도메인의 Kafka Consumer 분리는 Phase 4(MSA)에서 수행합니다.

**완료 기준**: 주문 생성 → Outbox 저장 → Kafka 발행 → Consumer 수신 전체 플로우 정상 동작

---

### Task 2-4: Consumer 멱등성
**상태**: ✅ 완료
**목표**: `processed_events` 테이블 기반 중복 소비 방지

| 항목 | 상태 | 비고 |
|------|------|------|
| `ProcessedEvent` Entity (`global/idempotency/`) | ✅ | `(event_id, consumer_group)` 복합 UK |
| `ProcessedEventRepository` 계층 (`global/idempotency/`) | ✅ | 인터페이스 + JPA + Impl |
| Consumer 멱등성 처리 로직 (event_id + consumer_group 중복 체크) | ✅ | `IdempotencyChecker.executeIfNew()` + Consumer 3개(7메서드) 적용 |
| 멱등성 통합 테스트 (동일 이벤트 2회 소비 시 1회만 처리) | ✅ | Testcontainers Kafka + MySQL + Redis, 2건 |

**완료 기준**: 동일 (event_id, consumer_group) 중복 소비 시 1회만 실행, 다른 consumer_group은 독립 처리

---

### Task 2-5: DLQ 구성
**상태**: ✅ 완료
**목표**: Consumer 재시도 실패 시 DLQ 토픽 라우팅 + Slack 알림

| 항목 | 상태 | 비고 |
|------|------|------|
| Consumer 재시도 정책 설정 (3회, fixed sequence backoff) | ✅ | FixedSequenceBackOff(1s, 5s, 30s), 단위 테스트 3건 |
| DLQ 토픽 설정 (`{원본토픽}.dlq`) | ✅ | 4개 DLQ 토픽 (KafkaConfig NewTopic Bean) |
| `DeadLetterPublishingRecoverer` 설정 | ✅ | DefaultErrorHandler + destination resolver(`{topic}.dlq`) |
| DLQ 메시지 수신 시 Slack 알림 발송 | ✅ | recoverer 람다에서 SlackPort.send() 호출 |
| DLQ 통합 테스트 (처리 실패 → DLQ 토픽 라우팅 검증) | ✅ | Testcontainers Kafka + MySQL + Redis, 1건 |

> **Consumer 코드 변경 없음**: `DefaultErrorHandler`가 listener container 레벨에서 동작하므로 기존 Consumer 3개(7메서드)는 수정 불필요. `@Transactional` + `IdempotencyChecker` save-first 패턴과 호환 (롤백 시 재시도 가능).

**완료 기준**: Consumer 3회 재시도 실패 → DLQ 토픽 이동 + Slack 알림 발송

---

### Task 2-6: ShedLock
**상태**: ✅ 완료
**목표**: 타임아웃/Outbox 스케줄러에 ShedLock 적용, 분산 환경 중복 실행 방지

| 항목 | 상태 | 비고 |
|------|------|------|
| `build.gradle` ShedLock 의존성 추가 | ✅ | shedlock-spring + shedlock-provider-jdbc-template 6.3.1 |
| Flyway `V3__shedlock.sql` 스키마 추가 | ✅ | shedlock 테이블 |
| `ShedLockConfig` 설정 (`@EnableSchedulerLock`) | ✅ | JdbcTemplateLockProvider, usingDbTime() |
| `OrderTimeoutScheduler` ShedLock 적용 | ✅ | `@SchedulerLock(name = "orderTimeoutCancelJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")` |
| `OutboxPollingScheduler` ShedLock 적용 | ✅ | `@SchedulerLock(name = "outboxPollingJob", lockAtMostFor = "PT5M", lockAtLeastFor = "PT4S")` |
| 통합 테스트 (ShedLock 동작 검증) | ✅ | Testcontainers MySQL + Redis + Kafka, 2건 |

**완료 기준**: 스케줄러 중복 실행 방지 동작 확인

---

## 현재 Phase: Phase 3 — 인프라 / 테스트 ✅ (2026-04-29 종결, throughput Threshold 는 D-002 추적으로 회부)

**Phase 3 Exit Criteria** (`docs/07-roadmap-portfolio.md` 참고):
- [x] K8s에 모든 서비스 정상 배포 확인
- [x] Grafana 대시보드에서 API 응답시간/에러율/Kafka Lag 모니터링 확인 (세션 B 시나리오 1 + 세션 C Kafka Lag/HPA)
- [x] 부하 테스트 리포트 완성 (캐싱 전/후 TPS 비교 — 세션 B + 시나리오 2/3 + Task 3-5 — 세션 C 통합)
- [x] HPA 동작 확인 (Pod 1→3 자동 증설 + Grafana 스크린샷 — 세션 C Run 1)

---

## Phase 3 Tasks

### Task 3-1: GitHub Actions CI
**상태**: ✅ 완료
**목표**: PR/push 시 빌드·테스트·Docker 이미지 빌드 자동화 파이프라인

| 항목 | 상태 | 비고 |
|------|------|------|
| 멀티스테이지 Dockerfile 작성 (Gradle 빌드 → JRE 런타임) | ✅ | eclipse-temurin:17-jdk → 17-jre, 의존성 레이어 캐싱, non-root 실행, `.dockerignore` 포함 |
| `.github/workflows/ci.yml` 워크플로우 작성 | ✅ | push/PR 트리거, 단일 job, concurrency 설정, Docker 레이어 캐시(GHA) |
| Gradle 빌드 + 단위 테스트 실행 | ✅ | `./gradlew build --no-daemon`, SPRING_PROFILES_ACTIVE=test |
| Testcontainers 통합 테스트 실행 (CI 환경) | ✅ | ubuntu runner Docker 데몬 직접 사용, @ServiceConnection 자동 설정 |
| Docker 이미지 빌드 + GHCR push | ✅ | main push 시 조건부 실행, latest + SHA 태그, JAR 파일명 고정(`app.jar`) |

**완료 기준**: PR 생성 시 빌드+테스트 자동 실행, main 머지 시 Docker 이미지 GHCR push

---

### Task 3-2: K8s 배포 (minikube 초기 / GKE 운영 전환)
**상태**: ✅ 완료 (minikube 범위) · GKE overlay/매니페스트 작성 완료 (Task 3-4 Step 0). 실 클러스터 apply 는 측정 직전 수동 수행
**목표**: K8s 매니페스트 작성. Phase 3 초기 검증은 로컬 minikube, 부하 테스트부터는 GKE 로 운영 전환 (ADR-0004 §Context 가 minikube 선택 근거 포함, 본격 전환은 ADR-0004 본문).

| 항목 | 상태 | 비고 |
|------|------|------|
| minikube 환경 설정 (CPU 4코어, Memory 8GB) | ✅ | 매니페스트 resource limits 반영, minikube start는 사용자 실행 |
| Namespace 생성 (`peekcart`) | ✅ | `k8s/base/namespace.yml` |
| `application-k8s.yml` 환경 profile 작성 | ✅ | K8s Service DNS 접속, Actuator health probe 노출 |
| ConfigMap / Secret 매니페스트 | ✅ | `k8s/base/services/peekcart/configmap.yml` + `secret.yml` |
| MySQL Deployment + Service + PVC | ✅ | `k8s/base/infra/mysql/mysql.yml`, PVC 1Gi, 크레덴셜 `secretKeyRef` 참조 |
| Redis Deployment + Service + PVC | ✅ | `k8s/base/infra/redis/redis.yml`, PVC 512Mi (JWT 블랙리스트 영속화) |
| Kafka (KRaft) Deployment + Service + PVC | ✅ | `k8s/base/infra/kafka/kafka.yml`, KRaft 단일 노드, PVC 1Gi |
| PeekCart Application Deployment + Service | ✅ | `k8s/base/services/peekcart/deployment.yml`, GHCR 이미지, `startupProbe` 포함 (`imagePullPolicy: Never` 는 minikube overlay 패치) |
| Liveness / Readiness / Startup Probe 설정 | ✅ | Actuator `/actuator/health/liveness`, `/actuator/health/readiness`, `startupProbe`(최대 150초 기동 대기) |
| Ingress 또는 NodePort 설정 | ✅ | NodePort 30080 — minikube overlay 에서 패치 적용 (`k8s/overlays/minikube/patches/peekcart-service.yml`) |
| 전체 서비스 정상 기동 검증 | ✅ | minikube 전체 Pod Running + Swagger UI 접근 확인 완료 |

> **k8s 디렉토리 구조**: Phase 3 GCP 재설계 작업으로 Kustomize `base/` + `overlays/{minikube,gke}/` 구조로 재배치 완료 (ADR-0005). 배포는 `kubectl apply -k k8s/overlays/minikube/` 또는 `k8s/overlays/gke/`. 상세 트리는 `docs/02-architecture.md` §12 Phase 3 섹션.

**완료 기준 (minikube 범위)**: `kubectl get pods -n peekcart` 전체 Running, API 호출 정상 응답 — **완료됨**

**GKE 마이그레이션** (Task 3-4 Step 0 에서 수행):
- GKE 클러스터 생성 (asia-northeast3-a, e2-standard-4 × 1)
- Kustomize `overlays/gke/` 로 배포, Artifact Registry 이미지 전환
- 상세 계획은 ADR-0004 참고

---

### Task 3-3: kube-prometheus-stack
**상태**: ✅ 완료
**목표**: Prometheus + Grafana 구축, 모니터링 대시보드 설정

| 항목 | 상태 | 비고 |
|------|------|------|
| `build.gradle` micrometer-prometheus 의존성 추가 | ✅ | `micrometer-registry-prometheus` + `logstash-logback-encoder:8.0` |
| `application.yml` Actuator/Prometheus 설정 | ✅ | `application-k8s.yml`에 `health,prometheus` 엔드포인트 노출, `metrics.tags.application: peekcart` 추가 (코드리뷰 P0) |
| 구조화된 로깅 설정 (JSON 포맷 + MDC traceId/userId/orderId) | ✅ | `logback-spring.xml` (`springProfile`: k8s=JSON, local=plain text) + `MdcFilter` + Kafka Consumer/OrderCommandService에 `orderId` MDC 추가 (코드리뷰 P1) |
| kube-prometheus-stack Helm 설치 | ✅ | `k8s/monitoring/minikube/values-prometheus.yml` + `install.sh`(`helm upgrade --install` 멱등성), minikube 경량 설정 (~1.2GB), subchart 키 수정, `serviceMonitorNamespaceSelector: {}` 전체 네임스페이스 허용 (minikube 검증). 경로는 ADR-0006 구현으로 `base/monitoring/` → `k8s/monitoring/minikube/` 이동 |
| ServiceMonitor 설정 (PeekCart 메트릭 수집) | ✅ | `k8s/base/services/peekcart/servicemonitor.yml` (ADR-0006 으로 `base/monitoring/` → `base/services/peekcart/` 이동), Service 포트 `name: http` + `app: peekcart` 레이블 추가, `release: kube-prometheus-stack` 레이블 추가 (minikube 검증) |
| Grafana 대시보드 구성 (API 응답시간, 에러율, JVM 힙 메모리) | ✅ | `k8s/monitoring/shared/api-jvm-dashboard.json`, ConfigMap sidecar 자동 로드 |
| Kafka Lag 모니터링 대시보드 | ✅ | `k8s/monitoring/shared/kafka-lag-dashboard.json`, Micrometer consumer lag 메트릭 |
| Pod CPU/메모리 + HPA 스케일 이벤트 대시보드 | ✅ | `k8s/monitoring/shared/pod-resources-dashboard.json`, HPA 패널 사전 구성, CPU 단위 `percentunit` 수정 (코드리뷰 P2) |
| Grafana Alert 설정 (에러율/응답시간 임계치) | ✅ | `k8s/monitoring/shared/grafana-alerts.yml`, 5xx>5% / p95>2s (2분 지속) |

**완료 기준**: Grafana에서 API 응답시간/에러율/Kafka Lag/Pod 리소스 실시간 모니터링 + Alert 동작 확인

---

### Task 3-4: 부하 테스트
**상태**: ✅ 완료 (단, 시나리오 2 throughput Threshold `http_req_failed<0.1` 는 미달 — D-002 추적). 세션 A 로컬 준비 → 세션 B 시나리오 1 → 세션 C 시나리오 2+3 + Task 3-5 + D-002 데이터 수집.
**완료 범위**: 정합성 (oversell 0건), HPA 1→3 검증, 시나리오 3 Kafka Lag (steady-state 0 복귀), D-002 데이터 수집 (1차 CPU + 2차 DB/연결 안정성 후보 식별), 리포트 작성. **미달 범위**: 시나리오 2 throughput Threshold (1000 VU 가 본 인프라 한계 초과 — `loadtest/reports/2026-04-29/REPORT.md` §관측/이슈)
**목표**: 부하 테스트 시나리오 실행, 캐싱 전/후 TPS 비교 + 동시 주문 정합성 + Kafka Lag 측정

| 항목 | 상태 | 비고 |
|------|------|------|
| **Step 0-a**: GKE overlay 패치 작성 (StorageClass `standard-rwo`, Internal LB, Artifact Registry, 리소스 상향) | ✅ | `k8s/overlays/gke/{kustomization.yml,README.md,patches/}`. 클러스터 프로비저닝/이미지 운반은 측정 직전 사용자 수동 (ADR-0004 운영 체크리스트) |
| **Step 0-b**: GKE monitoring values 작성 (`k8s/monitoring/gke/values-prometheus.yml` + `install.sh`) | ✅ | retention 24h, PVC standard-rwo 5Gi, Grafana Internal LB, 리소스 상향. ADR-0006 불변식 6 충족 |
| **Step 0-c**: 세션 A — 로컬 준비 (캐시 토글, 시드, 시나리오 스크립트, cleanup, docker-compose 리허설) | ✅ | `loadtest/` 트리 + `CacheConfig @ConditionalOnProperty`. 3-세션 실행 전략 (A 로컬 → B 시나리오 1 → C 시나리오 2+3) 으로 비용·정리 리스크 최소화 |
| nGrinder 설치 + 설정 (loadgen VM) | ✅ | nGrinder 3.5.9-p1 controller + agent. JDK 11 필수 (`update-java-alternatives`), JDK 17 미지원 |
| Baseline TPS 측정 (캐싱 비활성화 상태) | ✅ | 265.0 TPS / 188.38 ms MTT / 에러 0 (50 VUser, 5분) |
| 시나리오 1: 상품 조회 TPS (캐싱 전/후 비교) | ✅ | 캐시 ON 612.7 TPS / 81.87 ms MTT / 에러 0 → **×2.31** (목표 3× 미달, 유효 결과로 기록). 리포트: `loadtest/reports/2026-04-09/REPORT.md` |
| **리뷰 개선 P0-A**: Outbox 실패 경로 Slack 예외 격리 | ✅ | `OutboxPollingService.java:37-46` `slackPort.send()` try/catch + `log.warn` 감쌈. DLQ 경로(`KafkaConfig.java:88-91`)와 처리 철학 통일. 단위 테스트 2건 (`OutboxPollingServiceTest`) — Slack 실패 격리 + MAX_RETRY 정상 경로 검증 |
| **리뷰 개선 P0-B**: management 설정 공통화 | ✅ | `management.endpoints.web.exposure.include` + `management.metrics.tags.application` → `application.yml` 이동. k8s 전용(`probes.enabled`, `show-details`)만 잔류. 로컬 메트릭 사전 검증 가능. **ADR-0007 감사표 기준** (D-006 해결로 이동 범위 확정) |
| **리뷰 개선 P1-D**: 관측성 회귀 테스트 추가 | ✅ | `@SpringBootTest` + `@AutoConfigureObservability` — `GET /api/v1/products` 호출 후 `/actuator/prometheus` 응답에서 비즈니스 URI histogram bucket + `application="peekcart"` 태그 검증. D-001 재발 방지 |
| **리뷰 개선 P1-E**: Error Rate PromQL NaN 가드 | ✅ | `api-jvm-dashboard.json` / `dashboards-configmap.yml` 동기: `(A / (B > 0) * 100) or vector(0)` (idle 구간 0% 강제). `grafana-alerts.yml`: `($B > 0) && (($A / $B) * 100 > 5)` (Grafana math `&&` 문법). **신호 손실 보완**: Service Up stat 패널 + `peekcart-target-down` (up==0) + `peekcart-scrape-absent` (series 부재) 알림 2건 분리 추가. **런타임 검증 완료** (minikube, 2026-04-14): 5개 PromQL live 질의 성공 + 4개 알림 rule 로드 + firing 실증 (scale→0 시 scrape-absent firing, target-down inactive 로 배타적 분리 확인) |
| **리뷰 개선 P1-F**: 대시보드 JSON SSOT 단일화 | ✅ | 옵션 A (standalone JSON = SSOT) 채택. `k8s/monitoring/shared/kustomization.yml` 신규 — `configMapGenerator` 3개 (`options.labels: grafana_dashboard: "1"`, `disableNameSuffixHash: true`) + `grafana-alerts.yml` resources. `dashboards-configmap.yml` 삭제. 배포 진입점 `kubectl apply -k k8s/monitoring/shared/` 로 단일화. minikube 런타임 검증: 대시보드 3종 + alert rule 4건 로드 유지 |
| k6 설치 + Grafana 대시보드 ID 19665 import 준비 | ✅ | loadgen VM 에 k6 v0.55.0 stable 설치 (apt 의 v2.0.0-rc1 회피 — RC 빌드는 `experimental-prometheus-rw` output 미존재). 19665 import + Time range/Auto refresh 셋팅 완료 |
| 시나리오 2: 동시 주문 정합성 (1,000 VUser, k6) | ✅ | **정합성 100%** (Run 1/2 모두 모든 경합상품 OK, 오버셀링 0건). throughput Threshold (`http_req_failed<0.1`) 는 Run 1 60.59% / Run 2 35.90% 로 미달 — 1000 VU 동시 부하가 본 인프라 (e2-standard-4 × 1 + MySQL 250m-500m) 로는 본질적 미충족, **D-002 추적 대상으로 회부**. 5xx 0건 |
| 시나리오 3: Kafka Consumer Lag 모니터링 | ✅ | steady-state lag = 0 (NaN 또는 0) 확인, peak 후 5분 내 빈 결과 복귀. metric: `kafka_consumer_fetch_manager_records_lag_max` (Micrometer client, kafka-exporter 미배포). 토픽 `order_created` / `order_cancelled` / `payment_completed` / `payment_failed` |
| 테스트 리포트 작성 (전/후 비교 수치) | ✅ | 세션 B + 세션 C 통합. 세션 C: `loadtest/reports/2026-04-29/REPORT.md` (Run 1/2 + HPA + D-002 데이터 + Grafana 스크린샷 4장 + verify-concurrency 2회) |

> **측정 환경** (`docs/04-design-deep-dive.md` §10-7, `docs/01-project-overview.md` §4): GCP GKE Standard (asia-northeast3-a, e2-standard-4 × 1). 부하 발생기는 같은 zone 의 별도 Compute Engine VM (e2-standard-2). 수치는 절대값보다 **개선 비율**(캐싱 전/후 TPS 비교, HPA 적용 전/후 처리량 변화)에 초점. 목표 수치는 baseline 측정 후 확정. 환경 전환 근거는 ADR-0004.

**완료 기준**: 3개 시나리오 측정 결과 + 캐싱 전/후 TPS 비교 수치가 포함된 부하 테스트 리포트 완성

---

### Task 3-5: HPA 검증
**상태**: ✅ 완료
**목표**: `peekcart` Deployment HPA 설정, 부하 테스트 중 자동 스케일아웃 검증 (Phase 4 MSA 분리 이후 `Order Service` 로 재정의 예정)

| 항목 | 상태 | 비고 |
|------|------|------|
| metrics-server 설치 (GKE 는 기본 제공) | ✅ | GKE 기본 제공 확인 (`kubectl top pods/nodes` 정상). HPA TARGETS=`cpu: <X>%/60%` 정상 갱신 |
| HPA 매니페스트 작성 (CPU 기반, min 1 / max 3) | ✅ | `k8s/overlays/gke/hpa.yml` 추가 (task-hpa-manifest). minikube overlay 는 비포함이 정상 |
| k6 부하 중 Pod 자동 증설 확인 | ✅ | 세션 C Run 1: replicas **1 → 3** 전이 확인 (`kubectl get hpa -w` 로그 — CPU 269%→400%→scale-out, 신규 pod 65초 내 Ready, 안정화 후 90%→15%). 리포트: `loadtest/reports/2026-04-29/REPORT.md` §Task 3-5 |
| Grafana에서 HPA 스케일 이벤트 + Pod 증설 시점 스크린샷 캡처 | ✅ | `loadtest/reports/2026-04-29/grafana/03-pod-resources-hpa-scaleout.png` (HPA Current Replicas 1→3 전이 + Pod CPU/Memory 그래프 동시 캡처) |

**완료 기준**: 부하 테스트 중 Pod 1개 → 3개 자동 증설 + Grafana 스크린샷 — **모두 충족**

---

## 개발 부채 (Tech Debt)

> 작업 중 발견되었으나 해당 Task 범위 외인 항목. 후속 Task 생성 시 참고.

| # | 발견 시점 | 영역 | 설명 | 영향 | 우선순위 |
|---|---|---|---|---|---|
| D-001 | 세션 B (2026-04-09) | Monitoring | **Grafana API Response Time p95/p99 · Error Rate 패널 "No data"**. YAML 프로파일 병합으로 `management.metrics.distribution` 설정이 가려짐 → MetricsConfig.java로 histogram 활성화 이동하여 해결 | 브랜치 `fix/d001-metrics-histogram` (커밋 `715bcfa`)에서 수정 완료. 근본 원인 분석: `docs/progress/d001-metrics-histogram-fix.md` | ~~높음~~ **해결됨** |
| D-002 | 세션 B (2026-04-09) | Performance | **캐시 TPS ×2.31, 목표 ×3 미달**. 단일 Pod (2 vCPU) 환경에서 50 VUser 부하 시 CPU ~175% 도달 — 캐시 히트에도 CPU 가 병목. 가능한 원인: Redis 직렬화 비용, 커넥션 풀 크기, JSON 응답 직렬화 부하 | 포트폴리오 요구사항 미충족. **세션 C 추가 데이터** (`loadtest/reports/2026-04-29/REPORT.md` §D-002): Run 1 (1 pod cold) CPU 400% saturation 으로 **1차 병목 = CPU 확증**, Run 2 (3 pods warm) login 96.7% 로 1차 해소 후 cart/order 47-73% 실패율 잔존 + p95 30s. **2차 병목 후보 (가설 좁힘 BUT 단정 불가)**: (a) MySQL 커넥션 풀 / Redis 분산 락 contention, (b) Pod readiness / 연결 안정성 — `run2/k6-stdout.log` 의 EOF 519건 / connection refused 173건 / dial timeout 130건 분포가 (b) 가능성도 시사. 처리 주문 Run 1 25 → Run 2 110 (×4.4) | 중간 — 1차 확증, 2차 후보 2개 미분리. **후속 추적**: HikariCP wait time + Redisson lock acquisition latency + Pod readinessProbe transition 동시 수집으로 (a)/(b) 분리 검증, MySQL 리소스 + 풀 크기 튜닝 후 재측정, Phase 4 Order Service 분리 후 격리 측정 |
| D-003 | 세션 B (2026-04-09) | Monitoring | **Grafana K8s Pod 대시보드 기본 pod selector 이슈**. Helm 내장 대시보드(`Kubernetes / Compute Resources / Pod`)의 pod 변수 기본값이 kafka 로 선택되는 문제. 커스텀 대시보드(`PeekCart — Pod Resources & HPA`)는 `namespace="peekcart"` 필터로 범위 한정되어 영향 없음. 내장 대시보드는 수동 선택으로 우회 | 운영 편의성. 실사용 영향 없음 | ~~낮음~~ **Won't Fix** |
| D-004 | 세션 B (2026-04-09) | Infra / Tooling | **nGrinder 3.5.9-p1 JDK 17 미지원**. Worker process 가 system default Java 로 fork 하므로 loadgen VM 에서 `update-java-alternatives` 로 JDK 11 전환 필수. 세션 C 에서도 동일 설정 반복 필요 | 세션 C loadgen VM 프로비저닝 시 JDK 11 설치 + default 전환을 자동화 스크립트에 포함 권장 | 낮음 — 운영 지식으로 충분 |
| D-005 | 리뷰 종합 (2026-04-10) | Observability | **관측성 계약 5파일 분산**. MetricsConfig.java(histogram), application.yml(tags/actuator), SecurityConfig.java(보안 허용), servicemonitor.yml(scrape), grafana-alerts.yml(PromQL 전제). 개별 파일 정확해도 전체 계약 일관성 자동 미보장 | task-d005-observability-consolidation (2026-05-06) — ADR-0009 §Decision 표의 D5-V1~V6 강제 메커니즘 격상. lint 3종 (`scripts/observability-ssot-lint.sh` D5-V1+V2 / `scripts/servicemonitor-selector-lint.sh` D5-V5 / `scripts/observability-promql-lint.sh` D5-V6) + 통합 테스트 2건 (D5-V3 actuator exposure whitelist / D5-V4 `/actuator/health/**` 보안) + CI 통합. surface 의 *위치* 변경 0 (Phase 4 멀티모듈 분리 task 가 인용 수행). D5-V6 는 라벨 invariant 만 부분 격상 — PromQL syntax check 는 §7 R1 트레이드오프로 §8 후속 잔여 | ~~중간~~ **해결됨** |
| D-006 | 리뷰 종합 (2026-04-10) | Config | **YAML 프로파일 병합 원칙 미명문화**. `spring.kafka`가 base와 프로파일에 분산. 현재 `bootstrap-servers`만 override하여 안전하나, 향후 프로파일에 하위 키 추가 시 D-001 재발 가능 | `docs/d006-yaml-profile-principle` 브랜치에서 해결 — ADR-0007 작성 + CLAUDE.md §설정/YAML 프로파일 규칙 추가. YAML 전수 감사 결과 P0-B 이동 대상 2건 확정(`management.metrics.tags.application`, `management.endpoints.web.exposure.include`) | ~~중간~~ **해결됨** |
| D-007 | 리뷰 종합 (2026-04-10) | Observability | **Kafka Consumer MDC 불완전**. logback-spring.xml이 traceId/userId/orderId 기대. MdcFilter는 HTTP 경로만(traceId+userId). Kafka Consumer는 orderId만 수동 설정. Kafka 경로에서 traceId/userId 부재 → 로그 추적성 제한 | 브랜치 `refactor/d007-kafka-consumer-mdc` 에서 해결 — `MdcRecordInterceptor` 도입(헤더→eventId→UUID fallback, payload userId/orderId 자동 추출). 3개 Consumer 의 수동 `MDC.put/remove("orderId")` 제거. end-to-end 추적은 D-010 으로 분리 | ~~중간~~ **해결됨 (옵션 B)** |
| D-008 | 리뷰 종합 (2026-04-10) | Monitoring | **Grafana datasource UID 하드코딩**. 모든 대시보드/알림에서 `"uid": "prometheus"`. Helm 기본값과 일치하는 한 문제 없으나 Helm 업그레이드 시 변경 가능성 | 현재 동작. Helm 업그레이드 시 확인 | 낮음 |
| D-009 | CI 분석 (2026-04-14) | CI / Test Infra | **통합 테스트 인프라 분산**. 통합 테스트 7개가 각자 `@Testcontainers` + `static @Container` 로 MySQL/Redis/Kafka 를 클래스별 중복 기동. 공통 `AbstractIntegrationTest` 베이스 없음. 개별 `@Import(TestConfig)` / `@TestPropertySource` 사용으로 Spring 컨텍스트 캐시 적중 불가. 통합 테스트 증가 시 CI 시간·유지보수 비용 동반 악화 예상 | `AbstractIntegrationTest`(cleanup 규약) + `IntegrationTestConfig`(no-op SlackPort) 도입. 7개 통합 테스트 마이그레이션 완료. 컨테이너 수명 모델(per-class)은 유지, cleanup/mock 규약 단일화. 244건 전체 통과 | ~~중간~~ **해결됨 (1차 목표)** |
| D-010 | D-007 분리 (2026-04-14) | Observability | **Outbox trace context 미영속화**. 모든 production Kafka 발행이 `OutboxPollingService` (별도 스케줄 스레드) 경유 → 원본 HTTP 요청의 MDC traceId/userId 가 publish 시점에 이미 소멸. `OutboxEvent` 엔티티에 trace context 컬럼 부재로 end-to-end 추적 불가 | task-d010-outbox-trace-context (2026-05-01) — ADR-0008 + Flyway V4 (`trace_id` / `user_id` 컬럼) + `OutboxEvent.create(...)` MDC 명시 인자 + `MdcSnapshot` 헬퍼 + `OutboxPollingService` ProducerRecord 헤더 주입. 단위 테스트 신규 11건 + 통합 테스트 신규 3건. Phase 4 OpenTelemetry 도입 시 헤더 우선순위에 `traceparent` 추가만으로 forward-compat | ~~중간~~ **해결됨** |
| D-011 | task-hpa-manifest Codex 리뷰 (2026-04-21) | Tooling / Harness | **`/plan`·`/work` harness 견고화 필요** — task-hpa-manifest diff 리뷰에서 발견된 선-존재 harness 이슈 4건. (a) `hpx_lock_dir`/`hpx_state_path` 등이 `task_id` 를 경로에 그대로 삽입해 `../` injection 위험 (`.claude/scripts/shared-logic.sh:92,149-159,164-208,411-423`). (b) `hpx_diff_capture` 가 untracked 파일에 `git add -N` 을 실행해 index 전역 부작용 발생(`shared-logic.sh:503-520`). (c) 875줄 공용 shell helper 회귀 방지 테스트 전무 (Bats 등). (d) `scripts/timeout_wrapper.py:35` 가 0/음수 seconds 를 거부하지 않아 "정상 timeout" 처럼 오인 가능 | task-d011-harness-hardening (2026-05-02) — `hpx_task_id_validate` 도입(allowlist + 전 helper 진입부 1지점), `hpx_diff_capture` 격리 임시 `GIT_INDEX_FILE` 전환, `timeout_wrapper.py` `<=0`/NaN/Inf 거부, Bats 회귀 5종(task_id_validate / lock_state_paths / plan_audit_paths / diff_capture / timeout_wrapper) 추가 | ~~중간~~ **해결됨** |

## 다음 Phase 예정

- **Phase 4**: Gradle 멀티모듈, Spring Cloud Gateway, Choreography Saga, CQRS

---

## 완료된 작업

| 날짜 | 작업 | 내용 |
|------|------|------|
| 2026-03-21 | 문서 구조화 | README.md(진입점), docs/01~07 분리, 00-lagacy.md 보존 |
| 2026-03-21 | CLAUDE.md | 프로젝트 규칙 추가 |
| 2026-03-21 | TASKS.md | 태스크 관리 문서 초기화 |
| 2026-03-22 | Task 1-1 | 프로젝트 초기 설정 완료 (Gradle, Docker Compose, Flyway 스키마, global 공통 클래스) |
| 2026-03-22 | Task 1-2 | User 도메인 구현 완료 (회원가입/로그인/로그아웃/토큰 재발급, JWT 인증, RBAC, Grace Period) |
| 2026-03-25 | Task 1-3 | Product 도메인 완료 (엔티티, Repository, 서비스, Controller, 코드리뷰 개선, 단위 테스트 37건) |
| 2026-03-25 | Task 1-4 | Order 도메인 완료 (엔티티, Repository, 서비스, Controller, 단위 테스트 89건) |
| 2026-03-25 | Task 1-5 | Payment 도메인 완료 (엔티티, Repository, TossPaymentClient, EventListener, Controller, OrderPort/Adapter) |
| 2026-03-25 | Task 1-5 테스트 | Payment 도메인 단위 테스트 완료 (Domain 22건 + Application 12건 + Presentation 7건 = 41건) |
| 2026-03-26 | Task 1-6 | Notification 도메인 완료 (엔티티, Repository, SlackPort DIP, EventListener, Controller, 코드리뷰 개선, 단위 테스트 10건) |
| 2026-03-26 | Task 1-7 | 결제 타임아웃 스케줄러 완료 (OrderTimeoutScheduler, cancelExpiredOrder, REQUIRES_NEW 건별 트랜잭션, 단위 테스트 5건) |
| 2026-03-26 | 커버리지 측정 | JaCoCo 설정 + 커버리지 측정 (Domain 100%, Application 99%, 전체 213건 통과) |
| 2026-03-27 | Swagger 문서화 | OpenApiConfig(JWT SecurityScheme), 8개 Controller @Tag/@Operation, @ParameterObject Pageable |
| 2026-03-27 | Swagger 개선 | 에러 핸들링 4건 보강, 204 No Content 통일, LoginUser 전역 숨김, Pageable 기본값 설정 |
| 2026-03-27 | 낙관적 락 동시성 테스트 | Inventory @Version 낙관적 락 통합 테스트 (Testcontainers, 10스레드 동시 차감, lost update 방지 검증), ErrorCode PRD_004 + GlobalExceptionHandler OptimisticLockingFailureException 409 처리 |
| 2026-03-29 | Task 2-1 | Redis 캐싱 완료 (Cache Aside 패턴, CacheConfig, ProductCacheService, CachedPage, 코드리뷰 개선 4건, 통합 테스트 5건) |
| 2026-03-30 | Task 2-2 | Redis 분산 락 완료 (Redisson, DistributedLockManager, InventoryLockFacade, 50스레드 동시성 통합 테스트, 오버셀링 0건) |
| 2026-03-31 | Task 2-3 (12/13) | Kafka + Outbox 구현 (KRaft, Flyway V2, OutboxEvent Entity/Repository, Publisher 2개, Scheduler, Consumer 3개, EventListener 비활성화, 기존 테스트 44건 통과). 통합 테스트 미완료 |
| 2026-03-31 | Task 2-3 코드 리뷰 | 설계 문서 대조 + 코드 리뷰 3건 개선: SlackPort를 global/port/로 이동(P0 아키텍처 위반), OutboxEvent 팩토리 Function 패턴 적용(P1), EventListener 미사용 import 제거(P1). 전체 222건 테스트 통과 |
| 2026-04-01 | Task 2-3 완료 | Outbox → Kafka E2E 통합 테스트 5건 (Testcontainers Kafka + MySQL + Redis, Awaitility 비동기 대기). 전체 227건 테스트 통과 |
| 2026-04-01 | Task 2-4 코드 리뷰 | 설계 문서 대조 + 코드 리뷰 4건 개선: IdempotencyChecker save-first + UK 선점 패턴(P0 race condition), KafkaMessageParser 공통 추출(P1 3중 중복), Consumer Group ID 상수 추출(P1 이중 관리), 02-architecture.md 패키지 구조 동기화(P2). 전체 227건 테스트 통과 |
| 2026-04-02 | Task 2-4 완료 | 멱등성 통합 테스트 2건 (Testcontainers Kafka + MySQL + Redis): 동일 이벤트 중복 소비 시 1회만 처리, 다른 consumer group 독립 처리. 전체 229건 테스트 통과 |
| 2026-04-02 | Task 2-5 (4/5) | DLQ 구성 (FixedSequenceBackOff, DefaultErrorHandler + DeadLetterPublishingRecoverer, DLQ 토픽 4개, Slack 알림, 단위 테스트 3건). 통합 테스트 미완료. 전체 232건 테스트 통과 |
| 2026-04-02 | Task 2-5 코드 리뷰 | 설계 문서 대조 + 코드 리뷰 3건 개선: slackPort.send() try-catch 감싸기(P0 Slack 실패 시 DLQ 중복 발행 방지), "exponential backoff" → "fixed sequence backoff" 용어 수정(P1), 02-architecture.md에 FixedSequenceBackOff 패키지 트리 추가(P2). 전체 232건 테스트 통과 |
| 2026-04-02 | Task 2-5 완료 | DLQ 통합 테스트 1건 (Testcontainers Kafka + MySQL + Redis): Consumer 처리 실패 → 재시도 소진 → DLQ 라우팅 + Slack 알림 검증. 전체 233건 테스트 통과 |
| 2026-04-02 | Task 2-6 완료 | ShedLock 적용 (shedlock-spring 6.3.1, JdbcTemplateLockProvider, Flyway V3, OrderTimeoutScheduler + OutboxPollingScheduler @SchedulerLock 적용, 통합 테스트 2건). 전체 235건 테스트 통과. Phase 2 전체 Task 완료 |
| 2026-04-03 | Phase 3 환경 구축 | TASKS.md Phase 3 Task 5개 정의 (Task 3-1 ~ 3-5) + PHASE3.md 생성 |
| 2026-04-03 | Task 3-1 완료 | GitHub Actions CI 파이프라인 (멀티스테이지 Dockerfile, ci.yml 워크플로우, Testcontainers CI 환경, GHCR push). 로컬 Docker 빌드 검증 완료 |
| 2026-04-04 | Task 3-1 코드 리뷰 | 설계 문서 대조 + 코드 리뷰 4건 개선: `.dockerignore` 추가(P0 빌드 컨텍스트 경량화), JAR 파일명 고정 `app.jar`(P1 글로브 제거), Docker 레이어 캐시 GHA(P1), CI concurrency 설정(P2 중복 실행 방지). plain JAR 비활성화 |
| 2026-04-04 | Task 3-2 완료 | minikube K8s 배포 매니페스트 (Namespace, MySQL+PVC, Redis, Kafka KRaft, ConfigMap/Secret, PeekCart Deployment+NodePort 30080, Actuator Liveness/Readiness Probe). application-k8s.yml 프로파일 추가, spring-boot-starter-actuator 의존성 추가, SecurityConfig actuator 공개 URL 추가. 전체 235건 테스트 통과 |
| 2026-04-04 | Task 3-2 코드 리뷰 | 설계 문서 대조 + 코드 리뷰 4건 개선: MySQL 크레덴셜 `secretKeyRef` 참조(P1 하드코딩 제거), Redis/Kafka PVC 추가(P1 데이터 영속화), `startupProbe` 추가(P2 기동 지연 안전), K8s 권장 labels 추가(P2 ServiceMonitor 연계). 전체 235건 테스트 통과 |
| 2026-04-05 | Task 3-3 완료 | kube-prometheus-stack 모니터링 구축: Micrometer Prometheus + LogstashEncoder 의존성, Actuator prometheus 엔드포인트 노출, MdcFilter(traceId/userId), logback-spring.xml(k8s=JSON/local=plain), Helm values + install.sh, ServiceMonitor, Grafana 대시보드 3개(API&JVM/Kafka Lag/Pod Resources) + Alert 2개(에러율 5%/응답시간 2s). 전체 235건 테스트 통과 |
| 2026-04-05 | Task 3-3 코드 리뷰 | 설계 문서 대조 + 코드 리뷰 7건 개선: `metrics.tags.application` 추가(P0 PromQL 전체 불일치), orderId MDC 추가(P1 설계 문서 불일치), Grafana datasource uid 프로비저닝(P1), Helm subchart 리소스 키 수정(P2), retention 2h→6h(P2), Pod CPU 단위 수정(P2), install.sh 멱등성(P2). 전체 235건 테스트 통과 |
| 2026-04-05 | Task 3-3 minikube 검증 | 매니페스트 수정 3건: Service `app: peekcart` 레이블 누락, ServiceMonitor `release` 레이블 누락, `serviceMonitorNamespaceSelector` 전체 허용. Prometheus 16개 타겟 active, Grafana 대시보드 JVM/Kafka Lag 데이터 확인. API 메트릭은 트래픽 발생 후, Pod CPU/Memory는 cAdvisor 지연 해소 후 확인 필요 |
| 2026-04-06 | Phase 3 GCP 전환 준비 (ADR/구조) | 6개 세션 순차 실행: ① ADR 인프라(`docs/adr/`, template, README, `check-consistency.sh`) ② ADR 0001~0005 작성(레이어드+DDD/MSA 진화/Phase3 초기 minikube/Phase3 GKE 전환/Kustomize base+overlays) ③ Layer 1 핵심 문서(01 §4 운영 환경 SSOT 신설, 04 §10-7 환경 맥락, 07 환경 전환 노트) ④ Layer 1 파생(02 §4-3/§12, 03 §7-1, TASKS, CLAUDE.md) + ADR-0003 사후 정정(Phase 1·2 → Phase 3 초기) ⑤ k8s 매니페스트 Kustomize 재배치(`k8s/base/` + `overlays/{minikube,gke}/`, minikube 패치 분리) ⑥ PHASE3.md 작업 이력 + 힌트 스크립트 실행. 브랜치 `refactor/phase3-adr-kustomize-prep`, 8개 커밋. **본 작업은 ADR/구조 설계만 포함**하며 monitoring 스택의 환경 분리(ADR-0006)와 GKE overlay 실제 매니페스트 작성은 별도 브랜치(`refactor/phase3-monitoring-split` 예정)에서 수행 |
| 2026-04-08 | Task 3-4 Step 0 완료 (GKE overlay + monitoring values) | `k8s/overlays/gke/` patches 3개(peekcart Service Internal LB, Deployment 리소스 상향 500m/1Gi~2000m/2Gi, infra PVC standard-rwo) + images 치환(GHCR → Artifact Registry, `PROJECT_ID_PLACEHOLDER`) + README(이미지 수동 운반 / `kustomize edit set image` 절차 / ADR-0004 정리 명령). `k8s/monitoring/gke/values-prometheus.yml` 본문 작성(retention 24h, PVC standard-rwo 5Gi, Grafana Internal LB annotation, 리소스 상향) + `install.sh` 활성화. `02-architecture.md §12` 에 GKE 4단계 배포 순서 추가. 검증: `kubectl kustomize` 양 overlay 통과, `helm template` 통과(load-balancer-type Internal/retention 24h/standard-rwo 확인), `consistency-hints.sh` exit 0. 실 GKE 클러스터 apply 는 측정 직전 별도 작업. 브랜치 `feat/phase3-gke-overlay`, 1개 커밋 |
| 2026-04-07 | ADR-0006 구현 (monitoring 스택 base 분리) | `k8s/monitoring/{namespace.yml, shared/, minikube/, gke/}` 신규 트리. ServiceMonitor 를 `base/services/peekcart/` 로 이동(불변식 2). monitoring NS SSOT 단일화 + `install.sh --create-namespace` 제거(불변식 5). GKE monitoring 은 명시적 TODO(values 헤더 + `install.sh exit 1`, 불변식 6). `02-architecture §4-3/§12` 갱신 — 4단계 배포 순서 + "self-contained overlay" 운영 해석 노트(ServiceMonitor CRD 선행 의존). TASKS Task 3-4 Step 0 에 GKE monitoring values 작성 항목 추가. **ADR-0006 Status `Proposed → Accepted`**. 검증: `kubectl kustomize` 양 overlay 통과(monitoring NS 0건, ServiceMonitor 1건 peekcart NS), `consistency-hints.sh` exit 0. 실 클러스터 apply 검증은 Task 3-4 Step 0 GKE 환경 구성과 함께 수행. 브랜치 `refactor/phase3-monitoring-split`, 1개 커밋(`c28ba26`) |
| 2026-04-09 | Task 3-4 세션 B 준비 (GCP 환경 · 이미지 운반) | 과금 0 구간에서 세션 B 전제조건 선제 해결: GCP 프로젝트 `peekcart-loadtest` 생성 + billing 연결, ₩50,000 예산 50/90/100% 알림, API 활성화(container/compute/artifactregistry), Artifact Registry `peekcart` 레포(`asia-northeast3`), docker credHelper, peekcart 이미지 buildx `--platform linux/amd64` 이중 태그(`:3352c14` + `:latest`) AR push (digest `sha256:f86eb82c...`, 193MB). **실 GKE 프로비저닝·측정은 세션 B 에서 수행**. 현재 과금 ~30원/month (AR 스토리지, 프리 티어 흡수). 후속: helm v4 호환성을 세션 B monitoring 설치 전 `helm template` 으로 확인 |
| 2026-04-08 | Task 3-4 Step 0-c — 세션 A 로컬 준비 | 부하 테스트 실행을 3 세션(A 로컬 / B 시나리오 1 / C 시나리오 2+3) 으로 분할하여 안정성 확보. **캐시 토글**: `CacheConfig @ConditionalOnProperty(peekcart.cache.enabled, matchIfMissing=true)` + `NoOpCacheManager` fallback, ConfigMap `PEEKCART_CACHE_ENABLED`. **시드**: `loadtest/sql/seed.sql` (Flyway 독립) — users 1101 + products 1010 + 경합재고 1000 (id 1001..1010), BCrypt `LoadTest123!`. **시나리오**: nGrinder Groovy (목록 80% / 상세 20%), JMeter `.jmx` (1000 VUser ramp 30s, 경합타깃 `__Random(1001,1010)`) + `users.csv` 생성 스크립트. **검증 쿼리**: `verify-concurrency.sql` (오버셀링 체크). **리포트/정리**: `reports/TEMPLATE.md` (§10-7 a~f 스켈레톤), `cleanup.sh` (ADR-0004 운영 체크리스트 스크립트화). **docker-compose 리허설**: 앱 로컬 실행 → seed 적용 → 카운트 검증 → curl 로 login → cart → order 성공, 재고 100→99 차감, `verify-concurrency.sql` consistency=OK 확인. **발견된 버그 1건**: MySQL 8 `innodb_autoinc_lock_mode=2` 에서 `INSERT...SELECT` 가 auto_increment 를 블록 할당하여 경합 상품 ID가 1024..1033 으로 생성됨 → 명시 `VALUES` + `ALTER TABLE AUTO_INCREMENT=1001` 로 수정. `ProductCacheIntegrationTest` 5건 통과. 브랜치 `feat/phase3-loadtest-prep`, 3개 커밋 |
| 2026-04-10 | 전반적 리뷰 종합 | 3건 독립 리뷰 + Codex 토론 2회차 교차 검증 → 최종 보고서(`docs/review/final-report.md`). 세션 C 전 수정 5건(P0-A Outbox Slack 격리, P0-B management 공통화, P1-D 관측성 회귀 테스트, P1-E PromQL NaN 가드, P1-F 대시보드 SSOT) + 기술 부채 4건(D-005~D-008) 확정. TASKS.md Phase 표기 수정 + D-001 해결 상태 갱신. 실행 순서: 리뷰 개선 → 세션 C → Task 3-5 |
| 2026-04-16 | D-009 해결 (1차 목표) | 통합 테스트 인프라 표준화: `AbstractIntegrationTest`(cleanDatabase/cleanCaches 규약) + `IntegrationTestConfig`(no-op SlackPort) 도입. 7개 통합 테스트 마이그레이션 (inner TestConfig 2개 제거, InventoryConcurrencyTest 명시적 cleanup 추가, ProductCacheIntegrationTest/InventoryConcurrencyTest Kafka 컨테이너 추가). per-class 컨테이너 수명 유지. 전체 244건 테스트 통과 |
| 2026-04-29 | Task 3-4 세션 C + Task 3-5 완료 (Phase 3 종결) | GKE 1회 과금 세션으로 시나리오 2 (1,000 VU 동시 주문 정합성) + 시나리오 3 (Kafka Lag) + Task 3-5 (HPA 1→3 검증) + D-002 데이터 수집 통합 실행. **2회 측정 (Run 1: 1 pod cold-start / Run 2: 3 pods pre-warmed)**. **Run 1**: HPA replicas 1→3 전이 확인 (CPU 269%→400% saturation → scale-out, 신규 pod 65초 내 Ready, 안정화 90%→15%) — Task 3-5 핵심 산출물. **Run 2**: CPU 분산으로 login 96.7% (Run 1: 46.3%, +50pp) 도달, **2차 병목 = MySQL 커넥션 풀 / Redis 분산 락 contention** 식별. **정합성**: Run 1/2 모두 모든 경합상품 (1001-1010) consistency=OK + 오버셀링 0건 + 5xx 0건. **시나리오 3**: steady-state lag 0 (Micrometer client metric `kafka_consumer_fetch_manager_records_lag_max`, kafka-exporter 미배포). **D-002 가설 좁힘**: 1차 CPU 병목 + 2차 DB/락 contention. **Threshold 미달**: `http_req_failed<0.1` 60.59%/35.90% (1000 VU 가 본 인프라 한계 초과) — D-002 추적 대상으로 회부. **부산물**: cleanup.sh 의 VM 이름 변수 버그 (`loadgen` vs `peekcart-loadgen`) 발견 — 별도 fix 안건. 리포트: `loadtest/reports/2026-04-29/REPORT.md` (Grafana 4장 + verify-concurrency 2회 + k6 산출물). 브랜치 `test/phase3-loadtest-session-c`. PR: https://github.com/Kimgyuilli/PeakCart/pull/27 |
| 2026-05-01 | task-d010-outbox-trace-context 완료 (D-010 해결) | Outbox trace context 영속화 + Producer 헤더 전파. ADR-0008 작성 (Status `Accepted`) — 4 대안 비교, 옵션 a (명시 인자) 채택. Flyway V4 (`outbox_events` 에 `trace_id` / `user_id` VARCHAR(64) NULL 컬럼, NULL 허용으로 backfill 불필요). `OutboxEvent.create(...)` 시그니처 확장 (traceId/userId 명시 인자), 호출부 3곳 갱신 (Order/Payment Publisher + OutboxPollingServiceTest 픽스처). `MdcSnapshot.current()` 정적 헬퍼 신규 (`global/kafka/`). `OutboxPollingService` ProducerRecord 경로 전환 + `KafkaTraceHeaders.TRACE_ID` / `USER_ID` 헤더 주입 (null/blank 모두 미주입). 단위 테스트 신규/수정: MdcSnapshotTest 3건, OrderOutboxEventPublisherTest 3건, PaymentOutboxEventPublisherTest 3건, OutboxPollingServiceTest 헤더 검증 set/null/blank 3건, MdcRecordInterceptorTest 신규 경계 2건 (blank header / userId 부재). 통합 테스트: OutboxKafkaIntegrationTest raw consumer 헤더 전파 2건, DlqIntegrationTest 헤더 보존 1건. 모든 통합 테스트에 `@BeforeEach`/`@AfterEach` MDC.clear() 안전망. Phase 4 OpenTelemetry 도입 시 헤더 우선순위에 `traceparent` 추가만으로 forward-compat. 브랜치 `feat/d010-outbox-trace-context`. PR: https://github.com/Kimgyuilli/PeakCart/pull/28 |
| 2026-05-02 | task-d011-harness-hardening 완료 (D-011 해결) | `/plan`·`/work` 공용 shell helper 4건 정비 (Phase 3 잔여 부채 종결). (a) **경로 인젝션 차단**: `hpx_task_id_validate` 도입 (allowlist `[A-Za-z0-9._-]+`, 1~128자, `..` 금지, 선두 `-`/`.` 금지). `hpx_lock_dir` / `hpx_state_path` / `hpx_lock_acquire` / `hpx_lock_force_release` / `hpx_lock_release` / `hpx_state_*` (exists/read/write) / `hpx_plan_lint` / `hpx_audit_append` / `hpx_diff_capture` / `hpx_ship_pr_body_data` 진입부 검증 + `.claude/commands/{plan,work}.md` Step 1 직후 1회 검증 강제. (b) **`git add -N` 부작용 제거**: `hpx_diff_capture` 를 격리된 임시 `GIT_INDEX_FILE` 로 전환 (`.git/index` 복사 → 격리 인덱스에서 `git add -N` → diff 출력 → 임시 dir cleanup). 사용자 staged 상태 무손상 (sha256 불변 검증). (c) **Bats 회귀 테스트 5종**: `task_id_validate.bats`, `lock_state_paths.bats`, `plan_audit_paths.bats`, `diff_capture.bats`, `timeout_wrapper.bats` (`.claude/scripts/tests/bats/`). (d) **`timeout_wrapper.py` 견고화**: `seconds <= 0`, `math.isnan`, `math.isinf` 거부 → exit 2 + `invalid seconds:` stderr (124 정상 timeout 코드 침범 차단). 브랜치 `chore/task-d011-harness-hardening`. PR: https://github.com/Kimgyuilli/PeakCart/pull/29 |
| 2026-05-06 | task-d005-observability-consolidation 완료 (D-005 해결) | ADR-0009 §Decision 표의 D5-V1~V6 강제 메커니즘 격상 (surface 위치 변경 0건). **lint 3종** 신규: (a) `scripts/observability-ssot-lint.sh` — D5-V1 SSOT 위치 위반 (`management.metrics.tags.application` / `management.endpoints.web.exposure.include` 가 base `application.yml` 외 프로파일에 재선언됐는지 yaml 파싱 검출) + D5-V2 중복 재선언 (`MeterFilter` / `MeterRegistryCustomizer<>` 가 `MetricsConfig.java` 외 클래스에 있는지 grep + application 태그 값이 'peekcart' 외 값인지 yaml 검사). (b) `scripts/servicemonitor-selector-lint.sh` — D5-V5 양 overlay (`minikube`, `gke`) 의 `kubectl kustomize` 산출물에서 ServiceMonitor `spec.selector.matchLabels` 가 같은 namespace Service `metadata.labels` 와 매칭 + `endpoints[].port` 가 Service `spec.ports[].name` 에 포함되는지 정적 검증. kubectl 미존재 시 skip. (c) `scripts/observability-promql-lint.sh` — D5-V6 grafana-alerts ConfigMap 의 PromQL expr 추출 + alert uid 별 required-label matrix 검증 (S6.a/b: `application` 필수→S2 ground truth 일치 / S6.c/d: `namespace`+`service` 필수→S5 ground truth 일치). presence 부재 + value mismatch 모두 검출. **통합 테스트 2건** 추가 (`ObservabilityMetricsIntegrationTest`): D5-V3 (actuator exposure whitelist 정확도 — health/prometheus 200, info/env 404) + D5-V4 (`/actuator/health/**` 인증 없이 200, K8s liveness/readiness Probe 의존). `@TestPropertySource(properties = "management.endpoint.health.probes.enabled=true")` 클래스 레벨 추가 (ADR-0007/0009 회색지대 재결정 회피 — `application-test.yml` 비채택). **CI 통합** (`.github/workflows/ci.yml`): `chmod +x gradlew` 다음 + `./gradlew build` 이전 위치에 (1) `pip install --user pyyaml` (2) `azure/setup-kubectl@v4` (3) lint 3종 실행 step. 정책 위반을 build 비용 부담 전 빠르게 fail. **negative 검증** (PR 본문 1회 증빙, detector branch 11건): D5-V1 두 키 각각 / D5-V2 Java 중복 + base yaml 값 변경 / D5-V5 selector + port / D5-V6 value mismatch 3 + label absence 2 family 대표. D5-V3/V4 는 통합 테스트 assertion 자동 회귀. **명시적 비변경**: `MetricsConfig.java` / `application.yml` (S2/S3 base) / `application-k8s.yml` 회색지대 키 / `SecurityConfig.java` / `servicemonitor.yml` / `grafana-alerts.yml` — ADR-0009 §Decision 표 4번째 컬럼 ("본 task 변경" = "없음") 부동성 유지. **D5-V6 부분 격상 잔여**: PromQL syntax check (`promtool promql format` 의 experimental 기능 의존) 는 §8 후속. /work loop 1 (P1~P8) + Codex split review 1 loop (3 chunks 9건 — P0:1/P1:4/P2:4 — 7건 반영 + 1건 부분 + 2건 거부). 브랜치 `chore/task-d005-observability-consolidation`. PR: https://github.com/Kimgyuilli/PeakCart/pull/32 |
| 2026-05-04 | task-adr-observability-ssot 완료 (D-005 결정 문서화 — D-005 자체는 후속 task 까지 미해결 유지) | 관측성 계약 SSOT 결정 ADR-0009 작성 (Status `Accepted`). 9 surface (S1 histogram / S2 metrics tags / S3 actuator exposure / S4 actuator security / S5 ServiceMonitor / S6.a~d Grafana alerts) 를 분해해 §Context 표 (현 SSOT 파일:라인 + 의존 surface) + §Decision 6 컬럼 강제 표 (현 SSOT / 본 task 변경 / Phase 4 owner / 이동·복제 금지 규칙 / 검증 수단) 작성. 채택 = Alt B (현 위치 명시). Phase 4 owner 컬럼이 `peekcart-common-observability` 모듈/per-service 위치를 사전 결정. 자동 회귀 검증 범위 확정 (S1/S2/S3 happy path/S4 happy path = `ObservabilityMetricsIntegrationTest`, 동작 회귀 한정으로 강도 표현 — 위치/복제 위반은 정적 검증 영역). 후속 task 6 action 분해: **D5-V1** (SSOT 위치 위반 정적 검증) / **D5-V2** (중복 재선언/복제 정적 검증) / **D5-V3** (S3 whitelist 정확도) / **D5-V4** (S4 health 경로) / **D5-V5** (S5 selector k8s integration) / **D5-V6** (S6 PromQL lint). Phase 4 owner 위치로의 물리적 이동은 본 후속 task 비대상 — Phase 4 멀티모듈 분리 task 에서 본 ADR 인용 수행. ADR-0007 Extends (회색지대 분류 분리: `probes.enabled`=예외 허용 / `show-details`=후속 검토), ADR-0006 위치 분담 유지. 코드/매니페스트 변경 0건. 인덱스/CLAUDE.md 1줄 참조/TASKS·PHASE3 동기화. /work loop 3 (Codex 누적 12건 — P0:0, P1:9, P2:3 — 모두 반영). 브랜치 `docs/adr-0009-observability-ssot`. PR: https://github.com/Kimgyuilli/PeakCart/pull/30 |
| 2026-05-07 | cleanup.sh VM 이름 정정 (Task 3-4 세션 C 부산물 fix) | 세션 C 측정 시 발견된 cleanup.sh 기본값 불일치 fix (TASKS.md 491번 행 "별도 fix 안건"). `LOADGEN_NAME` 기본값 `loadgen` → `peekcart-loadgen` (실제 프로비저닝 + cluster `peekcart-loadtest` prefix 일관성, `docs/progress/PHASE3.md:981/1018` 증거). 동기화 3개 파일: `loadtest/cleanup.sh` (헤더 주석 + 변수 기본값) / `loadtest/README.md:41` (전제조건 VM 이름) / `loadtest/reports/TEMPLATE.md:123` (정리 체크리스트). 기존 REPORT.md (2026-04-09, 2026-04-29) 는 이력 기록이라 미수정. 검증: `bash loadtest/cleanup.sh --dry-run` 출력에서 `loadgen=peekcart-loadgen` + `gcloud compute instances delete peekcart-loadgen` 정상 치환 확인. 브랜치 `fix/cleanup-loadgen-vm-name`. PR: https://github.com/Kimgyuilli/PeakCart/pull/33 |
