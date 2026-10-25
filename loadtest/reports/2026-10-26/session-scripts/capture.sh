#!/usr/bin/env bash
# D-108 세션 Grafana 패널 캡처. d078-capture.py plan 출력(파일명 \t d-solo URL)을 받아 headless Chrome 으로 PNG 를 뜨고
# sips 로 JPEG 로 바꿔 grafana/ 에 둔다. URL 은 localhost:3001(요청마다 Basic 인증 헤더를 붙이는 세션 한정 프록시 → 3000)을 가리킨다.
#   PLAN=loadtest/reports/2026-10-26/grafana/capture-plan.tsv bash loadtest/reports/2026-10-26/session-scripts/capture.sh
set -uo pipefail
cd /Users/kimgyuill/dev/projects/PeakCart
: "${PLAN:?}"
OUT_DIR="$(dirname "$PLAN")"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
SCRATCH=/private/tmp/claude-501/-Users-kimgyuill-dev-projects-PeakCart/c9193dc0-2638-483b-979b-8d315c2b1370/scratchpad
mkdir -p "$SCRATCH/capture-png"
fail=0
while IFS=$'\t' read -r name url; do
  [ -n "$name" ] || continue
  png="$SCRATCH/capture-png/${name%.jpg}.png"
  # s1 캡처 첫 장에서 Chrome 이 10분 넘게 끝나지 않아(22:07~22:17Z) 장마다 120초 제한과 1회 재시도를 둔다.
  for attempt in 1 2; do
    rm -f "$png" "$SCRATCH"/chrome-capture/Singleton*
    perl -e 'alarm shift; exec @ARGV' 120 "$CHROME" --headless=new --disable-gpu --hide-scrollbars --window-size=1450,840 \
      --virtual-time-budget=15000 --user-data-dir="$SCRATCH/chrome-capture" --screenshot="$png" "$url" > /dev/null 2>&1
    [ -s "$png" ] && break
  done
  if [ -s "$png" ] && sips -s format jpeg "$png" --out "$OUT_DIR/$name" > /dev/null 2>&1; then
    echo "ok	$name	$(wc -c < "$OUT_DIR/$name" | tr -d ' ')"
  else
    echo "FAIL	$name"; fail=1
  fi
done < "$PLAN"
exit $fail
