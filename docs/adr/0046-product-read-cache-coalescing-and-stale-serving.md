# ADR-0046: 상품 조회 캐시의 로딩 묶기·재고 만료 전 갱신·장애 시 마지막 값 응답 (DB 진입 상한 1 유지)

- **Status**: Accepted
- **Date**: 2026-10-25
- **Deciders**: 프로젝트 오너 (2026-10-25, D-078 재측정 S2 의 "상한 4 증액 근거 있음" 처분)
- **관련 Phase**: Phase 5

## Context

### 상한 4 판정과 그 한계

D-078 재측정 S2(2026-10-25, `docs/progress/evidence/d078-gke-20261025.md`)는 ADR-0037 의 조회 DB 진입 상한을 1 과 4 로
비교했다. MySQL 2000m, VU 400, 60초에서 200 응답 rps 중앙값이 detail OFF 26.5 에서 115.9, list OFF 30.3 에서 123.7 로
올랐다. 503 이외 오류, Hikari 대기, MySQL 스로틀 차분은 모두 0 이었다. 계획서의 사전 규칙 네 조건이 성립해 "상한 4 증액 근거
있음"으로 적었다.

이 판정은 주문 경로가 없는 읽기 전용 스택에서 본 풀 여유다. ADR-0037 Consequences 는 상한을 높이려면 "동시성 실험과 다른
DB 사용자 여유를 재검증한다"고 적었고, S2 는 앞의 것에만 답했다. 상한 4 에서도 MySQL CPU 는 0.39코어 이하(한도 2)였다.
miss 경로의 처리량을 묶는 것은 MySQL 이 아니라 상한이다.

### 상한이 결과를 바꾸는 두 경우

1. Redis 장애나 콜드 캐시. 모든 조회가 miss 경로로 가므로 상한이 곧 처리 한도다(S2 의 OFF 셀).
2. 캐시가 정상인 고부하. S2 detail ON(상한 1)에서 `productStock`(TTL 5초)이 만료되는 순간 miss 가 겹쳐 503 이 4.8~6.1%
   섞였다. run 시작에 100키를 한꺼번에 채워 5초마다 함께 만료된 측정 조건 탓에 부풀었을 수 있다. 상한 4 의 ON 셀은 없었다.

### 원인 1: miss 가 트래픽에 비례해 DB 로 간다

- 같은 키의 동시 miss 를 묶는 장치가 없다. `CacheConfig` 는 `RedisCacheManager.builder(connectionFactory)` 를 쓴다. 이
  프로젝트가 쓰는 spring-data-redis 3.5.10 에서 이 builder 는 non-locking writer 를 고르고, `DefaultRedisCacheWriter` 와
  `RedisCache` 에는 JVM 안의 락이 없다(jar 바이트코드로 확인). 그래서 `@Cacheable(sync = true)` 를 달아도 동시 호출이
  각자 로더를 실행한다. locking writer 로 바꾸면 락 키가 키가 아니라 캐시 이름 단위(`{name}~lock`)라 모든 키가 한 줄로
  서고, Redis 가 죽으면 락도 쓸 수 없다. ADR-0037 Alternatives 의 "sync=true 는 같은 cache key 에 한정된다"는 전제는 이
  스택에서 성립하지 않는다.
- Redis 장애 중에는 get 과 put 이 모두 실패해 아무것도 채워지지 않는다(`ResilientCacheErrorHandler`). 같은 키 조회가 매번
  DB 로 간다.

### 원인 2: 조회와 쓰기가 한 풀을 나눠 쓴다

product-service 의 Hikari 풀은 기본 10개다. cache miss 조회 말고도 `@KafkaListener` 10개(각 동시성 1), 스케줄러 풀 4개, HTTP
쓰기가 같은 풀을 쓴다. 상한을 올리면 예약 리스너의 몫이 준다. 최악을 정적으로 더하면 상한 1 에서도 남는 9개를 넘는다.
ADR-0037 도 "9개를 보장하는 정책이 아니다"라고 적었고, 실제 겹침은 측정으로만 안다.

### 오래된 값을 내도 되는 경계

- 상세의 재고는 표시용 힌트이고 stale 상한은 5초다(ADR-0026 D1·D2).
- 주문 단가는 order 의 로컬 read model 이 정한다(ADR-0036). 상품 조회 API 가 오래된 값을 내도 주문 정확성은 바뀌지 않는다.
- L-006 정책은 Redis 장애 뒤 evict 가 실패하면 TTL(상세 30분, 목록 10분)까지 stale 가격·상태가 나가는 것을 이미 받아들였다
  (`ResilientCacheErrorHandler` javadoc).

### 외부 사례

같은 문제에 쓰이는 처방은 네 가지의 조합이다. 미캐시 자원의 요청 묶기, soft/hard TTL 로 만료 전에 갱신하며 기존 값 내주기,
외부 캐시 장애 때 메모리 사본으로 응답하기, 하위 서비스를 지키는 load shedding 이다(AWS Builders' Library, Facebook NSDI'13
의 lease 와 Gutter, RFC 5861, Vattani 외 VLDB 2015). PeekCart 에는 마지막 것(ADR-0037 의 503)만 있다. 출처와 각 자료에서
가져온 내용은 References 에 적는다.

## Decision

조회 DB 진입 상한은 1 로 두고, 캐시 계층에서 miss 를 묶고 정해진 범위 안에서 오래된 값을 내준다.

### D1 — 상한 1 유지

ADR-0037 Decision 4 의 상한 1 을 유지한다. 위 S2 판정과 그 한계는 이 ADR 이 기록한다. 증액은 재검토 조건 1 에서 다시 다룬다.

### D2 — 같은 키의 로딩을 Pod 안에서 묶는다

한 Pod 에서 같은 키가 miss 되면 DB 조회는 한 번이고, 나머지 요청은 그 결과를 기다린다. Redis writer 의 locking 에 기대지
않는다.

### D3 — 재고는 만료 전에 갱신한다 (stale-while-revalidate)

`productStock` 은 soft 시점이 지나면 한 요청만 갱신하고 나머지는 기존 값을 받는다. hard 상한은 ADR-0026 D2 의 5초 그대로이고,
soft 값은 그보다 작게 계획서에서 정한다. 갱신이 상한에 거절되면 hard 상한까지 기존 값을 준다.

### D4 — Redis 장애 때는 Pod 에 둔 마지막 값으로 응답한다 (stale-if-error)

정상 조회(Redis hit 또는 DB 적재)마다 Pod 메모리에 마지막 값을 크기 상한 안에서 둔다. Redis 오류 때 그 값이 허용 범위 안이면
그 값을 준다. 허용 범위는 상품 정보 30분, 목록 10분, 재고 5초다. 앞의 둘은 지금 Redis TTL 과 같아 L-006 이 받아들인 범위이고,
재고는 장애 중에도 ADR-0026 D2 를 지킨다. 범위를 넘었거나 값이 없으면 D2 로 묶은 DB 조회가 상한을 지난다.

### D5 — 평상시 정보·목록의 신선도는 바꾸지 않는다

Redis 가 정상이면 정보와 목록은 지금처럼 Redis 를 읽고 수정 때 evict 한다. Pod 메모리는 D2 의 진행 중 로딩, D3 의 재고 갱신,
D4 의 장애 응답에만 쓴다. 평상시 정보·목록에 Pod 사이의 stale 이 새로 생기지 않는다.

### D6 — 구현 위치

Pod 메모리 저장소는 Caffeine(Spring Boot BOM 관리)이다. 묶기·갱신·장애 응답은 Redis 캐시를 감싸는 캐시 계층에서 한다. 상한
advice(order -100)와 트랜잭션 순서는 바꾸지 않으므로 DB 로더 경로는 계속 상한을 지난다. 클래스 구조, 파라미터, 지표 이름은
계획서에서 정한다.

## Alternatives Considered

### Alternative A: 상한 4 로 증액
- **장점**: 설정 한 줄이다. S2 에서 miss 경로 처리량이 약 4배였다.
- **단점**: ADR-0037 의 "다른 DB 사용자 여유" 조건이 미검증이다. 원인 1 이 그대로라 트래픽이 늘면 다시 거절된다.
- **기각 사유**: 증액은 거절과 풀 압박 사이의 위치만 옮긴다.

### Alternative B: 조회 전용 커넥션 풀 분리
- **장점**: 조회가 몰려도 쓰기·소비 경로의 커넥션을 가져가지 못한다(Microsoft Bulkhead 패턴의 자원 분리). 상한을
  풀 공유가 아니라 MySQL 여유를 기준으로 정할 수 있다.
- **단점**: DataSource 2개와 readOnly 라우팅이 필요하다. Hikari 지표가 두 풀로 나뉘어 관측 계약(ADR-0009·0015)이 바뀐다.
  MySQL 의 CPU·디스크는 여전히 공유한다. 원인 1 은 그대로다.
- **기각 사유**: 보류한다. D2~D5 뒤에도 조회 동시성이 모자라다고 측정되면 꺼낸다(재검토 조건 1).

### Alternative C: read replica
- **장점**: DB 수준까지 조회와 쓰기를 떼어낸다.
- **단점**: 인프라와 과금이 늘고, 복제 지연이 stale 에 더해진다.
- **기각 사유**: 보류한다. Phase 5 는 수요 기반이다.

### Alternative D: `@Cacheable(sync = true)` 만 추가
- **장점**: 애너테이션 한 줄이다.
- **단점**: 기본 non-locking writer 에서는 묶이지 않는다. locking writer 는 캐시 이름 단위 Redis 락이라 모든 키가 직렬화되고,
  Redis 장애 때 쓸 수 없다.
- **기각 사유**: 원인 1 을 풀지 못한다.

### Alternative E: 평상시 경로의 2단 캐시(Pod 캐시, Redis, DB 순)와 pub/sub 무효화
- **장점**: Redis 왕복이 준다. Caffeine 과 Redis 를 겹친 다층 캐시는 업계에서 흔하다(DoorDash 사례).
- **단점**: 수정이 다른 Pod 에 늦게 반영된다. Redis pub/sub 은 최대 1회 전달이라 유실된 무효화는 TTL 까지 남는다. 움직이는
  부품이 는다.
- **기각 사유**: 평상시 stale 을 새로 만든다(D5).

### Alternative F: Redis client-side caching(tracking)
- **장점**: 서버가 무효화를 보내 Pod 캐시의 일관성을 지킨다.
- **단점**: 연결이 하나라도 끊기면 클라이언트가 로컬 캐시를 전부 비운다(Redis 문서).
- **기각 사유**: Redis 장애 대비가 되지 않는다.

### Alternative G: 확률적 조기 재계산(XFetch, PER)
- **장점**: 공유 캐시에서 여러 노드의 동시 재계산을 락 없이 흩는다(Vattani 외, VLDB 2015. NHN 도 RedisConf 2020 에서
  소개했다).
- **단점**: 확률적이라 동시 재계산이 완전히 없어지지는 않는다.
- **기각 사유**: Pod 수가 적고 D2 가 Pod 안에서 묶으므로 지금은 필요 없다(재검토 조건 2).

### Alternative H: 재고 변경 이벤트 전파(ADR-0026 Alternative C)
- **장점**: 재고 캐시가 쓰기 직후 갱신된다.
- **단점**: 지금 병목인 커밋·발행 축(D-108 ①)에 쓰기를 더 얹는다.
- **기각 사유**: ADR-0026 의 기각 사유가 그대로다.

### Alternative I: 재고 TTL 편차 또는 상한의 짧은 대기
- **장점**: 동시 만료를 흩거나 짧은 몰림을 흡수한다.
- **단점**: 부분 처방이다. 짧은 대기는 ADR-0037 의 "대기열 없음"을 바꾼다.
- **기각 사유**: D3·D4 가 거절이 사용자에게 드러나는 경우를 없애므로 따로 두지 않는다.

## Consequences

### 긍정적 영향
- Redis 장애 중 DB 조회 수가 트래픽이 아니라 "인기 키 수 × Pod 수 ÷ 허용 범위"로 묶인다. 상품 100개, 재고 허용 범위 5초면
  Pod 당 재고 DB 조회는 초당 최대 약 20회다.
- 재고 만료 순간의 거절이 hard 상한 안에서는 503 으로 드러나지 않는다.
- 상한 1 은 평상시 거의 걸리지 않는 최후 방어선으로 남는다(AWS 가 말하는 load shedding).

### 부정적 영향 / 트레이드오프
- Redis 장애 중 정보와 목록은 최대 30분, 10분 전 값일 수 있다. 판매중단이나 가격 변경의 표시가 늦어진다. 주문 정확성은
  ADR-0036 의 read model 과 사가가 지킨다.
- 장애 중 한 Pod 에서 수정해 evict 해도 다른 Pod 의 사본은 허용 범위까지 남는다.
- Pod 메모리 사용이 늘고(크기 상한으로 제한), Caffeine 의존과 캐시 계층의 직접 구현 코드가 생긴다.
- 관측 계약에 지표가 는다(묶인 로딩, 장애 시 stale 응답, 재고 갱신 거절). `docs/runbooks/redis-cache-fallback.md` 의 장애 동작
  서술이 바뀐다.

### 후속 결정에 미치는 영향
- 검증은 두 단계다. 로컬(Testcontainers)에서 같은 키 동시 요청 N 개에 DB 조회 1회, 재고 갱신이 상한에 거절돼도 hard 상한 안에서
  503 0, Redis 중단 시 허용 범위 안의 응답과 DB 조회 수 상한, 범위를 넘으면 상한 경로를 확인한다. GKE 에서는 다음 과금 세션
  (D-108 과 같은 세션)에 캐시 ON 고부하의 503 비율(같은 워밍업 조건)과 Redis 중단 시 DB 조회 수·503 비율을 잰다.
- 구현 뒤 `docs/04-design-deep-dive.md` 의 캐시 절은 이 ADR 을 참조한다.

## 재검토 조건

1. D2~D5 뒤에도 콜드 스타트나 Redis 장애에서 조회 거절이 의미 있게 남으면 Alternative B 를 검토하고, 그때 상한 증액을 다시
   판단한다.
2. product replica 가 늘어 같은 키의 Pod 간 동시 재적재가 DB 부하로 보이면 Alternative G 를 검토한다.
3. Redis 장애 중 stale 표시가 사용자 문제로 보고되면 D4 의 허용 범위를 줄인다.

## References

### 내부

- `product-service/src/main/java/com/peekcart/global/config/CacheConfig.java`·`ResilientCacheErrorHandler.java`·`ProductReadBulkheadConfig.java`
- `product-service/src/main/java/com/peekcart/product/application/ProductCacheService.java`
- `docs/progress/evidence/d078-gke-20261025.md` §S2·§읽는 법 ①, `docs/plans/D-078-remeasure-plan.md` §2.2 ①
- ADR-0007, ADR-0009·ADR-0015, ADR-0026 D1·D2·D3, ADR-0036, ADR-0037
- spring-data-redis 3.5.10(Spring Boot 3.5.12 BOM): `RedisCacheManager.RedisCacheManagerBuilder.fromConnectionFactory` 가
  `nonLockingRedisCacheWriter` 를 쓰고, `DefaultRedisCacheWriter`·`RedisCache` 에 JVM 락(`monitorenter`,
  `java.util.concurrent.locks`)이 없음을 jar 바이트코드(`javap`)로 확인했다(2026-10-25)

### 외부: 본문을 확인하고 근거로 쓴 자료 (2026-10-25 조회)

| 자료 | 이 ADR 에서 쓴 내용 |
|---|---|
| AWS Builders' Library, [Caching challenges and strategies](https://aws.amazon.com/builders-library/caching-challenges-and-strategies/) (2019, 현재 [builder.aws.com](https://builder.aws.com/content/3K9TeXp6strMiJ4vhakpRD7BQWO/caching-challenges-and-strategies) 으로 이동) | 미캐시 자원은 대기 중 요청이 하나만 나가게 묶는다. soft TTL 뒤 하위 서비스가 실패하면 hard TTL 까지 기존 값을 쓴다. 외부 캐시의 긴 장애는 하위 서비스에 비정상 급증을 만들므로 메모리 fallback, load shedding, 요청 상한으로 막고 캐시를 끈 상태로 시험한다. 서비스가 캐시에 "중독"되는 것을 경계한다 |
| Nishtala 외, [Scaling Memcache at Facebook](https://www.usenix.org/system/files/conference/nsdi13/nsdi13-final170_update.pdf) (NSDI '13) | §3.2.1 lease: 키당 10초에 토큰 하나로 피크 DB 질의가 17K/s 에서 1.3K/s 로 줄었다. stale 값으로 진행할 수 있는 응용은 DB 의 최신 값을 기다리지 않는다. §3.3 Gutter: 장애 시 실패의 일부를 hit 로 바꿔 백엔드를 지키고, 대가로 약간의 stale 을 낸다(사용자에게 보이는 실패 99% 감소) |
| [RFC 5861](https://www.rfc-editor.org/rfc/rfc5861), HTTP Cache-Control Extensions for Stale Content (2010) | stale-while-revalidate 는 지연을 숨기고, stale-if-error 는 오류 때 stale 응답으로 가용성을 높인다. D3·D4 의 용어 |
| Vattani, Chierichetti, Lowenstein, [Optimal Probabilistic Cache Stampede Prevention](https://www.vldb.org/pvldb/vol8/p886-vattani.pdf) (PVLDB 8(8), 2015) | stampede 크기는 요청률과 재계산 시간의 곱에 비례한다. 락 방식은 락을 못 얻은 요청이 기다리거나 재생성 동안 stale 값을 쓴다(D2·D3). 확률적 조기 만료(Alternative G) |
| Caffeine wiki, [Refresh](https://github.com/ben-manes/caffeine/wiki/Refresh) | 갱신 중에도 기존 값을 돌려주고, 갱신이 실패하면 기존 값을 유지한다. 갱신은 조회 때 시작되며 expireAfterWrite 와 함께 지정할 수 있다 |
| Spring Framework Reference, [Declarative Annotation-based Caching](https://docs.spring.io/spring-framework/reference/integration/cache/annotations.html) (Synchronized Caching) | `sync` 는 제공자가 지원하지 않을 수 있는 선택 기능이다 |
| Spring Data Redis 3.5.x 소스, [`RedisCacheManager`](https://github.com/spring-projects/spring-data-redis/blob/3.5.x/src/main/java/org/springframework/data/redis/cache/RedisCacheManager.java)·[`DefaultRedisCacheWriter`](https://github.com/spring-projects/spring-data-redis/blob/3.5.x/src/main/java/org/springframework/data/redis/cache/DefaultRedisCacheWriter.java) | builder 기본값은 non-locking writer 다. locking writer 의 락 키는 `{name}~lock` 이다 |
| Microsoft Azure Architecture Center, [Bulkhead pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/bulkhead) | 커넥션 풀 같은 자원을 나눠 한쪽의 고갈이 다른 쪽으로 번지지 않게 한다. 자원 효율이 떨어지는 것이 대가다(Alternative B) |
| Redis docs, [Client-side caching introduction](https://redis.io/docs/develop/clients/client-side-caching/) | 연결이 하나라도 끊기면 클라이언트가 로컬 캐시를 전부 비운다(Alternative F) |
| GeekNews, [NHN RedisConf 2020 정리 소개](https://news.hada.io/topic?id=2777) | 원문은 NHN Cloud Meetup [캐시 성능 향상기 (Improving Cache Speed at Scale)](https://meetup.nhncloud.com/posts/251). 만료 순간 읽기가 DB 로 몰리고 Redis 에 중복 쓰기가 몰리는 stampede, 확률적 조기 갱신과 디바운스, hot key 분산. 원문 본문은 스크립트로 그려져 소개글의 인용으로만 확인했다 |

### 외부: 요약만 확인한 자료 (근거로 쓰지 않음)

| 자료 | 메모 |
|---|---|
| Instagram Engineering, [Thundering Herds & Promises](https://instagram-engineering.com/thundering-herds-promises-82191c8af57d) (2019) | 값 대신 진행 중인 로딩(promise)을 캐시해 동시 요청을 묶었다는 2차 요약만 봤다 |
| InfoQ, [DoorDash multilayered cache](https://www.infoq.com/news/2023/10/doordash-multilayered-cache/) (2023) | 팀마다 다르던 Caffeine·Redis 캐시를 다층 공용 라이브러리로 표준화했다는 요약(Alternative E) |
| Google SRE Book, [Ch.22 Addressing Cascading Failures](https://sre.google/sre-book/addressing-cascading-failures/) | 과부하가 연쇄 장애의 가장 흔한 원인이고 자원 고갈이 health check 실패로 번진다는 요약. D-108 ③ 프로브 논의에도 참고 |
| Redis docs, [Pub/Sub](https://redis.io/docs/latest/develop/use-cases/pub-sub/) | 최대 1회 전달이라 구독자가 없을 때 메시지가 사라진다는 요약(Alternative E) |
| Baeldung, [Introduction to Caffeine](https://www.baeldung.com/java-caching-caffeine) | 만료는 새 값을 기다리고 refresh 는 기존 값을 주며 비동기로 다시 읽는다는 튜토리얼 설명 |
| Antirez Redis 문서, [Cache Stampede Prevention](https://redis.antirez.com/fundamental/cache-stampede-prevention.html) | XFetch 계열 설명이 있는 페이지로 검색에서만 확인 |
| velog, [Spring 캐시스탬피드 대응 글](https://velog.io/@sung_c/Spring-캐시스탬피드-어떻게-대응할까) | Spring 에서 PER 을 구현한 개인 블로그 요약 |
| Medium, [Redis local cache implementation and best practices](https://medium.com/@max980203/redis-local-cache-implementation-and-best-practices-f63ddee2654a) | L1 로컬 캐시와 L2 Redis, pub/sub 무효화 구성을 다룬 개인 글 요약 |
