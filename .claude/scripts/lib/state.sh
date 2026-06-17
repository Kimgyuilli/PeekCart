# ---------- State (atomic write) ----------

hpx_state_path() {
  hpx_task_id_validate "$1" || return 1
  printf 'docs/plans/%s.state.json' "$1"
}

# hpx_state_exists <task_id>
hpx_state_exists() {
  hpx_task_id_validate "$1" || return 1
  [ -f "$(hpx_state_path "$1")" ]
}

# hpx_state_read <task_id>
# stdout 에 JSON 전체 출력. 없으면 빈 문자열, exit 1.
hpx_state_read() {
  hpx_task_id_validate "$1" || return 1
  local path
  path="$(hpx_state_path "$1")" || return 1
  if [ ! -f "$path" ]; then
    return 1
  fi
  cat "$path"
}

# hpx_state_write <task_id> <json>
# stdin 으로 JSON 받으면 그쪽 우선. 원자적 tmp + mv.
hpx_state_write() {
  local task_id="$1"
  shift
  hpx_task_id_validate "$task_id" || return 1
  local path tmp parent_dir
  path="$(hpx_state_path "$task_id")" || return 1
  tmp="${path}.tmp.$$"
  parent_dir="$(hpx_parent_dir "$path")"

  mkdir -p "$parent_dir" >/dev/null 2>&1 || true

  if [ $# -gt 0 ]; then
    printf '%s\n' "$*" >"$tmp"
  else
    cat >"$tmp"
  fi

  if ! python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$tmp" 2>/dev/null; then
    printf 'state_write: invalid JSON, aborting. tmp=%s\n' "$tmp" >&2
    rm -f "$tmp"
    return 1
  fi

  mv "$tmp" "$path"
}

hpx_state_payload_preview() {
  local limit="${1:-240}"
  python3 - "$limit" <<'PY'
import sys

limit = int(sys.argv[1])
text = sys.stdin.read().strip().replace("\n", " ")
if len(text) > limit:
    text = text[: limit - 3] + "..."
print(text)
PY
}

hpx_state_log_failure() {
  local helper_name="$1"
  local task_id="$2"
  local command_name="${3:-unknown}"
  local step_name="${4:-unknown}"
  local reason="${5:-unknown}"
  local payload_preview="${6:-}"
  printf 'state_mutation: helper=%s task=%s command=%s step=%s reason=%s' \
    "$helper_name" "$task_id" "$command_name" "$step_name" "$reason" >&2
  if [ -n "$payload_preview" ]; then
    printf ' payload=%s' "$payload_preview" >&2
  fi
  printf '\n' >&2
}

_hpx_state_mutate_json() {
  local task_id="$1"
  local helper_name="$2"
  local command_name="$3"
  local step_name="$4"
  local mode="$5"
  local field_path="${6:-}"
  local payload="${7:-}"
  local unique_key="${8:-}"
  local state_path tmp_json tmp_err payload_preview reason

  state_path="$(hpx_state_path "$task_id")"
  payload_preview="$(printf '%s' "$payload" | hpx_state_payload_preview 240)"
  tmp_json="$(mktemp "${TMPDIR:-/tmp}/hpx-state-json.XXXXXX")" || return 1
  tmp_err="$(mktemp "${TMPDIR:-/tmp}/hpx-state-err.XXXXXX")" || {
    rm -f "$tmp_json"
    return 1
  }

  if ! HPX_STATE_PATH="$state_path" \
    HPX_STATE_MODE="$mode" \
    HPX_STATE_FIELD_PATH="$field_path" \
    HPX_STATE_PAYLOAD="$payload" \
    HPX_STATE_UNIQUE_KEY="$unique_key" \
    python3 - >"$tmp_json" 2>"$tmp_err" <<'PY'
import json
import os
import sys
from pathlib import Path

state_path = Path(os.environ["HPX_STATE_PATH"])
mode = os.environ["HPX_STATE_MODE"]
field_path = os.environ.get("HPX_STATE_FIELD_PATH", "")
payload_raw = os.environ.get("HPX_STATE_PAYLOAD", "")
unique_key = os.environ.get("HPX_STATE_UNIQUE_KEY", "")


def load_state(path: Path):
    if not path.exists():
        raise FileNotFoundError(f"state file missing: {path}")
    with path.open(encoding="utf-8") as fh:
        return json.load(fh)


def load_payload(raw: str):
    return json.loads(raw)


def resolve_parent(doc, path: str):
    if not path:
        raise ValueError("field_path is required")
    current = doc
    parts = path.split(".")
    for key in parts[:-1]:
        if key not in current or current[key] is None:
            current[key] = {}
        if not isinstance(current[key], dict):
            raise TypeError(f"path is not an object at {key}")
        current = current[key]
    return current, parts[-1]


def deep_merge(base, patch):
    if isinstance(base, dict) and isinstance(patch, dict):
        merged = dict(base)
        for key, value in patch.items():
            merged[key] = deep_merge(merged.get(key), value)
        return merged
    return patch


def should_append(current, item, key: str):
    if key == "scalar":
        return item not in current
    if key:
        item_key = item.get(key) if isinstance(item, dict) else None
        if item_key is None:
            raise ValueError(f"append item missing unique key: {key}")
        for existing in current:
            if isinstance(existing, dict) and existing.get(key) == item_key:
                return False
        return True
    return True


state = load_state(state_path)
item = load_payload(payload_raw) if mode == "append" else None

if mode == "patch":
    state = deep_merge(state, load_payload(payload_raw))
elif mode == "set":
    parent, leaf = resolve_parent(state, field_path)
    parent[leaf] = load_payload(payload_raw)
elif mode == "append":
    parent, leaf = resolve_parent(state, field_path)
    current = parent.get(leaf)
    if current is None:
        current = []
        parent[leaf] = current
    if not isinstance(current, list):
        raise TypeError(f"path is not a list: {field_path}")
    if should_append(current, item, unique_key):
        current.append(item)
else:
    raise ValueError(f"unsupported mode: {mode}")

json.dump(state, sys.stdout, ensure_ascii=False, indent=2)
print()
PY
  then
    reason="$(tr '\n' ' ' <"$tmp_err" | sed 's/[[:space:]]\+/ /g')"
    hpx_state_log_failure "$helper_name" "$task_id" "$command_name" "$step_name" "${reason:-python_mutation_failed}" "$payload_preview"
    rm -f "$tmp_json" "$tmp_err"
    return 1
  fi

  if ! hpx_state_write "$task_id" <"$tmp_json" 2>>"$tmp_err"; then
    reason="$(tr '\n' ' ' <"$tmp_err" | sed 's/[[:space:]]\+/ /g')"
    hpx_state_log_failure "$helper_name" "$task_id" "$command_name" "$step_name" "${reason:-state_write_failed}" "$payload_preview"
    rm -f "$tmp_json" "$tmp_err"
    return 1
  fi

  rm -f "$tmp_json" "$tmp_err"
}

# hpx_state_init <task_id> <session_id> [started_at] [command_name] [step_name]
hpx_state_init() {
  local task_id="$1"
  local session_id="$2"
  local started_at="${3:-$(date -u +%FT%TZ)}"
  local command_name="${4:-plan}"
  local step_name="${5:-state.init}"
  local payload

  payload="$(python3 - "$task_id" "$session_id" "$started_at" <<'PY'
import json
import sys

task_id, session_id, started_at = sys.argv[1:4]
state = {
    "task_id": task_id,
    "stage": "plan.draft.created",
    "session_id": session_id,
    "branch": None,
    "completed_plan_items": [],
    "pending_run": None,
    "review_plan": None,
    "review_runs": [],
    "loop_count_by_command": {"plan": 0, "work": 0},
    "attempts_by_command": {"plan": 0, "work": 0},
    "codex_attempts_cycle_total": 0,
    "commit_plan": [],
    "created_commits": [],
    "ship_resume_cursor": None,
    "push_status": None,
    "remote_branch": None,
    "pr_url": None,
    "done_applied": False,
    "last_diff_path": None,
    "started_at": started_at,
    "updated_at": started_at,
}
print(json.dumps(state, ensure_ascii=False, indent=2))
PY
)"

  if ! hpx_state_write "$task_id" < <(printf '%s\n' "$payload"); then
    hpx_state_log_failure "hpx_state_init" "$task_id" "$command_name" "$step_name" "state_write_failed" "$(printf '%s' "$payload" | hpx_state_payload_preview 240)"
    return 1
  fi
}

# hpx_state_patch <task_id> <patch_json> [command_name] [step_name]
hpx_state_patch() {
  local task_id="$1"
  local patch_json="$2"
  local command_name="${3:-unknown}"
  local step_name="${4:-state.patch}"
  _hpx_state_mutate_json "$task_id" "hpx_state_patch" "$command_name" "$step_name" "patch" "" "$patch_json"
}

# hpx_state_set_json_field <task_id> <field_path> <json_value> [command_name] [step_name]
hpx_state_set_json_field() {
  local task_id="$1"
  local field_path="$2"
  local json_value="$3"
  local command_name="${4:-unknown}"
  local step_name="${5:-state.set_json}"
  _hpx_state_mutate_json "$task_id" "hpx_state_set_json_field" "$command_name" "$step_name" "set" "$field_path" "$json_value"
}

# hpx_state_set_string_field <task_id> <field_path> <string_value> [command_name] [step_name]
hpx_state_set_string_field() {
  local task_id="$1"
  local field_path="$2"
  local string_value="$3"
  local command_name="${4:-unknown}"
  local step_name="${5:-state.set_string}"
  local json_value
  json_value="$(python3 - "$string_value" <<'PY'
import json
import sys
print(json.dumps(sys.argv[1], ensure_ascii=False))
PY
)"
  _hpx_state_mutate_json "$task_id" "hpx_state_set_string_field" "$command_name" "$step_name" "set" "$field_path" "$json_value"
}

# hpx_state_append_json_array_item <task_id> <field_path> <item_json> [command_name] [step_name]
hpx_state_append_json_array_item() {
  local task_id="$1"
  local field_path="$2"
  local item_json="$3"
  local unique_key="${4:-}"
  local command_name="${5:-unknown}"
  local step_name="${6:-state.append_json}"
  _hpx_state_mutate_json "$task_id" "hpx_state_append_json_array_item" "$command_name" "$step_name" "append" "$field_path" "$item_json" "$unique_key"
}

# hpx_state_append_string_array_item <task_id> <field_path> <string_value> [command_name] [step_name]
hpx_state_append_string_array_item() {
  local task_id="$1"
  local field_path="$2"
  local string_value="$3"
  local command_name="${4:-unknown}"
  local step_name="${5:-state.append_string}"
  local item_json
  item_json="$(python3 - "$string_value" <<'PY'
import json
import sys
print(json.dumps(sys.argv[1], ensure_ascii=False))
PY
)"
  _hpx_state_mutate_json "$task_id" "hpx_state_append_string_array_item" "$command_name" "$step_name" "append" "$field_path" "$item_json" "scalar"
}

hpx_state_set_stage() {
  local task_id="$1"
  local stage="$2"
  local command_name="${3:-unknown}"
  local step_name="${4:-state.set_stage}"
  hpx_state_set_string_field "$task_id" "stage" "$stage" "$command_name" "$step_name"
}

hpx_state_append_completed_item() {
  local task_id="$1"
  local item_id="$2"
  local command_name="${3:-work}"
  local step_name="${4:-state.append_completed_item}"
  hpx_state_append_string_array_item "$task_id" "completed_plan_items" "$item_id" "$command_name" "$step_name"
}

hpx_state_set_pending_run() {
  local task_id="$1"
  local run_json="$2"
  local command_name="${3:-unknown}"
  local step_name="${4:-state.set_pending_run}"
  hpx_state_set_json_field "$task_id" "pending_run" "$run_json" "$command_name" "$step_name"
}

hpx_state_set_review_plan() {
  local task_id="$1"
  local review_plan_json="$2"
  local command_name="${3:-work}"
  local step_name="${4:-state.set_review_plan}"
  hpx_state_set_json_field "$task_id" "review_plan" "$review_plan_json" "$command_name" "$step_name"
}

hpx_state_append_review_run() {
  local task_id="$1"
  local run_json="$2"
  local command_name="${3:-unknown}"
  local step_name="${4:-state.append_review_run}"
  hpx_state_append_json_array_item "$task_id" "review_runs" "$run_json" "run_id" "$command_name" "$step_name"
}

hpx_state_set_last_diff_path() {
  local task_id="$1"
  local diff_path="$2"
  local command_name="${3:-work}"
  local step_name="${4:-state.set_last_diff_path}"
  hpx_state_set_string_field "$task_id" "last_diff_path" "$diff_path" "$command_name" "$step_name"
}

hpx_state_set_commit_plan() {
  local task_id="$1"
  local commit_plan_json="$2"
  local command_name="${3:-ship}"
  local step_name="${4:-state.set_commit_plan}"
  hpx_state_set_json_field "$task_id" "commit_plan" "$commit_plan_json" "$command_name" "$step_name"
}

hpx_state_append_created_commit() {
  local task_id="$1"
  local commit_json="$2"
  local command_name="${3:-ship}"
  local step_name="${4:-state.append_created_commit}"
  hpx_state_append_json_array_item "$task_id" "created_commits" "$commit_json" "partition_id" "$command_name" "$step_name"
}

# hpx_run_id_new <command> <session_id> <attempt> [chunk_index]
hpx_run_id_new() {
  local command="$1"
  local session_id="$2"
  local attempt="$3"
  local chunk="${4:-}"
  local ts
  ts="$(hpx_utc_ts)"
  if [ -n "$chunk" ]; then
    printf '%s:%s:%s:%s:c%s' "$command" "$ts" "$session_id" "$attempt" "$chunk"
  else
    printf '%s:%s:%s:%s' "$command" "$ts" "$session_id" "$attempt"
  fi
}

