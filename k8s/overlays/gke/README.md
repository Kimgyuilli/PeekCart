# GKE Overlay

Phase 3 Task 3-4 부하 테스트 및 Phase 4 운영 환경용 Kustomize overlay.
근거: ADR-0004 (GKE 전환), ADR-0005 (Kustomize 구조), ADR-0006 (monitoring 분리).

## 전제

- GKE Standard 클러스터, `asia-northeast3-a`, `e2-standard-4 × 1`
- Artifact Registry 리포지토리: `asia-northeast3-docker.pkg.dev/<PROJECT_ID>/peekcart`
- 부하 발생기는 같은 VPC 의 별도 Compute Engine VM (ADR-0004)

## 이미지 경로 치환 (PROJECT_ID)

커밋된 `kustomization.yml` 의 `images:` 는 5개 서비스 각각에 `PROJECT_ID_PLACEHOLDER` 를 사용합니다 (PR3b — 단일 peekcart 분해). apply 전에 로컬에서 치환하되 **커밋하지 마세요** (operator 로컬 상태).

```bash
cd k8s/overlays/gke
for svc in notification-service user-service product-service order-service payment-service; do
  kustomize edit set image \
    "ghcr.io/kimgyuilli/peekcart-${svc}=asia-northeast3-docker.pkg.dev/<YOUR_PROJECT>/peekcart/${svc}:latest"
done

# 렌더링 확인
kubectl kustomize .

# apply 후 반드시 원복
git restore kustomization.yml
```

## 이미지 운반 (GHCR → Artifact Registry 승격, D-016)

CI 는 5개 서비스 이미지를 GHCR 로 push 합니다 (`.github/workflows/ci.yml` `publish` job). GKE 는 AR 에서 pull 하므로 승격이 필요합니다. 승격은 `scripts/promote-images.sh` 로 형식화됩니다 (수동 트리거 · crane 우선, docker 폴백 · 승격 후 AR digest 산출 → L-016a digest 고정 근거). 완전 자동 트리거는 후속.

```bash
# Artifact Registry 인증 + 리포지토리 생성 (최초 1회)
gcloud auth configure-docker asia-northeast3-docker.pkg.dev
gcloud artifacts repositories create peekcart --repository-format=docker --location=asia-northeast3

# 승격 미리보기 (실행 안 함 — GHCR→AR 매핑 확인)
scripts/promote-images.sh --dry-run --project <YOUR_PROJECT>

# 5개 서비스 승격 + 각 AR digest 출력
scripts/promote-images.sh --project <YOUR_PROJECT> --tag latest
# 단일 서비스만: scripts/promote-images.sh --project <YOUR_PROJECT> --service order-service
```

## 배포 순서

`docs/02-architecture.md §12` 의 GKE 배포 순서를 따릅니다.
ServiceMonitor CRD 선행 의존이 있으므로 monitoring 스택을 먼저 설치해야 합니다.

> **중요**: 아래 4단계는 **모두** 실행해야 부하 테스트 환경이 완성됩니다.
> `kubectl apply -k overlays/gke/` 단독 실행은 monitoring 스택을 포함하지 않습니다 (ADR-0006 불변식 1·4).
> 3단계(shared 대시보드/Alert) 를 건너뛰면 Grafana 가 비어 있는 상태로 뜨니 주의.

```bash
# 1. monitoring NS
kubectl apply -f k8s/monitoring/namespace.yml

# 2. kube-prometheus-stack (ServiceMonitor CRD 등록)
bash k8s/monitoring/gke/install.sh

# 3. 환경 무관 대시보드/Alert (configMapGenerator 가 *.json → ConfigMap 생성)
kubectl apply -k k8s/monitoring/shared/

# 4. app/infra + HPA + ServiceMonitor
kubectl apply -k k8s/overlays/gke/
```

> **HPA 전제**: 4단계 적용에 포함된 `HorizontalPodAutoscaler/order-service` (`hpa.yml`) 는 CPU Utilization 기반이며 metrics-server API (`metrics.k8s.io`) 가 필요합니다. GKE Standard 는 기본 제공이므로 추가 설치 없이 동작합니다. **PR3b: HPA 는 order-service 단일**(GP-2 #4 · 로드맵 §16 "Phase 4 이후 HPA=Order Service HPA") — 타 4서비스는 HPA 미적용(필요 시 후속). minikube overlay 에는 HPA 미포함.
> HPA 상태 확인: `kubectl get hpa -n peekcart order-service` · `kubectl top pods -n peekcart`.

## 정리 (ADR-0004 운영 체크리스트)

측정 종료 시 반드시 실행:

```bash
bash loadtest/cleanup.sh
```

기본 대상은 GKE 클러스터 `peekcart-loadtest`, 부하 발생기 VM `peekcart-loadgen`, zone `asia-northeast3-a`, region `asia-northeast3` 입니다. 실행 후 스크립트가 출력하는 orphan PD / 예약 IP 목록을 반드시 확인합니다.
