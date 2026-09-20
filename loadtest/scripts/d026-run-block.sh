#!/usr/bin/env bash
# loadtest/scripts/d026-run-block.sh
#
# D-026 / D-002a 읽기 경로 — **블록 1개 실행**. 계획서 §3-1.
#
# 블록 = MySQL CPU 조건 하나 + 그 안의 셀들(각 3회).
# 순서가 A → B → A′ 인 이유는 `mysql.yml` 의 strategy 가 **Recreate** 라
# CPU 를 바꾸면 Pod 가 재기동되고 InnoDB 버퍼풀이 식기 때문이다. 단방향으로 재면
# "CPU 효과" 와 "재시작·드리프트 효과" 가 섞인다(계획서 N6, 리뷰 지적 #1).
#
# 사용법:
#   MYSQL=500m  CELLS="detail:off detail:on list:off list:on" bash loadtest/scripts/d026-run-block.sh
#   MYSQL=2000m CELLS="detail:off detail:on list:off list:on" bash loadtest/scripts/d026-run-block.sh
#   MYSQL=500m  LABEL=A2 CELLS="detail:off list:off"          bash loadtest/scripts/d026-run-block.sh
#
# 셀 순서는 호출자가 정한다 — 블록마다 교차시켜 고정 순서가 만드는 드리프트를 흩는다.

set -uo pipefail
NS=peekcart
MYSQL="${MYSQL:?MYSQL=500m|2000m 필요}"
CELLS="${CELLS:?CELLS=\"detail:off list:on\" 형식 필요}"
REPS="${REPS:-3}"
LABEL="${LABEL:-$MYSQL}"
ROOT="$(git rev-parse --show-toplevel)"
OUTDIR="${OUTDIR:-$ROOT/.cache/d026-runs}"

echo "=== 블록 $LABEL (MySQL $MYSQL) ==="

# ── MySQL CPU 조건 전환 — overlay apply 로만 한다 ────────────────────────────
# kubectl patch 를 쓰지 않는 이유는 N5 다. 조건이 파일 밖에 있으면 다음 세션이
# "지금 어느 조건인가" 를 모른다 — D-002a 가 그래서 내내 500m 로 돌았다.
kustomize build --load-restrictor LoadRestrictionsNone \
  "$ROOT/k8s/overlays/gke-d002a-mysql-${MYSQL}" | kubectl apply -f - >/dev/null

echo "-- MySQL rollout 대기 (Recreate: 완전 종료 후 재기동) --"
kubectl -n "$NS" rollout status deploy/mysql --timeout=420s || exit 1

# readiness 만으로는 부족하다. 실제 쿼리가 받아지는지까지 본다.
echo "-- DB readiness --"
for i in $(seq 1 60); do
  kubectl -n "$NS" exec deploy/mysql -- \
    mysql -upeekcart_product -ppeekcart_product -e "SELECT 1" peekcart_product >/dev/null 2>&1 && break
  sleep 5
done

EFF=$(kubectl -n "$NS" get pod -l app=mysql -o jsonpath='{.items[0].spec.containers[0].resources.limits.cpu}')
# 쿠버네티스는 `2000m` 을 `2` 로 정규화한다(product 의 `3000m` 도 `3` 으로 보인다).
# 문자열로 비교하면 **조건이 맞는데도** 불일치로 읽는다 — 밀리코어로 환산해서 본다.
milli() { case "$1" in *m) echo "${1%m}";; *) echo $(( ${1%.*} * 1000 ));; esac; }
echo "-- effective mysql cpu limit = $EFF ($(milli "$EFF")m · 기대 $(milli "$MYSQL")m) --"
[ "$(milli "$EFF")" = "$(milli "$MYSQL")" ] || { echo "!! CPU 조건 불일치 — 중단"; exit 1; }

# ── 버퍼풀 워밍업 — 블록마다 **동일한 종료 조건**으로 ────────────────────────
# 재기동 직후의 콜드 버퍼풀이 그대로 측정에 들어가면 그것이 CPU 효과로 보인다.
echo "-- 버퍼풀 워밍업 (전 상품 풀스캔 3회) --"
for i in 1 2 3; do
  kubectl -n "$NS" exec deploy/mysql -- \
    mysql -upeekcart_product -ppeekcart_product -e \
    "SELECT COUNT(*) FROM products; SELECT COUNT(*) FROM inventories; SELECT SUM(quantity) FROM inventories;" \
    peekcart_product >/dev/null 2>&1
done

# ── 셀 실행 ──────────────────────────────────────────────────────────────────
for rep in $(seq 1 "$REPS"); do
  for cell in $CELLS; do
    EP="${cell%%:*}"; CACHE="${cell##*:}"
    EP="$EP" CACHE="$CACHE" MYSQL="$LABEL" REP="$rep" OUTDIR="$OUTDIR" \
      bash "$ROOT/loadtest/scripts/d026-run-cell.sh" || echo "  !! 셀 실패: $cell rep$rep"
  done
done

echo "=== 블록 $LABEL 완료 ==="
