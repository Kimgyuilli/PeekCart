# ---------- Codex 응답 헬퍼 ----------

# hpx_extract_tokens_used <stderr_path>
# stderr 의 "tokens used\n<숫자>" 2줄 패턴에서 숫자 추출.
hpx_extract_tokens_used() {
  local stderr_path="$1"
  [ -f "$stderr_path" ] || { printf ''; return; }
  grep -A1 "tokens used" "$stderr_path" 2>/dev/null | tail -1 | tr -d ' \r\n' || printf ''
}

# hpx_json_validate <path>
# exit 0 if valid JSON, else 1
hpx_json_validate() {
  python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$1" 2>/dev/null
}

