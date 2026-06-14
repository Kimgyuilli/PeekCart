# Phase 4 진행 보고서 — MSA 분리

> Phase 4 작업 이력, 주요 결정 사항, 이슈 기록
> 작업 상태 추적은 `docs/TASKS.md`, 설계·실행 로드맵은 `docs/progress/phase4-design-roadmap.md` 참고

---

## Phase 4 목표

ADR-0002 의 "모놀리식 → MSA 진화" 4단계 중 최종 단계. 5개 서비스 분해 + Gateway + Choreography Saga + CQRS.

**Exit Criteria** (`07-roadmap §16`):
- [ ] 모든 서비스 독립 배포 및 정상 동작 확인
- [ ] Saga 보상 트랜잭션 플로우 검증 (결제 실패 → 주문 취소 → 재고 복구)
- [ ] Gateway 라우팅 및 JWT 인증 정상 동작
- [ ] 서비스 간 직접 호출 없이 이벤트 + 로컬 캐시로 데이터 조합 확인

> 설계 단계(A1~A4) → 구현 단계(①~⑥). 상세 시퀀싱: `phase4-design-roadmap.md`.

---

## 작업 이력

### 2026-06-13 ~ 06-14

#### A1 — ADR-0010 서비스 분해 (설계)

**완료 항목**:
- **ADR-0010** 신규 (`docs/adr/0010-phase4-service-decomposition.md`, Status: Accepted) — §5(5개 풀 분해) 정본 비준, §4-5(3개 드리프트 목록) 정정. 5개 서비스 경계 표(D1) + 이벤트 토폴로지(D2, 토픽 4개) + Choreography Saga 체인(D3) + Phase 4 Exit Criteria coverage matrix(D4)
- 비준 시 추가 도식 정합 3건 (ADR-0010 §C4):
  - **F1** Notification DB 소유 확정 → `02-architecture.md §5` DataLayer 에 NotificationDB 추가, `05-data-design.md` 와 정합
  - **F2** 재고 차감 소유 트랜잭션 경계 충돌(Order 단일 트랜잭션 ↔ Product 재고 소유) 기록 → 재고 예약/차감 경계는 A3 위임
  - **F3** CQRS 로컬 캐시용 `product.updated`(Product→Order) 이벤트 필요 명시 → 스키마는 A3
- Layer 1 정합: `02-architecture.md §4-5`(5개로 정정 + `see ADR-0010`), `03-requirements.md §7-2`(Saga 재고 복구 주체 Order→Product 정정), `05-data-design.md`(Notification DB 정합 표기)
- `docs/adr/README.md` INDEX 행 추가

**설계 결정**: 서비스 경계 = §5 정본(5개). 근거·대안(Alt A 5개 vs Alt B 3개)은 ADR-0010.

**프로세스**: `/plan` 2회 Codex 리뷰(1차 5건, 2차 3건 전체 반영) → `/work` 구현 → `/ship` ([PR #44](https://github.com/Kimgyuilli/PeakCart/pull/44)). 계획서·audit: `docs/plans/task-adr0010-service-decomposition.md`.

**다음**: A2(멀티모듈 구조) — `common` 경계·의존 규칙. ADR-0010 §D1 의 5개 서비스 = 5개 모듈.

#### A2 — ADR-0011 멀티모듈 구조 (설계)

**완료 항목**:
- **ADR-0011** 신규 (`docs/adr/0011-phase4-multimodule-structure.md`, Accepted) — `common` + **`peekcart-common-observability`(ADR-0009 선결정)** + 5개 서비스 모듈. 모듈 레이아웃(D1) + class-level common 경계(D2) + 의존 규칙·위반 검출 필수(D3) + 빌드/테스트/이미지 계약(D4)
- 핵심 결정: 서비스는 `:common`+`:peekcart-common-observability` 만 의존, 서비스↔서비스 직접 의존 금지(CI 빌드 실패 검출). 이벤트 DTO 는 모듈 소유만, 스키마는 A3 위임(non-authoritative). Docker health smoke 서비스별 유지
- Layer 1 정합: `02-architecture.md §4-4`(관측성/5서비스 모듈 + `see ADR-0011`), §12(Phase 4 멀티모듈 포인터), `adr/README.md` INDEX

**프로세스**: `/plan` **3회** Codex 리뷰(1차 5건, 2차 2건[ADR-0009 모듈 충돌 발견], 3차 1건[자기모순 cleanup] — 5→2→1 수렴) → `/work` 구현(diff 리뷰 2건) → `/ship` ([PR #45](https://github.com/Kimgyuilli/PeakCart/pull/45)). 계획서·audit: `docs/plans/task-adr0011-multimodule-structure.md`.

**다음**: A3(DB-per-service + 이벤트/Saga 계약) · A4(Gateway 보안) — 병렬 가능. 이후 구현 ①(멀티모듈 전환).

#### A3 — ADR-0012 DB-per-service + 이벤트/Saga 계약 (설계)

**완료 항목**:
- **ADR-0012** 신규 (`docs/adr/0012-phase4-db-event-saga-contract.md`, Accepted) — DB-per-service domain/infra 경계(D1) + 이벤트 스키마(D2: envelope `schemaVersion`·파티션키·`product.updated` 필드·`OrderCancelled` items 보강) + 재고 예약 Saga(D3) + 토픽×producer×consumer×group 매트릭스(D4) + retention=멱등성 창 상한(D5)
- 핵심 결정: F2 해소 — Product 재고 소유로 Order 직접 차감 불가 → **예약 모델**(`order.created → Product 예약 → stock.reservation.result → 결제`). 예약 실패 신호용 **신규 토픽 `stock.reservation.result`** 채택(옵션 B). ADR-0010 4토픽 → 6토픽 refine. retention ≥ max(topic retention, consumer 다운타임, DLQ 수동 재처리 창, backfill)
- Layer 1 정합: `05`(Product DB outbox/processed/예약 컬럼), `04`(§9-6 전략 A→예약 모델, §9-4 Saga, §16 product.updated), `03 §7-2`(예약 경계), `02 §5`(토폴로지 6토픽), `adr/README.md`
- 편입 부채: L-008/L-011(retention), L-020-2(consumer group 라벨)

**프로세스**: `/plan` **3회** Codex 리뷰(1차 6건, 2차 1건, 3차 0건 — 6→1→0 수렴) → `/work` 구현(diff 리뷰 3건) → `/ship` ([PR #46](https://github.com/Kimgyuilli/PeakCart/pull/46)). 계획서·audit: `docs/plans/task-adr0012-db-event-saga-contract.md`.

**다음**: A4(Gateway 보안) — 마지막 설계 ADR. 이후 구현 ①(멀티모듈 전환).

#### A4 — ADR-0013 Gateway 보안 (설계, 마지막 설계 ADR)

**완료 항목**:
- **ADR-0013** 신규 (`docs/adr/0013-phase4-gateway-security.md`, Accepted) — RS256 전환(D1, Gateway 공개키 1차 검증·서비스 미재검증·JWKS·kid/alg allow-list·키 overlap) + 시크릿 저장소 3안(D2, Secret Manager 기본/KMS 격상) + Spring Cloud Gateway(D3, 검증 순서·헤더 신뢰 모델·route-class Rate Limit·fail-closed) + Reuse Detection(D4, `family_id` 이력 모델 + 탈취 containment) + 인증 실패 관측성(D5, ADR-0009 S9 surface 추가)
- 핵심 결정: 대칭키 공유 제거(RS256), reuse 감지 시 family 무효화 + access token `family_id` 클레임/family deny 로 이미 발급된 토큰까지 Gateway 차단
- Layer 1 정합: `04 §10-2/§9-2`, `03 §7-2`, `05 refresh_tokens`(family_id/status/grace_until), `02 §5`, `adr/0009`(S9 행 추가), `adr/README.md`
- 편입 보안 묶음: L-001(RS256)/L-002(시크릿)/L-003(Reuse Detection)/L-019(관측성)

**프로세스**: `/plan` **3회** Codex 리뷰(1차 8건, 2차 1건, 3차 0건 — 8→1→0 수렴) → `/work` 구현. 계획서·audit: `docs/plans/task-adr0013-gateway-security.md`.

**다음**: 🎯 설계 ADR(A1~A4) 전부 완료. **구현 단계 ①(Gradle 멀티모듈 전환)** 부터 — 실제 코드.
