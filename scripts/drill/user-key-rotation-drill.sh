#!/usr/bin/env bash
# User 토큰 회전 드릴 하네스 (minikube 전용 — 운영 도구 아님).
#
# runbook docs/runbooks/user-jwt-key-rotation.md §1.1/§3.1 축을 재현하기 위한 최소 스택을 띄우고,
# §3.1 전수 확인(Pod IP 직접 조회)을 반복 실행 가능한 형태로 제공한다.
# 범위/제외 사유: k8s/overlays/minikube-rotation-drill/README.md
set -euo pipefail

NS=peekcart
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OVERLAY="$ROOT/k8s/overlays/minikube-rotation-drill"

# base 밖 파일을 참조하므로 load restrictor 를 푼다(README 참고).
kustomize_apply() {
  kubectl kustomize --load-restrictor=LoadRestrictionsNone "$OVERLAY" | kubectl apply -f -
}

cmd_up() {
  kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -
  # 개인키는 레포에 없다(ADR-0013 D2) — local-keys/ 에서 Secret 을 만든다.
  kubectl -n "$NS" create secret generic drill-user-jwt-signing-key \
    --from-file=jwt-private.pem="$ROOT/local-keys/dev-jwt-private.pem" \
    --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n "$NS" create secret generic drill-gateway-internal-signing-key \
    --from-file=gateway-internal-private.pem="$ROOT/local-keys/dev-gateway-internal-private.pem" \
    --dry-run=client -o yaml | kubectl apply -f -
  kustomize_apply
  kubectl -n "$NS" rollout status deploy/user-service --timeout=300s
  kubectl -n "$NS" rollout status deploy/gateway --timeout=300s
}

# §3.1 전수 확인 — Service 가 아니라 **Pod IP 로 직접** 물어야 한다.
cmd_jwks() {
  local fail=0
  for p in $(kubectl -n "$NS" get pods -l app.kubernetes.io/name=user-service \
               --field-selector=status.phase=Running \
               -o jsonpath='{range .items[*]}{.metadata.name}={.status.podIP}{"\n"}{end}'); do
    local name="${p%%=*}" ip="${p##*=}"
    local kids
    kids=$(kubectl -n "$NS" run "jwksprobe-$RANDOM" --rm -i --restart=Never --quiet \
             --image=curlimages/curl:8.10.1 --command -- \
             curl -s --max-time 5 "http://$ip:8080/.well-known/jwks.json" 2>/dev/null \
           | python3 -c 'import sys,json; print(",".join(k["kid"] for k in json.load(sys.stdin)["keys"]))' 2>/dev/null) || kids="<probe failed>"
    echo "$name ($ip) -> $kids"
    [[ "$kids" == *","* ]] || fail=1
  done
  return $fail
}

# 로그인 → 발급 kid → 보호 경로 상태코드. runbook §3.2 에 해당하되 보호 경로는 /api/v1/users/me 다
# (§3.2 가 쓰는 /api/v1/orders 는 order-service 소관이고 이 드릴 스택에 없다).
cmd_probe() {
  local tag="${1:-p}"
  kubectl -n "$NS" run "probe-$tag-$RANDOM" --rm -i --restart=Never --quiet \
    --image=curlimages/curl:8.10.1 --command -- sh -c '
      GW=http://gateway:8080
      EMAIL="drill-'"$tag"'@peekcart.test"
      curl -s -o /dev/null -X POST "$GW/api/v1/auth/signup" -H "Content-Type: application/json" \
        -d "{\"email\":\"$EMAIL\",\"password\":\"Drill1234!\",\"name\":\"drill\"}"
      TOKEN=$(curl -s -X POST "$GW/api/v1/auth/login" -H "Content-Type: application/json" \
        -d "{\"email\":\"$EMAIL\",\"password\":\"Drill1234!\"}" \
        | sed -n "s/.*\"accessToken\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p")
      if [ -z "$TOKEN" ]; then echo "kid=<no token>"; else
        HDR=$(echo "$TOKEN" | cut -d. -f1)
        case $(( ${#HDR} % 4 )) in 2) HDR="$HDR==";; 3) HDR="$HDR=";; esac
        echo "kid=$(echo "$HDR" | tr "_-" "/+" | base64 -d 2>/dev/null)"
      fi
      echo "protected=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" "$GW/api/v1/users/me")"
    '
}

cmd_down() { kubectl delete namespace "$NS" --ignore-not-found; }

case "${1:-}" in
  up) cmd_up ;;
  jwks) cmd_jwks ;;
  probe) shift; cmd_probe "$@" ;;
  down) cmd_down ;;
  *) echo "usage: $0 {up|jwks|probe [tag]|down}" >&2; exit 2 ;;
esac
