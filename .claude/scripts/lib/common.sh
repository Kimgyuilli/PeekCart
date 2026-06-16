# ---------- 공통 유틸 ----------

# 일부 슬래시 커맨드 실행 환경에서 PATH 가 비정상적으로 축소되는 사례를 방어한다.
# core shell helper 는 /usr/bin, /bin 을 전제로 동작하므로 최소 PATH 를 복구한다.
if [ -z "${PATH:-}" ]; then
  PATH="/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
else
  case ":$PATH:" in
    *:/usr/bin:*) ;;
    *) PATH="$PATH:/usr/bin:/bin:/usr/sbin:/sbin" ;;
  esac
  case ":$PATH:" in
    *:/usr/local/bin:*) ;;
    *) PATH="/usr/local/bin:/opt/homebrew/bin:$PATH" ;;
  esac
fi
export PATH

hpx_parent_dir() {
  local path="$1"
  case "$path" in
    */*) printf '%s\n' "${path%/*}" ;;
    *) printf '.\n' ;;
  esac
}

# hpx_task_id_validate <task_id>
# 경로 보간 helper 진입부에서 호출. allowlist [A-Za-z0-9._-]+, 1~128자,
# `..` 부분문자열 금지, 선두 `-`/`.` 금지 (옵션 인자 / 숨김파일 오인 방지).
# 통과 시 exit 0, 실패 시 stderr 메시지 + exit 1.
hpx_task_id_validate() {
  local task_id="${1-}"
  if [ -z "$task_id" ]; then
    printf 'task_id_validate: empty task_id\n' >&2
    return 1
  fi
  if [ "${#task_id}" -gt 128 ]; then
    printf 'task_id_validate: too long (>128): %s\n' "$task_id" >&2
    return 1
  fi
  case "$task_id" in
    -*|.*)
      printf 'task_id_validate: leading -/. not allowed: %s\n' "$task_id" >&2
      return 1
      ;;
    *..*)
      printf 'task_id_validate: substring "..": %s\n' "$task_id" >&2
      return 1
      ;;
  esac
  case "$task_id" in
    *[!A-Za-z0-9._-]*)
      printf 'task_id_validate: disallowed char: %s\n' "$task_id" >&2
      return 1
      ;;
  esac
  return 0
}

hpx_utc_ts() {
  date -u +%Y%m%dT%H%M%SZ
}

hpx_epoch_ts() {
  date +%s
}

hpx_session_id() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  else
    printf '%s-%s' "$(hpx_epoch_ts)" "$$"
  fi
}

# hpx_timeout_prefix [seconds]
# echo 으로 timeout 명령 prefix 를 출력. eval 로 사용.
hpx_timeout_prefix() {
  local secs="${1:-60}"
  if command -v timeout >/dev/null 2>&1; then
    printf 'timeout %s' "$secs"
  elif command -v gtimeout >/dev/null 2>&1; then
    printf 'gtimeout %s' "$secs"
  elif [ -f scripts/timeout_wrapper.py ]; then
    printf 'python3 scripts/timeout_wrapper.py %s' "$secs"
  else
    printf '__NO_TIMEOUT_PROVIDER__'
    return 1
  fi
}

# hpx_codex_timeout_seconds <command>
# command 별 Codex 호출 timeout 기본값. env override 허용.
hpx_codex_timeout_seconds() {
  local command="${1:-}"
  case "$command" in
    plan)
      printf '%s\n' "${HPX_TIMEOUT_PLAN_SECONDS:-180}"
      ;;
    work)
      printf '%s\n' "${HPX_TIMEOUT_WORK_SECONDS:-180}"
      ;;
    *)
      printf '%s\n' "${HPX_TIMEOUT_DEFAULT_SECONDS:-180}"
      ;;
  esac
}

