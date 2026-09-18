# GKE Overlay

Phase 3 Task 3-4 부하 테스트 및 Phase 4 운영 환경용 Kustomize overlay.
근거: ADR-0004 (GKE 전환), ADR-0005 (Kustomize 구조), ADR-0006 (monitoring 분리).

## 전제

- GKE Standard 클러스터, `asia-northeast3-a`, `e2-standard-4 × 1`
- Artifact Registry 리포지토리: `asia-northeast3-docker.pkg.dev/<PROJECT_ID>/peekcart`
- 부하 발생기는 같은 VPC 의 별도 Compute Engine VM (ADR-0004)

## 이미지 경로 치환 (PROJECT_ID)

커밋된 `kustomization.yml` 의 `images:` 는 **도메인 5 + 인프라 gateway 1 = 6개** 각각에 `PROJECT_ID_PLACEHOLDER` 를 사용합니다 (구현 ① PR3b 단일 peekcart 분해 → 구현 ③ PR3b gateway 합류). apply 전에 로컬에서 치환하되 **커밋하지 마세요** (operator 로컬 상태).

```bash
cd k8s/overlays/gke
# 도메인 5 + 인프라 gateway 1 = 6. gateway 를 빠뜨리면 PROJECT_ID_PLACEHOLDER 가 남아 ImagePullBackOff 가 됩니다.
for svc in notification-service user-service product-service order-service payment-service gateway; do
  kustomize edit set image \
    "ghcr.io/kimgyuilli/peekcart-${svc}=asia-northeast3-docker.pkg.dev/<YOUR_PROJECT>/peekcart/${svc}:latest"
done

# gateway 를 canary/롤백 대상으로 배포할 때는 latest 가 아니라 digest 로 고정합니다
# (계획 §7 롤아웃 runbook — latest 면 canary 와 rollback 이 같은 태그를 가리켜 되돌릴 대상이 없습니다).
# kustomize edit set image \
#   "ghcr.io/kimgyuilli/peekcart-gateway=asia-northeast3-docker.pkg.dev/<YOUR_PROJECT>/peekcart/gateway@sha256:<digest>"

# 이미지 외에도 PROJECT_ID_PLACEHOLDER 를 쓰는 자리가 있습니다 — `kustomize edit set image` 로는
# 안 바뀌므로 apply 직전에 함께 치환합니다(치환 누락 시 Pod 가 개인키를 못 받아 ContainerCreating 고착):
#   - SecretProviderClass 2종의 `resourceName`  (gateway / user-service)
#   - ServiceAccount 2종의 `iam.gke.io/gcp-service-account` 어노테이션 (Workload Identity, PR3d-b-2 P2)

# 렌더링 확인 — 치환 누락이 남아 있으면 여기서 걸립니다 (이미지·SPC·KSA 어노테이션 전부)
kubectl kustomize . | grep -n PROJECT_ID_PLACEHOLDER && echo "치환 누락 있음" || echo "치환 완료"

# apply 후 반드시 원복
git restore kustomization.yml
```

## 이미지 운반 (GHCR → Artifact Registry 승격, D-016)

CI 는 **6개 이미지**(도메인 5 + 인프라 gateway 1)를 GHCR 로 push 합니다 (`.github/workflows/ci.yml` `publish` job). GKE 는 AR 에서 pull 하므로 승격이 필요합니다. 승격은 `scripts/promote-images.sh` 로 형식화됩니다 (수동 트리거 · crane 우선, docker 폴백 · 승격 후 AR digest 산출 → L-016a digest 고정 근거). 완전 자동 트리거는 후속.

```bash
# Artifact Registry 인증 + 리포지토리 생성 (최초 1회)
gcloud auth configure-docker asia-northeast3-docker.pkg.dev
gcloud artifacts repositories create peekcart --repository-format=docker --location=asia-northeast3

# 승격 미리보기 (실행 안 함 — GHCR→AR 매핑 확인)
scripts/promote-images.sh --dry-run --project <YOUR_PROJECT>

# 6개 이미지(도메인 5 + gateway) 승격 + 각 AR digest 출력
scripts/promote-images.sh --project <YOUR_PROJECT> --tag latest
# 단일 대상만: scripts/promote-images.sh --project <YOUR_PROJECT> --service order-service
#              scripts/promote-images.sh --project <YOUR_PROJECT> --service gateway
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
# 앱 overlay 배포는 래퍼를 쓴다 — drain preflight 선행 (ADR-0022 §D3)
#    GW_URL 을 주면 배포 후 **인증 왕복 게이트**(D-022)까지 래퍼가 돌린다.
#    주지 않으면 건너뛰고 그 사실을 경고로 남긴다 — 건너뛴 채로 두지 말 것.
GW_URL=http://<gateway-internal-lb>:8080 bash scripts/deploy-overlay.sh k8s/overlays/gke

# 5. (LB IP 를 배포 전에 모를 때) 인증 왕복 게이트를 따로 실행 — **통과해야 완료다**
GW_URL=http://<gateway-internal-lb>:8080 bash scripts/auth-roundtrip-gate.sh
```

> **왜 5단계가 필요한가** (D-022): ConfigMap 공개키가 Secret Manager 개인키와 **다른 쌍**이어도
> apply 는 전부 성공하고 파드는 Ready 이며 로그인도 200 입니다. 깨지는 것은 **인증된 요청뿐**이라
> 배포 시점에 아무 신호가 없습니다. `kid` 가 같으면 `unknown_kid` 로도 안 걸리고 `bad_signature`
> 로만 나타납니다. 게이트가 실패하면 **어느 키 도메인이 어긋났는지 지목**합니다.

> **HPA 전제**: 4단계 적용에 포함된 HPA (`hpa.yml`) 는 CPU Utilization 기반이며 metrics-server API (`metrics.k8s.io`) 가 필요합니다. GKE Standard 는 기본 제공이므로 추가 설치 없이 동작합니다. **도메인 서비스 HPA 는 order-service 단일**(구현 ① PR3b GP-2 #4 · 로드맵 §16 "Phase 4 이후 HPA=Order Service HPA") — 타 4서비스는 HPA 미적용(필요 시 후속). **gateway 는 그 원칙의 명시적 예외**(구현 ③ PR3b): 전 트래픽 단일 진입점이라 단일 replica 가 SPOF 이므로 `minReplicas: 2` HPA 를 둡니다(ADR-0013 D3). minikube overlay 에는 HPA 미포함.
> HPA 상태 확인: `kubectl get hpa -n peekcart` · `kubectl top pods -n peekcart`.

## 외부 노출 (구현 ③ PR3c — gateway 단일 진입점)

외부 진입점은 **gateway 하나**입니다 (ADR-0013 D3). PR3c 에서 5서비스 직접 LB 경로를 제거하고
ClusterIP 로 환원했으며, NetworkPolicy 로 업무 API(8080) 진입을 gateway·monitoring scrape 로만 제한합니다.

| 대상 | 노출 | 상태 |
|---|---|---|
| `gateway` | Internal LoadBalancer (`networking.gke.io/load-balancer-type: Internal`), 8080 | **정본 진입점** |
| 5개 도메인 서비스 | ClusterIP (외부 노출 없음) | header-trust + NetworkPolicy 로 gateway 경유 강제 |

**NetworkPolicy enforcement 는 CNI 의존입니다** — GKE 는 Dataplane V2 또는 `--enable-network-policy`(Calico)가
활성이어야 정책이 실제로 적용됩니다. 미활성이면 정책이 조용히 무시되어 직접 경로 spoof 가 열립니다.
롤아웃 전 `scripts/gke-security-smoke.sh` 가 enforcement 활성을 hard-fail 로 확인합니다
(절차·안전 순서: `docs/plans/task-impl3-spring-cloud-gateway.md` §8).

gateway Service 는 **8080 만 게시**합니다 — 관리 포트 8081(actuator)을 이 Service 에 추가하면 LB 가
`/actuator/prometheus` 까지 노출합니다. `scripts/gateway-exposure-lint.sh` 가 렌더 산출에서 이를 강제합니다
(scrape 용 `gateway-metrics` Service 는 ServiceMonitor 와 함께 PR4 에서 신설).

## 정리 (ADR-0004 운영 체크리스트)

측정 종료 시 반드시 실행:

```bash
bash loadtest/cleanup.sh
```

기본 대상은 GKE 클러스터 `peekcart-loadtest`, 부하 발생기 VM `peekcart-loadgen`, zone `asia-northeast3-a`, region `asia-northeast3` 입니다. 실행 후 스크립트가 출력하는 orphan PD / 예약 IP 목록을 반드시 확인합니다.
