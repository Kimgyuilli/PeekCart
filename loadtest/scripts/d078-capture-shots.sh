#!/usr/bin/env bash
# loadtest/scripts/d078-capture-shots.sh
#
# Grafana 패널 캡처 실행기. `d078-capture.py plan` 출력(파일명 \t d-solo URL)을 받아 headless Chrome 으로 PNG 를 뜨고
# sips 로 JPEG 로 바꿔 PLAN 과 같은 디렉터리에 둔다. 2026-10-26 세션 스크립트
# (loadtest/reports/2026-10-26/session-scripts/capture.sh)를 옮기고 경로를 인자로 받게 했다.
#
#   PLAN=loadtest/reports/<날짜>/grafana/capture-plan.tsv bash loadtest/scripts/d078-capture-shots.sh
#
# 필수: PLAN
# 선택: CHROME(기본 macOS Chrome 경로), SHOT_TIMEOUT(장당 제한 초, 기본 120)
#
# 장마다 시간 제한과 1회 재시도를 둔다. Grafana port-forward 가 끊기면 Chrome 이 끝나지 않아 10분 멈춘 일이 있다
# (2026-10-26 session-issues 12). URL 은 인증 없이 열려야 한다. 그 세션은 GRAFANA_URL 을 요청마다 Basic 인증 헤더를
# 붙이는 로컬 프록시(localhost:3001 → 3000)로 두고 plan 을 냈다.
# 한 장이라도 실패하면 exit 1 이다. 출력 줄은 `ok\t파일\t바이트` 또는 `FAIL\t파일` 이다.
set -uo pipefail
: "${PLAN:?PLAN=<capture-plan.tsv> 필요}"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
SHOT_TIMEOUT="${SHOT_TIMEOUT:-120}"
OUT_DIR="$(dirname "$PLAN")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail=0
while IFS=$'\t' read -r name url; do
  [ -n "$name" ] || continue
  png="$WORK/${name%.jpg}.png"
  for attempt in 1 2; do
    rm -f "$png" "$WORK"/chrome-profile/Singleton*
    perl -e 'alarm shift; exec @ARGV' "$SHOT_TIMEOUT" "$CHROME" --headless=new --disable-gpu --hide-scrollbars \
      --window-size=1450,840 --virtual-time-budget=15000 --user-data-dir="$WORK/chrome-profile" \
      --screenshot="$png" "$url" </dev/null >/dev/null 2>&1
    [ -s "$png" ] && break
  done
  if [ -s "$png" ] && sips -s format jpeg "$png" --out "$OUT_DIR/$name" >/dev/null 2>&1; then
    printf 'ok\t%s\t%s\n' "$name" "$(wc -c < "$OUT_DIR/$name" | tr -d ' ')"
  else
    printf 'FAIL\t%s\n' "$name"; fail=1
  fi
done < "$PLAN"
exit $fail
