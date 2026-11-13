# Runbook — Redis 캐시 장애 (fail-open) 대응

> 대상: product-service 의 상품 조회 캐시가 Redis 장애로 fallback 을 타는 상황
> 구현: 구현 ⑤ / L-006 (계획 `docs/plans/done/task-impl5-cqrs-cache-fallback.md`)
> 관련: `ResilientCacheErrorHandler` · `ResilientRedisCache` · `CacheConfig` · ADR-0009 S7 · ADR-0013 D3(gateway 대조) ·
> ADR-0046(로딩 묶기·재고 만료 전 갱신·장애 시 Pod 사본 응답, D-109)

---

## 0. 30초 요약

| 상황 | 할 일 |
|---|---|
| `cache_fallback_total{operation="get"}` 이 계속 증가한다 | Redis 장애다. §2 로 확인 → §3. **조회는 정상 응답 중이다**(Pod 사본 또는 DB, §1.1) |
| `cache_stale_served_total` 이 증가한다 | Redis 장애 중 Pod 사본으로 응답하고 있다. 정보 ≤30분·목록 ≤10분 전 값일 수 있다. §1.1 → §3 |
| `cache_fallback_total{operation="evict"}` 이 증가했다 | **stale 창이 열렸다.** §4 로 즉시 판단 |
| 상품 조회가 느려졌다 | §5 — 캐시 유실이 DB 로 전이된 상태 |
| Redis 를 복구했다 | §4 — 기본 방침은 **TTL 만료 대기**. 즉시 해소가 필요하면 §4.2 |
| 상품 조회가 **5xx** 다 | 캐시 문제가 **아니다**. fail-open 이므로 Redis 장애로는 5xx 가 나지 않는다 |

---

## 1. 이 fallback 이 무엇인가

Redis 는 5개 서비스가 공유하는 인프라다. Spring 기본 `SimpleCacheErrorHandler` 는 캐시 예외를 그대로
되던지므로, Redis 가 죽으면 **DB 는 멀쩡한데도** 상품 조회 API 가 통째로 5xx 로 떨어진다.

`ResilientCacheErrorHandler` 는 이를 fail-open 으로 바꾼다 — 캐시 조회 실패를 **미스로 취급**해
DB 로 흘린다. 캐시는 가속 장치이지 가용성 단일점이 아니라는 판단이다.

### 1.1 장애 중 응답은 어디서 오나 (ADR-0046, D-109)

캐시는 `ResilientRedisCache` 로 감싸져 있다. Redis 오류 때 요청마다 순서는 다음과 같다.

1. Redis 를 읽다가 실패한다. 무응답이면 명령 타임아웃(500ms)을 한 번 기다린다.
2. 이 Pod 에 마지막 값이 있고 허용 범위(정보 30분, 목록 10분, 재고 5초) 안이면 그 값을 낸다.
   `cache_stale_served_total` 이 오른다.
3. 없거나 범위를 넘었으면 DB 에서 읽는다. 같은 키의 동시 요청은 한 로딩으로 묶이고, 로딩은 DB 진입 상한(ADR-0037)을 지난다.
   읽은 값은 Redis 적재가 실패해도 Pod 사본으로 남아 다음 요청에 쓰인다.

그래서 장애 중 DB 조회는 트래픽에 비례하지 않는다. "키 수 × Pod 수 ÷ 허용 범위"로 묶인다. Pod 사본은 Redis 가 정상이면
응답에 쓰지 않는다. 장애 중에 다른 Pod 에서 상품을 수정하면 이 Pod 의 사본은 허용 범위까지 옛 값을 낸다.

> **gateway 는 정반대다.** `FailClosedRedisRateLimiter` 는 Redis 장애 시 **거부**한다(ADR-0013 D3).
> rate limit 은 *보호* 장치라 유실되면 남용이 무제한 통과하지만, 조회 캐시는 *가속* 장치라
> 유실돼도 DB 라는 정답이 남는다. 같은 Redis 를 두 정책으로 다루는 이유가 이 차이다.

---

## 2. 지금 무슨 일이 벌어지는지 확인

```promql
# 어떤 연산이 실패하고 있나 (operation = get | put | evict | clear)
sum by (cache, operation) (rate(cache_fallback_total[5m]))

# 캐시가 실제로 안 먹고 있나 (S7 — ADR-0009)
sum by (cache, result) (rate(cache_gets_total[5m]))
```

```promql
# 장애 중 Pod 사본 응답 · 같은 키 로딩 묶기 · 재고 갱신 거절 (ADR-0046)
sum by (cache) (rate(cache_stale_served_total[5m]))
sum by (cache) (rate(cache_load_coalesced_total[5m]))
sum by (cache) (rate(cache_refresh_rejected_total[5m]))
```

`cache_refresh_rejected_total` 은 재고 soft 갱신(3초)이 DB 진입 상한에 거절돼 기존 값(hard 5초 안)을 낸 횟수다. 503 이 아니다.
이 값이 계속 오르면 상한 1 이 평상시에도 걸린다는 뜻이다(ADR-0046 재검토 조건 1).

`cache_gets_total` 의 hit 이 0 으로 떨어지고 `cache_fallback_total{operation="get"}` 이 오르면
캐시가 통째로 우회되는 중이다.

**캐시는 셋이다** — `product`(상세 정보, TTL 30분) · `products`(목록, TTL 10분) ·
`productStock`(재고, TTL 5초, ADR-0026). 위 PromQL 은 `cache` 라벨로 갈라지므로 셋이 따로 보인다.
`productStock` 은 TTL 이 짧아 **정상 상태에서도 miss 비율이 다른 둘보다 구조적으로 높다** —
그 자체는 장애 신호가 아니다. 이 캐시에서 볼 것은 miss 비율이 아니라 **hit 이 0 인지**다
(0 이면 TTL 이 제 역할을 못 하는 것이고, ADR-0026 D4 의 승격 조건이 그것을 본다).

**상세 조회는 캐시를 둘 탄다.** 무응답 Redis 구간에서 상세의 타임아웃 예산은 목록의 2배
(4×500ms = 2s)다 — 상세만 유독 느리다는 신고는 이 구조 때문일 수 있다(ADR-0026 Consequences).

### 유한 조회 상한의 실패 해석

`ProductCacheFallbackIntegrationTest` V3의 3500ms bound는 전체 상세 조회의 wall-clock 상한이다.
실패가 timeout 설정 변경만을 뜻하지 않는다. 테스트가 함께 남기는 `elapsed`, Lettuce client의
`commandTimeout`, `product`/`productStock` get·put fallback 증가분을 먼저 확인한다.

1. `commandTimeout`을 base의 500ms 및 실제 배포 설정과 대조한다. 설정값은 client 구성의 관측이며 각 연산이 그 시간을 전부 소비했다는 증거는 아니다.
2. 네 경로의 fallback 증가분을 확인한다. 이는 경로별 실패 처리 횟수이며 예외 원인이나 지연 기여 시간을 분해하지 않는다.
3. timeout이 그대로여도 DB 조회 지연·JVM 정지·테스트 runner 경합·기타 측정 구간 지연으로 같은 상한이 깨질 수 있다. 관련 로그/메트릭을 함께 본다.

인위 지연 probe와 timeout 변경 probe는 원인이 다른 두 실패가 같은 bound를 깨뜨릴 수 있음을 확인한다.
실제 부하 전체를 재현한 결과나 원인을 자동 확정하는 진단으로 해석하지 않는다. 실패를 없애려고 bound만 늘리지 않는다.

### 2.1 자동 alert와 보조 수동 감시

`k8s/monitoring/shared/grafana-alerts.yml`의 `peekcart-cache-fallback-get`은 get rate가
10분 지속되면, `peekcart-cache-fallback-evict`는 5분 increase가 1 이상이면 추가 대기 없이
발화합니다. "즉시"는 다음 1분 평가 주기에서 pending 없이 판정한다는 뜻입니다.
두 규칙은 product-service만 평가하고 `application, cache`별로 집계합니다.
`signal=cache-fallback-get`/`cache-fallback-evict`와 operation 라벨로 경로를 구분합니다.
실제 외부 채널 전달은 기존 contact point/notification policy 운영 범위이며 여기서 보장하지 않습니다.
put은 아래 수동 감시를 유지합니다. 실제 series 기반 발화/해소 검증은 D-063에서 추적합니다.

| 신호 | 임계 | 판정 | 조치 |
|---|---|---|---|
| `rate(cache_fallback_total{operation="get"}[5m]) > 0` | **10분 지속** | Redis 장애 | §3 |
| `increase(cache_fallback_total{operation="evict"}[5m]) >= 1` | **1회라도** | stale 창 열림 | §4 — 즉시 확인 |
| `rate(cache_fallback_total{operation="put"}[5m]) > 0` | 10분 지속 | DB 부하 전이 중 | §5 |

- **감시 주체**: product-service 담당자
- **확인 주기**: 배포 직후 30분, 이후 일 1회 대시보드 확인

---

## 3. Redis 자체 복구

1. Redis 파드/컨테이너 상태 확인 → 재기동
2. 네트워크 경로 확인 — **연결 거부**(포트 닫힘)와 **무응답**(연결은 되는데 응답 없음)은 다른 문제다.
   `spring.data.redis.timeout: 500ms`는 각 Redis 명령의 대기 예산이다. 상세의 두 캐시 get·put 4회 예산은 2s이며 DB/JVM 등 전체 요청의 다른 지연은 별도다. 위 유한 조회 상한의 실패 해석을 함께 따른다
3. 복구되면 `cache_gets_total` 의 hit 이 다시 오르는지 확인
4. 그 다음 **§4 로 간다** — 복구했다고 끝이 아니다

---

## 4. 복구 후 stale 처리 — **여기가 핵심이다**

`@CacheEvict` 가 실패한 동안 **DB 는 커밋되고 캐시 무효화만 실패**했다. 즉 Redis 가 복구되면
**낡은 값이 그대로 살아 있다.**

| 캐시 | TTL | 최대 stale 노출 |
|---|---|---|
| `product` (상세) | 30m | **30분** |
| `products` (목록) | 10m | **10분** |

Redis 장애 중에 다른 Pod 가 한 수정도 같은 상한을 따른다. 이 Pod 의 사본은 허용 범위(정보 30분·목록 10분)까지 남는다(§1.1).

### 4.1 기본 방침 — TTL 만료 대기

**아무것도 하지 않는다.** 허용 stale 은 상세 ≤30분 / 목록 ≤10분이고, 그 안에 자연 만료된다.
대부분의 경우 이게 정답이다.

**재고 캐시(`productStock`)는 TTL 5초라 사실상 기다릴 것이 없다** (ADR-0026 D2). 이 캐시는
애초에 무효화가 없고 TTL 하나로만 수렴하므로(D3), evict 실패라는 개념 자체가 없다 —
§4.2 의 수동 삭제 대상도 아니다. 재고가 낡아 보인다는 신고가 5초를 넘겨 지속되면 캐시 문제가
아니라 **예약 Saga 쪽**을 본다.

### 4.2 즉시 해소가 필요할 때만 — 수동 삭제

가격 인하·판매중단처럼 **stale 이 사업적으로 문제가 되는 변경**이 evict 실패 구간에 있었다면 삭제한다.

```bash
# 1) 대상 확인 — SCAN 으로 센다
#    productStock 은 TTL 5초라 대상이 아니다(§4.1) — 패턴에 넣지 않는다.
#    키 접두사는 cache:v2: 다(D-109). 옛 cache: 키는 §6.3 을 본다.
redis-cli --scan --pattern 'cache:v2:product::*'  | wc -l
redis-cli --scan --pattern 'cache:v2:products::*' | wc -l

# 2) 삭제 — UNLINK(비동기 회수)로 배치 삭제
redis-cli --scan --pattern 'cache:v2:product::*'  | xargs -r -n 500 redis-cli UNLINK
redis-cli --scan --pattern 'cache:v2:products::*' | xargs -r -n 500 redis-cli UNLINK

# 3) 삭제 후 대조 — 0 이어야 한다
redis-cli --scan --pattern 'cache:v2:product::*'  | wc -l
redis-cli --scan --pattern 'cache:v2:products::*' | wc -l
```

> **`KEYS` 를 쓰지 않는다.** `KEYS` 는 단일 스레드 Redis 를 keyspace 크기만큼 블록한다.
> 공유 인프라이므로 다른 서비스(게이트웨이 rate limit·JWT 블랙리스트)까지 함께 멈춘다.
> `--scan` 은 커서 기반이라 블록하지 않는다.

- **Pod 사본은 지우지 않아도 된다**: Redis 가 정상이면 Pod 사본은 응답에 쓰이지 않는다(§1.1). Redis 키만 지우면 된다
- **패턴 주의**: `cache:` 프리픽스는 `CacheConfig#KEY_PREFIX` 소유다. JWT 블랙리스트(`bl:`)·
  Redisson 락 키와 네임스페이스가 분리돼 있으니 **`cache:` 밖을 지우지 않는다**
- **부분 실패**: 이 절차는 멱등이다. 중간에 끊기면 그냥 다시 실행한다 (삭제 대상이 줄어들 뿐)
- **삭제 직후**는 전량 캐시 미스라 DB 부하가 튄다 — §5 를 함께 본다

---

## 5. DB 부하 전이 (put 실패)

캐시 적재(put)가 실패해도 응답 정확성은 유지된다. D-109 전에는 모든 후속 요청이 DB 를 다시 쳤다. 지금은 세 장치가 이를 막는다.

- 읽은 값이 Pod 사본으로 남아 허용 범위 동안 다음 요청에 쓰인다(§1.1).
- 같은 키 동시 miss 는 한 로딩으로 묶인다. 기다림은 최대 3초이고, 넘으면 `PRD-005`(503)다.
- DB 진입은 Pod 당 동시 1개다(ADR-0037). 넘치면 `PRD-005` 로 거절한다.

그래도 남는 부하는 키 수와 Pod 수에 비례한다(콜드 스타트, 사본이 없는 키).

- 확인: `hikaricp_connections_pending` · 상품 조회 p95 지연 · 503(`PRD-005`) 비율 · `cache_stale_served_total`
- 무응답 Redis 에서는 사본을 내기 전에도 요청마다 명령 타임아웃을 한 번 기다린다. 이 지연은 Redis 복구 말고는 줄일 수 없다

---

## 6. 배포 / 롤백 순서

### 6.1 배포

**타임아웃(`spring.data.redis.timeout`)과 fail-open 핸들러는 같은 애플리케이션 버전에 담아 파드 단위로 원자 배포한다.**

타임아웃만 먼저 나가면 무응답 Redis 에서 요청이 60초 정체하는 대신 **~500ms 만에 5xx** 로 바뀐다 —
빨라질 뿐 여전히 실패다. 핸들러가 같이 있어야 그 실패가 DB 조회로 전환된다.

### 6.2 롤백

핸들러를 롤백하면 캐시 예외가 다시 전파된다(= 롤백 전 동작).

1. **먼저** 애플리케이션을 롤백한다
2. **그 다음** 필요하면 §4.2 로 stale 키를 삭제한다

순서를 뒤집으면 삭제 직후 들어온 요청이 롤백 전 코드로 캐시를 다시 채운다.

### 6.3 키 접두사 전환 (D-109)

D-109 는 Redis 값 형식을 `CacheEnvelope`(값과 DB 에서 읽은 시각)로 바꾸면서 키 접두사를 `cache:` 에서 `cache:v2:` 로 옮겼다.
같은 키를 쓰면 롤링 배포 중에 옛 Pod 가 새 형식을 읽다가 `SerializationException` 을 낸다. 핸들러가 이 예외를 되던지므로 500 이 된다.

- **배포 중**: 옛 Pod 와 새 Pod 는 서로 다른 키를 읽는다. 한쪽의 evict 는 다른 쪽 키를 지우지 못한다. 그래서 배포 중에 상품을 수정하면
  다른 쪽 사본이 TTL(상세 30분·목록 10분)까지 남을 수 있다. 사업적으로 문제가 되는 수정이 배포 창에 있었다면, 배포가 끝난 뒤
  §4.2 를 `cache:v2:` 패턴으로 돌린다.
- **배포 뒤**: 옛 `cache:product::*`·`cache:products::*`·`cache:productStock::*` 키는 아무도 읽지 않고 TTL(최대 30분)로 사라진다.
  지울 필요는 없다.
- **롤백**: 옛 코드는 `cache:` 키만 읽는다. 롤백 직후에는 옛 키가 이미 만료돼 콜드 스타트다. `cache:v2:` 키는 TTL 로 사라진다.

### 6.4 스키마

**DB 마이그레이션 없음.** 이 변경은 Flyway 파일을 추가하지 않는다 — 롤백에 스키마 되돌리기가 없다.
