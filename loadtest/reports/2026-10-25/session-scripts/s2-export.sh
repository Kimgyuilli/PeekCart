#!/usr/bin/env bash
# D-078 재측정 S2-5: 측정 run(r2~r4) 마다 원 시계열 export (loadtest/captures.md, 구간은 run 시작 60초 전 ~ 끝 120초 뒤).
#   OFF 셀(L1·L4): d078-1, d078-2 / list ON 셀: d078-4 / S2 측정 run 1개(L1 detail ON r3): d078-jvm
set -uo pipefail
ROOT=/Users/kimgyuill/dev/projects/PeakCart
cd "$ROOT"
RUNS="$ROOT/loadtest/reports/2026-10-25/s2-runs/runs.tsv"
OUT="$ROOT/loadtest/reports/2026-10-25/grafana"
export PROM_URL=http://localhost:9090
mkdir -p "$OUT"
n=0
while IFS=$'\t' read -r tag start end; do
  # tag 예: "L4 list OFF r3"
  read -r label ep cache rep <<<"$tag"
  [ "$rep" = "r1" ] && continue
  name="s2-${label}-${ep}-$(printf '%s' "$cache" | tr '[:upper:]' '[:lower:]')-${rep}"
  from=$((start - 60)); to=$((end + 120))
  items=""
  [ "$cache" = "OFF" ] && items="d078-1 d078-2"
  [ "$cache" = "ON" ] && [ "$ep" = "list" ] && items="d078-4"
  [ "$tag" = "L1 detail ON r3" ] && items="$items d078-jvm"
  for item in $items; do
    python3 loadtest/scripts/d078-capture.py export --item "$item" --label "$name" --from "$from" --to "$to" --out-dir "$OUT" \
      | sed 's/^/  /' || echo "  !! export 실패: $item $name"
    n=$((n + 1))
  done
done < "$RUNS"
echo "export 호출 ${n}회 → $OUT"
