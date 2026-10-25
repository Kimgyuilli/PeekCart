#!/usr/bin/env bash
# D-078 재측정 S3-7: 측정 run 마다 원 시계열 export (loadtest/captures.md 의 S3 항목 구간).
#   d078-5        경합 런:            t0 - 60 ~ t1 + 120
#   d078-5-drain  런 시작~backlog 0: t0 ~ t_full + 60
#   d078-6        배수 구간:          t1 ~ t_full
#   d078-jvm      경합 런 1개(JVM_RUN, 기본 c2): t0 - 60 ~ t1 + 120
# 사용: RUNS="c1 c2 c3 s1" bash s3-export.sh
set -uo pipefail
ROOT=/Users/kimgyuill/dev/projects/PeakCart
cd "$ROOT"
S3="$ROOT/loadtest/reports/2026-10-25/s3"
OUT="$ROOT/loadtest/reports/2026-10-25/grafana"
JVM_RUN="${JVM_RUN:-c2}"
export PROM_URL=http://localhost:9090
mkdir -p "$OUT"
for run in ${RUNS:?}; do
  tf_file="$S3/${run}-times.tsv"
  [ -f "$tf_file" ] || tf_file="$S3/warmup/${run}-times.tsv"
  t() { awk -v k="$1" '$1 == k { print $2 }' "$tf_file"; }
  t0=$(t t0); t1=$(t t1); tf=$(t t_full)
  if [ -z "$t0" ] || [ -z "$t1" ] || [ -z "$tf" ] || [ "$tf" = "-" ]; then
    echo "!! $run: 시각이 없다(times.tsv)"; continue
  fi
  ex() { python3 loadtest/scripts/d078-capture.py export --item "$1" --label "s3-${run}" --from "$2" --to "$3" --out-dir "$OUT" | sed 's/^/  /' || echo "  !! export 실패: $1 $run"; }
  ex d078-5 $((t0 - 60)) $((t1 + 120))
  ex d078-5-drain "$t0" $((tf + 60))
  ex d078-6 "$t1" "$tf"
  [ "$run" = "$JVM_RUN" ] && ex d078-jvm $((t0 - 60)) $((t1 + 120))
done
