---
grade: S
---
# task-d046-cache-timeout-bound

PR: [#150](https://github.com/Kimgyuilli/PeekCart/pull/150)

`ProductCacheFallbackIntegrationTest` V3 경과시간 상한 flake (D-046)

## 명제
V3 가 타임아웃 4회(2000ms) 외의 부하 잡음으로 실패하거나, 재고 캐시의 get·put fallback 을
증가분으로 확인하지 않거나, DisplayName 이 실제 상한과 다르면 미완이다.

## 코드 확인 (착수 전)
- 실패 실행 로그(key=3): 타임아웃은 product get·put, productStock get·put 정확히 4회. 추가 재시도 없음.
  초과 약 760ms 중 약 570ms 가 product get 실패 → put 실패 사이(DB 조회)다. 부하 잡음이지 퇴행이 아니다
- 상한 2600ms 는 여유 600ms. "캐시 추가"(+1000ms)와 부하 잡음을 시간 하나로 가르기엔 폭이 좁다
- `FallbackSnapshot` baseline 이 `STOCK_CACHE` 를 빠뜨려 `deltaOf(STOCK_CACHE, ...)` 가 누적값을 돌려준다.
  V1 의 재고 단언(`:245`)도 증가분이 아니라 누적값이었다 — 앞선 테스트 잔여로 통과할 수 있다
- DisplayName 은 "1.5s 이내" 인데 단언은 2600ms

## 작업 항목
- [x] P1. baseline 에 `STOCK_CACHE` 추가
- [x] P2. V3 에 재고 캐시 get·put 증가분 단언 추가(재연결 이중 집계 관측이 있어 `≥ 1`)
- [x] P3. 상한 3500ms 로 완화. 시간 상한은 "timeout 이 지워졌거나(Lettuce 기본 60s) 커졌는가" 만 본다
      (1s 면 4000ms 로 걸린다). 캐시 경로 계약은 증가분 단언이 맡는다. 메시지·DisplayName 정정

## 검증
- `redis.timeout` 을 1s 로 바꿔 V3 가 시간 상한에서 실패하는지 확인 후 원복
- baseline 에서 `STOCK_CACHE` 를 뺀 상태와 비교하는 대신, V3 재고 단언이 증가분인지 확인: V3 를 단독·전체 클래스로 돌려 통과
- `:product-service:test --tests ProductCacheFallbackIntegrationTest` 통과

## 검증 기록 (2026-09-27)
- `ProductCacheFallbackIntegrationTest` 7건 통과, V3 3.617s(테스트 전체 시간)
- 결함 주입: `spring.data.redis.timeout` 500ms → 1s 에서 V3 가 `4.141853958S` 로 3.5s 상한에 걸려 실패. 원복 확인
- `:product-service:test --rerun` 196건 0 실패
- 재고 캐시 단언의 소실(0) 주입은 하지 않았다 — 캐시 경계를 지우는 프로덕션 변경이 필요해 범위를 넘는다.
  baseline 누락 수정으로 증가분이 되었음은 코드상 `deltaOf` 가 baseline 을 빼는 것으로 확인
