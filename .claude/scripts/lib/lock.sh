# ---------- Lock (mkdir 원자성) ----------

# hpx_lock_dir <task_id>
hpx_lock_dir() {
  hpx_task_id_validate "$1" || return 1
  printf 'docs/plans/%s.lock' "$1"
}

# hpx_lock_acquire <task_id> <command> [existing_session_id]
# - existing_session_id 가 주어지고 lock meta 의 session_id 와 일치하면 re-enter (idempotent)
# - 성공 시 stdout 에 session_id 출력, exit 0
# - 실패 시 exit 1
hpx_lock_acquire() {
  local task_id="$1"
  local command="$2"
  local reuse_session="${3:-}"
  hpx_task_id_validate "$task_id" || return 1
  local lock_dir
  lock_dir="$(hpx_lock_dir "$task_id")"
  local pid_file="$lock_dir/pid"
  local meta_file="$lock_dir/meta.json"

  mkdir -p docs/plans >/dev/null 2>&1 || true

  if ! mkdir "$lock_dir" 2>/dev/null; then
    local existing_session=""
    if [ -f "$meta_file" ]; then
      existing_session="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("session_id",""))' "$meta_file" 2>/dev/null || true)"
    fi

    # Re-enter: caller provided a session id that matches existing lock
    if [ -n "$reuse_session" ] && [ -n "$existing_session" ] && [ "$reuse_session" = "$existing_session" ]; then
      printf '%s\n' "$existing_session"
      return 0
    fi

    local existing_pid=""
    if [ -f "$pid_file" ]; then
      existing_pid="$(cat "$pid_file" 2>/dev/null || true)"
    fi
    if [ -n "$existing_pid" ] && kill -0 "$existing_pid" 2>/dev/null; then
      printf 'lock: 다른 세션 진행 중 (pid=%s, session=%s, lock=%s). 자동 삭제 금지.\n' \
        "$existing_pid" "${existing_session:-unknown}" "$lock_dir" >&2
      return 1
    fi
    printf 'lock: stale 가능 lock 발견 (pid=%s, session=%s, lock=%s). 자동 삭제 금지 — meta 확인 후 수동 해제.\n' \
      "${existing_pid:-unknown}" "${existing_session:-unknown}" "$lock_dir" >&2
    return 1
  fi

  local session_id
  session_id="${reuse_session:-$(hpx_session_id)}"
  printf '%s\n' "$$" >"$pid_file"
  printf '{"session_id":"%s","pid":%s,"started_at":"%s","command":"%s","task_id":"%s"}\n' \
    "$session_id" "$$" "$(date -u +%FT%TZ)" "$command" "$task_id" >"$meta_file"

  printf '%s\n' "$session_id"
  return 0
}

# hpx_lock_force_release <task_id>
# 명시적으로 호출될 때만 lock 제거 (stale override 포함). audit 기록은 호출자 책임.
hpx_lock_force_release() {
  hpx_task_id_validate "$1" || return 1
  local lock_dir
  lock_dir="$(hpx_lock_dir "$1")" || return 1
  rm -rf "$lock_dir"
}

# hpx_lock_release <task_id>
hpx_lock_release() {
  hpx_task_id_validate "$1" || return 1
  local lock_dir
  lock_dir="$(hpx_lock_dir "$1")" || return 1
  rm -rf "$lock_dir"
}

