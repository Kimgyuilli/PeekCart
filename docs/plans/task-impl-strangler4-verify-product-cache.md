# task-impl-strangler4-verify-product-cache — `verifyProductExists` 캐시화 (strangler-4)

> 사가 클러스터 strangler 시리즈의 마지막 동기 seam 제거. Order→Product 의 유일하게 남은
> 동기 결합(`ProductPort.verifyProductExists`)을 로컬 가격 캐시 존재성 조회로 전환하고
> `ProductPort`/`ProductPortAdapter` 를 제거한다. 이 PR 이 머지되면 Order↔Product 의
> 컴파일타임·런타임 동기 결합이 0 이 되어 **Product peel-ready** 상태가 된다(peel 자체는 다음 단위).

## 1. 목표

- `CartCommandService.addItem` 의 `productPort.verifyProductExists(...)` 동기 호출을 Order 로컬
  read-model(`product_price_cache`) 존재성 조회로 대체한다.
- `ProductPort`(order.application.port) 인터페이스와 `ProductPortAdapter`(product.infrastructure.adapter)
  구현체를 제거한다.
- **성공 기준**: (a) `grep -rn "ProductPort\|verifyProductExists" src/main src/test` 결과 0건(테스트
  포함), (b) `grep -rn "import com.peekcart.order" src/main/java/com/peekcart/product` 0건(역방향 어댑터
  소멸), (c) 전체 `./gradlew test` 그린, (d) addItem 캐시-미스/히트 분기를 검증하는 테스트 통과.

> **스코프 경계 명시** (Codex P2#4): 본 PR 의 "peel-ready" 는 **`ProductPort` 동기 seam 제거 기준**의
> peel 선행조건 충족만을 의미한다. ADR-0012 의 `product.updated` 전체 계약(name/status/stock)과 장바구니
> *조회* CQRS 조합(현 `CartQueryService` 는 상품 정보를 조합하지 않음)은 **본 PR 스코프 밖**이며 후속
> 작업(full `product_cache` + 조회 조합)으로 남긴다.
- 근거 ADR: **ADR-0010 F2**(동기 결합 지목) · **ADR-0012 ⑤/D3**(로컬 캐시·예약 Saga 로 해소).
  roadmap §58 "새 ADR 불필요" — 경계 변경 결정은 기존 ADR 이 이미 보유. 새 외부 의존성/인프라 없음.

## 2. 배경 / 제약

### 현재 코드 (grep 검증 완료)

- `CartCommandService.addItem`(`src/main/java/com/peekcart/order/application/CartCommandService.java:32`)
  가 `productPort.verifyProductExists(command.productId())` 를 호출 — Order→Product 의 **유일하게
  남은 동기 호출**. (재고=예약 Saga, 단가=로컬 캐시로 이미 이관됨, strangler-1~3.)
- 구현체 `ProductPortAdapter`(`src/main/java/com/peekcart/product/infrastructure/adapter/ProductPortAdapter.java`)
  는 `productRepository.findById(id).orElseThrow(PRD_001)` — Product 테이블 직접 조회.
- Order 코드는 `com.peekcart.product.*` 를 **컴파일타임 import 하지 않는다**(검증: grep 0건). 결합은
  `ProductPort` 포트 + `ProductPortAdapter` 어댑터 한 쌍의 런타임 DI 뿐. 둘 제거 시 결합 완전 차단.

### 로컬 캐시 재사용 (B2 — ADR 타깃이 코드에 이미 존재함 확인)

- `product_price_cache`(`common/.../db/migration/V7__...sql`)는 strangler-2 가 도입. `product_id` PK,
  `unit_price`, `source_version`. **V7 이 `INSERT ... SELECT id,price,version FROM products` 로
  기존 상품 전량을 seed** 하므로, 캐시 존재성은 **모든 기존 상품 + 신규 상품(create 가 product.updated
  발행)** 을 커버한다 → cold-start false-negative 없음.
- `ProductPriceCacheRepository`(order.domain.repository)에 `findUnitPrice(Long): Optional<Long>` 이
  이미 존재. 존재성 = `findUnitPrice(id).isPresent()` 와 동치이나, 본 PR 은 의도 명확성과 비용(EXISTS)
  을 위해 `boolean existsByProductId(Long)` 을 추가한다(`@Id`=productId → JpaRepository.existsById 위임).

### 시맨틱 동등성 / 트레이드오프

- **eventual-consistency 창**: 신규 상품 `create()` commit ~ `product.updated` 가 Order 캐시에 도달하기
  전 사이엔 addItem 이 미스 → 거절. 이 창은 **이미 `createOrder` 가 ORD-007 로 가지고 있는 창과 대칭**
  (`OrderCommandService.java:57`). 즉 strangler-4 는 addItem 을 createOrder 와 동일 정합성 수준으로
  맞추는 것일 뿐 새 위험을 만들지 않는다. 포트폴리오 CQRS 의 수용된 트레이드오프.
- **discontinued 상품**: 현재 `findById` 는 status 필터 없음(soft delete=status 컬럼만 변경) →
  판매중단 상품도 verifyProductExists 통과. `delete()`(discontinue)도 product.updated 를 발행하고
  consumer 는 status 무시 후 upsert → 캐시에도 잔존 → **동등(둘 다 통과)**. 회귀 없음.
- **에러 코드 모호성**: 캐시 미스는 "존재 안 함" vs "아직 전파 안 됨" 을 구분 불가. 처리 방침은 §3 P2
  에서 결정(기본안 = 신규 ORD 코드, 대안 = PRD-001/ORD-007 재사용).
- **peel 시 seed 무효화**: V7 주석대로 DB 분리 후엔 cross-DB SELECT 불가 → product.updated 전량 replay
  로 대체. 이는 **다음 단위(Product peel)** 의 책임이며 본 PR 스코프 밖.

### B1 — 역의존 스윕 (제거/변경 대상의 인바운드 간선 처분)

`grep -rn "ProductPort\|verifyProductExists" src/` 결과 전부 분류:

| # | 인바운드 간선 | 위치 | 처분 |
|---|---|---|---|
| 1 | `CartCommandService` (프로덕션 유일 호출자) | order/application/CartCommandService.java:32 | **재배선** — 캐시 존재성 조회로 교체 (P2) |
| 2 | `ProductPortAdapter` (유일 구현체) | product/infrastructure/adapter/ProductPortAdapter.java | **제거** (P3) |
| 3 | `ProductPort` (포트 인터페이스 선언) | order/application/port/ProductPort.java | **제거** (P3) |
| 4 | `CartCommandServiceTest` (`@Mock ProductPort`, verify 호출) | test/.../order/application/CartCommandServiceTest.java:7,32,44 | **재작성** — 캐시 repo mock 으로 교체, 히트/미스 분기 검증 (P4) |
| 5 | `ProductPriceCacheSagaIntegrationTest.endToEnd_cacheMiss_throwsORD007` | test/.../order/infrastructure/ProductPriceCacheSagaIntegrationTest.java:191-203 | **재작성** — "addItem 은 동기 검증만" 전제가 역전됨. 이제 캐시 미스면 addItem 자체가 거절 → 테스트 의도/단언·주석(:194) 갱신 (P4) |

- `CartControllerTest` 는 `ProductPort`/`PRD` 참조 0건(grep) → 서비스 레이어 mock, 에러코드 결정은
  서비스 계약. 프레젠테이션 회귀 없음.
- 출처: PLAN-BLINDSPOTS **B1**(역의존 스윕), **B2**(ADR 타깃=코드 존재 확인).
- **나머지 항목 처분** (Codex P2#3 — 비해당도 명시):
  - **B3**(공유 테스트 인프라 소유처): 비해당 — test config/fixture 이동 없음.
  - **B4**(구체 메커니즘): 충족 — §3 P1~P5 와 §4 영향 파일로 파일/경로/배선 구체화 완료.
  - **B5**(공유 리소스 물리 위치): 비해당 — 마이그레이션/스키마/정적자원 이동 없음(V7 재사용).
  - **B6**(`:common` 스캔 횡단 빈): 비해당 — 신규 서비스 모듈 생성/peel 없음.
  - **B7**(버전 가드 upsert): 비해당 — 본 PR 은 read 쿼리(`existsByProductId`) 추가 only, upsert 미변경.

## 3. 작업 항목

- [ ] **P1.** `ProductPriceCacheRepository` 에 `boolean existsByProductId(Long productId)` 추가.
  - domain 인터페이스 + `ProductPriceCacheRepositoryImpl` + `ProductPriceCacheJpaRepository`
    (`@Id`=productId → `existsById` 위임). → verify: 단위/슬라이스에서 히트 true / 미스 false.
- [ ] **P2.** `CartCommandService.addItem` 재배선: `productPort.verifyProductExists(...)` →
    `priceCacheRepository.existsByProductId(...)` 검사 후 미스면 예외. `ProductPort` 필드/주입 제거,
    `ProductPriceCacheRepository` 주입(이미 order.domain). 
  - **에러 코드 확정** (GP-2 결정): 신규 `ORD-009`("상품 정보를 아직 사용할 수 없습니다. 잠시 후 다시
    시도", `HttpStatus.CONFLICT`) 채택 — eventual-consistency 재시도 시맨틱을 정확히 표현, ORD-007(가격
    미스)과 분리. (검토했던 대안 ORD-007 재사용/PRD-001 은 각각 '가격' 메시지 부정확·'미전파'를 404 로
    오표현이라 기각.) → verify: 미스 시 ORD-009 로 실패.
  - **외부 API 계약 변경 인지** (Codex P1#1): `addItem` 미스 응답이 404(현 PRD-001 경로)에서 409(ORD-009)
    로 바뀌는 것은 Presentation 계약 변경이다. 따라서 P4 에 컨트롤러 레벨 단언을 추가하고(아래), `ErrorCode`
    에 ORD-009 를 추가한다. 04-design-deep-dive 의 에러코드 예시/도메인 코드 표 갱신은 P2 작업 범위에 포함.
- [ ] **P3.** `ProductPort`(order/application/port) + `ProductPortAdapter`(product/infrastructure/adapter)
    삭제. 빈 `adapter` 패키지/미사용 import 정리(내 변경이 만든 orphan 만). 
  - → verify: `grep -rn "ProductPort\|verifyProductExists" src/main` 0건 · 부팅 성공(빈 누락 없음).
- [ ] **P4.** 테스트 처분 (B1 #4,#5):
  - `CartCommandServiceTest`: `@Mock ProductPort` → `@Mock ProductPriceCacheRepository`, addItem
    히트 성공 / 미스 거절 단언으로 교체.
  - `ProductPriceCacheSagaIntegrationTest.endToEnd_cacheMiss_throwsORD007`: 캐시 미스 시 **addItem 이**
    ORD-009 로 거절함을 단언하도록 재작성, :194 주석 갱신. (캐시 히트→createOrder 단가 스냅샷
    e2e 인 `endToEnd_realKafka_orderSnapshotsCachedPrice` 는 그대로 통과해야 함 — 회귀 가드.)
  - **`CartControllerTest`** (Codex P1#1): `POST /api/v1/cart/items` 캐시 미스 시 응답의 HTTP status(409)·
    `code`(ORD-009)·`message` 를 단언하는 슬라이스 테스트 추가(현재는 성공/검증실패만 존재). 서비스
    예외→`GlobalExceptionHandler`→응답 직렬화 계약을 닫는다.
  - → verify: 세 테스트 파일 그린.
- [ ] **P5.** 회귀/경계 검증 (Codex P1#2 — grep-only 보강): `./gradlew test` 전체 그린 + 다층 가드.
  - **FQCN 포함 전체 참조 검색**(import 누락분 차단): `rg "com\.peekcart\.product"
    src/main/java/com/peekcart/order` 0건 & 역방향 `rg "com\.peekcart\.order"
    src/main/java/com/peekcart/product` 0건(어댑터 소멸로 product→order 참조 0).
  - **seam 잔존 가드**: `grep -rn "ProductPort\|verifyProductExists" src/main src/test` 0건(테스트 포함).
  - **구조 가드** (Codex 2차 P1#1/P2#2 — 새 의존성 없는 custom 가드, production 한정): 기존
    `assertNoServiceProjectDeps`(build.gradle:201) 와 동일한 **custom Gradle 소스-스캔 task** 1개를
    추가한다 — `src/main/java/com/peekcart/order/**` 소스에서 `com.peekcart.product` 참조가 발견되면
    빌드 실패(역방향 `src/main/.../product` → `com.peekcart.order` 도). **ArchUnit 등 새 의존성 도입
    금지**(기존 가드 패턴과 일관, §1 "새 외부 의존성 없음" 유지). **`src/main` production 만 스캔** —
    `src/test` 의 Order 통합테스트는 Product 타입을 합법적으로 시드하므로(`ProductPriceCacheSagaIntegrationTest`,
    `OrderExpiredPaymentRequestedQueryIntegrationTest`) 가드 대상 제외, 테스트의 seam 잔존은 위 grep 으로만 검증.
  - → verify: 위 검색 전부 0 & custom 소스-스캔 가드 task 그린 → Order↔Product **production** 동기 결합
    소멸(peel-ready 선행조건). 마이그레이션 불필요(V7 seed 재사용).

## 4. 영향 파일

**프로덕션 (수정/삭제)**
- `src/main/java/com/peekcart/order/application/CartCommandService.java` — 재배선 (P2)
- `src/main/java/com/peekcart/order/domain/repository/ProductPriceCacheRepository.java` — exists 추가 (P1)
- `src/main/java/com/peekcart/order/infrastructure/ProductPriceCacheRepositoryImpl.java` — 구현 (P1)
- `src/main/java/com/peekcart/order/infrastructure/ProductPriceCacheJpaRepository.java` — existsById 위임 (P1)
- `src/main/java/com/peekcart/order/application/port/ProductPort.java` — **삭제** (P3)
- `src/main/java/com/peekcart/product/infrastructure/adapter/ProductPortAdapter.java` — **삭제** (P3)
- `common/src/main/java/com/peekcart/global/exception/ErrorCode.java` — ORD-009 추가 (P2, 기본안 채택 시)

- `docs/04-design-deep-dive.md` — 에러코드 예시/도메인 코드 표에 ORD-009 반영 (P2)

**테스트**
- `src/test/java/com/peekcart/order/application/CartCommandServiceTest.java` — 재작성 (P4)
- `src/test/java/com/peekcart/order/infrastructure/ProductPriceCacheSagaIntegrationTest.java` — 재작성 (P4)
- `src/test/java/com/peekcart/order/presentation/CartControllerTest.java` — 캐시 미스 409/ORD-009 단언 추가 (P4)

**빌드**
- `build.gradle` — order↔product production 패키지 의존 금지 custom 소스-스캔 가드 task 추가
  (`assertNoServiceProjectDeps` 패턴, 새 의존성 없음) (P5)

**마이그레이션**: 없음 (V7 의 product_price_cache seed 가 존재성을 이미 커버).

## 5. 검증 방법

- **단위**: `CartCommandServiceTest` — 캐시 히트→addItem 성공, 미스→ORD-009 throw.
- **슬라이스**: `CartControllerTest` — 캐시 미스 시 POST `/api/v1/cart/items` 가 409·code=ORD-009·message
  를 반환(서비스 예외→GlobalExceptionHandler 직렬화 계약).
- **통합(Testcontainers)**: `ProductPriceCacheSagaIntegrationTest` — (a) 캐시 미스→addItem 거절(ORD-009),
  (b) 실 Kafka relay 후 캐시 히트→addItem→createOrder 단가 스냅샷 e2e 회귀 그린.
- **정적/구조 경계 가드**: `grep -rn "ProductPort\|verifyProductExists" src/main src/test` == 0,
  `rg "com\.peekcart\.product" src/main/java/com/peekcart/order` == 0,
  `rg "com\.peekcart\.order" src/main/java/com/peekcart/product` == 0, custom 소스-스캔 가드 task(src/main 한정) 그린.
- **전체**: `./gradlew test` 그린.

## 6. 완료 조건

- P1~P5 모두 충족, `./gradlew test` 그린.
- `ProductPort`/`ProductPortAdapter`/`verifyProductExists` 가 코드베이스(`src/main`+`src/test`)에서 소멸.
- Order↔Product 동기(컴파일타임·런타임) 결합 0, custom 소스-스캔 가드(src/main)로 회귀 고정 → **`ProductPort` 동기 seam
  제거 기준**의 Product peel 선행조건 충족. (ADR-0012 full `product.updated` 계약/장바구니 조회 CQRS
  조합은 후속 작업 — §1 스코프 경계 참조.)
- TASKS.md 구현 ① 행에 strangler-4 완료 PR 링크 추가(머지 시).
