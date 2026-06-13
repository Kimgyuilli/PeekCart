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

**프로세스**: `/plan` **3회** Codex 리뷰(1차 5건, 2차 2건[ADR-0009 모듈 충돌 발견], 3차 1건[자기모순 cleanup] — 5→2→1 수렴) → `/work` 구현. 계획서·audit: `docs/plans/task-adr0011-multimodule-structure.md`.

**다음**: A3(DB-per-service + 이벤트/Saga 계약) · A4(Gateway 보안) — 병렬 가능. 이후 구현 ①(멀티모듈 전환).
