# Runbook — Redis 캐시 장애 (fail-open) 대응

> 대상: product-service 의 상품 조회 캐시가 Redis 장애로 fallback 을 타는 상황
> 구현: 구현 ⑤ / L-006 (계획 `docs/plans/done/task-impl5-cqrs-cache-fallback.md`)
> 관련: `ResilientCacheErrorHandler` · `CacheConfig` · ADR-0009 S7 · ADR-0013 D3(gateway 대조)

---

## 0. 30초 요약

| 상황 | 할 일 |
|---|---|
| `cache_fallback_total{operation="get"}` 이 계속 증가한다 | Redis 장애다. §2 로 확인 → §3. **조회는 정상 응답 중이다** |
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

`cache_gets_total` 의 hit 이 0 으로 떨어지고 `cache_fallback_total{operation="get"}` 이 오르면
캐시가 통째로 우회되는 중이다.

### 2.1 수동 감시 계약 (alert 도입 전)

`cache_fallback_total` 기반 자동 alert 은 아직 없다(계획 §6 M4). 그 전까지는 **수동 감시**다.

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
   무응답은 `spring.data.redis.timeout: 500ms` 로 유계화돼 있어 요청당 최대 ~1s(get+put) 지연으로 끝난다
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

### 4.1 기본 방침 — TTL 만료 대기

**아무것도 하지 않는다.** 허용 stale 은 상세 ≤30분 / 목록 ≤10분이고, 그 안에 자연 만료된다.
대부분의 경우 이게 정답이다.

### 4.2 즉시 해소가 필요할 때만 — 수동 삭제

가격 인하·판매중단처럼 **stale 이 사업적으로 문제가 되는 변경**이 evict 실패 구간에 있었다면 삭제한다.

```bash
# 1) 대상 확인 — SCAN 으로 센다
redis-cli --scan --pattern 'cache:product::*'  | wc -l
redis-cli --scan --pattern 'cache:products::*' | wc -l

# 2) 삭제 — UNLINK(비동기 회수)로 배치 삭제
redis-cli --scan --pattern 'cache:product::*'  | xargs -r -n 500 redis-cli UNLINK
redis-cli --scan --pattern 'cache:products::*' | xargs -r -n 500 redis-cli UNLINK

# 3) 삭제 후 대조 — 0 이어야 한다
redis-cli --scan --pattern 'cache:product::*'  | wc -l
redis-cli --scan --pattern 'cache:products::*' | wc -l
```

> **`KEYS` 를 쓰지 않는다.** `KEYS` 는 단일 스레드 Redis 를 keyspace 크기만큼 블록한다.
> 공유 인프라이므로 다른 서비스(게이트웨이 rate limit·JWT 블랙리스트)까지 함께 멈춘다.
> `--scan` 은 커서 기반이라 블록하지 않는다.

- **패턴 주의**: `cache:` 프리픽스는 `CacheConfig#prefixCacheNameWith` 소유다. JWT 블랙리스트(`bl:`)·
  Redisson 락 키와 네임스페이스가 분리돼 있으니 **`cache:` 밖을 지우지 않는다**
- **부분 실패**: 이 절차는 멱등이다. 중간에 끊기면 그냥 다시 실행한다 (삭제 대상이 줄어들 뿐)
- **삭제 직후**는 전량 캐시 미스라 DB 부하가 튄다 — §5 를 함께 본다

---

## 5. DB 부하 전이 (put 실패)

캐시 적재(put)가 실패하면 응답 정확성은 유지되지만 **모든 후속 요청이 DB 를 다시 친다**.
동시 요청 하에서는 cache stampede 로 커넥션 풀을 압박한다.

- 확인: `hikaricp_connections_pending` · 상품 조회 p95 지연
- 현재 완화 기구는 **없다** (bulkhead·`@Cacheable(sync)`·rate limit 은 계획 §6 M7 로 승격)
- 급하면 Redis 복구가 유일한 해소책이다

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

### 6.3 스키마

**DB 마이그레이션 없음.** 이 변경은 Flyway 파일을 추가하지 않는다 — 롤백에 스키마 되돌리기가 없다.
