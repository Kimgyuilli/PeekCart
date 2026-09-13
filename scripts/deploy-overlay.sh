#!/usr/bin/env bash
# deploy-overlay.sh — 앱 overlay 배포 래퍼 (④-c-2b-4b P24 · ADR-0022 §D3)
#
# preflight(drain 판정) → kubectl apply -k <overlay> 순으로 감싼다.
#
# **왜 래퍼인가**: 이 레포에는 CD 가 없다 — `ci.yml` 에 deploy job 이 0건이고 배포는 운영자의 수동
#   `kubectl apply -k` 다. 그래서 판정을 붙일 자리가 배포 명령 자체밖에 없다.
#   in-cluster initContainer 게이트가 진짜 강제지만 4서비스 × DB 자격증명 + mysql 클라이언트 이미지 +
#   **정상 배포마다 드는 상시 비용** 때문에 채택하지 않았다(ADR-0022 Alternative G).
#
# **감싸는 범위는 앱 overlay 뿐이다**: monitoring/shared 매니페스트 적용은 서비스 이미지를 바꾸지 않아
#   롤백 위험과 무관하다. 그것들은 raw `kubectl apply -k` 를 그대로 쓴다.
#
# **강제력의 한계**: 운영자가 이 래퍼를 건너뛰고 `kubectl apply -k` 를 직접 치면 우회된다.
#   검증(계획 V-28)은 "래퍼가 exit≠0 이다" 만 확인하고 **"우회 불가" 를 주장하지 않는다**.
#
# 사용:
#   scripts/deploy-overlay.sh k8s/overlays/gke
#   scripts/deploy-overlay.sh k8s/overlays/minikube
#   DRAIN_PREFLIGHT_SKIP_REASON="..." scripts/deploy-overlay.sh <overlay>   # 아래 참고
#
# Exit: 0 배포 성공 · 1 preflight 위반 또는 배포 실패 · 2 사용법/전제 오류

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TAG="[DEPLOY-OVERLAY]"
OVERLAY="${1:-}"

if [[ -z "$OVERLAY" ]]; then
    echo "사용법: $0 <overlay 경로> (예: k8s/overlays/gke)" >&2
    exit 2
fi
if [[ ! -d "$OVERLAY" ]]; then
    echo "$TAG overlay 디렉토리가 없다: $OVERLAY" >&2
    exit 2
fi

# **skip 은 사유를 강제하고 로그에 남긴다.** 조용히 끌 수 있는 게이트는 결국 꺼진 채로 남는다.
# 이것은 강제가 아니라 **기록**이다 — 어차피 raw apply 로 우회 가능하므로, 우회를 어렵게 만드는 대신
# 우회했다는 사실이 남게 한다.
if [[ -n "${DRAIN_PREFLIGHT_SKIP_REASON:-}" ]]; then
    echo "$TAG !! drain preflight 를 건너뛴다 — 사유: ${DRAIN_PREFLIGHT_SKIP_REASON}"
    echo "$TAG !! replay 행이 남은 상태로 구 이미지를 배포하면 발행 경로가 손상된다(ADR-0022 §D2)"
else
    echo "$TAG drain preflight 실행 — 통과해야 배포한다"
    if ! bash scripts/replay-drain-preflight.sh; then
        echo "$TAG preflight 가 막았다. 배포하지 않는다" >&2
        exit 1
    fi
fi

echo "$TAG kubectl apply -k $OVERLAY"
kubectl apply -k "$OVERLAY"
