## 15. 포트폴리오 어필 포인트

### 핵심 스토리라인

> "주문 폭주 시 동기 처리로는 재고 정합성 문제가 발생했고, Kafka + Redis 분산 락을 도입해 해결했습니다.
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

### Phase 1 — 모놀리식 구현

| 순서 | 작업 | 설명 |
| --- | --- | --- |
| 1 | 프로젝트 초기 설정 | Spring Boot 프로젝트 생성, Flyway · Docker Compose · 공통 예외/응답 구조 |
| 2 | User 도메인 | 회원가입/로그인, JWT 인증 (DB 저장 + Redis 블랙리스트 + Grace Period), RBAC |
| 3 | Product 도메인 | 상품 CRUD (관리자), 상품 목록/상세 조회, 카테고리 |
| 4 | Order 도메인 | 장바구니, 주문 생성 (재고 즉시 차감), 주문 상태 전이, `@TransactionalEventListener` |
| 5 | Payment 도메인 | Toss Payments 연동, 결제 승인/실패, 웹훅 수신 처리 |
| 6 | Notification 도메인 | `@TransactionalEventListener` → Slack Webhook 알림 발송 |
| 7 | 결제 타임아웃 처리 | `@Scheduled` 기반 타임아웃 자동 취소 + 재고 복구 스케줄러 (단일 인스턴스) |

**주요 산출물**: CRUD API, 결제 플로우, 웹훅 수신 처리, DDD 도메인 모델, 주문 상태 전이도

**Exit Criteria**:

- 모든 도메인 CRUD API 정상 동작 확인 (Swagger UI 기준)
- 주문 → 결제 → 알림 전체 플로우 정상 처리
- 주문 상태 전이 검증 완료 (결제 성공/실패/타임아웃 시나리오)
- 결제 타임아웃 스케줄러 동작 확인

### Phase 2 — 성능 개선

| 순서 | 작업 | 설명 |
| --- | --- | --- |
| 1 | Redis 캐싱 | 상품 목록/상세 캐싱, Cache Aside 패턴 |
| 2 | Redis 분산 락 | 재고 동시성 제어 (분산 락 + DB 낙관적 락 이중 방어) |
| 3 | Kafka + Outbox 도입 | Outbox 테이블 추가 (Flyway 마이그레이션), Polling 스케줄러, Kafka Producer |
| 4 | Consumer 멱등성 | processed_events 테이블 추가, 중복 소비 방지 로직 |
| 5 | DLQ 구성 | Consumer 재시도 정책, DLQ 토픽 설정, Slack 알림 |
| 6 | ShedLock | 타임아웃 스케줄러에 ShedLock 적용 (Phase 3~4 분산 환경 대비) |

**주요 산출물**: Redis 캐싱 구현체, Outbox 구현체, Kafka 파티션 설정, DLQ 토픽, 캐싱 적용 완료 (TPS 비교는 Phase 3에서 k6 로컬 실행으로 측정)

**Exit Criteria**:

- Redis 캐싱 적용 후 통합 테스트에서 캐시 적중/무효화 동작 확인
- 동시 주문 테스트 시 오버셀링 0건
- Outbox → Kafka 이벤트 발행 정상 동작
- DLQ 토픽으로 실패 메시지 라우팅 확인

### Phase 3 — 인프라 / 테스트

| 순서 | 작업 | 설명 |
| --- | --- | --- |
| 1 | GitHub Actions CI | 빌드 · 테스트 · Docker 이미지 빌드 파이프라인 |
| 2 | K8s 배포 | Kustomize base/overlays 매니페스트 작성, 환경별 배포 (환경·ADR: `docs/01-project-overview.md` §4) |
| 3 | kube-prometheus-stack | Prometheus + Grafana 구축, 대시보드 설정 |
| 4 | 부하 테스트 | nGrinder + k6 시나리오 실행, TPS 측정 |
| 5 | HPA 검증 | `peekcart` Deployment HPA 설정 (Phase 4 MSA 분리 이후 Order Service HPA 로 재정의 예정), 부하 테스트 중 자동 스케일아웃 검증 |

**주요 산출물**: GitHub Actions CI 파이프라인, Grafana 대시보드, 성능 테스트 리포트, Kustomize 기반 K8s 매니페스트 (`k8s/base/` + `k8s/overlays/{minikube,gke}/`)

> **환경 전환**: Phase 3 전반(Task 3-1 ~ 3-3)은 로컬 minikube 에서 수행되었고, 부하 테스트(Task 3-4)부터 GCP/GKE 환경으로 전환합니다. minikube 에서 측정 정확도를 확보하기 어렵다는 점이 드러난 것이 직접 원인이며, 결정 근거와 대안 검토는 ADR-0004 를 참고하세요. 환경 요약은 `docs/01-project-overview.md` §4 에 있습니다.

**Exit Criteria**:

- K8s에 모든 서비스 정상 배포 확인
- Grafana 대시보드에서 API 응답시간/에러율/Kafka Lag 모니터링 확인
- nGrinder 부하 테스트 리포트 완성 (캐싱 전/후 TPS 비교 수치 포함)
- HPA 동작 확인 (Pod 자동 증설 Grafana 스크린샷)

### Phase 4 — MSA 분리

| 순서 | 작업 | 설명 |
| --- | --- | --- |
| 1 | Gradle 멀티모듈 전환 | common 모듈 분리, 서비스별 모듈 생성 |
| 2 | 서비스별 DB 분리 | Flyway 서비스별 독립 마이그레이션, 스냅샷 저장 패턴 적용 |
| 3 | Spring Cloud Gateway | 라우팅, JWT 인증 필터, Rate Limiting |
| 4 | Choreography Saga | payment.failed → 주문 취소 → 재고 복구 이벤트 체인 |
| 5 | CQRS 로컬 캐시 | Product 변경 이벤트 구독, Order Service 내 상품 정보 캐시 |
| 6 | Cursor 페이지네이션 | 주문 조회 Cursor 기반 전환 검토 |

**주요 산출물**: MSA 구성도, Saga 시퀀스 다이어그램, 서비스별 K8s Deployment, CQRS 캐시 구현

**Exit Criteria**:

- 모든 서비스 독립 배포 및 정상 동작 확인
- Saga 보상 트랜잭션 플로우 검증 (결제 실패 → 주문 취소 → 재고 복구)
- Gateway 라우팅 및 JWT 인증 정상 동작
- 서비스 간 직접 호출 없이 이벤트 + 로컬 캐시로 데이터 조합 확인

### Phase 5 — 수요 기반 (로드맵 없음, 2026-09-22~)

**사전 순서표가 없는 단계다.** Phase 1~4 로 계획된 로드맵은 소진됐고, 이후 작업은
필요하다고 판단한 시점에 추가한다. 따라서 이 문서에는 Phase 5 의 작업 목록도
Exit Criteria 도 적지 않는다 — 적는 순간 로드맵이 되기 때문이다.

- **현황·백로그**: `docs/TASKS.md §개발 부채 / 작업` (D- 단일 축)
- **작업 이력·결정**: `docs/progress/PHASE5.md`

---

*PeekCart 설계 통합 문서 v5.0 — 멀티 에이전트 리뷰 (Tier 1~3) 반영 완료*
