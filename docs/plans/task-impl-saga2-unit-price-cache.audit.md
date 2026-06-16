## 2026-06-16 20:58 — GP-2 (loop 1)
- 리뷰 항목: 4건 (P0:0, P1:2, P2:2)
- 사용자 선택: [2] 전체 반영
  - #1(P1) ADR-0012:48 payload 계약 위반 → ProductUpdatedPayload 7필드 전체 발행, Order 캐시는 price 만 소비
  - #2(P1) timestamp-only stale-skip → (updatedAt, source_event_id) 복합 비교 + 동일-timestamp 테스트
  - #3(P2) "동기 read 와 본질 동일" 과대주장 → "이전 가격 주문 수용" 으로 정정 + lag 관측 추가
  - #4(P2) processed_events retention → P3/P5 에 ADR-0012 D5 정합 검증 추가
- run1(attempt 1): timeout(codex 180s 파일탐색 초과) → attempt 2 에서 탐색범위 좁힌 프롬프트 + 420s 로 성공
- raw: .cache/codex-reviews/plan-task-impl-saga2-unit-price-cache-1781610734.json
- run_id: plan:20260616T115057Z:95f528f8-774b-4bfb-af42-a1e04f71f6e5:2

## 2026-06-16 21:10 — GP-2 (loop 2, 사용자 재리뷰 요청)
- 리뷰 항목: 4건 (P0:0, P1:2, P2:2) — 모두 1차 수정의 후속 결함
- 사용자 선택: [2] 전체 반영 (Item 1 순서키 = Product @Version 선택)
  - #1(P1) source_event_id tie-breaker 가 랜덤 UUID(OutboxEvent.eventId=UUID.randomUUID, 인과순서 무관) → Product @Version monotonic version 으로 교체, 캐시 source_version BIGINT 비교
  - #2(P1) ProductStatus(ON_SALE/SOLD_OUT/DISCONTINUED) ≠ ADR-0012:48(ACTIVE/INACTIVE/SOLD_OUT) → 매핑 명시 + 스냅샷 테스트 문자열 고정
  - #3(P2) delete→discontinue status 변경 발행 누락 → create/update/delete 발행
  - #4(P2) seed sentinel 정렬 미정의 → version 기반 전환으로 자연 해소(seed=실제 version)
- raw: .cache/codex-reviews/plan-task-impl-saga2-unit-price-cache-1781611245.json
- run_id: plan:20260616T120012Z:95f528f8-774b-4bfb-af42-a1e04f71f6e5:3

## 2026-06-16 23:35 — GP-2 (loop 3, 변경영역 한정 · attempt 1 타임아웃 보정으로 사용자 승인)
- 리뷰 항목: 1건 (P0:0, P1:1, P2:0) — status 매핑·discontinue·Flyway 동봉은 "적절"로 확인, 잔여 1건
- 사용자 선택: 반영
  - #1(P1) @Version flush 타이밍 — payload version 을 flush 전 읽으면 seed(source_version=0) ↔ 첫 update event(version=0) 충돌로 0<0=false → 캐시 갱신 누락. → P1 에 saveAndFlush 후 getVersion() 명시 + seed 덮어쓰기 회귀 테스트(version 0→1) + OptimisticLockException 롤백 기대 문서화
- raw: .cache/codex-reviews/plan-task-impl-saga2-unit-price-cache-1781620341.json
- run_id: plan:20260616T121329Z:95f528f8-774b-4bfb-af42-a1e04f71f6e5:4

## 2026-06-17 00:54 — GW-2 (work loop 1)
- 리뷰 run: work:20260616T153645Z:95f528f8-774b-4bfb-af42-a1e04f71f6e5:1 (single)
- 항목: 2건 (P0:0, P1:1, P2:1)
- 사용자 선택: [2] 전체 반영
  - #1(P1) applyUpdate two-step update→insert 의 catch 가 commit-시점 UK 위반을 못 잡음 → ON DUPLICATE KEY UPDATE ... IF(:version>source_version,...) 원자 upsert 로 교체 (updateIfNewer/existsById/save 제거, ProductPriceCache.of 도 dead → 제거)
  - #2(P2) 통합테스트가 consumer 직접호출로 relay/listener/createOrder 우회 → e2e 2건 추가(create→pollAndPublish→@KafkaListener→캐시→cart→createOrder OrderItem 단가 스냅샷 / 캐시미스→ORD-007). users FK 는 native insert 시드(PR2b 패턴)
- 검증: ./gradlew test 전체 BUILD SUCCESSFUL (가격캐시 통합 7/7)
- diff: .cache/diffs/diff-task-impl-saga2-unit-price-cache-1781624152.patch
- raw: .cache/codex-reviews/diff-task-impl-saga2-unit-price-cache-1781624230.json
- run_id: work:20260616T153645Z:95f528f8-774b-4bfb-af42-a1e04f71f6e5:1

## 2026-06-17 01:04 — /done applied (PR https://github.com/Kimgyuilli/PeakCart/pull/57)
- TASKS.md: 구현 ① 사가 클러스터 행에 strangler-2 ✅ (#57) 반영
- PHASE4.md: strangler-2 progress 이력 추가(P1~P5·핵심결정·프로세스)
- ADR 상태 변경 없음(ADR-0012 Accepted 유지, ⑤ 구현·새 ADR 불필요)
- 커밋: 5개(feat common/product/order·test·docs) + 본 docs(tasks) 갱신
