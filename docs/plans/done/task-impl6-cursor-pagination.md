# 구현 ⑥ — 주문 내역 Cursor 페이지네이션

> 부모: `docs/TASKS.md` 구현 ⑥ · 로드맵 `docs/07-roadmap-portfolio.md §16` 행 6 ("주문 조회 Cursor 기반 전환 검토")
> 선행 근거: `docs/04-design-deep-dive.md §10-3` (Offset 한계 진술, Phase 4 전환 검토)
> 상태: 계획 리뷰 1라운드 반영 완료 (§7 정정 이력)

---

## 1. 명제 (부정형)

아래 중 **하나라도 성립하면 이 task 는 미완**이다.

- N1. `GET /api/v1/orders` 가 offset 기반 응답(`totalElements`·`totalPages`·`number`)을 반환하거나, 폐기된 `page`/`sort`/`offset` 파라미터를 **조용히 무시하고 200** 을 준다.
- N2. 커서 조회의 정렬이 결정적이지 않다 — 동일 `ordered_at` 을 가진 주문이 페이지 경계에서 **누락되거나 중복**된다.
- N3. 커서 질의가 `orders` 인덱스 범위 스캔이 아닌 방식(full scan / filesort)으로 실행된다.
- N4. **커서가 권한의 근거가 된다** — 응답 대상 `userId` 가 인증 주체가 아니라 커서에서 온다. (타 사용자 커서로 **타 사용자 데이터가 1건이라도** 반환되면 성립)
- N5. **형식오류 커서**(구조적으로 디코드 불가)가 **500** 이 되거나, `size`·폐기 파라미터 오류가 **400 이 아닌 응답**을 받는다.
  - *주의(2R #1)*: 서명이 없으므로 **"유효한 형식의 변조"는 탐지 대상이 아니다** — D4 참조. 여기서 요구하는 것은 형식오류의 상태코드이지 변조 탐지가 아니다.
- N6. 커서 응답이 내부 정렬 키(`ordered_at`, `id`)를 클라이언트에 **투명하게 노출**해 계약을 굳힌다.
- N7. 커서가 **JVM 기본 timezone 에 따라 다르게 복원**된다.
- N8. offset 대비 개선이 **구조적 지표(실행계획) 근거 없이** 주장된다.
- N9. Layer 1 문서(`04-design-deep-dive.md §10-3`, `05-data-design.md` 인덱스 표, `03-requirements.md` API 표) 및 OpenAPI(`/api-docs`) 가 구현과 어긋난 채 남는다.

---

## 2. 배경 — 착수 전 코드 검증

계획의 전제는 ADR/설계문서가 아니라 **현재 코드**다. 아래는 초안 작성 전에 직접 확인한 결과이며, **V11~V14 는 계획 리뷰 1라운드가 반증해 추가로 확인한 것**이다.

| # | 검증한 전제 | 결과 | 근거 |
|---|---|---|---|
| V1 | 주문 목록이 offset 페이징이다 | ✅ 확인 | `OrderController.java:52` `@PageableDefault(size = 20) Pageable` → `OrderQueryService.java:28` → `OrderJpaRepository.java:16` |
| V2 | 정렬이 지정돼 있다 | ❌ **반증** — `@PageableDefault` 에 `sort` 가 **없다**. 현재 응답 순서는 MySQL 이 정하는 미정의 순서이고, 클라이언트가 `?sort=` 로 임의 컬럼 정렬을 넣을 수 있다. **offset 페이징으로서의 정합성도 지금 이미 없다** | `OrderController.java:52` |
| V3 | 커서 정렬 키를 지지하는 인덱스가 있다 | ❌ **없음** — `orders` 인덱스는 `uk_orders_order_number`, `idx_orders_user_id_status (user_id, status)`, `idx_orders_status_ordered_at (status, ordered_at)`, `idx_orders_reservation_expiry (status, reservation_expires_at)` | `V1__init_order.sql:43-45`, `V3__...sql:14` |
| V4 | `ordered_at` 이 유니크한가 | ❌ 아니다 — `DATETIME(6) NOT NULL`, 유니크 제약 없음 → **tie-break 컬럼 필수** | `V1__init_order.sql:38`, `Order.java:52` |
| V5 | `Page` 응답을 소비하는 **레포 내** 클라이언트 | **없다** — 프론트엔드 없음. k6 는 `loadtest/scripts/order-concurrency.js:106` 에서 `POST` 만. **주의: 이것은 "레포 내 소비자 0"이지 "외부 소비자 0"의 증명이 아니다**(리뷰 1R #3) | grep `api/v1/orders` 전수 |
| V6 | 상품 목록도 같은 모양인가 | 같은 모양이나 **캐시에 결합** — `ProductCacheService.java:53-54` 캐시 키가 `pageNumber`/`pageSize`, `CachedPage` 가 `totalElements/pageNumber/pageSize` 저장 → **범위 밖**(§5) | `ProductCacheService.java`, `common/.../CachedPage.java` |
| V7 | 설계문서가 지정한 커서 키 | `04-design-deep-dive.md:461` 은 **`WHERE id < :cursor`(id 단독)**. V4 와 합치면 id 단독은 "최신순"이 아니라 "생성 PK 역순" → **`(ordered_at, id)` 복합으로 정정**(P12) | `04-design-deep-dive.md:457-462` |
| V8 | 에러 코드 여유 | `ORD_001`~`ORD_009` 사용 중 → **`ORD_010`·`ORD_011` 가용** | `ErrorCode.java:29-37` |
| V9 | 통합 테스트 기반 | 있다 — `AbstractIntegrationTest` + `IntegrationTestConfig` + Testcontainers MySQL, Flyway 활성 | `OrderReservationLeaseQueryIntegrationTest.java:40-45` |
| V10 | 게이트웨이 쿼리 파라미터 통과 | `Path=/api/v1/orders/**` 단순 라우트, 필터에 쿼리 조작 없음 → 게이트웨이 변경 불필요 | `gateway/src/main/resources/application.yml:108-111` |
| **V11** | **메서드 파라미터 `@Min/@Max` 가 400 이 되는가** | ❌ **반증(1R #2)** — `GlobalExceptionHandler` 에 `HandlerMethodValidationException`·`ConstraintViolationException` 핸들러가 **없다**. `@ExceptionHandler(Exception.class)`(`:92-98`)가 `DefaultHandlerExceptionResolver` 보다 먼저 잡아 **500 SYS-001** 이 된다 | `GlobalExceptionHandler.java:39-98` 전문 확인 |
| **V12** | **롤아웃 중 단일 계약이 유지되는가** | ❌ **반증(1R #3)** — `strategy.rollingUpdate {maxUnavailable:0, maxSurge:1}`, `replicas:1` → 신·구 Pod 가 동시에 Service 뒤에 있는 창이 존재한다 | `k8s/base/services/order-service/deployment.yml:11-17` |
| **V13** | **JVM 기본 timezone 이 고정돼 있는가** | ❌ **반증(1R #11)** — `user.timezone`/`TZ`/`TimeZone.setDefault` 가 코드·YAML·Dockerfile 어디에도 **없다**(grep 0건). JDBC URL 의 `serverTimezone=Asia/Seoul`(`application-k8s.yml:3`)은 JVM 기본값을 고정하지 않는다 → **epoch 기반 커서 인코딩 불가** | grep 전수 |
| **V14** | **OpenAPI 표면이 실재하는가** | ✅ 실재(1R #8) — `order-service/build.gradle:56` springdoc 2.8.6, `application.yml:48-52` `/api-docs`·`/swagger-ui.html`. 파괴적 변경인데 계약 회귀 검증이 없었다 | 위 2파일 |

**구조 변경 아님** — 모듈/경계 이동·peel·rename 없음. `PLAN-BLINDSPOTS.md` B1 역의존 스윕 대상 아님. 변경은 `order-service` 내부 + `common/ErrorCode` 상수 2개 추가.

**V2 가 범위를 늘렸다**: 애초 "offset → cursor 성능 전환"으로 잡았으나, 현재 API 는 정렬이 없어 **offset 페이징으로서도 결과가 미정의**다. 이 task 의 1차 가치는 성능이 아니라 **결정적 순서 계약의 최초 확립**이다(N2).

### 2.1 확정한 결정

| 결정 | 선택 | 근거 |
|---|---|---|
| **D1. 전환 범위** | **주문 목록만**. 상품 목록 제외 | 로드맵 §16 행 6 이 "주문 조회"로 한정. V6 — 상품은 Redis 캐시 키가 페이지 번호에 결합돼 있고, 구현 ⑤(#94)가 방금 그 표면을 고정했다. 같은 PR 에서 흔들지 않는다 |
| **D2. `totalElements` 계약** | **제거**. `CursorPageResponse<T>{content, nextCursor, hasNext}` 로 **파괴적 단일 전환**(호환 모드 미지원). 폐기 파라미터는 **명시적 400** | V5 로 확인된 것은 레포 내 소비자 0 뿐이다. 병행 지원은 인덱스 전략 2벌·테스트 2벌을 만들면서 얻는 게 없다. **잃는 것**: 총 건수·총 페이지·임의 페이지 점프·진행률 UI (§2.2) |
| **D3. 커서 키 / 인코딩** | 정렬 `ordered_at DESC, id DESC`. 커서 = **opaque base64url** of `"{uuuu-MM-dd'T'HH:mm:ss.SSSSSS}|{id}"` — **고정 6자리 마이크로초**, 인덱스 `idx_orders_user_id_ordered_at (user_id, ordered_at)` | V4·V7. **epoch 미사용** — V13 이 반증. `LocalDateTime` 을 timezone 이 개입하지 않는 문자열로 직렬화해 zone 질문 자체를 제거한다. **`ISO_LOCAL_DATE_TIME` 은 쓰지 않는다(2R #3)** — 실측 결과 가변 길이다(`2026-01-02T03:04:00` / `...05.000001` / `...05.123456789`). 컬럼이 `DATETIME(6)` 이므로 **고정 6자리**로 고정하고, 나노 단위가 남는 입력(`nano % 1000 != 0`)은 `ORD_010` 으로 거부한다. **인덱스에 `id` 미명시** 이유: InnoDB 세컨더리 인덱스는 PK 를 암묵 부착 → **P8 실행계획으로 검증하며, filesort 가 나오면 `(user_id, ordered_at, id)` 로 정정**한다 |
| **D4. 커서의 권한 지위** | 커서는 **위치 토큰이지 권한 토큰이 아니다**. 서명/HMAC 없음. `userId` 는 **항상 인증 주체(`LoginUser`)** 에서 오고 커서에 담지 않는다 | 1R #1 이 내 N4↔T4 모순을 잡았다. 커서는 아무 권한도 부여하지 않으므로 HMAC 은 보호할 대상이 없다 — 추가하면 키 관리 표면만 생긴다(CLAUDE.md §2). 대신 **"커서가 권한 근거가 되면 미완"**(N4)으로 명제를 고쳐 검증 가능하게 만든다. **명시(2R #1)**: 유효 형식의 위치 조작(임의의 시각·id 를 넣은 커서)은 **허용된 동작**이다 — 인증 주체의 주문 범위 안에서 위치만 바뀔 뿐 권한이 늘지 않는다. 탐지하지 않는다 |
| **D7. 입력 파싱 레이어** | **프레젠테이션이 소유한다.** `OrderPageQuery.of(HttpServletRequest)` 정적 팩토리가 ①폐기 파라미터 ②커서 decode ③`size` 파싱을 **전부** 수행하고, 컨트롤러는 검증된 `(OrderCursor, int)` 만 서비스에 넘긴다 | 3R #1 — 2R 은 파싱을 서비스에 두면서 테스트에는 "모든 오류에서 서비스 미호출"을 요구해 **자기모순**이었다(`@WebMvcTest` 는 서비스를 목킹하므로 서비스 파싱은 아예 실행되지 않는다). CLAUDE.md 아키텍처 규칙상 **입력 형식 검증은 Presentation** 이 맞다 — 비즈니스 로직이 아니다 |
| **D8. 오류 우선순위** | **`ORD_012`(폐기) > `ORD_010`(커서) > `ORD_011`(size)** — `OrderPageQuery.of` 안의 검사 순서로 고정 | 3R #6 — 혼합 오류(`?page=1&size=abc`)의 코드가 정해져 있지 않으면 구현이 바뀔 때 조용히 뒤집힌다 |
| **D6. 실패 코드 분리** | `ORD_010`(커서 형식) · `ORD_011`(`size` 범위/형식) · `ORD_012`(폐기 파라미터) **3개로 분리** | 2R #8 — 하나로 묶으면 잘못된 분기가 실행돼도 상태·코드 단언이 통과한다. 각 테스트는 코드에 더해 **`orderQueryService` 미호출**까지 단언한다 |
| **D5. 관측성** | **신규 커스텀 메트릭 없음.** 기존 `http.server.requests{application="order-service", uri="/api/v1/orders"}` 의 latency/status 로 배포 전후를 관찰. **커서 원문을 메트릭 태그·로그에 넣지 않는다** | 1R #10. ADR-0015 가 order-service per-service HTTP 관측성을 이미 규정하고 `MetricsConfig` 가 histogram 을 활성화한다. 커서는 고카디널리티라 태그 금지 |

### 2.2 D2 로 잃는 것 (트레이드오프 명시)

| 잃는 것 | 영향 | 수용 근거 |
|---|---|---|
| `totalElements` / `totalPages` | "총 N건" · 진행률 UI 불가 | 소비 UI 없음. 필요해지면 별도 count 엔드포인트로 분리(캐시 가능) |
| 임의 페이지 점프 (`?page=57`) | 순차 탐색만 가능 | 주문 내역은 최신순 스크롤 소비 패턴. **이것이 커서 방식의 본질적 대가**이고 성능 측정(P9)도 이 사용 모델 차이를 명시한다 |
| `COUNT(*)` 비용 | — | **정정(1R #9)**: 초안은 "COUNT 가 바로 없애려는 풀스캔"이라 적었으나 부정확하다. `(user_id, status)`·신규 `(user_id, ordered_at)` 이 `user_id` 선두라 사용자별 COUNT 는 **테이블 풀스캔이 아니라 인덱스 스캔**이다. 실제로 없어지는 것은 **별도 COUNT 왕복 + 사용자 주문 수에 비례하는 O(n) 인덱스 엔트리 스캔**이다 |

### 2.3 배포·롤백 (V12 반영)

- **롤아웃 창의 이중 계약을 인정한다.** `maxSurge:1`·`replicas:1` 이라 신·구 Pod 가 동시에 Service 뒤에 놓이는 구간이 있다. blue/green 원자 전환은 도입하지 않는다 — 이 API 의 확인된 소비자가 0이고, 전환 방식 변경은 5서비스 공통 배포 계약(PR3b/PR3c)을 건드린다.
- **Flyway 는 앱 Pod 시작 시 실행된다**(`application.yml:12-14`). 따라서 "인덱스 선적용 → 앱 전환"의 분리는 현 구조에서 **불가능**하다(1R #4 제안 중 이 부분은 채택하지 않고 사유를 남긴다). 대신 **인덱스 생성이 온라인이어야 한다**는 요구를 DDL 로 강제한다(P7).
- **롤백**: 앱 이미지만 되돌린다. **V7 인덱스는 유지**한다 — 구 버전 질의는 이 인덱스를 쓰지 않을 뿐 깨지지 않고, 인덱스 제거는 재적용 비용만 만든다. 제거가 필요해지면 후속 `V8` 로 별도 처리한다.

---

## 3. 작업 항목

### P1. 커서 값 객체 — `OrderCursor` (domain, 프레임워크 의존 0)
- `record OrderCursor(LocalDateTime orderedAt, Long id)`
- **포맷 고정**: `DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS").withResolverStyle(ResolverStyle.STRICT)` → `base64url(formatted + "|" + id)`
  - **`ResolverStyle.STRICT` 필수(3R #4)** — 기본값 `SMART` 는 실측 결과 `2026-02-30` 을 **거부하지 않고 `2026-02-28` 로 조용히 보정**한다. 커서가 가리키는 위치가 소리 없이 바뀐다
  - **저장 가능 범위 불변식** — `0001-01-01T00:00:00.000000` ~ **`9999-12-31T23:59:59.999999`**
    - **5R #1 정정(4R 수정이 틀렸다)**: 4R 은 상한을 `.499999` 로 잡았으나 **오류**다. MySQL **8.0.46** 실측 — `DATETIME(6)` 에 `.499999`·`.500000`·`.999999` 를 INSERT 후 SELECT 하면 **셋 다 원형 그대로** 나온다. 문서의 `.499999` 는 *컬럼보다 많은 소수 자릿수를 넣어 반올림할 때*의 경계이지 `DATETIME(6)` 의 저장 상한이 아니다. **그대로 갔으면 정상 데이터를 `ORD-010` 으로 거부했다**
    - **연도 검사는 여전히 필요**: 고정 패턴 `uuuu` 는 STRICT 로도 **부호형 `+10000`·`-0001` 을 파싱 통과**시킨다(실측). MySQL 은 연도 `10000` 을 거부한다(`ERROR 1292`). 검사가 없으면 decode 통과 후 JDBC/DB 단계에서 **`ORD_010` 이 아니라 500** 이 된다
    - 이 경계는 **핀 고정한 Testcontainers MySQL 에서 실제 INSERT/SELECT 로 검증**한다(T12) — 문서 인용이 아니라 실측이 근거다
  - **`ISO_LOCAL_DATE_TIME` 금지(2R #3)** — 실측 가변 길이(`03:04:00` / `.000001` / `.123456789`). `ordered_at` 은 `DATETIME(6)` 이므로 커서도 마이크로초 고정이어야 컬럼과 표현 가능 범위가 일치한다
  - **timezone 미개입**(V13/N7) — epoch 변환 없음
- `decode(String)` → 실패 시 **전부** `OrderException(ORD_010)`: 비-base64url / 구분자 부재 / 다중 구분자 / 포맷 불일치 / id 비수치 / id ≤ 0 / **MySQL `DATETIME` 범위 밖**(하한 `1000-01-01T00:00:00.000000` 미만 또는 상한 `9999-12-31T23:59:59.499999` 초과)
- **나노 잔여 검사는 별도로 두지 않는다(구현 중 정정)** — 패턴의 `SSSSSS` 가 정확히 6자리만 파싱하므로 **통과한 값의 나노는 항상 1000 의 배수**다. 별도 `% 1000` 분기는 도달 불가한 죽은 코드다. T5 ⑦(`.1234567`)의 거부는 **포맷이 수행**한다
- 위치: `order-service/.../order/domain/model/OrderCursor.java`

### P2. 에러 코드 3종 + `OrderException` 커스텀 메시지 생성자
- `common/.../ErrorCode.java`:
  - `ORD_010(BAD_REQUEST, "ORD-010", "유효하지 않은 커서입니다.")`
  - `ORD_011(BAD_REQUEST, "ORD-011", "size 파라미터가 올바르지 않습니다.")`
  - `ORD_012(BAD_REQUEST, "ORD-012", "지원하지 않는 페이지네이션 파라미터입니다.")`
- **`OrderException(ErrorCode, String)` 생성자 추가** — 2R #2: 현재 `OrderException` 은 `(ErrorCode)` 뿐이고 `BusinessException(ErrorCode, String)` 은 `protected` 라, 어떤 파라미터가 폐기됐는지 메시지에 담을 수단이 **지금은 없다**
- **`@Min/@Max` 를 쓰지 않는 이유**: V11 — 현 예외 표면에서 500 이 된다. `GlobalExceptionHandler`(5서비스 공유)에 핸들러를 추가하는 것은 이 PR 범위 밖이므로(§5-2), 검증을 애플리케이션 레이어의 명시적 `BusinessException` 으로 옮긴다

### P3. 리포지터리 커서 질의
```java
@Query("SELECT o FROM Order o WHERE o.userId = :userId ORDER BY o.orderedAt DESC, o.id DESC")
List<Order> findFirstPageByUserId(@Param("userId") Long userId, Pageable limit);

@Query("SELECT o FROM Order o WHERE o.userId = :userId "
     + "AND (o.orderedAt < :orderedAt OR (o.orderedAt = :orderedAt AND o.id < :id)) "
     + "ORDER BY o.orderedAt DESC, o.id DESC")
List<Order> findPageByUserIdAfterCursor(@Param("userId") Long userId,
                                        @Param("orderedAt") LocalDateTime orderedAt,
                                        @Param("id") Long id, Pageable limit);
```
- `Pageable` 은 **`PageRequest.of(0, size + 1)`** 만(`Sort` 미탑재 — 정렬은 JPQL 소유). `+1` 로 `hasNext` 판정 후 잘라낸다. `COUNT` 쿼리 없음.
- `OrderRepository`(domain) / `OrderRepositoryImpl` 대응. **기존 `Page<Order> findByUserId(Long, Pageable)` 삭제** — 남기면 인덱스 없는 죽은 경로가 된다.

### P4. 애플리케이션 — `OrderQueryService`
- `CursorSlice<OrderSummaryDto> getOrders(Long userId, OrderCursor cursor, int size)` — **이미 검증된 타입만 받는다**(D7). `cursor == null` 이면 첫 페이지
- 파싱·범위 검사는 **하지 않는다** — P6 이 소유
- `CursorSlice<T>(List<T> content, String nextCursor, boolean hasNext)`; `hasNext == false` → `nextCursor == null`
- `userId` 는 인자로 받은 인증 주체만 사용. 커서는 **위치 조건에만** 들어간다(D4/N4)

### P5. 프레젠테이션 — `OrderController.getOrders`
- 시그니처: `getOrders(@CurrentUser LoginUser, HttpServletRequest request)` → `OrderPageQuery q = OrderPageQuery.of(request)` → `orderQueryService.getOrders(loginUser.userId(), q.cursor(), q.size())`
- **`Pageable`/`@PageableDefault`/`@ParameterObject` import 제거**(내 변경이 만든 orphan)
- 응답 `ApiResponse<CursorPageResponse<OrderResponse>>`
- **OpenAPI 명시 선언(3R #5, 형태 고정 4R #5)**: springdoc 2.8.6 은 `HttpServletRequest` 를 **자동 무시**하므로 request 자체는 노출되지 않는다. 문제는 반대 — **Java 메서드에 `cursor`/`size` 파라미터가 없어 `@Parameter` 가 이름을 추론할 대상이 없다.** 따라서 `name` 과 `in` 을 **반드시 명시**한다:
  ```java
  @Operation(summary = "주문 목록 조회", parameters = {
    @Parameter(name="cursor", in=ParameterIn.QUERY, required=false,
               schema=@Schema(type="string")),
    @Parameter(name="size", in=ParameterIn.QUERY, required=false,
               schema=@Schema(type="integer", format="int32",
                              defaultValue="20", minimum="1", maximum="100"))
  })
  ```
  - `OpenApiConfig`(`peekcart-common-auth`) 는 `LoginUser` 를 전역 무시할 뿐이라 **충돌하지 않는다**(확인함)
  - 런타임 타입이 `String` 인 이유(V11 500 회피)를 주석으로 남긴다. 이걸 안 하면 구현은 1~100 정수만 받는데 문서는 임의 문자열이 되는 **계약 불일치가 false-green 으로 통과**한다
- `@Operation`: 커서는 불투명 문자열이며 형식에 의존하지 말 것

### P6. 요청 값 객체 — `OrderPageQuery` (presentation)
- `OrderPageQuery.of(HttpServletRequest)` 가 **순서대로**(D8) 검사한다:
  1. **폐기 파라미터** — `request.getParameterMap()` 키에 `page`·`sort`·`offset` 이 있으면 `OrderException(ORD_012, "지원하지 않는 파라미터: " + 발견된 이름들)`. `@RequestParam` 은 선언한 이름만 보므로 임의 파라미터의 **존재**를 알 수 없다
  2. **커서** — `cursor` 파라미터가 있으면 `OrderCursor.decode` (실패 시 `ORD_010`)
  3. **size** — 파라미터 **부재면 20**, **빈 문자열이면 `ORD_011`**
     - **3R #2**: `@RequestParam(defaultValue="20")` 은 spring-web 6.2.17 에서 **빈 문자열도 defaultValue 로 치환**한다(`AbstractNamedValueMethodArgumentResolver`). 그래서 `?size=` 를 오류로 잡으려면 `defaultValue` 를 쓰면 **안 되고**, 부재/빈값을 직접 구분해야 한다
     - 파싱 실패(`abc`)·오버플로(`2147483648`)·범위 밖(1~100 아님) → `ORD_011`.
  4. **다중값 계약(4R #2)** — `getParameterMap()` 의 값은 `String[]` 이다. `getParameter()` 를 쓰면 `?size=20&size=abc` 가 **첫 값만 보고 통과**해 우선순위를 우회한다. **`cursor`·`size` 는 값 배열 길이가 정확히 1** 이어야 하며, 중복 `cursor` → `ORD_010`, 중복 `size` → `ORD_011`, 중복 `page`/`sort`/`offset` 은 **존재만으로** `ORD_012`
  - **denylist 선택 명시**: `page`·`sort`·`offset` **만** 거부한다(allowlist 아님). 캐시버스터 `_=123`·`utm_*` 같은 무관한 파라미터는 통과시킨다 — allowlist 로 하면 무해한 파라미터에 400 을 주게 된다 `int` 바인딩이면 이들이 `MethodArgumentTypeMismatchException` → 핸들러 **0건** → **500 SYS-001** 이 된다(V11 확인)
- 결과: **모든 입력 오류가 서비스 호출 전에 던져진다** → `@WebMvcTest` 슬라이스에서 세 코드 전부 **`orderQueryService` 미호출** 단언이 성립한다(D7/3R #1)
- 조용한 무시는 **구 클라이언트가 항상 1페이지만 받는 오동작을 감춘다**. 실패는 소리 나야 한다

### P7. Flyway `V7__orders_cursor_index.sql`
```sql
CREATE INDEX idx_orders_user_id_ordered_at ON orders (user_id, ordered_at)
    ALGORITHM=INPLACE LOCK=NONE;
```
- **쉼표 없이** `ALGORITHM=INPLACE LOCK=NONE` 을 명시해 온라인 DDL 이 불가능한 상황에서 조용히 테이블을 잠그는 대신 **마이그레이션이 실패하도록** 만든다(1R #4).
- 기존 `idx_orders_user_id_status` 는 **삭제하지 않는다** — 상태 필터 조회를 이번 변경이 대체하지 않는다.

### P8. 실행계획 검증 (구조적 게이트)
- **두 SQL 경로를 모두 검사한다(5R #5)**: P3 에는 `findFirstPageByUserId`(첫 페이지)와 `findPageByUserIdAfterCursor`(다음 페이지)가 **별도로** 있다. 다음 페이지만 EXPLAIN 하면 **첫 페이지 쿼리에서 `user_id` 조건이나 `ORDER BY` 가 회귀해 full scan/filesort 가 나도 N3 게이트가 통과한다** — P4 의 `cursor == null` 분기가 바로 그 미검증 인접 경로다. **각 SQL 마다** 캡처·바인드·동등성·`key`/`access_type`/`used_key_parts`/`filesort` 를 단언하고, **독립적인 `IGNORE INDEX` 양성 대조군**을 각각 돌린다
- **EXPLAIN 대상 SQL 의 출처를 고정한다(2R #5)**: Hibernate `StatementInspector` 로 **P3 리포지터리 호출이 실제 발행한 SQL 문자열**을 캡처하고 **그 문자열 그대로** `EXPLAIN` 에 넣는다. 손으로 쓴 동등 SQL 은 쓰지 않는다 — row-constructor 등 다른 조건식이 실제 `OR` predicate 와 다른 계획을 통과시킬 수 있다
  - **3R #3 정정**: 2R 은 "SQL **과 바인드 값**"을 캡처한다고 적었으나 **불가능**하다 — `hibernate-core-6.6.44.Final` 의 인터페이스는 `String inspect(String)` 하나뿐이고(javap 확인) `?` placeholder 만 남은 SQL 을 준다. 계획이 구현 불가능한 것을 요구하고 있었다
  - **대체**: 캡처한 SQL 을 `PreparedStatement` 로 준비하고 **테스트가 이미 아는 값**(`userId`·`orderedAt`·`id`·limit)을 같은 순서로 바인딩한다. JDBC 계층 도구(datasource-proxy/P6Spy) 도입은 하지 않는다 — 이 검증에 필요 없다
- **동등성 단언**: 위 `PreparedStatement` 직접 실행 결과 id 목록 == 리포지터리 호출 결과 id 목록. 바인딩 순서를 틀리면 여기서 깨진다
- Testcontainers MySQL 에 주문 **5,000건(5사용자 분산 — 단일 사용자면 `user_id` 가 비선택적이라 옵티마이저가 full index scan 을 고른다)** 적재 후 `EXPLAIN FORMAT=JSON` 을 **구조적으로 파싱**(문자열 grep 금지)
- **table 노드 정규화(4R #4)**: filesort 가 생기는 대조군에서는 table 노드가 `query_block.table` 이 아니라 **`query_block.ordering_operation.table`** 아래로 내려간다. 직접 경로만 읽으면 대조군이 예외가 되고, 부재를 허용하면 반대로 false-green 이 된다. → 파서는 **두 경로 중 정확히 하나**를 찾아 정규화해 반환하고, **둘 다 없거나 둘 다 있으면 실패**시킨다
- 판정 필드(정규화된 동일 노드에서): `.key`, `.access_type`, `.used_key_parts`. `using_filesort` 는 **별도 단언** — 정상군은 `false` 이거나 `ordering_operation` 부재, 대조군은 `true`
- **`backward_index_scan` 은 게이트가 아니라 진단 정보로만 기록한다**(2R #9) — 옵티마이저 선택은 패치 버전에 따라 바뀔 수 있다
- **전용 테스트 클래스 1개 + static container 1개**(3R #8): **P8·P9·T9 를 같은 클래스·같은 컨테이너에서** 실행한다. **슬라이스는 `@DataJpaTest`**(4R #6) — `@SpringBootTest` 를 쓰면 안 된다: 기존 Order 통합테스트 선례가 MySQL+Redis+Kafka **3개**를 띄워 "static container 1개" 가 성립하지 않고, Kafka listener 등 인접 인프라가 무관한 실패를 만든다. **레포에 `@DataJpaTest` 선례가 0건**이므로 이 클래스가 첫 사례다.
  - **배선을 코드 수준으로 고정한다(5R #3)** — 슬라이스가 자동으로 주지 않는 것이 있다:
    ```java
    @DataJpaTest
    @Testcontainers
    @AutoConfigureTestDatabase(replace = NONE)   // 없으면 임베디드 DB 로 대체된다
    @Import({OrderRepositoryImpl.class, StatementInspectorTestConfig.class})
    // @Repository 인 OrderRepositoryImpl 과 커스텀 StatementInspector 는 슬라이스가 스캔하지 않는다
    class OrderCursorQueryPlanTest extends AbstractIntegrationTest {
        @Container @ServiceConnection
        static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.46"); // 핀 고정
    }
    ```
    - Boot 3.5.12 의 DataJpa 슬라이스에는 **Flyway 가 포함된다**(확인) · `common` testFixtures 는 `order-service/build.gradle:59-63` 로 **이미 클래스패스에 있다**(확인) — 이 둘은 추가 작업 불요
    - **시작 시 선행 단언**: Flyway 적용 후 `idx_orders_user_id_ordered_at` 가 존재하는지 먼저 확인한다(인덱스 없이 EXPLAIN 하면 전 단언이 무의미)
  - **트랜잭션·연결 가시성 계약(5R #4)**: `@DataJpaTest` 는 기본 `@Transactional` + 롤백이라 fixture 가 **미커밋**이다. `dataSource.getConnection()` 으로 새 연결을 얻으면 **fixture 를 못 봐** 동등성 비교가 깨지고 EXPLAIN ANALYZE 가 빈 데이터로 돈다. → **fixture flush 후 `EntityManager.unwrap(Session.class).doWork(...)` 로 동일 물리 연결에서** `PreparedStatement`/EXPLAIN 을 실행한다. `AbstractIntegrationTest.cleanDatabase()` 는 **별도 EntityManager 로 독립 커밋**하므로(`:39-68`) 이 클래스에서는 **호출하지 않는다**(컨테이너가 이 클래스 전용이라 불필요) 부동 태그 `mysql:8.0` 대신 **정확한 패치 버전으로 핀 고정** — 실행계획 계약은 옵티마이저 버전 종속이라 부동 태그면 어느 날 조용히 깨진다
  - 컨테이너 추가는 핀 때문이 아니라 **클래스 신설 때문**이다(order-service 에 `MySQLContainer` 선언이 이미 16개, per-class 수명). 이미지 pull 여부·5,000건 fixture 적재 시간·테스트 타임아웃을 **CI 증거로 기록**하고, 자원 압박이 관측되면 이 클래스만 직렬 실행을 건다
- **D3 전제는 "PK 암묵 저장의 증명"이 아니라 "인덱스에 `id` 를 명시하지 않고도 filesort 없는 인덱스 스캔"이라는 실행계획 계약으로 검증**한다

### P9. 성능 실측 (증거, N8)
- **주 게이트 = 구조적 지표**: `EXPLAIN ANALYZE` (TREE 출력) 에서 **`orders` 인덱스 스캔 iterator 노드 1개를 고정 지목**하고, 그 노드의 **`actual rows` × `loops`** 를 examined rows 로 삼는다.
  - **2R #4 정정**: 초안은 `rows_examined_per_scan`(FORMAT=JSON 필드)을 `EXPLAIN ANALYZE`(TREE) 에서 읽겠다고 적었다. **두 형식을 섞은 것**이고, 구현자가 없는 필드를 찾다가 임의 파싱으로 false-green 을 만들 수 있다. 노드 지목 규칙과 환산식을 위와 같이 고정한다
  - 지목한 노드가 출력에 없으면 **예외를 던진다**(부재 = 성공 아님)
- 판정: 깊이 1 / 100 / 900 에서 **cursor 는 examined rows 가 동일 오더(최대/최소 비 < 2), offset 은 깊이에 비례 증가**
- **벽시계 측정은 철회했다(구현 중 정정)** — 구조 지표가 평탄(20/20/20) vs 선형(40/420/920)으로 이미 결정적이라, 컨테이너 벽시계는 같은 결론에 잡음만 더한다. 증거: `docs/progress/evidence/impl6-cursor-queryplan-20260830.md`
- **규모 축소(구현 중 정정)**: 20,000행/동일 사용자 → **5,000행 / 5사용자 분산(대상 1,000행)**, 깊이는 페이지가 아니라 **행 기준 20/400/900**. per-test 시딩이라 20,000행은 클래스 실행이 수 분대가 된다. **축소가 검출력을 낮추지 않음을 실패 주입으로 확인** — 커서 predicate 를 항상 참으로 바꾸면 4건 중 2건이 FAILED(`used_key_parts` 축소 · 평탄성 상실). 원안대로 단일 사용자로 두면 `user_id` 가 비선택적이라 옵티마이저가 `ref` 대신 full index scan 을 고른다(실측)
- **사용 모델 차이 명시**: cursor 의 깊은 지점 도달은 순차 N회 왕복이다. offset 의 임의 점프와 동일 비교가 아니며 그 대가는 §2.2 에 있다

### P10. 테스트 — 정확성·격리
- P10-1 단위: `OrderCursor` round-trip + 변조 입력 7종 → `ORD_010`
- P10-2 단위: **timezone 독립성**(N7) — `user.timezone` 이 다른 두 조건에서 동일 문자열 round-trip
- P10-3 통합: 동률 `ordered_at` 경계 정확성(N2)
- P10-4 통합: 커서 권한 무효성(N4)
- P10-5 슬라이스(`@WebMvcTest`, `OrderQueryService` 목킹): **세 코드 전부** 서비스 호출 전에 던져지므로 **모든 케이스에서 `orderQueryService` 미호출 단언이 성립한다**(D7)
  - `size`: `0`·`101`·`abc`·**빈값(`?size=`)**·`2147483648` → **400 `ORD-011`**
  - 커서: T5 의 커서 입력 전종 → **400 `ORD-010`**
  - 폐기: `?page=1`·`?sort=id,asc`·`?offset=5`·복수 동시 → **400 `ORD-012`**
  - **우선순위 조합(3R #6/D8)**: `?page=1&size=abc` → `ORD-012` / `?page=1&cursor=쓰레기` → `ORD-012` / `?cursor=쓰레기&size=abc` → `ORD-010`
- P10-6 슬라이스: `OrderControllerTest.getOrders_success` 재작성(기존은 `PageImpl` 스텁이라 컴파일 실패)
- **P10-7 단위: `OrderQueryServiceTest` 재작성**(2R #6) — 기존 `:35-42` 가 `findByUserId` 를 스텁하고 `Page` 를 단언한다(P3 삭제로 컴파일 실패). 첫 페이지 / 다음 페이지 / **`size+1` 절단과 `hasNext` 판정** / `nextCursor == null` 조건을 덮는다
- **P10-9 통합: 저장 경계 DB 계약**(5R #2) — 상한/하한 날짜 자체를 `ordered_at` 에 저장하고 재조회·커서 재요청까지. T11(일반 날짜의 마이크로초 경계)과 **다른 테스트**다: T11 은 마이크로초 자릿수, T12 는 표현 범위의 끝
- **P10-8 통합: 마이크로초 경계 round-trip**(2R #3) — `ordered_at` 의 마이크로초를 `0` · `1` · `999999` 로 저장한 주문에 대해 **응답이 준 커서를 그대로 재요청**해 경계 주문이 누락·중복되지 않음을 확인. 단위 round-trip 만으로는 JDBC 바인딩 경계를 못 잡는다

### P11. OpenAPI 계약 회귀 테스트 (V14, 1R #8)
- **부팅 방식 확정(5R #6)**: `@SpringBootTest` + `@AutoConfigureMockMvc` 로 **실제 앱 OpenAPI 구성**을 검사한다. `@WebMvcTest` 는 제3자 springdoc 자동 구성을 보장하지 않아 `/api-docs` 가 **404 가 될 수 있고**, 필요한 자동 구성만 골라 import 하면 **실제 앱과 다른 컨텍스트**를 검사하게 된다. 외부 인프라는 명시적으로 대체한다
- **선행 단언**: `/api-docs` **200** + 대상 operation(`get /api/v1/orders`) 존재 → 그 다음에 스키마 필드를 본다(404 를 조용히 통과시키지 않는다)
- `/api-docs` JSON 을 읽어 `GET /api/v1/orders` 의 파라미터가 **`cursor`·`size` 뿐**이고 `page`·`sort` 가 **부재**함을 단언
- **`size` 파라미터의 `type`·`default`·`minimum`·`maximum` 4필드 + `cursor`·`size` 의 `in=query`·`required=false` 단언**(3R #5, 4R #5) — 런타임 타입이 `String` 이라 `@Schema` 를 안 붙이면 문서가 임의 문자열이 된다
- 응답 스키마에 `content`·`nextCursor`·`hasNext` 존재, `totalElements` **부재**

### P12. 문서 동기화 (N9)
- `04-design-deep-dive.md §10-3` — "전환 검토" → 전환 완료. 커서 키를 `id` 단독에서 `(ordered_at, id)` 로 **정정**하고 사유(V4/V7) 명시
- `05-data-design.md:468` 인덱스 표에 `idx_orders_user_id_ordered_at` 행 추가
- `03-requirements.md:67` 주문 내역 조회 행에 커서 파라미터/응답 형태 + **breaking change 표기**
- §2.2 트레이드오프를 `04-design-deep-dive.md §10-3` 에 요약 반영

### P13. 회귀
- `./gradlew test` 10 모듈 + 기존 lint 13종

### P14. `/ship` 기록
- `docs/TASKS.md` ⑥ 행 ✅ + PR 링크, `docs/progress/PHASE4.md` 에 이 PR 이 뒤집은 전제(V11·V12·V13) 기록

---

## 4. 검증 방법 (실패 주입 기준)

"존재한다 / 배선됐다"는 검증이 아니다. 각 항목은 **깨뜨렸을 때 빨개지는지**로 판정한다.

| id | 대상 | 실패 주입 | 기대 |
|---|---|---|---|
| **T1** (N2, P10-3) | 동률 경계 | 동일 `ordered_at` 주문 5건 삽입 후 `size=2` 로 끝까지 순회, id 집합 비교 | 누락 0·중복 0. **독립 변이 3종**(1R #7) — ① predicate 의 `o.id < :id` 절 삭제 ② `<` → `<=` ③ `ORDER BY o.id DESC` → `ASC`. **각각 단독으로** 실행했을 때 셋 다 FAILED 여야 한다(합쳐서 돌리면 서로를 가린다) |
| **T2** (N3, P8) | 인덱스 범위 스캔 | `StatementInspector` 로 캡처한 **Hibernate 실제 SQL** 을 `EXPLAIN FORMAT=JSON` 에 넣고 구조 파싱 | `key == "idx_orders_user_id_ordered_at"`, `access_type ∈ {ref, range}`, `ordering_operation.using_filesort` 가 **`false` 이거나 키 부재**. `backward_index_scan` 은 **기록만**. **경로 부재를 성공으로 처리하지 않는다** — `query_block` 이 없으면 파서가 예외. **양성 대조군**: `IGNORE INDEX` 를 건 동일 SQL 을 **같은 파서**에 넣어 `using_filesort == true` 를 읽어야 한다. **동등성 단언**: EXPLAIN 대상 SQL 직접 실행 결과 id 목록 == 리포지터리 결과 id 목록(2R #5) |
| **T3** (D3, P8) | id 미명시 인덱스로 충분한가 | 인덱스를 `(user_id, ordered_at)` 로만 둔 채 T1·T2 실행 | 통과. **반증되면**(filesort 발생) V7 을 `(user_id, ordered_at, id)` 로 바꾸고 §7 에 정정 이력을 남긴다 |
| **T4** (N4, P10-4) | 커서의 권한 무효성 | **데이터 배치·크기를 전부 고정한다(3R #7 + 4R #3)**: **A 주문 정확히 1건**, B 주문 2건(B_new, B_old)을 `ordered_at` 상 **B_new > A > B_old** 로 배치. 커서는 **B_new** 에서 만들고 **`size = 2`**(= A 건수 + 1) 로 **A 의 토큰**으로 전송 | **200 + A 의 주문만**. B 의 행이 1건이라도 섞이면 FAILED. **`size` 를 A 건수보다 크게 잡아야 변이가 죽는다(4R #3)** — 3R 은 배치만 정하고 `size`·A 건수를 안 정해서, `userId` 조건을 제거해도 **A 행이 limit 을 다 채우면 B_old 가 결과 밖에 남아 변이가 살아남는다**. `A=1건, size=2` 로 고정하면 정상 구현은 `[A]`, 변이는 `[A, B_old]` 로 **id 목록이 확정적으로 갈린다**. **변이(2R #10 정정)**: 초안의 "커서 유래 userId 사용" 변이는 `OrderCursor` 에 `userId` 가 없어 **실행 불가능**했다. 실행 가능한 변이로 교체 — **JPQL 에서 `o.userId = :userId` 조건을 제거**하면 B 의 행이 섞여 FAILED |
| **T5** (N5, P10-1/5) | 잘못된 입력의 상태코드·코드 분리·우선순위 | **커서**: ① 비-base64url ② `"abc\|1"` ③ 구분자 없음 ④ id 음수 ⑤ 구분자 2개 ⑥ 포맷 불일치 ⑦ 나노 잔여(`.1234567`) ⑧ **존재하지 않는 날짜(`2026-02-30`)** ⑨ **`0000`년** ⑩ **음수 연도** ⑪ **`+10000`년** ⑫ **`+10000` 연도(부호형)** ⑬ **`-0001` 연도(부호형)** — **`size`**: `0`·`101`·`abc`·**빈값**·`2147483648` — **폐기**: `page`·`sort`·`offset`·복수 — **중복(4R #2)**: `?size=20&size=abc` · `?cursor=정상&cursor=쓰레기` · `?page=1&page=2` — **무관 파라미터**: `?_=123&utm_source=x` — **혼합**: `page+size오류`·`page+커서오류`·`커서오류+size오류` | ①~⑬ = **400 `ORD-010`**. **양성 대조군 3종은 200**: `9999-12-31T23:59:59.499999`·**`.500000`**·**`.999999`** — 전부 저장 가능한 정상 값이다(5R #1 실측) / 중복 `cursor`=`ORD-010`·중복 `size`=`ORD-011`·중복 `page`=`ORD-012` / 무관 파라미터는 **200**(denylist 계약) / size = **400 `ORD-011`** / 폐기 = **400 `ORD-012`** / 혼합은 **D8 우선순위**대로. **전 케이스 `orderQueryService` 미호출 단언**(D6/D7). **변이**: `ResolverStyle.STRICT` 를 빼면 ⑧이 200 이 되어 FAILED(실측 — SMART 는 `2026-02-30`→`02-28` 보정) · 연도 범위 검사를 빼면 ⑨⑩⑪ 이 500 FAILED · **상한을 `.499999` 로 잘못 잡으면 양성 대조군 `.500000`/`.999999` 가 400 이 되어 FAILED**(4R 이 실제로 이 오류를 넣었다) · 연도 부호형 검사를 빼면 ⑫⑬ 이 500 FAILED · **`getParameter()` 로 구현하면 중복 3종이 전부 통과해 FAILED** · `size` 를 `int` `@RequestParam` 으로 되돌리면 `abc`/오버플로가 500, **빈값이 200** 이 되어 FAILED |
| **T6** (N7, P10-2) | timezone 독립성 | 동일 `LocalDateTime` 을 `user.timezone=UTC` / `Asia/Seoul` 두 조건에서 encode → 문자열 비교, 교차 decode | 문자열 동일 + 교차 round-trip 동일. **변이**: 인코딩을 `toEpochSecond(ZoneOffset.systemDefault())` 로 바꾸면 FAILED |
| **T7** (N1, P10-6/P11) | 응답·문서 계약 | `$.data.totalElements` `doesNotExist()`, `nextCursor`·`hasNext` 존재. `/api-docs` 에서 `page`·`sort` 부재 + **`size` 의 `type=integer`·`default=20`·`minimum=1`·`maximum=100` 4필드 + `cursor`/`size` 각각 `in=query`·`required=false` 단언**(3R #5, 4R #5) | 둘 다 그린. **변이**: 컨트롤러에 `Pageable` 을 되돌리면 FAILED · `@Schema` 선언을 지우면 `size` 가 `type=string` 이 되어 FAILED(런타임 `String` 과 문서의 불일치를 잡는다) |
| **T8** (N8, P9) | 성능 구조 지표 | `orders` 5,000행 / 5사용자 분산(대상 1,000행) 적재 후 깊이 **20 / 400 / 900행** 지점에서 `EXPLAIN ANALYZE` 의 `idx_orders_user_id_ordered_at` 스캔 iterator **1개**를 지목해 `rows × loops` 기록 | **cursor 는 평탄(최대/최소 비 < 2), offset 은 깊이에 비례 증가**. 실측 cursor `[20,20,20]` vs offset `[40,420,920]`. **벽시계는 측정하지 않는다**(§7 정정). 결과는 `docs/progress/evidence/impl6-cursor-queryplan-20260830.md` |
| **T9** (P7) | 마이그레이션 | Flyway 적용 컨테이너에서 `SHOW INDEX FROM orders` | 신규 인덱스 존재 + **기존 4개 인덱스 잔존**(P7 이 기존 것을 지우지 않았음). `ALGORITHM=INPLACE, LOCK=NONE` 구문이 MySQL 8 에서 실제로 수용되는지 확인 |
| **T10** (P13) | 전 모듈 회귀 | `./gradlew test` 10 모듈 | 그린. 특히 `OrderSecurityIntegrationTest` 가 `/api/v1/orders` 를 인증 표면으로 쓰므로 시그니처 변경 후에도 통과 |
| **T12** (N7, P10-9) | **저장 경계의 DB 계약**(5R #2) | T5 는 `@WebMvcTest` 라 **DB 에 도달하지 않는다** — 양성 대조군조차 decode 통과만 본다. 별도 MySQL 통합 테스트에서 `orders.ordered_at` 에 **`9999-12-31 23:59:59.499999` · `.500000` · `.999999` · `0001-01-01 00:00:00.000000`** 을 실제 저장 | 네 값 모두 **저장·정확한 값 재조회 성공** + 그 행의 응답 커서로 **재요청까지 성공**. 핀 고정 컨테이너(`mysql:8.0.46`)에서 실행. **변이**: `OrderCursor` 상한을 `.499999` 로 되돌리면 `.500000`/`.999999` 재요청이 400 이 되어 FAILED |
| **T11** (P10-8) | 마이크로초 경계 | `ordered_at` 마이크로초 `0`·`1`·`999999` 주문 저장 후 **응답 커서를 그대로 재요청** | 경계 주문 누락 0·중복 0. **변이**: 커서 포맷을 `.SSS`(밀리초)로 낮추면 FAILED |

---

## 5. 범위 밖 (처분 명시)

1. **상품 목록 커서 전환** — D1/V6. **처분: 별도 task 후보.** 구현 ⑤ 미충족 #1(장바구니 상품정보 조합)과 같은 캐시 표면을 건드리므로 그것과 묶는 게 자연스럽다.
2. **`GlobalExceptionHandler` 에 `HandlerMethodValidationException` 핸들러 추가** — V11 이 드러낸 **선재하는 갭**이다(내 변경이 만든 것이 아니다). 공통 모듈이라 5서비스에 영향. **처분: 부채로 등록(`/ship` 시 D-021 후보), 이 PR 은 우회**(P2).
3. **상태(`status`) 필터 + 커서 조합** — 현 API 에 상태 필터 없음. 추가 시 `(user_id, status, ordered_at)` 별도 인덱스 필요. **처분: 요구 발생 시.**
4. **양방향 커서(이전 페이지)** — 요구 없음. **처분: 미구현.**
5. **blue/green 원자 전환** — §2.3. 5서비스 공통 배포 계약 변경. **처분: 미채택, 사유 기록.**
6. **전 테스트의 MySQL 이미지 핀 고정** — 레포 전역이 부동 태그 `mysql:8.0`(`docker-compose.yml:3`, `k8s/.../mysql.yml:37`, 각 통합테스트). **처분: 이번 PR 은 실행계획 계약이 걸린 P8 테스트만 핀 고정**하고, 전역 핀은 부채 후보로 남긴다(D-022 후보).
7. **`spring.data.web.pageable.serialization-mode`** — 주문이 `Page` 를 안 쓰게 되면 이 경로는 상품 목록만 남는다. **처분: 1번과 함께.**

---

## 6. 완료 조건

### 구현 상태 (P1~P14)

- [x] P1 `OrderCursor` (고정 6자리 · STRICT · 저장범위 불변식)
- [x] P2 `ORD_010`/`ORD_011`/`ORD_012` + `OrderException(ErrorCode, String)`
- [x] P3 리포지터리 커서 질의 2종 (`findFirstPage` / `findPageAfterCursor`), `findByUserId` 삭제
- [x] P4 `OrderQueryService.getOrders(userId, OrderCursor, int)` + `CursorSlice`
- [x] P5 `OrderController` + `@Operation(parameters=...)` OpenAPI 선언
- [x] P6 `OrderPageQuery.of(HttpServletRequest)` (폐기 파라미터 · 중복 · denylist)
- [x] P7 `V7__orders_cursor_index.sql`
- [x] P8 `OrderCursorQueryPlanTest` (EXPLAIN JSON · 양성 대조군 · 두 SQL 경로)
- [x] P9 examined rows 추세 게이트 (T8)
- [x] P10 정확성·격리 테스트 (단위 · 슬라이스 · 통합)
- [x] P11 `OrderApiDocsContractTest`
- [x] P12 문서 동기화 (04 · 05 · 03)
- [~] P13 전 모듈 회귀 — **로컬 미완주(자원), CI 로 이관.** 배치 1(단위·슬라이스 20클래스) 191 tests 0 실패 · 신규 커서 통합 8 + 실행계획 4 개별 그린 · lint 14종 PASS. 기존 통합 클래스 15개의 **동시 실행**은 `ContainerLaunchException`(Testcontainers 기동 실패, 코드 실패 아님)으로 미확인 — 로컬 free 메모리 ~65MB, 타 세션 컨테이너 8개 상주. CI(`ubuntu-latest`, `./gradlew build --no-daemon`)가 이 칸을 채운다
- [x] P14 `/ship` 기록



- [x] N1~N9 전부 불성립 (N1→T6·T7 · N2→T1 · N3→T2·T3 · N4→T4 · N5→T5 · N6→opaque 단언 · N7→T6 · N8→T8 · N9→P11·P12)
- [x] T1~T12 그린, **T1(3종 독립)·T2(양성 대조군+동등성)·T4(A=1·size=2 고정)·T5(4종 변이)·T6·T7·T11·T12 는 변이 검사로 빨개지는 것까지 확인**
- [x] P9 실측 표가 `docs/progress/evidence/impl6-cursor-queryplan-20260830.md` 에 존재. 게이트는 **구조 지표 단독**(벽시계는 §7 정정으로 철회)
- [~] `./gradlew test` 10 모듈 그린 → **CI 로 이관**(위 P13) · **lint 14종 PASS** (13종 + `dockerfile-module-sync-lint`)
- [x] P12 문서 3건 + P11 OpenAPI 계약 테스트
- [x] Codex diff 리뷰 수렴 — 1R 5건(P1:3) → **2R P1=0**, 전량 반영

> **계획 리뷰는 수렴하지 않은 채 종료했다**(5라운드, P1=5). 상세·잔여 위험 4건은 `task-impl6-cursor-pagination.audit.md` §종료 판정. 구현 중 그 4건이 계획과 다르면 **계획서를 고치고 사유를 §7 에 남긴다.**

---

## 7. 정정 이력

### 계획 리뷰 1라운드 (Codex, 12건 / P0:0 P1:6 P2:6) — **전량 반영**

초안이 틀렸던 것:

1. **N4 와 T4 가 서로 모순이었다**(#1). 명제는 "타 사용자 커서가 200 이면 미완", 검증은 "200 기대". 원인은 **커서의 지위를 정하지 않은 채** 명제를 썼기 때문이다. → D4 로 "위치 토큰"임을 확정하고 N4 를 "커서가 권한 근거가 되면 미완"으로 재작성. HMAC 은 도입하지 않는다(보호할 권한이 없다).
2. **`@Min/@Max` 가 400 을 준다고 가정**(#2, V11). 실제로는 `@ExceptionHandler(Exception.class)` 가 먼저 잡아 **500**. → 애플리케이션 레이어 `ORD_011` 로 이동, T5 를 상시 가드로.
3. **"외부 소비자 0" 을 V5 로 증명했다고 서술**(#3). V5 는 레포 내 소비자 0 만 보인다. → 문구 정정 + §2.3 배포 창 이중 계약 인정 + P6 폐기 파라미터 명시적 400.
4. **인덱스 DDL 을 온라인성 고려 없이 적었다**(#4). → `ALGORITHM=INPLACE, LOCK=NONE` 명시. 단, 리뷰가 제안한 "인덱스 선적용 → 앱 전환" 분리는 **채택하지 않았다** — Flyway 가 앱 Pod 시작 시 실행되므로(`application.yml:12-14`) 현 구조에서 분리 불가. 사유를 §2.3 에 남겼다.
5. **EXPLAIN 판정을 전통 표기(`Using filesort`)로 적으면서 입력은 `FORMAT=JSON`**(#5). 불일치이자 false-green 경로. → 구조 파싱 + 필드 고정 + 양성 대조군을 **같은 파서**로.
6. **T5(성능) 를 시간 기반 게이트로 걸었다**(#6). 컨테이너 변동에 민감해 잡음으로 통과/실패한다. → 주 게이트를 `EXPLAIN ANALYZE` examined rows 로 옮기고 벽시계는 증거로 강등. 순차 탐색 비용 트레이드오프도 §2.2 에 명시.
7. **T1 변이를 한 번에 여러 개 제거하도록 서술**(#7). 서로를 가린다. → 3종 독립 변이.
8. **OpenAPI 회귀 검증 부재**(#8, V14). → P11 신설.
9. **"COUNT 가 바로 없애려는 풀스캔" 은 부정확**(#9). `user_id` 선두 인덱스가 있어 인덱스 스캔이다. → §2.2 에서 "별도 COUNT 왕복 + O(n) 인덱스 엔트리 스캔 제거"로 정정.
10. **관측성 처분 없이 "새 계약 표면 무추가" 를 선언**(#10). → D5 로 명시 결정.
11. **epoch 인코딩이 JVM 기본 timezone 에 의존**(#11, V13). grep 결과 timezone 고정이 **어디에도 없다**. → ISO-8601 문자열 인코딩으로 교체(zone 미개입), N7·T6 신설.
12. **P7-a~f 가 id 규약 위반**(#12). → P1~P14 연속 승격.

### 계획 리뷰 2라운드 (Codex, 10건 / P0:0 P1:5 P2:5) — **전량 반영**

**1라운드 수정이 실제로 새 결함을 만들었다.** 5건이 1R 에서 새로 넣은 표면에서 나왔다.

리뷰가 **확인해준 것**(반증되지 않음): P7 의 `ALGORITHM`/`LOCK` 옵션은 MySQL 8 에서 유효 — **단, 구현에서 뒤집힘**: `CREATE INDEX` 는 두 옵션 사이에 **쉼표를 허용하지 않는다**(`ERROR 1064`, 8.0.46 실측). 쉼표 형식은 `ALTER TABLE` 전용이다. 계획 리뷰가 옵션 존재는 맞혔지만 구문 형태는 실행에서만 드러났다 · `/api-docs/**` 는 공개 접근 가능 · 기존 `OrderSecurityIntegrationTest` 는 무파라미터 요청이라 P6 로 깨지지 않음 · 게이트웨이가 `page`/`sort` 를 주입하지 않음 · P1~P14 id 규약 준수.

1R 수정이 만든 새 결함:

1. **D4(HMAC 미도입)와 N5(변조 커서 400)가 모순**(#1). 서명이 없으면 "유효 형식의 변조"는 원리적으로 탐지 불가인데 명제는 그것을 요구했다. → N5 를 **형식오류**로 좁히고, D4 에 "유효 형식의 위치 조작은 허용된 동작"을 명시. 1R 에서 N4 를 고치면서 **N5 를 같이 보지 않은 것**이 원인이다.
2. **P6(폐기 파라미터 400)이 서술대로 구현 불가**(#2). ① `@RequestParam` 선언에 없는 임의 파라미터의 존재를 볼 수단이 P5 시그니처에 없다 ② `OrderException` 은 `(ErrorCode)` 생성자뿐이고 `BusinessException(ErrorCode, String)` 은 `protected` 라 "어떤 파라미터가 폐기됐는지 메시지에 포함"이 **불가능**하다(확인함). → 검사 위치를 `getParameterMap()` 으로 확정 + P2 에 생성자 추가 작업 신설.
3. **ISO 인코딩에 정밀도 계약 부재**(#3). `ISO_LOCAL_DATE_TIME` 은 가변 길이다 — 실측: `2026-01-02T03:04:00` / `...05.000001` / `...05.123456789`. 외부에서 만든 7~9자리 나노 커서가 decode 를 통과해 `DATETIME(6)` 컬럼과 비교된다. → **고정 6자리 포맷**으로 교체 + `nano % 1000 != 0` 거부 + **T11 마이크로초 경계 통합 round-trip** 신설. 1R 이 timezone 문제(V13)를 고치면서 **정밀도 문제를 새로 들여왔다.**
4. **`EXPLAIN ANALYZE` 와 `FORMAT=JSON` 필드 혼용**(#4). `rows_examined_per_scan` 은 JSON 필드인데 P9 는 `EXPLAIN ANALYZE`(TREE)에서 읽겠다고 적었다. 없는 필드를 찾다 임의 파싱 → false-green 경로. → 노드 지목 규칙 + `actual rows × loops` 환산식 고정, 노드 부재 시 예외.
5. **EXPLAIN 대상 SQL 의 출처 미규정**(#5). Hibernate 생성 SQL 인지 손으로 쓴 동등 SQL 인지 안 정했다. 후자면 실제 `OR` predicate 와 다른 계획을 통과시킨다. → `StatementInspector` 캡처 + **결과 id 동등성 단언**.
6. **`OrderQueryServiceTest` 누락**(#6). P3 가 `findByUserId` 를 삭제하는데 1R 은 `OrderControllerTest` 만 재작성 대상으로 적었다. → P10-7 신설.
7. **`int size` 바인딩의 비수치·오버플로**(#7). `size=abc`·`2147483648` 은 범위 검사 **전에** `MethodArgumentTypeMismatchException` 이고 핸들러가 **0건**이라 500 이 된다(확인함). **1R 이 `@Min/@Max` 를 걷어내면서 타입 변환 경로를 놓쳤다** — 같은 500 함정의 다른 입구. → `size` 를 `String` 으로 받아 애플리케이션에서 파싱.
8. **`ORD_011` 이 두 실패를 하나로 묶어 판정이 흐려짐**(#8). → **`ORD_011`(size) / `ORD_012`(폐기 파라미터)** 로 분리(D6) + 각 케이스에 **서비스 미호출** 단언.
9. **`backward_index_scan == true` 를 절대 계약으로 고정 + 부동 태그 `mysql:8.0`**(#9). 옵티마이저 선택은 패치 버전 종속이라 flaky. → 진단 정보로 강등 + P8 테스트만 이미지 핀 고정, 전역 핀은 §5-6 부채.
10. **T4 변이가 실행 불가능**(#10). `OrderCursor` 에 `userId` 가 없어 "커서 유래 userId 사용" 변이를 만들 수 없다. → **JPQL 의 `o.userId = :userId` 제거** 변이로 교체.

**교훈**: 1R 의 두 수정이 각각 새 함정을 만들었다 — timezone 을 고치며 정밀도를(#3), `@Min/@Max` 를 걷어내며 타입 변환을(#7). 둘 다 "고친 쪽"이 아니라 **"고치면서 건드린 인접 경로"**에서 나왔다.

### 계획 리뷰 3라운드 (Codex, 8건 / P0:0 P1:5 P2:3) — **전량 반영**

**2라운드 수정이 또 새 결함을 만들었다.** 5건이 2R 신규 표면에서 나왔다.

리뷰가 **확인해준 것**: T3 가 T1·T2 를 재사용하는 것은 역참조가 없어 **순환 참조가 아니다**.

2R 수정이 만든 새 결함:

1. **P4(서비스 파싱)와 P10-5(전 케이스 서비스 미호출)가 자기모순**(#1). `@WebMvcTest` 는 `OrderQueryService` 를 목킹하므로 서비스에 둔 파싱은 **아예 실행되지 않는다** — `ORD-010`/`ORD-011` 이 발생할 수 없고, 실제 서비스를 쓰면 미호출 단언이 반드시 깨진다. 2R 이 #8(미호출 단언)을 넣으면서 **파싱 위치를 같이 보지 않았다.** → **D7 신설: 입력 파싱을 프레젠테이션 `OrderPageQuery` 로 올린다.** CLAUDE.md 아키텍처 규칙(입력 형식 검증은 Presentation, 비즈니스 로직 아님)과도 이쪽이 맞다.
2. **`@RequestParam(defaultValue="20") String size` 에서 `?size=` 는 오류가 안 된다**(#2). spring-web **6.2.17**(확인) 의 `AbstractNamedValueMethodArgumentResolver` 는 빈 문자열도 `defaultValue` 로 치환한다 → T5 의 빈값 400 기대가 **성립하지 않는다**. 2R 이 `int`→`String` 으로 바꾸며 생긴 함정. → `defaultValue` 미사용, 부재/빈값을 직접 구분.
3. **`StatementInspector` 로 바인드 값 캡처는 불가능**(#3). `hibernate-core-6.6.44.Final` 인터페이스는 **`String inspect(String)` 하나뿐**(javap 확인)이고 `?` placeholder 만 남은 SQL 을 준다. 2R 이 "SQL **과 바인드 값**"이라고 적어 **구현 불가능한 계획**이 됐다. → SQL 형태만 캡처하고 테스트가 아는 값을 `PreparedStatement` 에 직접 바인딩. JDBC 프록시 도구는 도입하지 않는다.
4. **고정 6자리 formatter 의 기본 `ResolverStyle` 이 SMART**(#4). 실측: `2026-02-30T03:04:05.123456` 이 **거부되지 않고 `2026-02-28` 로 보정**된다 — 커서가 가리키는 위치가 조용히 바뀐다. STRICT 로도 `0000`·음수·`+10000` 연도는 통과하고, MySQL `DATETIME` 범위(1000~9999) 밖이라 **`ORD_010` 이 아니라 500** 이 된다. **2R 이 정밀도를 고치면서 날짜 유효성·연도 범위를 놓쳤다.** → `.withResolverStyle(STRICT)` + 연도 범위 검사 + T5 에 ⑧⑨⑩⑪ 추가.
5. **`String size` 전환이 OpenAPI 계약을 망가뜨림**(#5). springdoc 이 `size` 를 문자열 스키마로 공개하는데 P11 은 **이름만** 검사했다 → 구현은 1~100 정수, 문서는 임의 문자열인 불일치가 false-green 통과. → `@Schema(type=integer, default=20, min=1, max=100)` 명시 + P11 이 4필드 단언.
6. **혼합 오류의 우선순위 미정**(#6). `?page=1&size=abc` 가 어느 코드인지 계획에 없어 구현이 바뀌면 조용히 뒤집힌다. → **D8: `ORD_012` > `ORD_010` > `ORD_011`** 고정 + 조합 3종 테스트.
7. **T4 변이가 데이터 배치에 따라 살아남을 수 있음**(#7). B 에게 커서보다 오래된 주문이 없으면 `userId` 조건을 제거해도 B 행이 안 나온다. → **B_new > A들 > B_old** 교차 배치를 테스트 계약에 명시.
8. **P8·P9·T9 의 실행 위치·비용 미규정**(#8). → 전용 클래스 1개 + static container 1개로 고정, 패치 버전 핀, fixture 적재 시간·타임아웃을 CI 증거로 기록. 컨테이너 증가는 핀이 아니라 **클래스 신설** 때문임을 명시(기존 `MySQLContainer` 선언 16개).

**교훈(2R 과 동일한 형태로 재현)**: 이번에도 결함은 "고친 쪽"이 아니라 **"고치면서 건드린 인접 경로"**에서 나왔다 — 미호출 단언을 넣으며 파싱 레이어를(#1), `int`→`String` 을 바꾸며 `defaultValue` 의미를(#2), 정밀도를 고치며 날짜 유효성을(#4). 세 라운드 연속 같은 패턴이다.

### 계획 리뷰 4라운드 (Codex, 6건 / P0:0 P1:4 P2:2) — **전량 반영** · *사용자 승인으로 상한 연장*

리뷰가 **해소해준 것**: `HttpServletRequest` 시그니처는 MVC·springdoc 에서 정상 처리되고 현재 인증 필터는 request 를 **wrapping 하지 않는다** · `List<Order> + Pageable + JPQL ORDER BY` 는 Spring Data JPA 가 지원하며 **count 쿼리를 실행하지 않는다** · `OpenApiConfig` 는 `LoginUser` 전역 무시일 뿐 충돌 없음 · `AbstractIntegrationTest` 는 컨테이너 미소유라 자식이 MySQL 하나만 선언하는 것 자체는 가능.

3R 수정이 만든 새 결함:

1. **연도 범위만으로는 `DATETIME` 상한을 못 막는다**(#1). MySQL 8 의 상한은 `9999-12-31 23:59:59.**499999**` 다 — `.500000`~`.999999` 는 반올림 오버플로. 3R 이 "연도 1000~9999" 로 좁히면서 **초·마이크로초 경계를 놓쳤다.** → 전 범위 불변식 + T5 ⑫⑬ 및 **상한 직전 `.499999` 양성 대조군** 추가.
2. **`HttpServletRequest` 이동이 다중값 의미를 새로 만들었다**(#2). `getParameterMap()` 의 값은 `String[]` 인데 계획은 단일값처럼 다뤘다. `getParameter()` 로 구현하면 `?size=20&size=abc` 가 **첫 값만 보고 통과**해 D8 우선순위를 우회한다. 3R 이 파싱을 프레젠테이션으로 올리며 생긴 **새 표면**이다. → 배열 길이 1 계약 + 중복 3종 코드 고정 + **denylist(무관 파라미터 통과) 선택 명시**.
3. **T4 변이가 여전히 안 죽을 수 있다**(#3). 3R 은 배치(`B_new > A들 > B_old`)만 정하고 **`size` 와 A 건수를 안 정했다** — A 행이 limit 을 채우면 B_old 가 결과 밖이라 변이가 살아남는다. → **A=1건, size=2** 로 고정해 정상 `[A]` vs 변이 `[A, B_old]` 로 확정 분리.
4. **EXPLAIN JSON 경로가 대조군에서 다르다**(#4). filesort 가 생기면 table 노드가 `query_block.ordering_operation.table` 아래로 내려간다. 3R 이 양성 대조군을 "같은 파서"로 강제하면서 **두 트리 형태가 다르다는 걸 보지 않았다** — 직접 경로만 읽으면 대조군이 예외, 부재를 허용하면 false-green. → 두 경로 중 **정확히 하나**를 정규화, 둘 다 없거나 둘 다 있으면 실패.
5. **`@Parameter` 가 이름을 추론할 대상이 없다**(#5). springdoc 은 `HttpServletRequest` 를 자동 무시하는데(문제 없음), 반대로 메서드에 `cursor`/`size` 파라미터가 없어 **`name`·`in` 을 명시하지 않으면 문서가 안 생긴다**. → `@Operation(parameters={@Parameter(name=..., in=ParameterIn.QUERY, ...)})` 형태로 코드 고정 + P11 이 `in`·`required` 까지 단언.
6. **전용 EXPLAIN 클래스의 슬라이스 미정**(#6). 기존 Order 통합테스트 선례는 `@SpringBootTest` + MySQL/Redis/Kafka **3개**라, 그대로 따르면 "static container 1개" 주장이 무너지고 Kafka listener 가 무관한 실패를 만든다. → **`@DataJpaTest`** 로 고정(레포 **첫 사례**임을 명시).

**패턴은 4라운드에도 유지됐다** — 4건 중 3건(#1·#2·#4)이 "3R 이 고치며 건드린 인접 경로"에서 나왔다. 다만 신규 결함 수는 5 → 5 → 3 이고, 이번 라운드에서 **리뷰가 해소해준 항목이 처음으로 5건** 나왔다(이전 라운드는 각 1~4건).

### 계획 리뷰 5라운드 (Codex, 6건 / P0:0 P1:5 P2:1) — **전량 반영**

**이번 라운드는 4R 수정 하나를 통째로 뒤집었다.**

리뷰가 **해소해준 것**: Boot 3.5.12 의 `@DataJpaTest` 슬라이스에 **Flyway 자동 구성이 포함**된다 · `common` testFixtures 는 `order-service/build.gradle:59-63` 로 **이미 클래스패스에 연결**돼 있다 · denylist 의 `page`/`sort` 는 현재 `Pageable` 기본 파라미터 이름과 **일치**하고 prefix 커스터마이징 없음 · GET 이라 form POST body 혼입 해당 없음 · P3 의 첫/다음 페이지 분기를 애플리케이션에 두는 것은 D7 과 **모순되지 않는다**.

4R 수정이 만든 새 결함:

1. **`DATETIME(6)` 상한을 `.499999` 로 잡은 것이 틀렸다**(#1) — **4R 수정 자체의 오류**다. MySQL **8.0.46** 에 직접 INSERT/SELECT 한 결과 `.499999`·`.500000`·`.999999` **셋 다 원형 저장**된다. 문서의 `.499999` 는 *컬럼보다 많은 자릿수를 넣어 반올림할 때*의 경계이지 `DATETIME(6)` 저장 상한이 아니다. **그대로 갔으면 정상 데이터를 400 으로 거부했다.** → 상한을 `.999999` 로 고치고 ⑫⑬ 을 **양성 대조군으로 전환**. 연도 검사는 유지하되 근거를 교체 — 실측상 `uuuu` 는 **부호형 `+10000`·`-0001` 을 통과**시키고 MySQL 은 연도 `10000` 을 거부한다(`ERROR 1292`).
   - **이것이 이 리뷰 루프에서 처음 나온 "정정의 정정"이다.** 4R 은 "연도만 보면 부족하다"는 옳은 지적을 받아 범위를 넓혔는데, **넓히면서 잘못된 상한을 들여왔다.** 좁히는 수정뿐 아니라 **넓히는 수정도 틀린 값을 들여올 수 있다**.
2. **`.499999` 양성 대조군이 DB 계약을 검증하지 않았다**(#2). T5 는 `@WebMvcTest` 이고 전 케이스 서비스 미호출을 단언하므로 **대조군조차 DB 에 도달하지 못한다**. 4R 이 대조군을 T5 안에 넣으면서 슬라이스 성격을 보지 않았다. → **T12/P10-9 신설**: 경계값을 실제 `ordered_at` 에 저장하고 재조회·커서 재요청까지.
3. **`@DataJpaTest` 배선 미고정**(#3). 슬라이스는 `@AutoConfigureTestDatabase(replace=NONE)` 없이는 **임베디드 DB 로 대체**하고, `@Repository` 인 `OrderRepositoryImpl` 과 커스텀 `StatementInspector` 를 **스캔하지 않는다**. → 어노테이션·import 를 코드 수준으로 고정 + 인덱스 존재 선행 단언.
4. **트랜잭션·연결 가시성 계약 부재**(#4). `@DataJpaTest` 기본 롤백 트랜잭션 안의 fixture 는 **미커밋**이라, 새 연결로 EXPLAIN 하면 빈 데이터를 본다. → 동일 물리 연결(`Session#doWork`) 로 고정 + `cleanDatabase()` 미호출 명시.
5. **P8 이 두 SQL 경로 중 하나만 검사**(#5). `findFirstPageByUserId` 를 빼면 **첫 페이지 쿼리의 full scan/filesort 회귀가 N3 게이트를 그대로 통과**한다. `cursor == null` 분기가 미검증 인접 경로였다. → 두 경로 각각 캡처·대조군.
6. **P11 부팅 방식 미정**(#6). `@WebMvcTest` 는 springdoc 자동 구성을 보장하지 않아 `/api-docs` 404 가능. → `@SpringBootTest` + `@AutoConfigureMockMvc` + **200·operation 존재 선행 단언**.

**신규 결함 추세**: 5 → 5 → 3 → 5. 꺾였다고 본 4R 판단은 성급했다.

### 구현 중 정정 (diff 리뷰 포함)

계획이 코드와 갈라진 지점은 **계획서를 먼저 고치고** 구현했다. 6건이다.

1. **`nano % 1000 != 0` 검사 삭제** — 도달 불가한 죽은 코드였다. 패턴의 `SSSSSS` 가 정확히 6자리만 파싱하므로 통과한 값의 나노는 항상 1000 의 배수다. T5 ⑦(`.1234567`)의 거부는 포맷이 수행한다.
2. **`CREATE INDEX` 의 `ALGORITHM=INPLACE, LOCK=NONE` → 쉼표 제거** — 계획 리뷰 2R 이 "문법 유효"로 확인해준 항목인데 **실행에서 뒤집혔다**. `CREATE INDEX` 는 두 옵션 사이 쉼표를 허용하지 않는다(`ERROR 1064`, MySQL 8.0.46 실측). 쉼표 형식은 `ALTER TABLE` 전용이다.
3. **T12 하한 케이스 재작성** — `0001-01-01` 은 동반 행(`2000-01-01`)보다 오래돼서 "경계 행이 첫 페이지"라는 전제가 하한에서만 깨졌다. 순서에 의존하지 않고 **경계 행에서 만든 커서를 항상 사용**하도록 고쳤다.
4. **P9 규모 축소 + 벽시계 철회** — 20,000행/동일 사용자 → 5,000행/5사용자(대상 1,000행), 깊이 20/400/900행. per-test 시딩이라 20,000행은 클래스 실행이 수 분대가 되고, **단일 사용자로 두면 `user_id` 가 비선택적이라 옵티마이저가 `ref` 대신 full index scan 을 고른다**(실측 `access_type=index`). 축소가 검출력을 낮추지 않음을 **실패 주입으로 확인**: 커서 predicate 를 항상 참으로 바꾸면 4건 중 2건 FAILED. 벽시계는 구조 지표가 평탄(20/20/20) vs 선형(40/420/920)으로 결정적이라 철회했다.
5. **T9 를 총목록 고정에서 보존 계약으로 완화**(diff 2R #1) — `containsExactlyInAnyOrder` 는 이후 정당한 인덱스 추가를 깨뜨린다. P7 이 요구하는 것은 기존 인덱스 **보존**이다.
6. **`runForIds` 가 컬럼 순서 대신 메타데이터로 `id` 를 찾도록**(diff 2R #2) — JPQL 은 `SELECT o` 라 SELECT 목록 순서가 리포지터리 계약이 아니다. diff 1R 에서 동등성을 보강하며 들여온 구현 종속성이었다.

**D3 은 반증되지 않았다 — 실측으로 확인됐다.** 인덱스를 `(user_id, ordered_at)` 2컬럼으로 선언했는데 커서 질의의 `used_key_parts` 가 **`[user_id, ordered_at, id]`** 3개다. InnoDB 가 PK 를 암묵 부착해 tie-break 까지 인덱스 안에서 처리한다.
