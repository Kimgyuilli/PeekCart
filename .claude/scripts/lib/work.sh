# ---------- /work: base branch / diff capture / split / risk ----------

# hpx_base_branch_name
# $PEAKCART_BASE_BRANCH -> git config peakcart.baseBranch -> origin/HEAD -> 'main'
# stdout: base branch **이름** (display/gh pr create --base 용). merge-base 계산 없음.
hpx_base_branch_name() {
  local base_branch
  base_branch="${PEAKCART_BASE_BRANCH:-}"
  base_branch="${base_branch:-$(git config --get peakcart.baseBranch 2>/dev/null)}"
  base_branch="${base_branch:-$(git symbolic-ref --short refs/remotes/origin/HEAD 2>/dev/null | sed 's|^origin/||')}"
  base_branch="${base_branch:-main}"
  printf '%s\n' "${base_branch}"
}

# hpx_base_branch_discover
# stdout: resolved base ref (merge-base SHA 반환, `git diff $BASE` 에서 사용).
# 이름이 필요하면 hpx_base_branch_name.
hpx_base_branch_discover() {
  local base_branch base
  base_branch="$(hpx_base_branch_name)"
  base="$(git merge-base HEAD "origin/${base_branch}" 2>/dev/null \
         || git merge-base HEAD "${base_branch}" 2>/dev/null \
         || printf '%s' "${base_branch}")"
  printf '%s\n' "${base}"
}

# hpx_diff_capture <task_id> <ts>
# git diff <BASE> > .cache/diffs/diff-<task>-<ts>.patch, 경로 echo
# 주의: `git diff` 는 untracked 파일을 포함하지 않으므로, .gitignore 에 걸리지 않은
# untracked 파일을 intent-to-add 으로 먼저 등록해 diff 에 포함시킨다. 사용자 staging
# 작업 (`git add -p` 등) 과 충돌하지 않도록 격리된 임시 index (`GIT_INDEX_FILE`) 에서
# 수행하여 실제 `.git/index` 는 건드리지 않는다.
hpx_diff_capture() {
  local task_id="$1"
  local ts="$2"
  hpx_task_id_validate "$task_id" || return 1
  local base path
  base="$(hpx_base_branch_discover)"
  path=".cache/diffs/diff-${task_id}-${ts}.patch"
  mkdir -p .cache/diffs >/dev/null 2>&1 || true

  # 격리된 임시 index 디렉토리. 실제 .git/index 를 그대로 복사하여 staged 변경
  # (수정/신규 모두) 을 보존한 상태로 untracked 만 추가 등록한다.
  local tmp_dir tmp_index git_dir real_index
  tmp_dir="$(mktemp -d -t hpx-diff-index.XXXXXX)" || return 1
  tmp_index="${tmp_dir}/index"
  git_dir="$(git rev-parse --git-dir 2>/dev/null)" || {
    rm -rf "$tmp_dir"
    return 1
  }
  real_index="${git_dir}/index"
  if [ -f "$real_index" ]; then
    cp "$real_index" "$tmp_index" || {
      rm -rf "$tmp_dir"
      return 1
    }
  fi
  # unborn repo / 신규 worktree 는 .git/index 가 없을 수 있다 — 빈 tmp_index 로 진행
  # (git 이 0 byte index 를 빈 트리로 수용).

  # NUL-delimited 파이프 직결 (변수 경유 X) 로 공백/개행 파일명 안전.
  # untracked 0 개일 때 git add 호출은 일어나지 않는다 (xargs 가 빈 입력 시 미실행).
  GIT_INDEX_FILE="$tmp_index" git ls-files --others --exclude-standard -z 2>/dev/null \
    | xargs -0 sh -c '[ "$#" -gt 0 ] && GIT_INDEX_FILE="'"$tmp_index"'" git add -N -- "$@"' _ \
    2>/dev/null || true

  if ! GIT_INDEX_FILE="$tmp_index" git diff "${base}" >"${path}"; then
    rm -rf "$tmp_dir"
    return 1
  fi
  rm -rf "$tmp_dir"
  printf '%s\n' "${path}"
}

# hpx_diff_lines <patch_path>
hpx_diff_lines() {
  local p="$1"
  if [ ! -f "$p" ]; then printf '0\n'; return; fi
  wc -l <"$p" | tr -d ' '
}

# hpx_diff_files <patch_path>
# diff --git a/<f> b/<f> 로부터 b 측 파일 목록 추출 (중복 제거)
hpx_diff_files() {
  local p="$1"
  [ -f "$p" ] || return 0
  awk '/^diff --git / { sub(/^b\//,"",$4); print $4 }' "$p" | awk '!seen[$0]++'
}

# hpx_diff_absorption_status <patch_path>
# stdout:
#   all_absorbed   - patch files exist but none are currently uncommitted
#   partially_live - patch files 일부만 현재 uncommitted
#   all_live       - patch files 전부 현재 uncommitted
#   no_files       - patch 에서 파일을 추출할 수 없음
hpx_diff_absorption_status() {
  local patch_path="$1"
  [ -f "$patch_path" ] || { printf 'no_files\n'; return 0; }

  python3 - "$patch_path" <<'PY'
import subprocess, sys
from pathlib import Path

patch = Path(sys.argv[1])
files = []
for line in patch.read_text(encoding="utf-8", errors="ignore").splitlines():
    if line.startswith("diff --git "):
        parts = line.split()
        if len(parts) >= 4:
            path = parts[3]
            if path.startswith("b/"):
                path = path[2:]
            files.append(path)

files = list(dict.fromkeys(files))
if not files:
    print("no_files")
    raise SystemExit(0)

res = subprocess.run(
    ["git", "status", "--porcelain"],
    check=True,
    capture_output=True,
    text=True,
)
uncommitted = set()
for raw in res.stdout.splitlines():
    if len(raw) < 4:
        continue
    path = raw[3:]
    if " -> " in path:
        path = path.split(" -> ", 1)[1]
    uncommitted.add(path)

matched = [f for f in files if f in uncommitted]
if len(matched) == 0:
    print("all_absorbed")
elif len(matched) == len(files):
    print("all_live")
else:
    print("partially_live")
PY
}

# hpx_risk_classify <patch_path>
# stdout: 두 줄 — line1: risk_level (low|medium|high), line2: CSV signals
hpx_risk_classify() {
  local p="$1"
  local lines signals=() files
  lines="$(hpx_diff_lines "$p")"
  files="$(hpx_diff_files "$p" || true)"

  if [ "${lines:-0}" -ge 800 ]; then signals+=("diff_large_800"); fi
  if [ "${lines:-0}" -ge 500 ]; then signals+=("split_review_candidate"); fi

  while IFS= read -r f; do
    [ -z "$f" ] && continue
    case "$f" in
      *auth*|*Auth*|*security*|*Security*|*oauth*|*OAuth*|*jwt*|*Jwt*|*JWT*)
        signals+=("auth_touch"); break;;
    esac
  done <<<"$files"
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    case "$f" in
      *payment*|*Payment*|*billing*|*Billing*|*order*|*Order*)
        signals+=("payment_touch"); break;;
    esac
  done <<<"$files"
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    case "$f" in
      *.yml|*.yaml|*.properties|*.env|infra/*|k8s/*|helm/*|kustomize/*|terraform/*|.github/workflows/*)
        signals+=("config_infra_touch"); break;;
    esac
  done <<<"$files"

  local level="low"
  for s in "${signals[@]:-}"; do
    case "$s" in
      diff_large_800|auth_touch|payment_touch|config_infra_touch) level="high";;
    esac
  done
  if [ "$level" = "low" ] && [ "${#signals[@]}" -gt 0 ]; then
    level="medium"
  fi

  # dedup signals
  local joined=""
  if [ "${#signals[@]}" -gt 0 ]; then
    joined="$(printf '%s\n' "${signals[@]}" | awk '!s[$0]++' | paste -sd, -)"
  fi
  printf '%s\n%s\n' "$level" "${joined}"
}

# hpx_diff_split <patch_path> <out_dir> [max_chunks=3]
# 우선순위: (1) 실행 코드 (2) 테스트 (3) 설정/문서. file 단위로 chunk 할당.
# stdout: 한 줄당 "<chunk_index>\t<chunk_path>\t<file_count>\t<line_count>"
hpx_diff_split() {
  local patch="$1"
  local out_dir="$2"
  local max_chunks="${3:-3}"
  mkdir -p "$out_dir" >/dev/null 2>&1 || true
  python3 - "$patch" "$out_dir" "$max_chunks" <<'PY'
import os, re, sys
patch_path, out_dir, max_chunks = sys.argv[1], sys.argv[2], int(sys.argv[3])
with open(patch_path, 'r', errors='replace') as f:
    data = f.read()
# diff --git a/.. b/.. 기준으로 split
parts = re.split(r'(?m)^(?=diff --git )', data)
blocks = [p for p in parts if p.startswith('diff --git ')]

def classify(block):
    m = re.search(r'^diff --git a/\S+ b/(\S+)', block, re.M)
    fname = m.group(1) if m else ''
    low = fname.lower()
    if re.search(r'(^|/)(src/test/|test/|tests/|__tests__/|.*\.test\.|.*\.spec\.)', low):
        return 2, fname  # tests
    if low.endswith(('.md','.yml','.yaml','.properties','.json','.toml','.ini','.txt')) \
       or low.startswith(('docs/','infra/','k8s/','helm/','kustomize/','terraform/','.github/')):
        return 3, fname  # config/docs
    return 1, fname  # executable code

bucketed = {1: [], 2: [], 3: []}
for b in blocks:
    pr, fname = classify(b)
    bucketed[pr].append((fname, b))

# Flatten by priority, then chunk up to max_chunks by roughly equal line count
ordered = bucketed[1] + bucketed[2] + bucketed[3]
if not ordered:
    sys.exit(0)

total_lines = sum(b.count('\n') for _, b in ordered)
# If only 1 chunk needed or few files: don't split further than necessary
n_chunks = min(max_chunks, max(1, len(ordered)))
# simple greedy pack to balance lines
chunks = [[] for _ in range(n_chunks)]
loads = [0] * n_chunks
for fname, block in ordered:
    idx = loads.index(min(loads))
    chunks[idx].append((fname, block))
    loads[idx] += block.count('\n')

# collapse empty tails
chunks = [c for c in chunks if c]

base = os.path.basename(patch_path).rsplit('.patch', 1)[0]
for i, c in enumerate(chunks, start=1):
    out = os.path.join(out_dir, f'{base}-c{i}.patch')
    with open(out, 'w') as f:
        for _, blk in c:
            f.write(blk)
    fcount = len(c)
    lcount = sum(b.count('\n') for _, b in c)
    print(f'{i}\t{out}\t{fcount}\t{lcount}')
PY
}

