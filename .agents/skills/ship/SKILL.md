---
name: ship
description: Prepare or execute PeakCart commits, push, PR creation, and completion updates for a finished task. Use for shipping a planned task.
---

# Ship a task

Run from the repository root. Follow `.claude/commands/ship.md` for its dry-run and execute modes, except its `hpx_review_health` check. The original command does not call Codex; reuse its existing shell helpers and scripts.

- Default to dry-run. Resolve and validate the task ID, then run `hpx_ship_preflight`, `hpx_plan_grade`, and `hpx_consistency_precheck` through `.claude/scripts/shared-logic.sh`. Use `hpx_staged_category_check` for explicit staged paths. Do not use `git add -A`.
- Build `.cache/pr-body-<task-id>.md` from the plan, actual code and test results, ADRs, and git facts. Keep the original Why, What, How, Test plan, related, and applicable deferred/unmet sections. In `리뷰 이력`, state `별도 Codex 리뷰 미호출` for this Codex workflow. Preserve historical review results already in an audit, but do not manufacture a new review result or run `hpx_review_health` on an unreviewed task.
- Run `scripts/writing-lint.sh` for commit messages, PR body, and PR title as in the original command. Show a concrete preview and what execute would do. Dry-run may write `.cache/` artifacts, but does not commit, push, create a PR, or update completion docs.
- In execute mode, follow the original command's preflight, commit classification, push, existing-PR lookup, PR creation, and completion-document update steps. Use `hpx_ship_resume_point` to recover from an interrupted run. Do not merge, force push, or silently ignore a failed check.
- Use `done` only after a PR URL is known. Preserve the original `/ship` result audit for M/L, recording the PR URL, precheck, and document updates without invented review findings. Record the actual PR and any unmet checks. Report the resulting branch and PR URL.

An explicit user request to ship or execute authorizes the corresponding mode. A request to preview only authorizes dry-run. Never turn a dry-run request into a push or PR.
