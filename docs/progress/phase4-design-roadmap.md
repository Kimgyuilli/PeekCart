# Phase 4 설계·실행 로드맵 — MSA 분리

> 작성: 2026-06-13 · Phase 4 진입 전 부채 해소(버킷 1) 완결 후, MSA 분리 착수 시점.
> 입력: `docs/07-roadmap-portfolio.md §16`(Phase 4 범위 6항목) · `docs/02-architecture.md §4~5`(서비스 전략·Phase 4 다이어그램) · `phase4-prep-debt-roadmap.md §3`(버킷 2 이관 부채).
> 이 문서는 **설계·실행 로드맵**(ADR 시퀀싱 + 구현 순서 + 부채 편입)이다. 결정 근거(Why)는 각 ADR, 작업 이력(When)은 `PHASE4.md`(착수 시 생성).
> 항목이 모두 ADR 비준/구현 완료되면 본 문서의 참조 상태를 함께 정리한다.

---

## 0. 서비스 경계 결정 (2026-06-13 확정)

기존 설계 문서 내 **서비스 경계 자기모순**을 먼저 해소했다.

| 위치 | 경계 |
|---|---|
| `02-architecture.md §4-5` "MSA 분리 대상" | 3개만 나열 — Order · Payment · Notification |
| `02-architecture.md §5` Phase 4 다이어그램 | 5개 전부 독립 서비스 + 서비스별 DB + 이벤트 토폴로지 + Saga 체인 |

**결정**: **§5(5개 풀 분해)를 정본**으로 채택. User · Product · Order · Payment · Notification 5개 독립 서비스 + 서비스별 DB. → **ADR-0010 에서 비준 완료 (Accepted, 2026-06-14)**.

- **근거**: §5 다이어그램이 이미 DB-per-service·Kafka 토픽 토폴로지·Saga 체인·Gateway 라우팅을 5개 기준으로 완성. downstream 재설계 없음. Gateway도 어차피 User/Product 라우팅 필요. 포트폴리오 "MSA 역량" 서사도 풀 분해가 강함.
- **§4-5는 불완전·드리프트된 목록**으로 판단 → ADR-0010 비준과 함께 정정 완료.
- **비준 시 추가 정합 (ADR-0010 §C4)**: F1(Notification DB 소유 확정), F2(재고 차감 트랜잭션 경계 충돌 — A3 위임), F3(Product→Order `product.updated` 캐시 이벤트 필요 — A3), `03-requirements §7-2` Saga 재고 복구 주체 Order→Product 정정.
- **SSOT 는 ADR-0010** (경계·이벤트 토폴로지·Saga 골격). 본 표는 로드맵 차원의 방향 기록.

---

## 1. 설계 단계 — ADR 먼저 (코드 0줄)

> 원칙(CLAUDE.md §문서 레이어): 대안 비교가 있는 결정은 ADR을 먼저 쓰고, Layer 1 문서는 `(see ADR-NNNN)`로 참조. A1이 나머지의 입력값(SSOT)이라 **반드시 선행**. A2~A4는 A1 확정 후 병렬 검토 가능.

| 순서 | ADR | 내용 | 편입 부채 |
|---|---|---|---|
| **A1** ✅ | **ADR-0010 서비스 분해** (Accepted) | §5 비준 + §4-5 정정. 5개 서비스 bounded context·책임·소유 데이터·이벤트 토폴로지(토픽 4개)·Saga 체인 명문화 + F1/F2/F3 정합. 이후 전부의 SSOT | — |
| A2 ✅ | 멀티모듈 구조 (ADR-0011) | `common` + `peekcart-common-observability`(ADR-0009 선결정) + 5개 서비스 모듈. class-level common 경계, 서비스↔서비스 의존 금지(위반 검출 필수), 빌드/테스트/이미지 계약 | L-016a(digest 고정), D-016(image promotion 자동화) |
| A3 ✅ | DB-per-service + 이벤트/Saga 계약 (ADR-0012) | 서비스별 Flyway 독립, envelope 버저닝·파티션 키, 재고 예약 Saga(`stock.reservation.result` 신규 토픽)·실패 경로, retention=멱등성 창 상한(DLQ 창 포함) | L-008/L-011, L-020-2 |
| A4 | Gateway 보안 | RS256 전환 + 시크릿 저장소(KMS/Vault) + 라우팅 + Rate Limit + Reuse Detection(`family_id`) + 인증 실패 관측성 | 보안 묶음 L-001/L-002/L-003/L-019 |

---

## 2. 구현 단계 — PR 단위 시퀀싱 (버킷 1 방식 그대로)

> 각 항목은 선행 ADR 비준 후 착수. PR 1개 = 작업/부채 1묶음. 착수 시 D- 승격 또는 task 흡수.

| 순서 | 작업 (`07 §16`) | 선행 ADR | 편입 부채 |
|---|---|---|---|
| ① | Gradle 멀티모듈 전환 (common 분리, 서비스별 모듈) | A2 | L-016a, D-016 |
| ② | 서비스별 DB 분리 (Flyway 독립, 스냅샷 저장 패턴) | A3 | L-008/L-011 |
| ③ | Spring Cloud Gateway (라우팅·JWT·Rate Limit) | A4 | 보안 묶음 |
| ④ | Choreography Saga (payment.failed → 주문취소 → 재고복구) | A3 | — |
| ⑤ | CQRS 로컬 캐시 (Product 변경 이벤트 구독, Order 내 캐시) | A3 | L-006 Redis fallback (L-005 선결 완료) |
| ⑥ | Cursor 페이지네이션 (주문 조회 전환 검토) | — | — |
| — | D-002 격리 재측정 (2차 병목 MySQL풀/Redis락 분리) | — | D-002 (Order Service 분리 후) |

**Exit Criteria** (`07 §16` 인용):
- 모든 서비스 독립 배포 및 정상 동작 확인
- Saga 보상 트랜잭션 플로우 검증 (결제 실패 → 주문 취소 → 재고 복구)
- Gateway 라우팅 및 JWT 인증 정상 동작
- 서비스 간 직접 호출 없이 이벤트 + 로컬 캐시로 데이터 조합 확인

---

## 3. 버킷 3 게이트 (변동 없음)

L-007(주문 생성 락⊃트랜잭션) · L-013(상태전이 `@Version` 부재)은 "17편 후속 부하 세션" 실측 게이트 유지. 측정 결과에 따라 모놀리스 단계 선제 승격 또는 Phase 4 분리 시 자연 해소/필수 승격. 상세: `phase4-prep-debt-roadmap.md §4`.

---

## 4. 다음 단계

1. ~~**A1 (ADR-0010 서비스 분해)** 착수~~ ✅ 완료 (Accepted 2026-06-14) — §5 비준 + §4-5 정정 + 5개 서비스 계약 + F1/F2/F3 정합.
2. ~~A2(ADR-0011)~~ ✅ · ~~A3(ADR-0012)~~ ✅ 완료. 다음 **A4(Gateway 보안)** — 마지막 설계 ADR. 이후 구현 ①~⑥ PR 단위.
3. 버킷 2 D-016은 ①(멀티모듈/배포 자동화)에 편입, D-002는 분리 완료 후 재측정.
