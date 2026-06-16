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

# hpx_commit_plan_group <patch_path>
# 파일별 분류를 TSV 출력: category\tfile\tlines
# category ∈ {adr, docs, test, chore, src}
# Claude 가 이 TSV 를 읽어 partition_id / scope / subject 결정 (§10-3).
hpx_commit_plan_group() {
  local patch="$1"
  [ -f "$patch" ] || return 1
  python3 - "$patch" <<'PY'
import re, sys
patch = sys.argv[1]
with open(patch, 'r', errors='replace') as f:
    data = f.read()

parts = re.split(r'(?m)^(?=diff --git )', data)
blocks = [p for p in parts if p.startswith('diff --git ')]

def classify(fname):
    low = fname.lower()
    if low.startswith('docs/adr/') and low.endswith('.md'):
        return 'adr'
    if low.startswith('docs/') or low.endswith('.md'):
        return 'docs'
    if re.search(r'(^|/)(src/test/|test/|tests/|__tests__/|.*\.test\.|.*\.spec\.)', low):
        return 'test'
    if (low.endswith(('.yml','.yaml','.properties','.toml','.ini'))
        or low.startswith(('infra/','k8s/','helm/','kustomize/','terraform/','.github/'))
        or low in ('build.gradle','settings.gradle','docker-compose.yml','gradle.properties','package.json','pnpm-lock.yaml','yarn.lock','tsconfig.json')):
        return 'chore'
    return 'src'

for b in blocks:
    m = re.search(r'^diff --git a/\S+ b/(\S+)', b, re.M)
    if not m: continue
    fname = m.group(1)
    lines = b.count('\n')
    cat = classify(fname)
    print(f'{cat}\t{fname}\t{lines}')
PY
}

# hpx_ship_pr_body_data <task_id>
# PR 본문 생성을 위한 데이터 번들을 JSON 으로 출력.
# Claude 가 이 데이터를 §10-2 템플릿에 끼워 본문 작성.
# 포함:
#   - task_id, branch, base_branch
#   - commit_subjects[] (branch vs base 구간의 커밋 subject 목록)
#   - accepted_items[] (state.review_runs[] 마지막 work run 의 accepted_ids 와 해당 id 의 finding/suggestion — 원본 raw JSON 참조)
#   - p0_ignores[] (audit log 에서 "P0 무시 사유" 라인 파싱)
#   - adr_mentions[] (plan.md / diff 에서 등장하는 ADR-NNNN)
hpx_ship_pr_body_data() {
  local task_id="$1"
  hpx_task_id_validate "$task_id" || return 1
  local state_path
  state_path="$(hpx_state_path "$task_id")"
  [ -f "$state_path" ] || return 1
  local plan_path="docs/plans/${task_id}.md"
  local audit_path="docs/plans/.audit/${task_id}.md"
  local base_branch
  base_branch="$(hpx_base_branch_name)"
  local current_branch
  current_branch="$(git branch --show-current 2>/dev/null || printf '')"

  python3 - "$task_id" "$state_path" "$plan_path" "$audit_path" "$base_branch" "$current_branch" <<'PY'
import json, os, re, subprocess, sys
task_id, state_path, plan_path, audit_path, base_branch, current_branch = sys.argv[1:7]

with open(state_path, 'r') as f:
    state = json.load(f)

# commits between base_branch and HEAD
commit_subjects = []
try:
    out = subprocess.check_output(
        ['git', 'log', '--pretty=format:%h %s', f'{base_branch}..HEAD'],
        stderr=subprocess.DEVNULL, text=True)
    commit_subjects = [l for l in out.splitlines() if l.strip()]
except Exception:
    pass

# last work run accepted_ids + raw JSON items
accepted_items = []
last_work_run = None
for r in reversed(state.get('review_runs', [])):
    if r.get('command') == 'work':
        last_work_run = r
        break
if last_work_run:
    raw_path = last_work_run.get('raw_path', '')
    accepted_ids = set(last_work_run.get('accepted_ids', []))
    if raw_path and os.path.isfile(raw_path) and accepted_ids:
        try:
            with open(raw_path, 'r') as f:
                raw = json.load(f)
            for it in raw.get('items', []):
                if it.get('id') in accepted_ids:
                    accepted_items.append({
                        'id': it.get('id'),
                        'severity': it.get('severity'),
                        'file': it.get('file'),
                        'line': it.get('line'),
                        'finding': it.get('finding'),
                        'suggestion': it.get('suggestion'),
                    })
        except Exception:
            pass

# P0 ignore reasons from audit log
p0_ignores = []
if os.path.isfile(audit_path):
    with open(audit_path, 'r') as f:
        audit = f.read()
    for m in re.finditer(r'- P0 무시[^\n]*:\s*(.+)', audit):
        p0_ignores.append(m.group(1).strip())

# ADR mentions from plan.md
adr_mentions = []
if os.path.isfile(plan_path):
    with open(plan_path, 'r') as f:
        plan = f.read()
    adr_mentions = sorted(set(re.findall(r'ADR-\d{4}', plan)))

print(json.dumps({
    'task_id': task_id,
    'branch': current_branch,
    'base_branch': base_branch,
    'commit_subjects': commit_subjects,
    'accepted_items': accepted_items,
    'p0_ignores': p0_ignores,
    'adr_mentions': adr_mentions,
    'completed_plan_items': state.get('completed_plan_items', []),
}, ensure_ascii=False, indent=2))
PY
}
