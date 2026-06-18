## 2026-06-18 06:13 — GP-2 (loop 1)
- 리뷰 항목: 4건 (P0:0, P1:3, P2:1)
- 사용자 선택: [2] 전체 반영 (4건 모두 코드로 확인됨)
  - #1(P1) product 코드가 root 전속 global.outbox 실행세트(7종)·global.idempotency(5종)·ShedLockConfig 에 의존 → 모듈 peel 시 컴파일 실패. :common 은 payload DTO 만 보유. → P3 신규(복제, notification idempotency 선례) + P1 build.gradle ShedLock 의존 + §2 배경에 위치/disposition 명시. PLAN-BLINDSPOTS B8 신설.
  - #2(P1) 테스트 이동 범위 오류(7개→실제 13개) → P5 를 13개 전체 + 누락 6개 명시로 수정.
  - #3(P1) assertNoDuplicateGlobalFqcn apps 맵 하드코딩(build.gradle:236) → P7 에 :product-service 추가, 동적 구성(subprojects endsWith -service) 권장.
  - #4(P2) 검증이 컨텍스트 로드 스모크뿐 → §5 에 :product-service:test 분리 + 자체 poller 발행/consumer processed_events/캐시/PreAuthorize 통합 assert.
- 검증: hpx_plan_lint OK (반영 후 재확인, P1~P7 연속)
- raw: .cache/codex-reviews/plan-task-impl-product-peel-1781762697.json
- run_id: plan:20260618T060418Z:48ba7b1a-d200-4c44-a5eb-de919d413f98:1

## 2026-06-18 06:26 — GP-2 (loop 2, 사용자 재리뷰 요청)
- 리뷰 항목: 1건 (P0:0, P1:1, P2:0) — 1차 P3 복제 결정이 만든 후속 결함
- 사용자 선택: [반영]
  - #1(P1) P3 복제 poller 가 공유 DB 전환기 실행 소유권 미분리: OutboxEventJpaRepository 가 status=PENDING 만 필터(:11), ShedLock 단일 outboxPollingJob(:15) → root·product 두 poller 가 같은 outbox_events 경합(소유권 붕괴/중복 발행). 코드 확인: OutboxEvent 에 aggregate_type/event_type 컬럼 기존재 → 스키마 변경 불요. → P4 신규(poller별 eventType allowlist + ShedLock 이름 분리, root: order/payment / product: product.updated·stock.reservation.result), §5 동시실행 1회발행 검증, §4 root OutboxEventJpaRepository/Scheduler 수정 명시. PLAN-BLINDSPOTS B8 에 공유 DB poller 경합 후속 Check 추가.
  - 항목 재번호: 기존 P4~P7 → P5~P8 (P1~P8 연속 확인)
- 검증: hpx_plan_lint OK
- raw: .cache/codex-reviews/plan-task-impl-product-peel-1781763617.json
- run_id: plan:20260618T061947Z:48ba7b1a-d200-4c44-a5eb-de919d413f98:2

## 2026-06-18 09:07 — GW-2 (work loop 1)
- 리뷰 run: work:20260618T071051Z:4c4c837e-f728-47a2-877f-27e06b7d9eca:1 (single, diff 2416줄 — 대부분 기계적 이동/byte-identical 복사)
- 항목: 2건 (P0:0, P1:0, P2:2) — 자동 통과 임계 충족, 단 둘 다 반영(타당)
  - #1(P2) backlog gauge(outbox.backlog)가 countByStatus 로 aggregateType 미필터 → 공유 DB 에서 상대 앱 PENDING 까지 집계(P4 소유권 분리 불완전, 내 변경 결함). → countByStatusAndAggregateTypeIn 추가, gauge 가 주입된 allowlist 사용. root+product 동일 복제.
  - #2(P2) product poller 실발행+cross-aggregateType 무시(계획 §5 완료조건)가 테스트로 안 닫힘. → ProductOutboxOwnershipIntegrationTest 신설(PRODUCT 발행→PUBLISHED·ORDER 무시→PENDING·gauge PRODUCT-only 집계).
- 빌드 검증: 전체 ./gradlew build BUILD SUCCESSFUL(root 0실패 → product-service 20실패[schema missing] 수정 → 재그린). 가드 3종 통과(assertNoDuplicateGlobalFqcn 동적 product-service 포함).
- 디커플 부산물(FQCN-grep B1 이 놓친 string-level 결합): ShedLock 락이름·ObservabilityMetrics(/api/v1/products·products 캐시→product-service 이관)·outbox probe aggregateType·product 통합테스트 flyway.enabled.
- diff: .cache/diffs/diff-task-impl-product-peel-1781766650.patch
- raw: .cache/codex-reviews/diff-task-impl-product-peel-1781766680.json

## 2026-06-18 09:32 — /done applied (PR https://github.com/Kimgyuilli/PeakCart/pull/62)
- 커밋: 5개 (feat src / chore build / test / docs plan / docs progress)
- TASKS.md 구현① 셀: Product peel #62 추가 (Order+Payment peel·PR3 잔존)
- roadmap §4: Product peel 완료, 다음 = Order+Payment peel → PR3
- PHASE4.md: Product peel 이력 추가
- ADR: 신규 없음 (ADR-0010 F2·ADR-0011·ADR-0012·ADR-0014 기보유)
- 구현 ① 상태: 🔄 유지
