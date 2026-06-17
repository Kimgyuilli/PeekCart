# ---------- Sync context 수집 ----------

# hpx_plan_lint <task_id>
# 계획서의 필수 섹션/작업 항목 stable id 규약을 기계적으로 점검한다.
# stdout: 문제 목록 또는 "OK"
# exit 0: 통과, exit 1: 실패
hpx_plan_lint() {
  local task_id="$1"
  hpx_task_id_validate "$task_id" || return 1
  local path="docs/plans/${task_id}.md"
  python3 - "$path" <<'PY'
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
if not path.exists():
    print(f"- 계획서가 없습니다: {path}")
    raise SystemExit(1)

text = path.read_text(encoding="utf-8", errors="ignore")
heading_lines = [line.strip() for line in text.splitlines() if line.lstrip().startswith("#")]
errors = []

required = {
    "목표/목적": [r"목표", r"목적"],
    "배경/제약": [r"배경", r"제약"],
    "작업 항목": [r"작업 항목"],
    "영향 파일": [r"영향 파일"],
    "검증 방법": [r"검증 방법", r"검증"],
    "완료 조건": [r"완료 조건"],
}

for label, keywords in required.items():
    if not any(any(re.search(k, line) for k in keywords) for line in heading_lines):
        errors.append(f"- 필수 섹션 누락: {label}")

stable_ids = re.findall(r"^- \[[ xX]\] \*\*(P\d+\.)\*\*", text, flags=re.M)
if not stable_ids:
    errors.append("- 작업 항목 stable id 가 없습니다 (`- [ ] **P1.**` 형식 필요)")
else:
    expected = [f"P{i}." for i in range(1, len(stable_ids) + 1)]
    if stable_ids != expected:
        errors.append(f"- stable id 순서가 연속적이지 않습니다: {', '.join(stable_ids)}")

if errors:
    print("\n".join(errors))
    raise SystemExit(1)

print("OK")
PY
}

# hpx_sync_context
# TASKS / ADR / git 상태를 구조화 요약으로 출력.
hpx_sync_context() {
  python3 - <<'PY'
import glob
import json
import re
import subprocess
from pathlib import Path

def run(*cmd):
    res = subprocess.run(cmd, capture_output=True, text=True)
    return res.stdout.strip()

def print_block(title, rows):
    print(title)
    if not rows:
        print("- (none)")
        return
    for row in rows:
        print(f"- {row}")

print("=== SYNC SUMMARY ===")
branch = run("git", "branch", "--show-current") or "(unknown)"
status_lines = [line for line in run("git", "status", "--short").splitlines() if line.strip()]
print(f"branch: {branch}")
print(f"worktree: {'clean' if not status_lines else f'dirty ({len(status_lines)} paths)'}")

state_rows = []
for path in sorted(glob.glob("docs/plans/*.state.json")):
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        state_rows.append(f"{Path(path).name}: stage={data.get('stage','?')} updated_at={data.get('updated_at','?')}")
    except Exception:
        state_rows.append(f"{Path(path).name}: (unreadable)")
print_block("active_states:", state_rows)

lock_rows = [Path(path).name for path in sorted(glob.glob("docs/plans/*.lock"))]
print_block("active_locks:", lock_rows)

tasks_path = Path("docs/TASKS.md")
phase = "(missing)"
pending = []
if tasks_path.exists():
    text = tasks_path.read_text(encoding="utf-8", errors="ignore")
    m = re.search(r"^## 현재 Phase:\s*(.+)$", text, flags=re.M)
    if m:
        phase = m.group(1).strip()
    lines = text.splitlines()
    for idx, line in enumerate(lines):
        if line.startswith("### Task "):
            title = line.strip("# ").strip()
            status = ""
            goal = ""
            for nxt in lines[idx + 1: idx + 6]:
                if nxt.startswith("**상태**:"):
                    status = nxt.split(":", 1)[1].strip()
                if nxt.startswith("**목표**:"):
                    goal = nxt.split(":", 1)[1].strip()
            if status.startswith(("🔲", "🔄")):
                pending.append(f"{title} ({status})" + (f" — {goal}" if goal else ""))
    pending = pending[:3]
print(f"current_phase: {phase}")
print_block("pending_tasks:", pending)

adr_ids = []
adr_readme = Path("docs/adr/README.md")
if adr_readme.exists():
    text = adr_readme.read_text(encoding="utf-8", errors="ignore")
    adr_ids = list(dict.fromkeys(re.findall(r"ADR-\d{4}", text)))[:8]
print_block("adr_ids:", adr_ids)

commit_rows = [line for line in run("git", "log", "--oneline", "-5").splitlines() if line.strip()]
print_block("recent_commits:", commit_rows)
PY
}

# hpx_diff_meta_summary <patch_path>
# diff 의 파일/카테고리/라인 수를 요약해 stdout 으로 출력한다.
hpx_diff_meta_summary() {
  local patch_path="$1"
  python3 - "$patch_path" <<'PY'
import re
import sys
from pathlib import Path

patch = Path(sys.argv[1])
if not patch.exists():
    print("files_total: 0")
    print("lines_total: 0")
    raise SystemExit(0)

blocks = re.split(r'(?m)^(?=diff --git )', patch.read_text(encoding="utf-8", errors="ignore"))
blocks = [b for b in blocks if b.startswith("diff --git ")]

def classify(path: str) -> str:
    low = path.lower()
    if re.search(r'(^|/)(src/test/|test/|tests/|__tests__/|.*\.test\.|.*\.spec\.)', low):
        return "test"
    if low.endswith(('.md', '.txt')):
        return "docs"
    if low.endswith(('.yml', '.yaml', '.properties', '.json', '.toml', '.ini')) or low.startswith(('k8s/', 'infra/', '.github/')):
        return "config"
    return "src"

rows = []
totals = {"src": 0, "test": 0, "docs": 0, "config": 0}
line_total = 0
for block in blocks:
    m = re.search(r'^diff --git a/\S+ b/(\S+)', block, re.M)
    path = m.group(1) if m else "(unknown)"
    category = classify(path)
    added = sum(1 for line in block.splitlines() if line.startswith("+") and not line.startswith("+++"))
    removed = sum(1 for line in block.splitlines() if line.startswith("-") and not line.startswith("---"))
    delta = added + removed
    status = "new" if "/dev/null" in block else "modified"
    totals[category] += 1
    line_total += delta
    rows.append(f"{path} [{category}, {status}, {delta} lines]")

print(f"files_total: {len(rows)}")
print(f"lines_total: {line_total}")
print(f"category_counts: src={totals['src']} test={totals['test']} docs={totals['docs']} config={totals['config']}")
for row in rows:
    print(f"- {row}")
PY
}

