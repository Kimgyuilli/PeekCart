# 구현 ⑤ — CQRS 로컬 캐시 (범위 재확정 + Redis 조회 fallback, L-006)

> 부모: `docs/TASKS.md` 구현 ⑤ · `docs/progress/phase4-design-roadmap.md §51`
> 선행 ADR: ADR-0012 §D2(`product.updated` 계약) · ADR-0009 S7 · ADR-0015 · ADR-0013 D3(대조)
> 선결 부채: L-005 (`RedisCacheManager.enableStatistics`) ✅ PR #38
> 리뷰 이력: `task-impl5-cqrs-cache-fallback.audit.md`
> **이 PR 은 구현 ⑤ 를 종결한다.**

---

## 1. 목표/목적 — 명제

**Redis 가 죽거나 매달려도 상품 조회는 유계 시간 안에 DB 로 응답하고, 그 fallback 이 발동했다는 사실이 메트릭에 남는다. 그리고 캐시 무효화 실패는 조회 실패와 다르게 처리·복구된다.**

부정형 — 아래가 하나라도 성립하면 미완이다.

1. Redis 가 죽었을 때 상품 상세/목록 조회가 **5xx 로 실패한다**
2. Redis 가 **응답하지 않을 때**(연결 거부가 아닌 무응답) 요청이 유계 시간 안에 끝나지 않는다
3. fallback 이 발동했는데 **메트릭 표면에 흔적이 없다** (로그를 읽어야만 알 수 있다)
4. `@CacheEvict` 실패를 조회 실패와 **같은 정책으로 조용히 삼켜**, Redis 복구 후 stale 상품 정보가 무기한 서빙된다
5. 검증이 "빈이 존재한다"에 그쳐, **실제 Redis 장애를 주입하지 않은 채** 통과한다
6. 기존 S7 계약(`cache_gets_total`, `cache_manager="cacheManager"` 라벨)이 **깨진다**
7. ADR-0012 의 잘못된 사실 진술(테이블명·장바구니 소비처)이 **정정되지 않은 채 남는다**

---

## 2. 배경/제약 — 착수 전 코드 검증

### 2.1 구현 ⑤ 의 본체는 구현 ① 안에서 이미 끝났다 (범위 축소)

로드맵이 ⑤ 에 귀속시킨 표면을 코드로 전수 대조했다. 구현 ④ 와 같은 패턴이다 — ADR 이 규정한 산출물 대부분이 선행 PR 에서 이미 완료돼 있었다.

| # | 표면 | 문서 근거 | 코드 사실 | 처분 |
|---|---|---|---|---|
| 1 | `product.updated` 발행 (Product) | ADR-0012 §D2 | `ProductOutboxEventPublisher:68` — 7 필수필드 + `version`, 파티션키 `productId`, Outbox 경유 | ✅ 완료 (strangler-2 [#57]) |
| 2 | Order 로컬 캐시 구독·갱신 | `04 §9-13` | `ProductPriceCacheConsumer` + `ProductPriceCache`(`product_price_cache`). 멱등 = `processed_events`, 순서 = `source_version < version` stale-skip | ✅ 완료 (strangler-2 [#57]) |
| 3 | 동기 호출 제거 | ADR-0010 F2/F3 | `ProductPort` 소멸. `OrderCommandService` 는 가격 캐시(`ORD-007`), `CartCommandService.addItem` 은 존재검증(`ORD-009`) | ✅ 완료 (strangler-4 [#61]) |
| 4 | **Redis 캐시 fallback** | prep-roadmap L-006, ADR-0009 S7 | `CacheErrorHandler` 구현체 **0건** (`grep -rl` rc=1) | 🔲 **본 PR 의 실질 잔여** |
| 5 | 장바구니 조회 시 상품 정보 조합 | `05-data-design.md:290`, ADR-0012 §D2 | `CartItemDto=(id,productId,quantity)`, `CartQueryService.getCart` 조합 없음. 캐시도 `unit_price` 만 보유 | 🔲 **미구현 — 별도 task 승격**(§6 M1), 본 PR 은 문서 정정 + 귀속 차이 기록(P8·P9) |

코드 주석이 이미 자기 귀속을 적어둔 상태다 — `ProductPriceCache` / `ProductPriceCacheConsumer` javadoc 이 `"(CQRS ⑤, strangler-2)"`.

**결론: 구현 ⑤ 의 잔여 = 표면 #4 (L-006) 1건 + 문서/귀속 정정.**

### 2.1-a ADR-0012 귀속과의 차이 — Update Log 로 정정한다 (r1 #1 · r2 #5 · r3 #1)

ADR-0012 는 두 곳에서 ⑤ 를 언급한다.

- `:124-127` **"구현 ⑤: `product.updated` 발행/소비 + `product_cache`"** — 발행·소비는 §2.1 의 #1·#2 로 충족. 다만 실제 테이블은 `product_cache` 가 아니라 **`product_price_cache`** 이고 컬럼은 4개(`product_id`/`unit_price`/`source_version`/`updated_at`)다.
- `:53` D2 — 7 필수필드가 `"Order product_cache/**장바구니 조회**(05 §249-250) 충족"`. **이 문장은 소비처를 명시한다.** 그런데 장바구니 조합은 구현되지 않았고 Order 는 payload 중 `price`/`version` 만 소비한다(`ProductPriceCacheConsumer:43-46`).

> **〔초안 정정 — 3라운드 #1 수용〕** 초안 §2.1-a 는 이를 *"payload 필드 계약에 대한 진술이고 그 계약은 지켜졌으므로 정정할 사실 오류가 없다"* 고 판정했다. **이 논거는 철회한다.** `:53` 은 소비처를 적은 문장이 맞고, "필드 계약일 뿐"이라는 축소는 결론(ADR 무변경)에 맞춰 끌어온 재해석이었다. 리뷰어가 2·3라운드 연속으로 같은 지점을 지적했고 그쪽이 정확하다.

**처분: `docs/adr/README.md:11-13` 의 Update Log 경로.** 테이블명·컬럼 범위·소비처 구현 상태는 **사실 진술의 오류**(README 가 예시로 든 "파일명·Phase 귀속·수치" 계열)이지, 트레이드오프 변경이나 Consequences 재해석(`:14` → 새 ADR 사유)이 아니다 — 결정 자체(`product.updated` 로 동기 호출을 대체한다, 7필드를 싣는다)는 그대로 유효하고 지켜지고 있다.

→ P11 로 ADR-0012 본문을 정정하고 `## Update Log` 절을 추가하며, 해당 커밋에 **`fix(adr):` 접두사**를 쓴다(README 가 요구하는 두 조건). ADR 개수·Status 는 변경하지 않는다. 진행 이력(어느 PR 에서 무엇이 남았는지)은 별도로 `PHASE4.md` 에 남긴다(P9) — Layer 2 는 결정, Layer 3 는 이력이라는 CLAUDE.md 원칙대로다.

**ADR 선행 신호 점검**: 새 **운영** 외부 의존성 ❌ / 아키텍처 경계 변경 ❌ / 신규 환경·인프라 ❌ → 신규 ADR 선행 불필요. `org.testcontainers:toxiproxy` 는 `testImplementation` 전용이라 이 신호에 해당하지 않는다(§2.7).

### 2.2 L-006 의 대상은 product-service 하나뿐이다

Spring Cache 어노테이션 전수 — **product-service 5곳이 전부**:

| 위치 | 어노테이션 | 캐시 (TTL) |
|---|---|---|
| `ProductCacheService:38` | `@Cacheable("product", key="#productId")` | 상세 (30m) |
| `ProductCacheService:53` | `@Cacheable("products", ...)` | 목록 (10m) |
| `ProductCommandService:41` | `@CacheEvict("products", allEntries=true)` | 생성 |
| `ProductCommandService:69-70` | `@CacheEvict` ×2 | 수정 |
| `ProductCommandService:98-99` | `@CacheEvict` ×2 | 판매중단 |

Redis 를 쓰는 다른 지점은 **Spring Cache 경로가 아니므로 대상 밖**:

| 위치 | 용도 | 왜 대상 밖인가 |
|---|---|---|
| `gateway/.../FailClosedRedisRateLimiter` | rate limit | ADR-0013 D3 이 **의도적 fail-closed**. 기본 SCG 구현의 fail-open 을 `@Primary` 로 덮은 코드다 — fail-open 주입은 ADR 위반 |
| `gateway/.../TokenDenyLookup` | 토큰 차단 조회 | 보안 판정. fail-open = 폐기 토큰 통과 |
| `user-service/.../TokenBlacklistRepository` | 블랙리스트 | 동상. `RedisTemplate` 직접, `CacheInterceptor` 미경유 |

### 2.3 라이브러리 전제 — 바이트코드로 확인

`spring-context-6.2.17.jar`(Spring Boot 3.5.12 가 해석한 버전)를 풀어 `javap` 로 확인했다.

**(a) 맨 `@Bean CacheErrorHandler` 는 무시된다.**
`AbstractCachingConfiguration` 의 `errorHandler` 공급원은 `setConfigurers(ObjectProvider<CachingConfigurer>)` 하나뿐이다 → `CacheConfig` 가 **`CachingConfigurer` 를 구현**해야 한다. 이 전제가 틀리면 배선이 조용히 무효가 되므로 §5 의 장애 주입 테스트가 이를 직접 판별한다(기본 `SimpleCacheErrorHandler` 는 예외를 되던진다).

**(b) `errorHandler()` 만 오버라이드해도 캐시매니저 배선이 유지된다.**
`CachingConfigurer` 4개 메서드는 전부 `default`(null 반환), `CacheAspectSupport.afterSingletonsInstantiated` 는 cacheResolver 부재 시 `beanFactory.getBean(CacheManager.class)` 로 타입 폴백한다 → 현재의 `@ConditionalOnProperty` 2빈 구조를 건드릴 필요가 없다.

**(c) 〔초안 정정〕 `cacheManager()` 오버라이드가 빈 이름을 바꾼다는 초안 주장은 틀렸다.**
초안 §2.3-c 는 *"`CachingConfigurer#cacheManager()` 를 오버라이드하면 빈 이름이 바뀌어 S7 의 `cache_manager="cacheManager"` 라벨이 깨진다"* 고 적었다. **반증됨**(리뷰 1라운드 #7) — 라벨은 `CacheMetricsAutoConfiguration` 이 읽는 **`@Bean` 메서드 이름**(`CacheConfig:40-42`)에서 오고, `CachingConfigurer` 의 no-arg `cacheManager()` 를 추가해도 기존 `@Bean cacheManager(RedisConnectionFactory)` 빈의 이름은 그대로다. 오버라이드와 빈 이름을 혼동한 것이다.
→ **결론은 유지하되 근거를 교체한다**: `cacheManager()` 를 오버라이드하지 않는 이유는 라벨 보존이 아니라 **불필요한 명시 배선을 피하고 `@ConditionalOnProperty` 2빈 구조에서 CacheManager 모호성을 만들지 않기 위해서**다. 라벨 보존은 `@Bean cacheManager` 메서드를 그대로 두는 것으로 달성되고, `ProductObservabilityMetricsIntegrationTest:96-108` 이 이를 검증한다.

### 2.4 fail-open 의 비대칭 — 콜백마다 결과가 다르다

`CacheErrorHandler` 는 `handleCacheGetError` / `PutError` / `EvictError` / `ClearError` 4콜백을 갖는다.

- **get 실패 → 삼킴**: `CacheInterceptor` 가 미스로 간주 → 타깃 메서드 실행 → DB 조회 → 정상 응답. **L-006 본체.**
- **put 실패 → 삼킴**: 〔초안 정정, 리뷰 1라운드 #5 수용〕 초안은 이를 **"무해"** 라고 단정했다. **부정확하다** — 현재 응답의 정확성은 유지되지만 캐시가 채워지지 않으므로 **모든 후속 요청이 DB 를 다시 친다**. Redis 장애가 DB 부하·커넥션 풀 압박으로 **전이**된다. 삼키는 정책은 유지하되(응답 실패보다 낫다) 이 전이를 **메트릭으로 보이게** 하고 트레이드오프로 명시한다. 동시 요청 하에서는 이것이 **cache stampede** 로 나타나 DB 커넥션 풀을 포화시킬 수 있다 — 본 PR 은 이를 인지·문서화하고 임계 감시를 운영 계약에 남기되(P7), 완화 기구(bulkhead·`@Cacheable(sync)`·rate limit)는 §6 M7 로 승격한다(r2 #10).
- **evict 실패 → 삼키면 위험**: 상품 수정/판매중단이 **DB 는 커밋되고 무효화만 실패**한다. Redis 복구 후 TTL(상세 30m / 목록 10m) 만료 전까지 **stale 가격·상태를 서빙**한다. 판매중단 상품이 최대 30분 노출될 수 있다.

→ 정책을 분리하고(P2), evict/clear 는 **WARN + 메트릭으로 반드시 가시화**하며, 복구 절차를 운영 계약으로 못 박는다(P7).

### 2.5 fail-open 은 "빠르게 실패할 때"만 의미가 있다

`spring.data.redis.timeout` / `connect-timeout` 이 **어느 yml 에도 없다**(product-service base/local/k8s 전수). Lettuce 기본 command timeout 은 60s.

- Redis 다운 → 포트 닫힘 → connection refused **즉시 실패** → fallback 이 빠르게 동작
- Redis 가 살아있으나 무응답(네트워크 블랙홀, swap 스톨) → **요청이 60s 매달림**. 핸들러는 60s 뒤에야 호출되므로 fail-open 이 **사실상 무의미**하고, 스레드 고갈로 장애를 증폭한다

→ 유계 타임아웃을 base 에 선언한다(P4). ADR-0007 판단: 타임아웃은 "환경마다 달라야 하는 연결 정보"가 아니라 **동작 규약**이므로 base 소유 (`app.outbox.polling.publish-timeout`·`app.scheduler.*` 선례). 다만 `spring.data.redis` 하위 트리를 base(timeout)와 프로파일(host/port)이 **나눠 갖게 되므로** ADR-0007 §"Java Config 우선" 조건 1(병합 충돌 가능성)에 걸리는지를 추정하지 않고 **최종 바인딩 회귀 테스트로 실증**한다(P4, r2 #7).

**타임아웃 값은 1s 가 아니라 500ms 다** (r2 #2 수용). `@Cacheable` 한 번의 요청은 **get 실패 → DB → put 실패** 로 command timeout 을 **두 번** 소비한다. 1s 로 두면 무응답 시 최소 2s + DB/HTTP 오버헤드라 초안의 "2s 이내" 상한이 산술적으로 성립하지 않았다.

| | command timeout | 요청당 최대 Redis 대기 | 검증 상한 |
|---|---|---|---|
| 초안 | 1s | 2s (get+put) | 2s ❌ 성립 불가 |
| 확정 | **500ms** | 1s (get+put) | **1.5s** (여유 500ms) |

→ 그리고 이 경로는 `redis.stop()`(연결 거부)으로 재현되지 않는다. **Toxiproxy 로 연결은 유지한 채 응답만 차단**해야 검증된다(P5, r1 #2 수용).

### 2.6 관측성 lint 는 캐시 메트릭을 게이트하지 않는다

`observability-ssot-lint.sh` 는 D5-V1(SSOT 위치)·D5-V2(`MeterRegistryCustomizer`/`MeterFilter` 중복 소유, `management.metrics.tags.application` 값)만 본다. `observability-promql-lint.sh` 의 `cache` 매치는 전부 `.cache/` 경로다.
→ 신규 Counter 추가에 **lint 변경 불필요**. ADR-0015 §45 가 S7 을 정정 대상 밖으로 명시 → **ADR 개정 불필요**.

### 2.7 제약

- **구조 변경 없음** → `PLAN-BLINDSPOTS.md` 비대상 (모듈/경계 변경·코드 이동·peel·rename 전무). B1 역의존 스윕 대상 아님
- **DB 마이그레이션 없음** — Flyway 신규 파일 0개. 스키마 무변경
- **신규 테스트 의존성 1개**: `org.testcontainers:toxiproxy` (product-service `testImplementation` **전용**). 운영 산출물·이미지·런타임 미포함 → ADR-0007/ADR 선행의 "새로운 외부 의존성" 신호에 해당하지 않는다
- **`hpx_plan_lint` 는 현재 zsh 에서 실행 불가** — `.claude/scripts/lib/sync.sh:10` 의 `local path=` 가 zsh 의 `path`↔`PATH` 연동을 건드려 `PATH` 를 `docs/plans/...` 로 덮고 다음 줄 `python3` 가 command not found 로 죽는다. bash 에서는 정상. 재현 확인함 → 완료 조건이 실행 가능하려면 P10 선결
- 테스트 하네스 기반: `ProductCacheIntegrationTest` 가 이미 `@Container @ServiceConnection(name="redis") GenericContainer<>("redis:7")` 를 씀

---

## 3. 작업 항목

- [x] **P1.** `CacheConfig implements CachingConfigurer` 로 전환하고 `errorHandler()` **하나만** 오버라이드한다. `cacheManager()`/`cacheResolver()`/`keyGenerator()` 는 오버라이드하지 않는다(§2.3-b/c). 기존 `@ConditionalOnProperty` 2빈 구조·TTL·prefix·직렬화는 무변경 — `@Bean cacheManager` 메서드 이름을 그대로 두어 S7 라벨을 보존한다.
- [x] **P2.** `ResilientCacheErrorHandler` 를 신설해 콜백별 정책을 분리한다 — get/put 은 삼킴(조용히), evict/clear 는 삼킴 + **WARN 로그 + 메트릭**. 클래스 javadoc 에 §2.4 의 stale 창(30m/10m)·put 실패의 DB 부하 전이/stampede·그리고 gateway 가 왜 정반대(fail-closed, ADR-0013 D3)인지를 명시한다 — 한 저장소에 두 정책이 공존하는 이유가 코드에서 읽혀야 한다.
- [x] **P3.** fallback 메트릭 `cache.fallback{cache, operation=get|put|evict|clear}` Counter 를 등록한다(Prometheus `cache_fallback_total`). `MeterRegistry` 생성자 주입만 쓰고 `MetricsConfig`(peekcart-common-observability 단독 소유)는 건드리지 않는다 — D5-V2 lint 가 `MeterRegistryCustomizer`/`MeterFilter` 중복 소유를 검출한다. S7(`cache_gets_total`)과 짝을 이뤄 "적중률 하락이 미스인지 Redis 장애인지"를 구분 가능하게 한다.
- [x] **P4.** `product-service/src/main/resources/application.yml`(base)에 `spring.data.redis.timeout: 500ms` · `connect-timeout: 300ms` 를 §2.5 근거 주석과 함께 선언한다. **그리고 `spring.data.redis` 하위 트리가 base(timeout)와 프로파일(host/port)로 나뉘어도 최종 바인딩이 깨지지 않음을 회귀 테스트로 고정한다** — `local`·`k8s` 각 프로파일에서 `RedisProperties` 의 `host`/`port`/`timeout`/`connectTimeout` 4값을 전부 단언(ADR-0007 조건 1 을 추정 대신 실증, r2 #7).
- [x] **P5.** Toxiproxy 장애 주입 하네스를 세운다 — Redis 컨테이너와 `ToxiproxyContainer` 를 **동일 `Network`** 에 두고, **backend Redis 의 `@ServiceConnection` 을 제거**한 뒤 `@DynamicPropertySource` 로 `spring.data.redis.host/port` 를 **프록시 주소**에 명시 배선한다. 애플리케이션 트래픽이 프록시를 실제로 통과하는지를 **첫 단언으로 고정**한다(프록시 정지 시 조회 경로가 fallback 을 타는지 확인) — 이 배선을 놓치면 V1~V5 가 전부 false-green 이 된다(r2 #1). 장애 조작은 **두 기구를 구분**한다(r3 #2) — **연결 거부는 `Proxy#disable()`/`enable()`**, **무응답은 downstream `timeout(_, 0)` toxic 추가/제거**(0 = 제거 전까지 연결 유지). bandwidth toxic 은 정체만 만들고 거부하지 않으므로 쓰지 않는다. `@DynamicPropertySource` 는 `toxiproxy.getHost()` + 프록시의 host-mapped port 를 공급하고, Redis 는 network alias 로 프록시의 upstream 이 된다. 모든 주입은 `finally` 에서 원복하므로 컨테이너 정지가 없고 테스트 간 순서 의존도 없다.
- [x] **P6.** put 실패의 **DB 부하 전이**를 검증한다 — 전면 장애(get·put 동시 실패) 상태에서 동일 상품을 N회 순차 조회하고, `ProductRepository` 스파이로 **`findById` 가 N회 호출**됨 + `cache.fallback{operation="get"}`/`{operation="put"}` 이 각각 N회 증가함을 확인한다. **재고 조회(`ProductQueryService` 의 상세 경로)는 캐시와 무관하게 매 요청 발생하므로 부하 증거에서 제외**한다(r2 #3). *Toxiproxy 는 L4 프록시라 Redis 명령을 해석하지 못해 SET 만 선택적으로 실패시킬 수 없다 — 그래서 '쓰기 전용 실패' 시나리오를 쓰지 않는다.*
- [x] **P7.** 운영·배포 계약을 `docs/runbooks/` 에 문서화한다 — (a) **복구 방침 = TTL 만료 대기**(허용 stale: 상세 ≤30m·목록 ≤10m), (b) 즉시 해소가 필요할 때의 수동 절차는 **`SCAN`(MATCH `cache:product::*` / `cache:products::*`, COUNT 배치) → `UNLINK` 배치 삭제**로 적는다. **`KEYS` 금지**(대형 keyspace 에서 Redis 를 블록), 삭제 전후 개수 대조, 부분 실패 시 재실행 규칙(멱등)을 명시(r2 #9), (c) 배포 순서 = **P4 타임아웃과 P1/P2 핸들러를 같은 애플리케이션 버전에 담아 pod 단위 원자 배포**. 근거는 "실패율 상승"이 아니라 **"타임아웃만 선배포하면 무응답 요청이 60s 정체 대신 ~500ms 만에 5xx 로 바뀐다 — 빨라질 뿐 여전히 실패다"**(r2 #8 정정), (d) 롤백 시 애플리케이션 롤백과 stale 키 삭제의 순서, (e) M4 로 alert 를 미루므로 **수동 감시 계약을 수치로 고정**한다 — `rate(cache_fallback_total{operation="get"}[5m]) > 0` 이 **10분 지속**되면 Redis 장애로 판정, `increase(cache_fallback_total{operation="evict"}[5m]) >= 1` 은 **stale 창이 열렸다는 뜻이므로 즉시 확인**(복구 후 TTL 만료 대기 여부 판단), 감시 주체·확인 주기를 명시(r3 #4), (f) **DB 마이그레이션 없음** 명시.
- [x] **P8.** 문서를 코드 사실로 정정한다 — `04 §9-13` 의 "가격/**재고** 조회"(재고는 예약 사가 ADR-0012 F2/D3 소관, Order 는 `price`/`version` 만 소비) 및 테이블명(`product_price_cache`), `05-data-design.md:290` 의 장바구니 조합 진술(미구현 → §6 M1 승격 명시), `04 §9-13` 에 Redis 장애 시 fallback 정책 1문단 추가. ADR-0012 정정은 **P11 이 Update Log 경로로 별도 커밋**한다(§2.1-a) — Layer 1 문서 정정과 섞지 않는다.
- [x] **P9.** `docs/TASKS.md` ⑤ 행 → ✅ + **범위 재확정 근거를 행에 기록**(④ 선례), `docs/progress/PHASE4.md` 에 **"ADR-0012 ⑤ 산출물 대비 실제 범위"** 절을 신설해 §2.1-a 를 남긴다, `phase4-prep-debt-roadmap.md` L-006 행 → ✅(PR 번호), `phase4-design-roadmap.md §51` 의 ⑤ 정의를 실제 범위로 정정.
- [x] **P10.** `.claude/scripts/lib/sync.sh` 의 **`:10` 선언과 `:11` 참조를 함께** 고친다 — `local path="docs/plans/${task_id}.md"` → `local plan_path=...`, `python3 - "$path"` → `python3 - "$plan_path"`. **선언만 바꾸면 bash 에서 빈 인자, zsh 에서 `$path` 특수배열이 전달돼 더 확실히 깨진다**(r3 #3) — zsh 에서 `path` 는 `PATH` 와 연동된 특수 배열이라 `PATH` 가 `docs/plans/...` 로 덮이고 다음 줄 `python3` 가 죽는다(§2.7 재현 확인). bash·zsh 양쪽에서 `hpx_plan_lint` 가 OK 를 내는지, **그리고 python3 에 전달된 인자가 정확히 대상 계획서 한 경로인지**를 회귀로 고정한다(`.claude/scripts/tests/bats/plan_audit_paths.bats` 인접). **이 항목은 본 PR 의 완료 게이트(V8)가 사용자의 기본 셸에서 실행 가능해지려면 선결이며, 1줄 수정이라 분리 커밋으로 남긴다.**

- [x] **P11.** `docs/adr/0012-phase4-db-event-saga-contract.md` 를 **Update Log 경로**로 정정한다(§2.1-a) — `:53` 의 `"Order product_cache/장바구니 조회 충족"` 을 실제 구현 상태(테이블 `product_price_cache`, Order 는 `price`/`version` 만 소비, 장바구니 조합은 미구현 → 별도 task)로 고치고 `:124-127` 의 `product_cache` 표기를 실제 테이블명으로 맞춘다. 말미에 `## Update Log` 절(변경 일자·커밋 해시·변경 사유)을 추가하고 — **해시는 자기 커밋을 참조할 수 없으므로 후속 커밋에서 채운다** — **커밋 메시지에 `fix(adr):` 접두사**를 쓴다 — README:11-13 이 요구하는 두 조건. **Status·ADR 개수는 변경하지 않는다**(결정 자체는 유효). 트레이드오프·Consequences 는 손대지 않는다(`:14` 우회 금지).

---

## 4. 영향 파일

| 경로 | 변경 | 비고 |
|---|---|---|
| `product-service/.../global/config/CacheConfig.java` | 수정 | `implements CachingConfigurer` + `errorHandler()` (P1) |
| `product-service/.../global/config/ResilientCacheErrorHandler.java` | 신설 | 콜백별 정책 + Counter (P2·P3) |
| `product-service/src/main/resources/application.yml` | 수정 | Redis 타임아웃 2키 (P4) |
| `product-service/build.gradle` | 수정 | `testImplementation 'org.testcontainers:toxiproxy'` (P5) |
| `product-service/src/test/.../ProductCacheFallbackIntegrationTest.java` | 신설 | Toxiproxy 장애 주입 (P5·P6) |
| `product-service/src/test/.../RedisPropertiesBindingTest.java` | 신설 | local/k8s 최종 바인딩 4값 (P4) |
| `.claude/scripts/lib/sync.sh` | 수정 | `:10` 선언 + `:11` 참조 동시 변경 `path`→`plan_path` (P10, 분리 커밋) |
| `.claude/scripts/tests/bats/*` | 수정 | zsh/bash lint 회귀 (P10) |
| `docs/runbooks/*` | 신설 | Redis 장애·복구·배포/롤백 (P7) |
| `docs/04-design-deep-dive.md` §9-13 · `docs/05-data-design.md` :290 | 수정 | 코드 사실 정정 (P8) |
| `docs/TASKS.md` · `PHASE4.md` · `phase4-prep-debt-roadmap.md` · `phase4-design-roadmap.md` | 수정 | 상태·이력·귀속 차이 (P9) |
| **DB 마이그레이션** | **없음** | 스키마 무변경 |
| `docs/adr/0012-phase4-db-event-saga-contract.md` | **수정 (Update Log)** | `:53`·`:124-127` 사실 정정 + `## Update Log` 절. `fix(adr):` 커밋 분리. Status 무변경 (P11) |
| 그 외 `docs/adr/*` | **무변경** | 결정 변경 없음 |

---

## 5. 검증 방법 (실패를 주입한 뒤 상태로 확인)

"빈이 존재한다"·"배선됐다"는 검증이 아니다. Toxiproxy 로 **연결 거부**와 **무응답**을 구분해 주입하고, **각 시나리오가 자기 시계열의 노출까지 스스로 확인**한다(테스트 순서 독립 — r2 #4).

| id | 주입 | 확인 대상 (상태) | 막는 부정형 |
|---|---|---|---|
| V0 | 없음 (하네스 자체 검증) | 애플리케이션의 Redis 연결 endpoint 가 **프록시 포트**임을 단언. 캐시 워밍 성공을 **Redis 값으로** 확인 | (V1~V5 의 false-green) |
| V1 | `Proxy#disable()` (연결 거부) | 상세·목록 조회가 예외 없이 **DB 값 반환** + `/actuator/prometheus` 에 `cache_fallback_total{operation="get"}` 노출 | #1, #3, #5 |
| V2 | 동일 | `GET /api/v1/products/{id}` **200** (5xx 아님) | #1 |
| V3 | **무응답** — downstream `timeout(_, 0)` toxic (연결 유지) | 상세·목록 HTTP 요청이 **1.5s 이내** 200 종료 + `operation="get"`·`"put"` 카운터가 **둘 다** 증가(두 타임아웃 경로 고정). `RedisProperties.timeout=500ms` 바인딩 단언은 **보조** | #2 |
| V4 | `disable()` 상태에서 상품 수정 | **DB 에 수정 커밋됨** + `cache_fallback_total{operation="evict"}` 노출·증가 + WARN 로그 | #4 |
| V5 | `disable()` 상태에서 동일 상품 N회 순차 조회 | `ProductRepository` 스파이의 `findById` **N회** + `operation="get"`/`"put"` 각 N회. 재고 조회는 집계 제외 | #3 |
| V6 | 없음 (회귀) | `ProductObservabilityMetricsIntegrationTest`(`cache_manager="cacheManager"`) + `ProductCacheIntegrationTest` **그대로 통과** | #6 |
| V7 | 없음 (프로파일 바인딩) | `local`·`k8s` 각각에서 `host`/`port`/`timeout`/`connectTimeout` 4값이 의도대로 바인딩 (ADR-0007 조건 1 실증) | — |
| V8 | 없음 (형식) | **bash 와 zsh 양쪽**에서 `hpx_plan_lint task-impl5-cqrs-cache-fallback` → OK, python3 인자가 대상 경로 1개 (P10 선결) | — |
| V9 | 없음 (문서 계약) | `hpx_plan_lint` 는 **제목 존재와 P 연속성만** 본다(`sync.sh:25-44`) — 내용은 통과시킨다. 따라서 grep 계약을 따로 건다: runbook 에 `SCAN`·`UNLINK`·`KEYS 금지`·감시 임계 수치·책임자, `TASKS.md` ⑤ 행 ✅ 와 L-006 ✅, `PHASE4.md` 의 `"ADR-0012 ⑤ 산출물 대비 실제 범위"` 제목, **ADR-0012 의 `## Update Log` 절 존재 + 커밋 메시지 `fix(adr):` 접두사** (r3 #4, P11) | #7 |

**false-green 차단**: 기본 `SimpleCacheErrorHandler` 는 예외를 **되던지므로**, 핸들러가 배선되지 않으면 V1·V2·V4 가 자동 실패한다 — 이 테스트들 자체가 §2.3-a 의 상시 CI 게이트다. 여기에 **1회성 변이 증적**을 더한다: `errorHandler()` 오버라이드를 맨 `@Bean CacheErrorHandler` 로 바꿨을 때 V1/V2/V4 가 FAILED 임을 확인하고 PR 본문·audit 에 기록한다(④ P17 `@Scheduled` 변이 검사 선례).

---

## 6. 미해결 (범위 밖 — 처분 명시)

| id | 항목 | 처분 |
|---|---|---|
| M1 | 장바구니 조회 시 상품명/상태 조합 (`05:290`, ADR-0012 §D2 소비처) | **기능 task 로 승격.** `product_price_cache` 에 `name`/`status` 컬럼 추가 + consumer 확장 + DTO/응답 확장이 필요하고, "캐시 미수신 상품의 표시 정책"이라는 별도 결정을 동반한다. 귀속 차이는 P9 로 기록 |
| M2 | gateway/user-service 의 Redis 의존 | **대상 아님** (§2.2). fail-closed 가 ADR-0013 D3 의 결정 |
| M3 | Redis HA(Sentinel/Cluster) | Phase 4 범위 밖. fail-open 은 가용성 보강이지 HA 대체가 아니다 |
| M4 | `cache_fallback_total` 기반 alert 발화 | ADR-0015 가 정적 lint 범위를 규정 — alert 발화 검증은 ④ 미충족 #3 과 동일 게이트 |
| M5 | Order 로컬 캐시(`product_price_cache`) 의 fallback | **불필요.** JPA/MySQL 기반이라 Redis 의존이 없다 |
| M6 | evict 실패 시 자동 재시도/보류 큐 | 과설계. 복구 방침은 TTL 만료 대기로 확정(P7), 필요해지면 M4 alert 가 근거를 준다 |
| M7 | **cache stampede · DB 커넥션 풀 포화 완화** (bulkhead / `@Cacheable(sync=true)` / rate limit) | Redis 장애 하 **동시** 요청이 전부 DB 로 몰리는 표면. 본 PR 은 §2.4·P7 로 인지·감시까지만 하고 완화 기구는 승격한다 — 허용 동시성·SLO 를 먼저 정해야 설계가 결정되기 때문 (r2 #10) |

---

## 7. 기각한 대안

| 대안 | 기각 사유 |
|---|---|
| evict 실패 시 트랜잭션 롤백 | Redis 장애가 **상품 수정 API 전체를 마비**시킨다. L-006 의 취지(Redis 를 가용성 단일점에서 제외)와 정반대. stale 창은 TTL 로 이미 상한(30m/10m) |
| `@Bean CacheErrorHandler` 만 등록 | **동작하지 않는다** (§2.3-a). 조용한 false-green 이므로 §5 변이 증적으로 방어 |
| `CachingConfigurer#cacheManager()` 까지 오버라이드 | 불필요한 명시 배선 + `@ConditionalOnProperty` 2빈 구조에서 CacheManager 모호성 유발 (§2.3-c 정정 후 근거) |
| `redis.stop()` 만으로 검증 | 연결 거부만 재현하고 **무응답을 재현하지 못한다** → 부정형 #2 가 미검증으로 남는다 (§2.5) |
| `CompositeCacheManager` + `NoOpCacheManager` 폴백 | 폴백 전환 시점 판정을 직접 구현해야 한다. Spring 이 제공하는 `CacheErrorHandler` 훅으로 충분 |
| `redis.stop()` 기반 장애 주입 (Toxiproxy 미도입) | 연결 거부만 재현. 무응답(부정형 #2)이 미검증으로 남고, 컨테이너 정지는 테스트 순서 의존을 만든다 (r2 #1·#3) |
| read-only Redis ACL 로 '쓰기 전용 실패' 재현 | 별도 fixture·ACL 관리 비용 대비 얻는 것이 적다. 전면 장애 하 N회 순차 조회로 put 실패의 DB 전이가 이미 실증된다 (P6) |
| `spring.data.redis` 타임아웃을 Java Config 로 이관 | ADR-0007 조건 1 해당 여부가 **추정**이다. 2키 YAML + 바인딩 회귀 테스트(V7)로 실증하는 편이 코드량이 적고 결론이 명확하다 |
| 공통 모듈(`common`)에 핸들러 배치 | Spring Cache 사용처가 product-service 하나뿐 (§2.2). 단일 사용처 추상화는 CLAUDE.md §2 위반 |
| ADR 신설로 ⑤ 범위 재귀속 | 결정(이벤트로 동기 호출 대체·7필드)은 **바뀌지 않았다**. 틀린 것은 테이블명·소비처 구현 상태라는 **사실 진술**이므로 README:11-13 의 Update Log 가 정확한 경로다 (§2.1-a) |
| ADR 무변경 + progress 기록만 (초안 판정) | **철회.** `:53` 은 소비처를 명시한 문장이며 "필드 계약일 뿐"은 결론에 맞춘 재해석이었다 (r3 #1 수용) |

---

## 8. 완료 조건

1. 애플리케이션 트래픽이 **Toxiproxy 를 실제로 경유**함이 단언됐다 (V0 — 이게 없으면 나머지가 무의미)
2. Redis 를 **차단한 상태**에서 상품 상세·목록 조회가 200 으로 DB 값을 반환한다 (V1, V2)
3. Redis **무응답** 시 조회가 **1.5s 이내** 200 으로 종료하고 get·put 카운터가 둘 다 증가한다 (V3)
4. 차단 상태에서 상품 수정이 DB 커밋되고 `cache_fallback_total{operation="evict"}` 가 증가한다 (V4)
5. put 실패 시 `findById` 가 N회 발생함이 스파이로 실증되고 트레이드오프가 문서화됐다 (V5, §2.4)
6. 맨 `@Bean` 변이 시 V1/V2/V4 가 실패함이 확인·기록됐다 (§5)
7. 기존 S7 계약 테스트와 `ProductCacheIntegrationTest` 가 그대로 통과한다 (V6)
8. `local`·`k8s` 프로파일의 Redis 바인딩 4값이 고정됐다 (V7)
9. `./gradlew build` 전 모듈 그린 · `observability-ssot-lint.sh` 통과 · **bash·zsh 양쪽에서** `hpx_plan_lint` OK (V8, P10)
10. Redis 복구 절차(SCAN/UNLINK·`KEYS` 금지)·배포/롤백 순서·**수치로 고정된 감시 임계**가 runbook 에 있고 **DB 마이그레이션 없음**이 명시됐다 — grep 계약으로 판정 (V9, P7)
11. TASKS ⑤ ✅ + 범위 재확정 근거 기록, **PHASE4 에 "ADR-0012 ⑤ 산출물 대비 실제 범위" 절 신설**, L-006 ✅ — grep 계약으로 판정 (V9, P9)
12. ADR-0012 가 Update Log 경로로 정정됐다 — 본문 사실 정정 + `## Update Log` 절 + `fix(adr):` 분리 커밋, **Status 무변경** (V9, P11)
13. **〔기계 판정 제외 — 명시적 리뷰 체크〕** `04 §9-13` · `05-data-design.md:290` 의 진술이 코드 사실과 **의미상** 일치한다 (P8). 자동 lint 로는 판정 불가하므로 PR 리뷰에서 사람이 확인한다 (r3 #4)
