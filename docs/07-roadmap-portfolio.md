## 15. 포트폴리오 어필 포인트

### 핵심 스토리라인

> "재고 경합은 `@Version` 낙관적 락과 jitter 재시도로 처리합니다. 재고 경로의 Redis 분산 락은 제거했습니다 (see ADR-0025).
Outbox 패턴으로 이벤트 유실을 방지하고, processed_events로 중복 소비까지 처리했습니다.
DLQ 원장과 표준화된 에러 핸들링 체계로 장애 시나리오에 대한 대응력을 갖추었습니다.
GKE 에서 상품 하나에 초당 170건 주문을 몰아도 음수 재고와 품목별 재고 불일치가 0이었고,
주문 부하가 시작되고 42초 뒤 order-service HPA 가 Pod 를 1개에서 3개로 늘리기로 결정해 93초 뒤 3개 모두 Ready 가 됐습니다."
>

근거: 정합성은 `docs/progress/evidence/d078-gke-20261024.md`(4 run)·`d078-gke-20261025.md`(7 run), HPA 는 `d078-gke-20261024.md` 의 S4 다.

### 기술별 어필 포인트

| 기술 | 어필 내용 |
| --- | --- |
| 4-Layered + DDD | 비즈니스 로직이 Entity 안에 응집된 설계, JPA 절충안 적용 근거 설명 가능 |
| 단계적 진화 | 모놀리식의 `@TransactionalEventListener` 에서 Kafka + Outbox, 서비스 분리로 옮긴 이유 설명 가능 (이력: `docs/progress/PHASE1.md`~`PHASE4.md`) |
| Outbox 패턴 | Polling vs CDC 트레이드오프 인지, retry_count/FAILED 상태로 발행 실패 처리 |
| DLQ 전략 | Consumer 재시도 → DLQ 토픽 → 서비스별 DLQ 원장 → alert → 관리자 전이·replay 체계 |
| Kafka 멱등성 | processed_events로 중복 처리 방지, 파티션 키 전략으로 이벤트 순서 보장 |
| Saga 패턴 | Choreography Saga 와 보상·환불 수렴(see ADR-0018), 주문 상태 전이도로 흐름 설명 가능 |
| 재고 예약 모델 | 예약 원장·lease·승인 보호(fence)와 타임아웃 회수, ShedLock으로 분산 환경 중복 실행 방지 |
| 에러 핸들링 | 도메인별 에러 코드 체계, 표준 에러 응답 포맷, 예외 계층 구조 설계 |
| 테스트 전략 | 레이어별 테스트 유형/도구/커버리지 목표, Testcontainers 통합 테스트 |
| Redis | 조회 캐시(fail-open)와 토큰 deny·rate limit(fail-closed)의 장애 정책 분리 |
| K8s HPA | 주문 부하 중 Pod 자동 확장 Grafana 캡처 (`loadtest/reports/2026-10-24/grafana/`) |
| CQRS 로컬 캐시 | MSA 동기 호출 문제를 이벤트 기반 로컬 캐시로 해결, 기술 선택 근거 설명 가능 |
| Slack Webhook | 실제 동작하는 알림 발송 데모 |
| 모노레포 | "실무에서는 멀티레포가 적합하나, 포트폴리오 가시성과 공유 효율을 위해 모노레포 채택" 명시 |

---

## 16. 개발 로드맵

Phase 1~4 의 순서표와 Exit Criteria 원문은 [Layer 1 이력 아카이브](progress/archive/layer1-history-phase1-4.md)로 옮겼다(D-116). 네 단계는 모두 끝났고 작업 이력은 `docs/progress/PHASE1.md`~`PHASE4.md` 에 있다.

### Phase 5 — 수요 기반 (로드맵 없음, 2026-09-22~2026-10-27, 완료)

순서표 없이 진행한 첫 단계다. Exit Criteria 가 아니라 사용자 결정으로 2026-10-27 에 닫았고, 열린 행은 그대로 Phase 6 으로
넘겼다(see ADR-0047). 작업 이력과 종결 엔트리는 `docs/progress/PHASE5.md` 에 있다.

### Phase 6 — 수요 기반 (로드맵 없음, 2026-10-27~)

**사전 순서표가 없는 단계다.** Phase 5 와 같이 작업은 필요하다고 판단한 시점에 추가하고, 단계는 사용자 결정으로
닫는다(see ADR-0047). 따라서 이 문서에는 Phase 6 의 작업 목록도 Exit Criteria 도 적지 않는다. 적으면 그것이
로드맵이 된다.

- **현황·백로그**: `docs/TASKS.md §개발 부채 / 작업` (D- 단일 축)
- **작업 이력·결정**: `docs/progress/PHASE6.md`

---

*PeekCart 설계 통합 문서 — 현재 코드 기준 (2026-10-27)*
