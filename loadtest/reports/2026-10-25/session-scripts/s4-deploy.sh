#!/usr/bin/env bash
# D-078 재측정 S4-1·S4-2 (base overlay `k8s/overlays/gke`). 계획과 다른 점:
#   notification Slack 두 값은 실제 webhook 대신 base 기본값과 같은 loopback(127.0.0.1:9)을 넣는다. 이번 S4 는 D-092(실제 전달)를
#   반복하지 않고 D-065 ②⑤ 만 본다. notification-service 는 두 키가 있고 서로 다르면 기동한다(NotificationSlackConfig).
set -uo pipefail
ROOT=/Users/kimgyuill/dev/projects/PeakCart
cd "$ROOT"
R="$ROOT/loadtest/reports/2026-10-25/s4"
mkdir -p "$R"
echo "=== S4 배포 시작 $(date -u +%FT%TZ) ==="
gcloud container clusters resize peekcart-d078 --node-pool default-pool --num-nodes 3 --zone asia-northeast3-a --quiet 2>&1 | tail -2
kubectl get nodes --no-headers | awk '{print "  " $1, $2}'
DRAIN_PREFLIGHT_SKIP_REASON="fresh namespace, no prior stack" bash scripts/deploy-overlay.sh k8s/overlays/gke > "$R/deploy-1.log" 2>&1
echo "  첫 배포 래퍼 exit=$?"
grep -E 'DEPLOY-OVERLAY' "$R/deploy-1.log" | sed 's/^/  /'
kubectl -n peekcart patch secret payment-service-secret --type merge \
  -p '{"stringData":{"TOSS_SECRET_KEY":"test_sk_smoke","TOSS_WEBHOOK_SECRET":"test_webhook_smoke"}}'
kubectl -n peekcart patch secret notification-service-secret --type merge \
  -p '{"stringData":{"SLACK_USER_WEBHOOK_URL":"http://127.0.0.1:9/user","SLACK_OPS_WEBHOOK_URL":"http://127.0.0.1:9/ops"}}'
kubectl -n peekcart rollout restart deploy/payment-service deploy/notification-service
for d in mysql redis kafka user-service product-service order-service payment-service notification-service gateway; do
  printf '  %-22s ' "$d"; kubectl -n peekcart rollout status deploy/$d --timeout=600s 2>&1 | tail -1
done
kubectl -n peekcart exec -i deploy/mysql -- mysql -upeekcart_user -ppeekcart_user peekcart_user \
  < loadtest/sql/d002bc-user-seed.sql 2>&1 | grep -v 'Using a password' | sed 's/^/  /'
kubectl -n peekcart get pods -o wide --no-headers > "$R/pods-after-deploy.txt"
awk '{printf "  %-46s %-10s %s\n", $1, $3, $4}' "$R/pods-after-deploy.txt"
echo "=== S4 배포 끝 $(date -u +%FT%TZ) ==="
