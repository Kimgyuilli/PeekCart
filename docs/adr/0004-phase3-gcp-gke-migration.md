# ADR-0004: Phase 3 GCP/GKE 환경 전환

- **Status**: Accepted
- **Date**: 2026-04-06
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 3 (부하 테스트 이후) · Phase 4

## Context

> 본 §Context 는 2026-04-07 Deprecated 처리된 ADR-0003 의 내용을 흡수합니다. ADR-0003 은 회고적 재구성이었고 `Decided: Phase 0` 와 본문의 "K8s 는 Phase 3 부터 도입할 계획" 서술 간 내적 모순이 지적되어 폐기되었습니다. Phase 3 초기 minikube 선택의 근거를 본 절에 통합 기록합니다.

### Phase 3 Task 3-1~3-3 (minikube) 의 선택 근거

Phase 1·2 는 Docker Compose 로 MySQL/Redis/Kafka 를 띄우고 Spring Boot 를 로컬 실행하는 구조였습니다(`docker-compose.yml`). K8s 는 Phase 3 부터 도입할 계획이었고, **Phase 3 에서 K8s 를 처음 도입할 때의 초기 환경**은 다음 제약 하에 선택되었습니다.

- **비용 최소화** — Phase 3 초기 시점에 클라우드 크레딧 미확보
- **빠른 반복 사이클** — K8s 매니페스트 시행착오는 rebuild → apply → describe 왕복이 필수. 클라우드 왕복은 생산성 저하
- **오프라인 개발 가능성** — 외부 의존 없이 재현 가능한 환경
- **정확한 측정 요구의 지연** — Task 3-1~3-3 (CI, 매니페스트, 관측성 스택) 은 "측정 정확도" 보다 "반복 속도" 가 중요

이 제약들 하에서 **로컬 minikube (CPU 4 / Memory 8GB)** 가 선택되었습니다. kind 는 Addon 생태계에서 minikube 대비 열세, 클라우드 K8s 는 반복 비용 문제로 각각 기각되었습니다. `k8s/overlays/minikube/` 는 Phase 3 이후에도 **로컬 개발용 검증 환경**으로 계속 유효합니다 (ADR-0005).

### Phase 3 Task 3-4 (부하 테스트) 진입 시 드러난 한계

Phase 3 Task 3-1~3-3 (CI, minikube 배포, kube-prometheus-stack) 까지는 위 minikube 환경에서 정상 완료되었습니다. 그러나 Task 3-4 (부하 테스트) 와 Task 3-5 (HPA 검증) 를 준비하면서 minikube 환경의 한계가 명시적으로 드러났습니다.

**드러난 한계**:
1. **메모리 예산** — minikube 8GB 안에 앱(1.2GB) + MySQL/Redis/Kafka(~2GB) + kube-prometheus-stack(~1.2GB) + 시스템(~1GB) 이 공존. 여유 1.5~2GB. HPA max=3 발동 시 추가 1~2GB 필요 → eviction 위험
2. **부하 도구 병목** — nGrinder(controller+agent) ~3GB + JMeter ~1.5GB 가 host 16GB에 공존. macOS/IDE/브라우저 고려 시 swap 발생 → **부하 발생 측이 병목이 되어 측정값 왜곡**
3. **시나리오 간 간섭** — Prometheus 시계열 누적 + 부하 트래픽 동시 수집 시 Prometheus OOM 가능성. 측정 데이터 손실 시 부하 테스트 전체 무효화
4. **JMeter 1,000 VUser 동시성 한계** — host OS file descriptor/ephemeral port 제약으로 실효 동시성이 200~400 수준에 그쳐, 동시 주문 정합성 시나리오의 false negative 위험

Phase 3 Exit Criteria의 "nGrinder 부하 테스트 리포트 (캐싱 전/후 TPS 비교 수치)" 는 **측정의 정확성**이 핵심인데, 위 한계들은 수치 신뢰도를 직접 위협합니다.

동시에 2026-04-06 기준 GCP 신규 계정 크레딧 ₩453,008 (~$326) 을 확보하여 **비용 제약이 해소**되었습니다.

## Decision

**Phase 3 Task 3-4 (부하 테스트) 부터 운영 환경을 GCP/GKE로 전환**한다.

- **클러스터**: GKE Standard, `asia-northeast3-a` (서울)
- **노드**: e2-standard-4 (4 vCPU / 16GB) × 1 (일반 노드, Spot 미사용)
- **부하 발생기**: 같은 zone 의 별도 Compute Engine VM (e2-standard-2) — 네트워크 병목 제거 목적
- **부하 스택**: nGrinder + JMeter 조합 유지 (TASKS 명세 준수)
- **이미지 레지스트리**: GCP Artifact Registry (`asia-northeast3-docker.pkg.dev/<project>/peekcart/*`)
- **운영 패턴**: 측정 시에만 클러스터 기동, 종료 시 cluster delete + PD/VM 정리 (비용 최소화)
- **Phase 3 Task 3-1~3-3 의 minikube 환경은 ADR-0003 유지**. `k8s/overlays/minikube/` 는 로컬 개발용 검증 환경으로 계속 사용 (Phase 1·2 는 Docker Compose 였음)

## Alternatives Considered

### Alternative A: minikube 유지 + 호스트 메모리/설정 조정
- **장점**: 비용 0, 환경 변경 없음
- **단점**:
  - HPA max=3 → max=2 로 축소, Prometheus retention 축소 등 "측정의 정확도" 대신 "환경의 제약"을 받아들여야 함
  - 부하 도구 메모리 병목은 minikube 설정으로 해결 불가능 (host 제약)
  - false negative 리스크는 그대로
- **기각 사유**: Phase 3 Exit Criteria의 핵심인 "측정 정확도"를 포기하게 됨. 포트폴리오 가치 저하

### Alternative B: 부하 발생기만 클라우드 VM에서 실행 (앱은 minikube)
- **장점**: nGrinder/JMeter 메모리 문제만 해결
- **단점**:
  - minikube 8GB 리소스 한계 (#1, #3) 는 그대로
  - 클라우드 VM → 집 인터넷 → 공유기 → macOS → minikube 경로로 **네트워크가 새로운 병목**이 됨. 가정용 업로드 대역폭(100Mbps↓)이 TPS 상한을 결정하게 되어 측정이 무의미
- **기각 사유**: 한 문제를 해결하면서 더 큰 문제를 만듦

### Alternative C: Oracle Cloud Always Free (Ampere ARM 4 OCPU / 24GB)
- **장점**: 진짜 $0. 리소스 여유 충분
- **단점**:
  - ARM 이미지 빌드 파이프라인 필요 → CI 재작성 비용
  - Phase 4 MSA 전환 시 멀티 노드 확장 제약 (Always Free 티어 한계)
  - 포트폴리오에서 "GCP/GKE" 가 "Oracle Cloud" 대비 시장 친숙도 높음
- **기각 사유**: GCP 크레딧 확보로 비용 이점이 사라졌고, CI 재작성 비용이 장점을 상쇄

### Alternative D: GKE Autopilot
- **장점**: 노드 관리 불필요, 단순성
- **단점**:
  - Pod 단위 과금 방식이라 Prometheus처럼 메트릭 많은 워크로드는 오히려 비쌈
  - ServiceMonitor/CRD 호환성 제약 (Task 3-3 kube-prometheus-stack 이미 적용)
- **기각 사유**: Task 3-3 자산 보존 + 비용 측면에서 Standard가 유리

## Consequences

### 긍정적 영향
- 부하 테스트 측정값의 신뢰도 확보 (도구 병목 제거, 메모리 여유, 네트워크 최소화)
- HPA 검증(Task 3-5) 을 원래 계획대로 max=3 까지 수행 가능
- Phase 4 MSA 전환 시 클러스터 재사용 가능 (노드풀 scale 만 조정)
- 포트폴리오 스토리텔링 확장 — "로컬 Docker Compose(Phase 1·2) → 로컬 K8s(Phase 3 초기 minikube) → 클라우드 측정(Phase 3 부하 테스트~) → MSA(Phase 4)" 의 환경 진화 서사 획득
- 부하 테스트 결과에 "GKE / asia-northeast3 / e2-standard-4" 라는 재현 가능한 환경 명시 가능 (이전 minikube 환경 수치와 구분)

### 부정적 영향 / 트레이드오프
- **예산 소비**: Phase 3 부하 테스트 ~₩5,500 + Phase 4 ~₩15,000 예상. 크레딧(₩453,008)의 ~5% 수준으로 여유 충분하지만 0은 아님
- **운영 복잡도 증가** — 클러스터 기동/종료 루틴, PD/VM 정리 체크리스트 필요. 방치 시 크레딧 누수 리스크 (orphan disk 등)
- **Phase 3 초기 minikube 수치와 Phase 3 GKE 수치의 비교 불가** — 환경이 다르므로 동일 축 비교 불가. 다만 minikube 단계에서 실제 부하 측정을 수행한 기록이 없어 비교 대상 자체가 존재하지 않음 → 실질 영향 없음
- **문서 재설계 비용** — Layer 1 문서(04 10-7 등) 의 환경 가정 갱신 필요. ADR 레이어 분리로 비용은 제한적

### 후속 결정에 미치는 영향
- ADR-0005 (Kustomize 구조): 환경 이원화(minikube / gke) 를 위한 매니페스트 구조 결정의 직접 원인
- Phase 4 MSA 전환 시 본 클러스터를 재사용하는 것을 전제로 설계 (서비스별 디렉토리 추가 패턴)
- `docs/04-design-deep-dive.md` 10-7 재구성 — 환경별 측정 방침 이원화

## 운영 체크리스트 (예산 보호)

측정 종료 시 **반드시** 정리 스크립트를 실행하고 billing alert 를 ₩50,000 에 걸어둔다.
스크립트 기본값은 GKE 클러스터 `peekcart-loadtest`, 부하 발생기 VM `peekcart-loadgen`, zone `asia-northeast3-a`, region `asia-northeast3` 이다.

```bash
bash loadtest/cleanup.sh
```

실행 후 스크립트가 출력하는 `disks list` / `addresses list` 결과에서 orphan PD 와 예약 IP 잔존 여부를 육안 확인한다.

## References
- `docs/07-roadmap-portfolio.md` Section 16 Phase 3 Exit Criteria
- `docs/04-design-deep-dive.md` Section 10-7 (재구성 대상)
- `docs/03-requirements.md` Section 7-1 — 성능 목표 수치 및 측정 방법
- `docs/progress/PHASE3.md` — Task 3-1~3-3 minikube 환경 작업 이력
- 선행: ADR-0003 (본 ADR이 부분 supersede — Task 3-4 이후 범위)
