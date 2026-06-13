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
| [0009](./0009-observability-contract-ssot.md) | 관측성 계약 SSOT 결정 | Accepted | 전체 | 02 |
| [0010](./0010-phase4-service-decomposition.md) | Phase 4 서비스 분해 — 5개 마이크로서비스 경계 확정 | Accepted | Phase 4 | 02, 03, 04, 05 |
<!-- INDEX:END -->
