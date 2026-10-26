# PeekCart — Layer 1 의 Phase 1~4 서술 아카이브

> ⚠️ **아카이브 문서**: 2026-10-27 분리 (D-116 P4a, 결정 3-1). Layer 1 문서는 현재 상태만 적으므로, Phase 1~4 의 단계별 서술과 그 시점의 결정 원문을 그대로 옮겼다. 이후 수정하지 않는다.
> 현재 상태는 `docs/01~07` 이 정본이고, 단계별 작업 이력은 `docs/progress/PHASE1.md`~`PHASE4.md` 에 있다. 2026-09-30 의 첫 분리(다이어그램·패키지 구조·ERD)는 [design-archive-phase1-2.md](design-archive-phase1-2.md) 에 있다.

---

## `01-project-overview.md` §4-1 Phase별 환경

| Phase | 환경 | 용도 | 근거 ADR |
|---|---|---|---|
| Phase 1 · Phase 2 | **로컬 Docker Compose** (MySQL/Redis/Kafka 컨테이너) | 구현 및 통합 테스트 | — (기본 로컬 개발 환경) |
| Phase 3 (Task 3-1 ~ 3-3) | **로컬 minikube** (CPU 4 / Memory 8GB) | CI, K8s 매니페스트 초기 작성, 관측성 스택 초기 검증 | ADR-0004 §Context |
| Phase 3 (Task 3-4 ~ ) · Phase 4 | **GCP / GKE** (asia-northeast3-a, e2-standard-4) | 부하 테스트, HPA 검증, MSA 운영 | ADR-0004 |

## `01-project-overview.md` §4-3 환경 진화의 의도

### 4-3. 환경 진화의 의도

Phase 1·2 에서는 Docker Compose 로 MySQL/Redis/Kafka 만 컨테이너화하고 앱은 로컬에서 직접 실행하여 빠른 반복에 집중했습니다. Phase 3 에서 K8s 관측성·HPA 검증이 필요해지면서 로컬 minikube 를 도입(Task 3-1 ~ 3-3)하였고, 부하 테스트 단계(Task 3-4)에서 minikube 의 메모리 한계가 측정 정확도를 제약하는 것이 명시적으로 드러나 GCP/GKE 로 전환했습니다. 결정 과정과 대안 검토는 ADR-0004 에 상세히 기록되어 있습니다 (구 ADR-0003 의 Phase 3 초기 minikube 선택 근거는 ADR-0004 §Context 로 흡수).

## `02-architecture.md` §4-2 서비스 전략 — 단계표

| 단계 | 구조 | 내용 |
| --- | --- | --- |
| Phase 1 | 모놀리식 | 단일 Spring Boot, 4-Layered + DDD, `@TransactionalEventListener` |
| Phase 2 | 성능 개선 | Redis 캐싱, Kafka + Outbox 패턴 도입, 재고 동시성 제어 |
| Phase 3 | 인프라 / 테스트 | K8s 배포, Prometheus + Grafana, 부하 테스트, HPA 검증 |
| Phase 4 | MSA 분리 | Gradle 멀티모듈, Spring Cloud Gateway, 서비스별 DB 분리 |

## `02-architecture.md` §4-3 인프라 전략 — 단계별 환경

- **Phase 1~2**: Docker Compose 로 MySQL/Redis/Kafka 만 컨테이너화, 앱은 로컬 실행
- **Phase 3 (Task 3-1 ~ 3-3)**: 로컬 minikube K8s 로 매니페스트/관측성 스택 초기 구축
- **Phase 3 (Task 3-4 ~ ) · Phase 4**: GCP / GKE 로 전환. 부하 테스트/HPA/MSA 운영

## `03-requirements.md` §6-3 주문 — Phase 1 이벤트 전달

- 주문 생성 → `@TransactionalEventListener`로 결제/알림 도메인에 이벤트 전달 (Phase 1)

## `03-requirements.md` §6-5 알림 — Phase 1

- **Phase 1**: `@TransactionalEventListener`로 이벤트 수신 → Slack Webhook으로 알림 발송

## `03-requirements.md` §7-2 안정성 — 이벤트 처리의 Phase 1

- **이벤트 처리 (Phase별 구분)**
    - Phase 1: `@TransactionalEventListener`로 도메인 간 이벤트 처리 (로컬 트랜잭션)

## `03-requirements.md` §7-2 안정성 — Saga 의 Phase 1

- **Saga 패턴 (Phase별 구분)**
    - Phase 1: `@TransactionalEventListener`로 결제 실패 시 로컬 보상 트랜잭션 처리

## `04-design-deep-dive.md` §9-3 Outbox 패턴 — 도입 시점

Phase 1에서는 `@TransactionalEventListener`만 사용하며 Kafka는 없습니다.
**Phase 2**부터 Kafka + Outbox 패턴을 도입하고 `outbox_events` 테이블을 추가합니다.

## `04-design-deep-dive.md` §9-4 Saga 패턴 — Phase별 구현 방식

Phase 1과 Phase 4의 구현 방식은 다릅니다.

```
Phase 1 (모놀리식):
  @TransactionalEventListener(phase = AFTER_COMMIT)
  → 결제 실패 이벤트 수신 후 로컬 보상 트랜잭션 실행
  → 분산 환경이 아니므로 Saga라고 부르기보다 보상 트랜잭션에 가까움

Phase 4 (MSA):
  Choreography-based Saga
  → order.created → Product 재고 예약 → stock.reservation.result → 결제 시작(reserved=true)
  → payment.failed 이벤트 → Order Service 소비 → 주문 취소
  → order.cancelled 이벤트 → Product Service 소비 → 재고(예약) 복구
  → 각 서비스가 이벤트에 반응해 자율적으로 보상 처리 (see ADR-0012 §D3/§D4 토폴로지 매트릭스)
```

## `04-design-deep-dive.md` §9-6 재고 차감 시점 — 전략 A

재고 차감을 언제 수행하는지는 비즈니스 정합성과 구현 복잡도에 직접 영향을 미치는 핵심 설계 결정입니다.

```
전략 A (채택): 주문 생성 시 즉시 차감
  주문 생성 트랜잭션 내에서 재고 차감 + 주문 저장을 단일 트랜잭션으로 처리
  (Phase 2부터는 + Outbox 저장 포함)
  결제 실패 시 order.cancelled 이벤트 → 재고 복구 (Saga 보상 트랜잭션)
  장점: 이중 주문 방지, 구현 단순
  단점: 결제 실패 → 복구 완료 사이 재고가 일시적으로 묶임

전략 B (미채택): 결제 완료 후 확정 차감
  주문 생성 시 재고 예약(soft lock) → 결제 완료 시 확정 차감
  장점: 재고 묶임 최소화
  단점: 예약 상태 관리, 예약 만료 처리 등 구현 복잡도 증가
```

> **Phase 4 (MSA) 전환 시**: Product Service 가 재고 소유자가 되어 Order 가 단일 트랜잭션으로 직접 차감할 수 없으므로, 전략 A 대신 **Product 예약 모델(전략 B 계열)** 로 전환한다 — `order.created → Product 예약 → stock.reservation.result → 결제`, 실패/취소 시 예약 해제·복구 (see ADR-0012 §D3). 모놀리스(Phase 1~3) 단계에서는 전략 A 유지.

포트폴리오 범위에서는 전략 A를 채택합니다. 재고 묶임 시간을 최소화하기 위해 결제 타임아웃(Toss Payments 기준 15분)을 명시합니다.

**Phase 1 결제 타임아웃 대응**:
Phase 1에서는 단일 인스턴스이므로 `@Scheduled` 기반의 간단한 타임아웃 취소 스케줄러를 구현합니다 (ShedLock 불필요). 주기적으로 `PAYMENT_REQUESTED` 상태가 15분을 초과한 주문을 조회하여 자동 취소 + 재고 복구를 수행합니다. Phase 2에서 ShedLock을 추가하여 분산 환경 대비를 완료합니다.

## `04-design-deep-dive.md` §10-5 @TransactionalEventListener — AFTER_COMMIT 이후 실패 처리

### 10-5. @TransactionalEventListener — AFTER_COMMIT 이후 실패 처리

Phase 1에서 `@TransactionalEventListener(phase = AFTER_COMMIT)`를 사용할 때, 핸들러 내부에서 예외가 발생해도 주문 트랜잭션은 이미 커밋된 상태입니다.

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleOrderCreated(OrderCreatedEvent event) {
    // 여기서 예외 발생 → 주문은 커밋되어 있으나 결제/알림 처리 누락
    // 롤백 불가능
}
```

```
Phase 1 대응:
  - try-catch로 감싸고 실패 로깅 처리
  - 결제/알림 이벤트 처리 실패는 수동 확인 대상

한계이자 Phase 2 전환 근거:
  - 이 문제가 Phase 2에서 Outbox 패턴으로 전환하는 직접적인 이유임
  - Outbox 패턴에서는 이벤트 저장이 주문 트랜잭션 안에서 이루어지므로
    발행 실패 시 retry_count 증가 → 자동 재시도로 해결됨

면접 대응:
  "Phase 1에서 결제 이벤트 처리가 실패하면 어떻게 되나요?"
  → "AFTER_COMMIT 이후이므로 주문 롤백은 불가하며, 로그 기록 후 수동 재처리합니다.
     이 한계가 Phase 2 Outbox 전환의 핵심 근거입니다."
```

## `04-design-deep-dive.md` §10-7 단계별 측정 환경

#### Phase 1·2 — 기능 검증 단계 (로컬 Docker Compose)

- **목적**: 기능 동작 검증 및 통합 테스트. 정식 부하 측정은 수행하지 않음
- **환경**: 로컬 Docker Compose (MySQL / Redis / Kafka 컨테이너) + 앱 로컬 실행
- **수치 기록**: 단위/통합 테스트 통과 여부 중심. TPS·p99 등의 성능 수치는 Phase 3 에서 수집
- **선택 근거**: 기본 로컬 개발 환경 (별도 ADR 없음)

#### Phase 3 초기 (Task 3-1 ~ 3-3) — K8s 도입 (로컬 minikube)

- **목적**: CI, K8s 매니페스트 작성, kube-prometheus-stack 구축의 반복 사이클 확보
- **환경**: 로컬 minikube, CPU 4 / Memory 8GB (macOS)
- **수치 기록**: 기능 검증 중심. 본격적인 부하 측정은 Task 3-4 에서 GKE 로 수행
- **선택 근거**: ADR-0004 §Context (Phase 3 Task 3-1~3-3 minikube 선택 근거 포함. 구 ADR-0003 은 Deprecated 처리되어 내용이 ADR-0004 로 흡수됨)

#### Phase 3 — 부하 테스트 단계 (GCP / GKE)

- **목적**: 상품 조회 TPS(캐싱 전/후), 동시 주문 정합성(1,000 VUser), HPA 스케일아웃, Kafka Consumer Lag 의 실측
- **환경**: GCP GKE Standard (asia-northeast3-a), 노드 e2-standard-4 × 1 (일반), 부하 발생기는 같은 zone 의 별도 Compute Engine VM
- **수치 기록 방침**: 각 시나리오마다 (a) 환경 스펙 (b) 도구 버전·설정 (c) 시나리오 파라미터 (d) baseline 수치 (e) 개선 후 수치 (f) 개선 비율을 함께 기록
- **선택 근거**: ADR-0004 (minikube 의 메모리/네트워크 한계가 측정 정확도를 제약하는 점이 직접 원인)

> Phase 1·2 측정값과 Phase 3 측정값은 환경이 다르므로 동일 축 비교 대상이 아닙니다. Phase 3 의 캐싱 전/후·HPA 전/후 비교는 모두 **동일 GKE 환경 내에서** 수행합니다.

## `07-roadmap-portfolio.md` §16 Phase 1~4 로드맵

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
