#!/usr/bin/env bash
# D-078 재측정 S2 블록 1개 (계획 S2-2·S2-3).
#
# 블록 준비(overlay apply, MySQL rollout·readiness, CPU 조건 대조, 버퍼풀 워밍업)는 d026-run-block.sh 를 그대로 쓴다.
# CELLS=" " 로 그 스크립트의 셀 루프를 비우고 셀 루프만 여기서 돈다. 셀은 d026-run-cell.sh 그대로다.
# 여기서 더하는 것: run 마다 k6 구간(runs.tsv), annotate, Hikari active·pending 원값(JSON), 블록 전후 Pod 배치.
# k6 구간: 끝은 k6 로그의 마지막 기록 시각, 길이는 로그 끝의 `running (1m00.2s)` 를 올림한 초.
#
# 사용: BLOCK=b1 MYSQL=2000m LABEL=L1 BULKHEAD=1 CELLS="detail:off list:off" REPS=4 \
#         OUTDIR=... LOADGEN=peekcart-d078-loadgen bash s2-block.sh
set -uo pipefail
ROOT=/Users/kimgyuill/dev/projects/PeakCart
cd "$ROOT"
: "${BLOCK:?}" "${MYSQL:?}" "${LABEL:?}" "${CELLS:?}" "${REPS:?}" "${OUTDIR:?}" "${LOADGEN:?}"
export MYSQL LABEL OUTDIR LOADGEN
export BULKHEAD="${BULKHEAD:-}"
if [ -n "${OVERLAY:-}" ]; then export OVERLAY; else unset OVERLAY; fi
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
GRAFANA_AUTH="${GRAFANA_AUTH:-admin:admin}"
PROM_URL="${PROM_URL:-http://localhost:9090}"
mkdir -p "$OUTDIR/hikari" "$OUTDIR/pods"

echo "=== 블록 $BLOCK 준비 ($(date -u +%FT%TZ)) ==="
kubectl -n peekcart get pod -o wide > "$OUTDIR/pods/${BLOCK}-${LABEL}-before.txt" 2>&1
CELLS=" " REPS=1 bash loadtest/scripts/d026-run-block.sh || { echo "!! 블록 준비 실패 ($BLOCK)"; exit 1; }

k6_seconds() {
  python3 -I - "$1" <<'PY'
import math, re, sys
text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
m = re.findall(r"running \((?:(\d+)m)?([\d.]+)s\)", text)
if not m:
    sys.exit(1)
mins, secs = m[-1]
print(math.ceil(int(mins or 0) * 60 + float(secs)))
PY
}

for rep in $(seq 1 "$REPS"); do
  for cell in $CELLS; do
    EP="${cell%%:*}"; CACHE="${cell##*:}"
    CELL="mysql${LABEL}-${EP}-cache${CACHE}-r${rep}"
    EP="$EP" CACHE="$CACHE" MYSQL="$LABEL" REP="$rep" bash loadtest/scripts/d026-run-cell.sh \
      || echo "  !! 셀 실패: $cell rep$rep"
    log="$OUTDIR/${CELL}.k6.log"
    if ! dur=$(k6_seconds "$log" 2>/dev/null); then
      echo "  !! k6 구간을 낼 수 없다: $CELL"
      continue
    fi
    end=$(stat -f %m "$log")
    start=$((end - dur))
    tag="$LABEL $EP $(printf '%s' "$CACHE" | tr '[:lower:]' '[:upper:]') r$rep"
    printf '%s\t%s\t%s\n' "$tag" "$start" "$end" >> "$OUTDIR/runs.tsv"
    GRAFANA_URL="$GRAFANA_URL" GRAFANA_AUTH="$GRAFANA_AUTH" \
      python3 loadtest/scripts/d078-capture.py annotate --label "$tag" --from "$start" --to "$end" >/dev/null \
      || echo "  !! annotate 실패: $tag"
    for m in active pending; do
      curl -s --get "$PROM_URL/api/v1/query_range" \
        --data-urlencode "query=max(hikaricp_connections_${m}{application=\"product-service\"})" \
        --data-urlencode "start=$start" --data-urlencode "end=$end" --data-urlencode "step=15" \
        > "$OUTDIR/hikari/${CELL}.${m}.json" || echo "  !! Hikari ${m} 조회 실패: $CELL"
    done
    echo "  run 기록: $tag $start..$end (${dur}s)"
  done
done
kubectl -n peekcart get pod -o wide > "$OUTDIR/pods/${BLOCK}-${LABEL}-after.txt" 2>&1
echo "=== 블록 $BLOCK($LABEL) 끝 ($(date -u +%FT%TZ)) ==="
