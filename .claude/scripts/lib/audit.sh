# ---------- Audit log / metrics ----------

# hpx_audit_append <task_id> <markdown_block>
# markdown_block 는 stdin 으로 받아도 됨.
hpx_audit_append() {
  local task_id="$1"
  hpx_task_id_validate "$task_id" || return 1
  local path="docs/plans/.audit/${task_id}.md"
  mkdir -p "$(hpx_parent_dir "$path")" >/dev/null 2>&1 || true
  shift
  {
    if [ $# -gt 0 ]; then
      printf '%s\n\n' "$*"
    else
      cat
      printf '\n'
    fi
  } >>"$path"
}

# 헤더 필요 시 최초 1회만 작성
hpx_metrics_header() {
  printf 'ts\ttask_id\tcommand\trun_id\tloop\tinput_type\tdiff_lines\tinput_bytes\toutput_bytes\tduration_ms\tresult\tfallback_mode\ttokens_in\ttokens_out\tcost_usd\n'
}
hpx_metrics_path() {
  printf '.cache/codex-reviews/_metrics.tsv'
}
# hpx_metrics_append <tsv_line_without_newline>
hpx_metrics_append() {
  local path
  path="$(hpx_metrics_path)"
  mkdir -p "$(hpx_parent_dir "$path")" >/dev/null 2>&1 || true
  if [ ! -f "$path" ]; then
    hpx_metrics_header >"$path"
  fi
  printf '%s\n' "$*" >>"$path"
}

hpx_gate_events_header() {
  printf 'ts\ttask_id\tgate_id\tgate_type\tcommand\trun_id\tshown\tauto_passed\tresult\tuser_choice\tresponse_ms\tdefault_selected\tignored_p0_count\tdegraded_accepted\trisk_level\trisk_signals\treason\n'
}
hpx_gate_events_path() {
  printf '.cache/codex-reviews/gate-events.tsv'
}
# hpx_gate_events_append <tsv_line>
hpx_gate_events_append() {
  local path
  path="$(hpx_gate_events_path)"
  mkdir -p "$(hpx_parent_dir "$path")" >/dev/null 2>&1 || true
  if [ ! -f "$path" ]; then
    hpx_gate_events_header >"$path"
  fi
  printf '%s\n' "$*" >>"$path"
}

