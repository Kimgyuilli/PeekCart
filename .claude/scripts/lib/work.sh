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
