## 15. 포트폴리오 어필 포인트

### 핵심 스토리라인

> "재고 경합은 `@Version` 낙관적 락과 jitter 재시도로 처리합니다. 재고 경로의 Redis 분산 락은 제거했습니다 (see ADR-0025).
Outbox 패턴으로 이벤트 유실을 방지하고, processed_events로 중복 소비까지 처리했습니다.
DLQ 전략과 표준화된 에러 핸들링 체계로 장애 시나리오에 대한 대응력을 갖추었습니다.
nGrinder 부하 테스트 결과 TPS X → Y로 개선, 오버셀링 0건을 달성했으며
K8s HPA가 `peekcart` Pod를 1개 → 3개로 자동 확장해 안정성을 유지했습니다 (Phase 4 MSA 분리 이후 Order Service HPA 로 재정의 예정)."
>

### 기술별 어필 포인트

| 기술 | 어필 내용 |
| --- | --- |
| 4-Layered + DDD | 비즈니스 로직이 Entity 안에 응집된 설계, JPA 절충안 적용 근거 설명 가능 |
| Phase별 진화 | Phase 1 (`@TransactionalEventListener`) → Phase 2 (Kafka + Outbox) 단계적 도입 이유 설명 가능 |
| Outbox 패턴 | Polling vs CDC 트레이드오프 인지, retry_count/FAILED 상태로 발행 실패 처리 |
| DLQ 전략 | Consumer 재시도 → DLQ 토픽 → 모니터링 → 수동 재처리 체계 |
| Kafka 멱등성 | processed_events로 중복 처리 방지, 파티션 키 전략으로 이벤트 순서 보장 |
| Saga 패턴 | Phase 1/4 구현 방식 차이, 주문 상태 전이도로 보상 트랜잭션 흐름 설명 가능 |
| 재고 차감 전략 | 전략 A/B 트레이드오프, 타임아웃 자동 복구, ShedLock으로 분산 환경 중복 실행 방지 |
| 에러 핸들링 | 도메인별 에러 코드 체계, 표준 에러 응답 포맷, 예외 계층 구조 설계 |
| 테스트 전략 | 레이어별 테스트 유형/도구/커버리지 목표, Testcontainers 통합 테스트 |
| Redis | 캐싱/분산 락/블랙리스트/Grace Period 역할 분리 설계 |
| K8s HPA | 부하 테스트 중 Pod 자동 확장 Grafana 스크린샷 첨부 |
| CQRS 로컬 캐시 | MSA 동기 호출 문제를 이벤트 기반 로컬 캐시로 해결, 기술 선택 근거 설명 가능 |
| Slack Webhook | 실제 동작하는 알림 발송 데모 |
| 모노레포 | "실무에서는 멀티레포가 적합하나, 포트폴리오 가시성과 공유 효율을 위해 모노레포 채택" 명시 |

---

## 16. 개발 로드맵

Phase 1~4 의 순서표와 Exit Criteria 원문은 [Layer 1 이력 아카이브](progress/archive/layer1-history-phase1-4.md)로 옮겼다(D-116). 네 단계는 모두 끝났고 작업 이력은 `docs/progress/PHASE1.md`~`PHASE4.md` 에 있다.

### Phase 5 — 수요 기반 (로드맵 없음, 2026-09-22~)

**사전 순서표가 없는 단계다.** Phase 1~4 로 계획된 로드맵은 소진됐고, 이후 작업은
필요하다고 판단한 시점에 추가한다. 따라서 이 문서에는 Phase 5 의 작업 목록도
Exit Criteria 도 적지 않는다 — 적는 순간 로드맵이 되기 때문이다.

- **현황·백로그**: `docs/TASKS.md §개발 부채 / 작업` (D- 단일 축)
- **작업 이력·결정**: `docs/progress/PHASE5.md`

---

*PeekCart 설계 통합 문서 v5.0 — 멀티 에이전트 리뷰 (Tier 1~3) 반영 완료*
