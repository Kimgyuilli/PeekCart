# task-adr0010-service-decomposition — Phase 4 서비스 분해 ADR-0010 작성

> 작성: 2026-06-13
> 관련 Phase: Phase 4 (MSA 분리) — 설계 단계 A1
> 로드맵: `docs/progress/phase4-design-roadmap.md §0~1`
> 후속: 본 ADR 비준 후 A2(멀티모듈 구조) · A3(DB-per-service + 이벤트/Saga 계약) · A4(Gateway 보안) ADR 이 본 ADR 을 SSOT 로 참조
> 관련 ADR: 신규 = **ADR-0010** (Proposed → Accepted). ADR-0002(모놀리식→MSA 진화)의 Phase 4 구체화. ADR-0001(4-Layered+DDD) 도메인 경계와 정합. ADR-0009(관측성 계약 SSOT)가 이미 정한 Phase 4 서비스별 surface owner 와 정합 필요.

## 1. 목표

Phase 4 MSA 분리의 **서비스 경계를 단일 결정으로 못 박는 것**이 본 task 의 primary 산출물(**ADR-0010**)이다. 현재 설계 문서는 서비스 경계를 두 곳에서 서로 다르게 말하고 있다:

- `docs/02-architecture.md §4-5` "MSA 분리 대상" — Order · Payment · Notification **3개**만 나열
- `docs/02-architecture.md §5` Phase 4 다이어그램 — User · Product · Order · Payment · Notification **5개 전부** 독립 서비스 + 서비스별 DB + 이벤트 토폴로지 + Saga 체인

이 불일치는 A2(멀티모듈)·A3(DB분리/이벤트계약)·A4(Gateway)의 입력값을 흔든다. 본 ADR 이 §5(5개 풀 분해)를 **정본으로 비준**하고 §4-5 를 정정하여, 이후 모든 Phase 4 설계의 SSOT 가 된다.

세부 목표:

- **(D1) 경계 확정** — 5개 서비스의 bounded context, 책임, 소유 엔티티/데이터, 소유 DB 를 표로 명문화
- **(D2) 이벤트 토폴로지 확정 (범위 분리)** — §5 다이어그램의 **결제/Saga 토폴로지 토픽 4개**(`order.created`/`payment.completed`/`payment.failed`/`order.cancelled`)만 producer/consumer 표로 **확정**한다. CQRS 로컬 캐시(구현 ⑤)가 전제하는 **Product→Order 캐시 이벤트**(`product.updated` 계열, `04-design-deep-dive §367-369`)는 본 ADR 에서 "필요함"만 명시하고 이름/스키마는 **A3 입력**으로 위임 (= "전체 Phase 4 토폴로지 확정" 아님)
- **(D3) Saga 체인 명문화** — `payment.failed → 주문 취소 → 재고 복구` 보상 흐름의 서비스 간 책임 경계 (소유 트랜잭션 / 소비 이벤트 / 발행 이벤트)
- **(D4) §4-5 정정 + 참조 연결** — Layer 1 문서(§4-5, §5)를 ADR 결정에 정합. surface 별 SSOT 표는 ADR 본문에만, Layer 1 은 `(see ADR-0010)` 참조

본 task 는 **문서만 변경**한다. 멀티모듈 디렉토리 구조, Flyway 분리 SQL, 토픽 스키마/네이밍 규약, Gateway 라우팅 등 구현·세부 계약은 비대상(각 후속 ADR/구현 task).

## 2. 배경 / 제약

### 발견 경위

- 2026-06-13 Phase 4 설계 로드맵 수립 중, `02-architecture.md` §4-5 ↔ §5 서비스 경계 불일치 발견
- 로드맵 §0 에서 방향 확정: **§5(5개 풀 분해) 정본**. 본 ADR 이 그 결정의 비준·명문화
- 버킷 1(진입 전 부채)의 "인프라 도식 정합성"(D-015~018)과 동종 — 이번엔 **서비스 토폴로지 도식 정합성**

### 경계 결정 근거 (로드맵 §0)

- §5 다이어그램이 이미 DB-per-service(User/Product/Order/Payment DB) · Kafka 토픽 토폴로지 · Saga 체인 · Gateway 5개 라우팅을 완성 → 5개 채택 시 downstream 재설계 없음
- Gateway 는 어차피 User/Product 라우팅 필요. CQRS 로컬 캐시(구현 ⑤)는 Product→Order 캐시라 Product 독립 전제
- 포트폴리오 "MSA 역량" 서사상 풀 분해가 강함 (ADR-0002 동기와 정합)

### ADR-0002 와의 관계

- ADR-0002 = "모놀리식 → MSA 4단계 진화" 일반 전략. Phase 4 = MSA 분리 단계라고만 결정
- ADR-0010(본 task) = ADR-0002 의 Phase 4 를 **구체화** — "어떤 서비스로, 어떤 경계로 쪼개는가". Supersede 아님, "Refines ADR-0002"

### 추가 발견 (Codex 리뷰 2026-06-13 반영) — ADR §Context 에 함께 기록

§4-5↔§5 외에 비준 전 정합해야 할 도식·설계 불일치 3건:

- **(F1) Notification DB 경계 불명확** — §5 DataLayer 에는 User/Product/Order/Payment DB + Redis 만 있고 **Notification DB 가 없으나**(`02-architecture.md §140-146`), §4-5 는 Notification 을 분리 대상에 넣고 `05-data-design.md §362-380` 에는 Notification DB(`notifications`/`processed_events`)가 별도 정의됨. → ADR 에서 Notification Service 의 DB 소유 여부를 **명시 결정**하고 §5 DataLayer·`05` 와 정합
- **(F2) 재고 차감 소유 트랜잭션 경계 충돌** — 모놀리스는 주문 생성 시 Order 가 "재고 차감 + 주문 저장 + Outbox 저장"을 한 트랜잭션으로 처리(`04-design-deep-dive §104-106`). 그러나 §5 정본은 Product 가 재고 소유자 → Order 가 재고를 직접 차감 불가. ADR §Context 에 기존 설계 충돌로 기록하고, 실제 예약/차감 이벤트 경계는 A3 위임
- **(F3) Product→Order 캐시 이벤트** — D2 참조. ADR 은 "필요" 명시, 스키마는 A3

### 제약 / 비대상

- 코드/매니페스트 변경 0건 (문서만)
- **비대상**: A2 멀티모듈 디렉토리 구조 · `common` 모듈 경계 / A3 Flyway 서비스별 분리 SQL · 토픽 스키마·네이밍 규약·retention / A4 Gateway 라우팅·RS256 전환 — 각 후속 ADR 에서 본 ADR 을 인용해 결정
- 본 ADR 은 **경계와 계약의 골격**만. 물리 구현 디테일은 비대상

## 3. 작업 항목

### Part A — 사전 감사 (ADR Context 입력 정확성 확보)

- [ ] **P1.** §4-5 ↔ §5 불일치 실증 — 두 절의 실제 라인 인용으로 "3개 vs 5개" 차이를 ADR §Context 표로 기록 (추측 금지, 파일 직접 인용)
- [ ] **P2.** 현 모놀리스 도메인 5개 책임/소유 데이터 감사 — `docs/02-architecture.md §12` 패키지 구조 + 실제 `src/main/java/com/peekcart/{user,product,order,payment,notification}` 패키지를 읽고 각 도메인의 소유 엔티티/책임을 표로 정리 (서비스 경계의 근거)
- [ ] **P3.** 현 이벤트/토픽 현황 감사 — 현 Kafka 토픽 정의, Outbox 사용처(`order`/`payment` 도메인), `@TransactionalEventListener`/Kafka consumer 현황을 인용. §5 토폴로지(토픽 4개)와의 매핑 확인

### Part B — Decision 후보 비교 + 확정

- [ ] **P4.** Alternatives 비교 — Alt A(5개 풀 분해, §5) / Alt B(3개 분리, §4-5, User·Product 모놀리스 유지) 를 동일 비교축(분리 범위, downstream 재설계 비용, 운영 부담, Gateway/CQRS 정합, 포트폴리오 서사)으로 정리. **채택 = Alt A**, 기각 사유 대칭 기술
- [ ] **P5.** 서비스 경계 표(D1) — 5개 서비스 × [서비스명 / 책임 / 소유 엔티티 / 소유 DB / 주요 외부연동]. "TBD"/"추후" 금지. **F1 반영**: Notification Service 의 DB 소유 여부를 명시 결정(소유 시 `notifications`/`processed_events` 행 포함, 미소유 시 근거 기록)
- [ ] **P6.** 이벤트 토폴로지 표(D2) — **결제/Saga 토픽 4개** × [발행 서비스(Outbox 경유 여부) / 소비 서비스(들) / 트리거 / **서비스 간 데이터 의존성·식별자 수준 메모(비스키마)**]. §5 다이어그램 L120~136 기준. 스키마 필드명/필수·선택/버전/파티션 키/retention 은 **A3 입력**으로 명시 위임(본 ADR 비확정 — 페이로드 골자/예시 재확정 금지). **F3 반영**: 표 하단에 "A3 입력: Product→Order 로컬 캐시 이벤트(`product.updated` 계열) 필요, 이름/스키마는 A3" 명시
- [ ] **P7.** Saga 체인 명문화(D3) — `payment.failed → order.cancelled → 재고복구` 보상 흐름을 서비스 책임 경계(소유 트랜잭션 / 소비 이벤트 / 발행 이벤트)로 단계별 기술. choreography 방식(중앙 오케스트레이터 없음) 명시. **F2 반영**: 주문 생성 시점의 재고 차감/예약 소유 트랜잭션 경계 충돌(Order 단일 트랜잭션 ↔ Product 재고 소유)을 ADR §Context 에 기록하고, 실제 차감/예약 이벤트 경계 확정은 A3 위임임을 명시

### Part C — ADR-0010 작성

- [ ] **P8.** `docs/adr/0010-phase4-service-decomposition.md` 신규 — `docs/adr/template.md` / ADR-0009 형식 준수
  - **Status**: 초안 `Proposed`
  - **Decided**: 2026-06-13 / **관련 Phase**: Phase 4
  - **Context**: §4-5↔§5 불일치(P1) + 도메인 감사(P2) + 이벤트 현황(P3) + 경계 결정 근거(로드맵 §0)
  - **Decision**: 서비스 경계 표(P5) + 이벤트 토폴로지 표(P6) + Saga 체인(P7) + **Phase 4 Exit Criteria coverage matrix**(각 Exit Criteria가 ADR-0010/A2/A3/A4 중 어디서 닫히는지 owner ADR 매핑)
  - **Alternatives Considered**: P4 의 Alt A/B
  - **Consequences**: 긍정(downstream 설계 SSOT 확보, 서사 강화) / 부정(분산 트랜잭션 복잡도 + **관측성 N배 — ADR-0009 surface(S1~S8)의 서비스별 owner 영향**: per-service `application=` 태그·ServiceMonitor·outbox 계측 owner 가 A2/A3 입력으로 연결) / 후속(A2/A3/A4 가 본 ADR 인용)
  - **References**: ADR-0001, ADR-0002, **ADR-0009**(Phase 4 관측성 surface owner), 로드맵 §0~1, `02-architecture.md §4-5/§5`, `05-data-design.md`(Notification DB)
- [ ] **P9.** ADR Status 전환 — 본문 작성 후 별도 커밋으로 `Proposed` → `Accepted` (ADR-0008/0009 패턴)

### Part D — 인덱스/참조 동기화

- [ ] **P10.** `docs/adr/README.md` INDEX 표에 ADR-0010 행 추가
- [ ] **P11.** Layer 1 What 문서 정합 — 새 서비스 경계와 어긋난 잔여 서술 정정:
  - `docs/02-architecture.md §4-5` 5개 서비스로 갱신 + `(see ADR-0010)`. §5 다이어그램 캡션/인접에 `(see ADR-0010)` 1줄
  - **F1 반영**: P5 에서 Notification DB 소유로 결정했다면 §5 DataLayer 에 NotificationDB 노드 추가, 미소유면 `05-data-design.md` Notification DB 정의를 후속 정정 대상으로 표기
  - **잔여 충돌 정정**: `docs/03-requirements.md §7-2`(L94-97)의 Phase 4 Saga 설명이 "payment.failed 시 Order Service 가 재고 롤백"으로 남아 있어 새 경계(Product 가 재고 소유)와 충돌 → `payment.failed → Order Service 주문 취소 → order.cancelled → Product Service 재고 복구` 로 정정 + `(see ADR-0010)` (근거: `02-architecture.md:113,134-135`, `04-design-deep-dive.md:189-193`)
- [ ] **P12.** `bash docs/consistency-hints.sh` exit 0 확인 — ADR 참조 파일 존재 hint 통과

### Part E — 문서 동기화

- [ ] **P13.** `docs/progress/phase4-design-roadmap.md §0/§1` 상태 갱신 — A1(ADR-0010) 비준 반영, "방향 확정 → ADR 비준 완료" 로 SSOT 위임 표기
- [ ] **P14.** `docs/TASKS.md` A1 행 ✅ + `docs/progress/PHASE4.md` 신규 생성 후 A1 엔트리 기록 (Phase 4 작업 이력 시작점)

## 4. 영향 파일

| 파일 | 변경 유형 | Part |
|------|-----------|------|
| `docs/adr/0010-phase4-service-decomposition.md` | 신규 (Proposed → Accepted) | C (P8, P9) |
| `docs/adr/README.md` | INDEX 표 행 추가 | D (P10) |
| `docs/02-architecture.md` | §4-5 정정 + §5 정본 표기 (+ F1 시 §5 DataLayer) | D (P11) |
| `docs/05-data-design.md` | F1 결정에 따라 Notification DB 정정 검토 (미소유 시 후속 정정 대상 표기) | D (P11) |
| `docs/03-requirements.md` | §7-2 Phase 4 Saga 재고 복구 주체 정정 (Order→Product) + ADR-0010 참조 | D (P11) |
| `docs/progress/phase4-design-roadmap.md` | §0/§1 상태 갱신 | E (P13) |
| `docs/TASKS.md` | A1 행 ✅ | E (P14) |
| `docs/progress/PHASE4.md` | 신규 생성 + A1 엔트리 | E (P14) |

코드/매니페스트 변경: **0건**.

## 5. 검증 방법

### 자동

- `bash docs/consistency-hints.sh` exit 0 — ADR 참조 파일 존재 hint 통과
- `./gradlew test` 불필요 (코드 변경 0건) — 생략

### 수동 (ADR 본문 품질 — 모든 항목 통과해야 Status `Accepted` 전환)

**§Context 검증:**
- [ ] **C1**: §4-5↔§5 불일치가 실제 라인 인용으로 기록됨 (P1)
- [ ] **C2**: 5개 도메인 책임/소유 데이터가 실제 패키지 인용 기반 (P2, 추측 아님)
- [ ] **C3**: 현 이벤트/토픽 현황이 §5 토폴로지와 매핑됨 (P3)
- [ ] **C4**: 추가 불일치 F1(Notification DB)·F2(재고 차감 트랜잭션 경계)·F3(Product→Order 캐시 이벤트)가 라인 인용으로 §Context 에 기록됨

**§Decision 검증:**
- [ ] **D1**: 서비스 경계 표 5행 모두 [책임/소유 엔티티/소유 DB] 채워짐, "TBD" 금지. Notification DB 소유 여부 명시 결정 (F1)
- [ ] **D2**: 이벤트 토폴로지 표가 §5 다이어그램(결제/Saga 토픽 4개 producer/consumer)과 1:1 일치. 스키마(필드/버전/파티션 키/retention)는 A3 위임으로 명시, 페이로드 골자/예시 재확정 없음. Product→Order 캐시 이벤트는 "A3 입력"으로 분리 표기 (F3)
- [ ] **D3**: Saga 체인이 서비스 책임 경계(소유 트랜잭션/소비/발행)로 단계별 기술됨, choreography 명시. 재고 차감/예약 경계 충돌(F2) 기록 + A3 위임 명시
- [ ] **D4**: ADR-0001(도메인 경계)·ADR-0002(MSA 진화)·ADR-0009(관측성 surface owner)와 모순 없음
- [ ] **D5**: Phase 4 Exit Criteria coverage matrix 존재 — 4개 Exit Criteria 각각의 owner ADR(ADR-0010 경계만 / 독립배포=A2 / Gateway·JWT=A4 / Saga·로컬캐시=A3) 매핑

**§Alternatives 검증:**
- [ ] **A1**: Alt A(5개)/Alt B(3개) 동일 비교축으로 채워짐, 채택/기각 사유 대칭

**§Consequences 검증:**
- [ ] **CQ1**: "A2/A3/A4 가 본 ADR 의 어느 표를 입력값으로 쓰는가"가 후속 ADR 단위로 도출 가능
- [ ] **CQ2**: 부정적 영향이 구체적 — 분산 트랜잭션 복잡도 + ADR-0009 surface(S1~S8)의 서비스별 owner 영향(per-service `application=` 태그·ServiceMonitor·outbox 계측)이 A2/A3 입력으로 연결

## 6. 완료 조건

- [ ] P1 ~ P14 전부 체크
- [ ] ADR-0010 파일 존재 + Status: Accepted
- [ ] `bash docs/consistency-hints.sh` exit 0
- [ ] §5 수동 체크리스트 (C1~C4, D1~D5, A1, CQ1~CQ2) 전부 통과
- [ ] `02-architecture.md §4-5` 가 5개 서비스로 정정 + ADR-0010 참조 존재
- [ ] `03-requirements.md §7-2` Saga 재고 복구 주체가 Product Service 로 정정 + ADR-0010 참조
- [ ] Notification DB 소유 결정과 `02-architecture.md §5`·`05-data-design.md` 정합 (F1)
- [ ] `phase4-design-roadmap.md`·`TASKS.md`·`PHASE4.md` 동기화
- [ ] PR 생성 + 머지

## 7. 트레이드오프 및 결정 근거

| 결정 | 채택 (계획 시점) | 기각 대안 | 근거 |
|------|------|-----------|------|
| 서비스 경계 | **Alt A — 5개 풀 분해 (§5)** | Alt B — 3개 분리 (§4-5) | §5 가 이미 downstream(DB/토폴로지/Saga/Gateway) 완성 → 재설계 0. Gateway/CQRS 가 User/Product 독립 전제. 서사 강함 |
| ADR 관계 | "Refines ADR-0002" | Supersede ADR-0002 | ADR-0002 일반 전략은 유효, 본 ADR 은 Phase 4 구체화 |
| 산출물 범위 | 경계+계약 골격만 (A1) | 멀티모듈/DB/Gateway 까지 한 ADR | 한 ADR 에 다 담으면 비대해지고 결정 단위가 흐려짐 → A2/A3/A4 분리 |
| §5 다이어그램 처리 | 서비스 토폴로지(5개)는 정본 유지, DataLayer 는 F1(Notification DB 소유) 결정에 따라 보정 | 다이어그램 전면 재작도 | 서비스 5개는 이미 정확 → §4-5 텍스트 정정 + DataLayer 만 F1 결정에 맞춰 조정 |

## 8. 후속 (Out-of-Scope)

- A2 — 멀티모듈 구조 ADR (`common` 경계, 의존 규칙). 편입: L-016a, D-016
- A3 — DB-per-service + 이벤트/Saga 계약 ADR (Flyway 분리, 토픽 스키마/네이밍, retention). 편입: L-008/011, L-020-2
- A4 — Gateway 보안 ADR (RS256, 라우팅, Rate Limit). 편입: 보안 묶음 L-001/002/003/019
- 구현 ①~⑥ 및 D-002 격리 재측정 — 로드맵 §2
