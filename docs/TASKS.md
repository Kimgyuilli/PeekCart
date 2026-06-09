# PeekCart — Task 관리

> 현행 작업을 **PR 단위**로 추적한다. PR 1개 = 부채/기능 1묶음.
> 상태: `🔲 대기` / `🔄 진행 중` / `✅ 완료` / `⏸ 보류`

## 문서 맵

| 대상 | 경로 |
|---|---|
| Phase 1~3 task 이력 (아카이브) | `docs/progress/TASKS-archive-phase1-3.md` |
| 부채 해소 로드맵 (트리아지·시퀀싱·게이트) | `docs/progress/phase4-prep-debt-roadmap.md` |
| 부채 후보 분류·승격 매핑 (L-001~L-022) | `docs/progress/phase4-prep-debt-roadmap.md §2~5` |
| Phase별 작업 이력 | `docs/progress/PHASE1.md` · `PHASE2.md` · `PHASE3.md` |

---

## 현재 단계: Phase 4 진입 전 기술부채 해소

> 로드맵 §2(버킷 1) 을 PR 단위로 분리. 각 PR 착수 시 상태를 `🔄`, 머지 시 `✅` 로 갱신하고 PR 링크를 단다.

### PR 단위 (버킷 1)

| PR | 작업 | 묶는 부채 | D- | 상태 | PR |
|---|---|---|---|---|---|
| 문서 정비 | TASKS 아카이빙 + 신규 시작, 부채 로드맵 작성 | — | — | 🔄 | — |
| Tier A 즉시 정정 | 문서/주석/쿼리 명백한 오류 일괄 정정 | L-018, L-021, L-022, L-016(b), L-020(1) | — | ✅ | — |
| CI 품질 게이트 | PR Docker build+smoke / branch protection / NS lint | L-014, L-015, L-017 | **D-012** | ✅ | — |
| 발행 경로 하드닝 | DLQ 발행 확정 + outbox `.get()` 타임아웃·사이클 상한 | L-010, L-012 | **D-013** | 🔲 | — |
| 관측성 선결 표면 (선택) | 캐시 적중률 + outbox 파이프라인 메트릭 | L-005, L-009 | **D-014** | 🔲 | — |

> 권장 시퀀스: 문서 정비 → Tier A → D-012 → D-013 → (여유 시) D-014. 상세 근거는 로드맵 §2.

---

## 개발 부채 (Tech Debt)

> 해결 완료(D-001~D-012) 상세는 아카이브(`TASKS-archive-phase1-3.md §개발 부채`) 보존. 여기서는 **live + 신규**만 추적.

### Live / 신규

| ID | 영역 | 요약 | 묶음 | 상태 |
|---|---|---|---|---|
| D-002 | Performance | 캐시 TPS ×2.31(목표 ×3 미달). 1차 병목 CPU 확증, 2차 후보(MySQL 풀 / Redis 락 contention) 미분리 | Phase 4 Order Service 분리 후 격리 재측정 | 🔄 추적 |
| D-012 | CI / Deliverable | CI 가 품질 게이트가 아니다 — PR Docker build·smoke 부재, branch protection 미설정, NS 누출 lint 부재 | 버킷 1 (L-014/015/017) | ✅ 완료 |
| D-013 | Resilience | 발행 경로 resilience 갭 — DLQ 발행 미확정(유실), outbox `.get()` 타임아웃 부재와 polling cycle 상한 미정의(워커 잠식) | 버킷 1 (L-010/012) | 🔲 대기 |
| D-014 | Observability | 선결 측정 표면 부재 — 캐시 적중률 / outbox 파이프라인 메트릭 | 버킷 1 선택 (L-005/009) | 🔲 대기 |

### 해결 완료 (아카이브 참조)

D-001(✅), D-005(✅), D-006(✅), D-007(✅), D-008(✅), D-009(✅), D-010(✅), D-011(✅), D-012(✅) · D-003(Won't Fix) · D-004(운영지식) — 상세: `docs/progress/TASKS-archive-phase1-3.md §개발 부채`.

---

## Phase 4 — MSA 분리 (예정)

> 로드맵 §3(버킷 2) 이관 부채를 각 Phase 4 task 에 편입. 착수 시 D- 승격 또는 task 항목 흡수.

| 순서 | 작업 | 편입 부채 |
|---|---|---|
| 1 | Gradle 멀티모듈 전환 | L-016(a) digest 고정, L-020(2) consumer group 라벨, D-002 격리 재측정 |
| 2 | 서비스별 DB 분리 | L-008/L-011 retention(보존기간=멱등성 창 상한 결정) |
| 3 | Spring Cloud Gateway | **보안 묶음** L-001/L-002/L-003/L-019 (RS256 전환 + KMS/Vault + Reuse Detection + 인증 관측성) |
| 4 | Choreography Saga | — |
| 5 | CQRS 로컬 캐시 | L-006 Redis fallback (L-005 선결) |
| 6 | Cursor 페이지네이션 | — |
| — | 운영 관측성 | L-004 Slack 채널 재설계 |

상세: `docs/07-roadmap-portfolio.md §16` · 로드맵 §3.

---

## 보류 (측정 후 결정)

> 게이트: **17편 후속 부하 세션** 실측. 나오면 모놀리스 단계 선제 승격, 아니면 Phase 4 분리 시 자연해소(L-007)/필수화(L-013).

| ID | 영역 | 측정 게이트 |
|---|---|---|
| L-007 | 주문 *생성* 경로 "락 ⊃ 트랜잭션" 불변식 | 동일-상품 경합 시 `PRD-004`/`OptimisticLockingFailureException` 응답률 유의 |
| L-013 | 주문 *상태 전이* 동시성(`Order @Version` 부재) | payment.completed/failed ↔ 타임아웃 동시 적용 시 상태 모순 실측 |

상세·승격 시 동반 결정: 로드맵 §4.
