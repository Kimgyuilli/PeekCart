# ---------- /ship: consistency precheck / commit plan / PR body ----------

# hpx_consistency_precheck <task_id>
# bash docs/consistency-hints.sh 실행. 결과 3줄 출력:
#   line1: status (ok|warnings|unavailable|script_error)
#   line2: log path (stdout+stderr 합쳐 기록)
#   line3: exit code
# §7-5-E 분기 근거 제공. script 부재는 unavailable (호출자가 skip), exec 실패는 script_error.
hpx_consistency_precheck() {
  local task_id="$1"
  hpx_task_id_validate "$task_id" || return 1
  local ts
  ts="$(hpx_epoch_ts)"
  local log_path=".cache/consistency-${task_id}-${ts}.log"
  mkdir -p .cache >/dev/null 2>&1 || true

  if [ ! -f docs/consistency-hints.sh ]; then
    printf 'unavailable\n%s\n0\n' "$log_path"
    return 0
  fi

  bash docs/consistency-hints.sh >"$log_path" 2>&1
  local ec=$?

  local status
  case "$ec" in
    0) status="ok" ;;
    1) status="warnings" ;;
    *) status="script_error" ;;
  esac
  printf '%s\n%s\n%s\n' "$status" "$log_path" "$ec"
}
