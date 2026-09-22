# Architecture Decision Records

> PeekCart 프로젝트의 주요 아키텍처 결정 이력을 기록합니다.
> 형식: [Michael Nygard ADR](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)

## 원칙

- ADR은 **immutable**. 한 번 작성된 본문은 수정하지 않습니다.
- 결정이 바뀌면 **새 ADR을 작성**하고 기존 ADR의 Status를 변경합니다. Status 변경은 항상 허용됩니다.
- 결정의 일부만 무효화될 경우 `Partially Superseded by ADR-XXXX`를 사용하고, Status 줄 바로 아래에 **무효화된 범위**를 명시합니다 (예: ADR-0005 의 monitoring 범위가 ADR-0006 으로 전환).
- **본문 정정 예외 (Update Log)**: 사실 오류(파일명, Phase 귀속, 수치 등) 가 발견된 경우 본문을 직접 수정할 수 있습니다. 단 다음 두 조건을 모두 충족해야 합니다.
  - ADR 말미에 `## Update Log` 절을 추가하여 변경 일자, 커밋 해시, 변경 사유를 기록한다
  - 커밋 메시지에 `fix(adr):` 접두사를 사용하여 일반 ADR 작성/Status 변경 커밋과 구분한다
- 의사결정의 트레이드오프 변경, 대안 추가, Consequences 재해석은 본문 정정이 아니라 **새 ADR 작성** 사유입니다 (Update Log 로 우회 금지).
- Layer 1 설계 문서(01~07)는 **현재 상태(What)** 만 기술하고, **결정 근거(Why)** 는 이 ADR에 기록 후 참조합니다.
- 새 ADR 작성 시 `template.md`를 복사하여 `NNNN-{slug}.md` 형식으로 저장합니다.

## 인덱스

> 신규 ADR 추가 시 이 표의 맨 아래에 행을 추가합니다.
> Status 컬럼 값: `Proposed` · `Accepted` · `Deprecated` · `Superseded` · `Partially Superseded`

<!-- INDEX:BEGIN -->
| # | 제목 | Status | Phase | 관련 Layer 1 문서 |
|---|------|--------|-------|-------------------|
| [0001](./0001-layered-ddd-architecture.md) | 4-Layered + DDD 아키텍처 채택 | Accepted | 전체 | 02, 04 |
| [0002](./0002-monolith-to-msa-evolution.md) | 모놀리식 → MSA 단계적 진화 전략 | Accepted | 전체 | 02, 07 |
| [0003](./0003-phase3-initial-minikube.md) | Phase 3 초기 K8s 환경 — 로컬 minikube 채택 | Deprecated | Phase 3 Task 3-1~3-3 | (ADR-0004 흡수) |
| [0004](./0004-phase3-gcp-gke-migration.md) | Phase 3 GCP/GKE 환경 전환 | Accepted | Phase 3+ | 01, 04, 07 |
| [0005](./0005-kustomize-base-overlays-structure.md) | Kustomize base/overlays 매니페스트 구조 | Partially Superseded | Phase 3+ | 02 |
| [0006](./0006-monitoring-stack-environment-separation.md) | Monitoring 스택 환경 분리 (base 에서 제외) | Accepted | Phase 3 Task 3-4+ | 02 |
| [0007](./0007-yaml-profile-merge-principle.md) | YAML 프로파일 병합 원칙 — 연결 정보 vs 동작 정책 | Accepted | 전체 | CLAUDE.md |
| [0008](./0008-outbox-trace-context-propagation.md) | Outbox Trace Context Propagation | Accepted | Phase 3 잔여 부채 | 02, 05 |
| [0009](./0009-observability-contract-ssot.md) | 관측성 계약 SSOT 결정 | Partially Superseded | 전체 | 02 |
| [0010](./0010-phase4-service-decomposition.md) | Phase 4 서비스 분해 — 5개 마이크로서비스 경계 확정 | Accepted | Phase 4 | 02, 03, 04, 05 |
| [0011](./0011-phase4-multimodule-structure.md) | Phase 4 Gradle 멀티모듈 구조 — common + 관측성 + 5개 서비스 | Partially Superseded | Phase 4 | 02 |
| [0012](./0012-phase4-db-event-saga-contract.md) | Phase 4 DB-per-service + 이벤트/Saga 계약 | Partially Superseded | Phase 4 | 02, 03, 04, 05 |
| [0013](./0013-phase4-gateway-security.md) | Phase 4 Gateway 보안 — RS256 + Spring Cloud Gateway + Reuse Detection | Accepted | Phase 4 | 02, 03, 04, 05 |
| [0014](./0014-transitional-auth-module.md) | 전환기 인증 검증 공유 모듈 — peekcart-common-auth (게이트웨이 이전) | Accepted | Phase 4 | 02 |
| [0015](./0015-observability-per-service-contract.md) | 관측성 per-service 계약 — 5서비스 분리 완료 상태로 SSOT 위치·검증 정정 (ADR-0009 부분 무효화 · ADR-0019/0024 로 부분 무효화) | Partially Superseded | Phase 4 | 02 |
| [0016](./0016-reservation-and-payment-table-model.md) | 재고 예약(별도 stock_reservations 테이블) + Payment 취소 테이블 모델 — ADR-0012 D1/D3 재기록 | Accepted | Phase 4 | 05 |
| [0017](./0017-gateway-signed-internal-token.md) | Gateway 서명 내부 토큰 — header-trust 를 평문 헤더에서 서명 assertion 으로 격상 (defense-in-depth) | Accepted | Phase 4 | 02, 04 |
| [0018](./0018-compensation-refund-contract.md) | 보상/환불 트리거 계약 — 감지 3지점 → Payment 환불 실행 → 원장 종결 (ADR-0012 D3 ④ 구체화) | Partially Superseded | Phase 4 | 03, 04, 05 |
| [0019](./0019-alert-expression-pinning.md) | alert 식 정본 고정 — PromQL 정적 lint 를 라벨 invariant 에서 식 동일성으로 격상 (ADR-0015 부분 무효화) | Accepted | Phase 4 | 02 |
| [0020](./0020-dlq-replay-contract.md) | DLQ replay 계약 — 재발행 보장·발행 권한 예외·좌표 유효성·종결 축 분리 (ADR-0012 D1/D4 · ADR-0018 producer 규약 부분 무효화 · ADR-0021/0022 로 부분 무효화) | Partially Superseded | Phase 4 | 02, 04 |
| [0021](./0021-dlq-replay-correlation-anchor.md) | DLQ replay 재실패 상관 — 대조 축을 `record_kind` 에서 원장 앵커 + payload digest 로 (ADR-0020 D5-4 부분 무효화) | Accepted | Phase 4 | 02, 05 |
| [0022](./0022-replay-entrypoint-rollout-and-drain.md) | replay 개시 진입점의 개방 절차 — 도달 경로 · drain/롤백 계약 · `PUBLISH_UNKNOWN` (ADR-0020 §D6-1/§D6-2b/§D6-4 부분 무효화) | Accepted | Phase 4 | 02, 04, 05 |
| [0023](./0023-payment-approval-reconciliation.md) | 결제 승인 경계 계약 — 승인 원장 + 멱등키 + 조회 기반 reconciliation (D-020, ADR-0018 D2/D3/D5 를 승인 경로로 확장) | Accepted | Phase 4 | 03, 04 |
| [0024](./0024-observability-canonical-with-infra.md) | 관측성 canonical 집합 = 도메인 5 + 인프라 1 — gateway 편입과 세 집합(태그/Service/SM)의 분리 (ADR-0015 부분 무효화) | Accepted | Phase 4 | 02 |
| [0025](./0025-inventory-concurrency-control.md) | 재고 동시성 제어 — 분산 락 제거, `@Version` 단일 수단 + jitter 재시도 (D-025, 흡수된 L-007) | Accepted | Phase 4 | 02, 04, 05 |
| [0026](./0026-product-detail-stock-cache-boundary.md) | 상품 상세 재고 캐시 경계 — 재고 전용 짧은 TTL 캐시, 쓰기 경로 무효화 없음 (D-026) | Accepted | Phase 4 | 04, 05 |
| [0027](./0027-mysql-cpu-limit-baseline.md) | base MySQL CPU 상한 = 2000m — 공통 읽기/쓰기 천장의 해제 (D-029) | Accepted | Phase 4 | 02 |
<!-- INDEX:END -->
