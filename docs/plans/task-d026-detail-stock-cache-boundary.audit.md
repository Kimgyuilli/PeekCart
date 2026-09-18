## 2026-09-18 — 계획 리뷰 라운드 0 (미호출)

- 항목: 0건 (Codex **미실행**)
- 사유: ① `@openai/codex@0.155.0` 플랫폼 바이너리 누락(`@openai/codex-darwin-arm64`) → CLI 즉사
        ② 재설치 대신 미호출 진행을 사용자가 선택
- 처리: 외부 리뷰 없음. "P0/P1 = 0" 주장 없음.
- 뒤집힌 전제 (착수 전 코드 검증에서 자체 발견, 리뷰 아님):
  - **V3** — 증적 `d002a-gke-20260916-0030.md` 가 `@Transactional(readOnly=true)` 를 detail 열세의
    원인으로 병기했으나, 클래스 레벨 선언이라 **list 에도 똑같이 걸린다**(list 는 ×2.02).
    `open-in-view: false` + 지연 커넥션 획득까지 더하면 트랜잭션 개방은 차이를 설명하지 못한다.
    → 원인은 **재고 SELECT 1회**로 좁혀진다. P9-② 에서 증적에 정정 추기.
  - **V8** — `product.updated` 가 `availableStock` 을 싣고 있으나 **예약/복구 경로는 발행하지 않고**
    소비 측(order-service)은 그 필드를 **버린다**(소비 0건). "이벤트로 재고 전파" 선택지 D 기각 근거.
  - **V2** — 현 경계는 버그가 아니라 **javadoc 에 명시된 의도된 결정**이었다(`ProductCacheService:32`).
    ADR-0026 은 이를 명시적으로 뒤집어야 한다.
  - **V11** — 기존 `ProductCacheIntegrationTest.getProduct_cacheHit` 은 재고 출처를 묻지 않아
    이 변경에 무감각하다(**false-green 위험 선재**). 검증 축을 SQL 발행 수로 잡은 이유.
  - **V1** — TASKS.md 의 `getProduct:44` 는 행 번호 오류(실제 `:45`, `:44` 는 빈 줄).
- 확정: 선택지 **B**(재고 전용 캐시 + 짧은 TTL) — 사용자 승인. TTL 값은 P2 에서 결정.
- raw: 없음 (미실행)

## 2026-09-18 — diff 리뷰 라운드 0 (미호출)

- 항목: 0건 (Codex **미실행** — 계획 리뷰와 동일 사유: `@openai/codex-darwin-arm64` 누락)
- 처리: 외부 리뷰 없음. **"P0/P1 = 0" 주장 없음.**
- 계획과 달랐던 것 (구현 중 발견, 리뷰가 아니라 **기존 테스트**가 잡았다):
  - **타임아웃 예산 배가** — `ProductCacheFallbackIntegrationTest` V3(무응답 Redis)가 2.05s 로
    1.5s 상한을 깼다. 상세가 캐시를 둘 타 get/put 타임아웃이 2회 → **4회(2s)** 가 된다.
    계획 §1 N-목록에 없던 축이다. 상한을 2.6s 로 올리고 ADR-0026 Consequences 에 비용으로 기록.
  - **V1 fallback delta 가 실행 구성에 의존** — 재고 캐시 get 실패가 단독 실행 1 ↔ 전체 실행 2.
    앞선 테스트가 남긴 Lettuce 재연결 상태 탓. 단언을 `= 1` → `>= 1` 로 바꿨다(증가분은 유지).
    고정 대상은 "재고 캐시가 fail-open 경로에 있다" 이지 Lettuce 재시도 횟수가 아니다.
  - **TTL 주입 지점 추가** — 계획은 "테스트 프로파일에서 짧게 주입" 이라고만 적었다. 실제로는
    `CacheConfig` 가 기본값(5s)을 소유하고 프로퍼티로 덮는 형태가 필요했다(ADR-0007 준수).
  - **P9-③ 대상 축소** — `docs/05-data-design.md` 에는 캐시 경계 서술이 없어 `04` 만 고쳤다.
- 가드 실패 주입 3종 전부 red 확인:
  - `getStock` `@Cacheable` 제거 → 왕복 1 복귀 red
  - TTL 1s→1h → stale 상한만 red (쓰기 경로 가드는 green)
  - 쓰기 경로 `@CacheEvict` 주입 → 쓰기 경로 가드만 red
- 선재 갭 발견, 손대지 않음: `04 §9-1` 이 재고 동시성을 "Redis 분산 락 + 낙관적 락" 으로 기술
  (ADR-0025 가 락을 제거했다). D-025 범위라 보고만 한다.
- 검증: (아래 최종 보고 참조) · lint `observability-ssot` exit 0 · `observability-promql` exit 0
- raw: 없음 (미실행)
