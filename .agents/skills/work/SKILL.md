---
name: work
description: Implement a planned PeakCart task, update plan progress, and run its tests and lint checks. Use for work on an existing task plan.
---

# Implement a task

Run from the repository root. Reuse `.claude/commands/work.md` sections 1–3 and 8 for task resolution, plan tracking, and verification. Its sections 4–7 and review portions of section 9 are for a separate `codex exec` reviewer and do not apply.

1. Validate the task ID with `hpx_task_id_validate` in `.claude/scripts/shared-logic.sh`. Read `docs/plans/<task-id>.md`, especially its verification section, and read the grade with `hpx_plan_grade`. If the plan is absent, use `plan` first. Respect the original branch check and existing worktree changes.
2. Implement the plan's `P1.`/`P2.` items. Update a plan item to `[x]` only when code and its verification are complete. If evidence changes the approach or scope, update the plan and grade. Keep tests or other checks with the implementation.
3. Check the complete change, including untracked files, against the plan, affected contracts, ADRs, and layer rules in `CLAUDE.md`. Recheck likely false-green cases: tests that restate implementation, mocks offered as proof of transaction behavior, and a verifier derived from the same source as the code it checks. Fix actual in-scope defects; record bounded follow-ups with a reason.
4. Run `./gradlew test`, each applicable plan verification, and relevant `scripts/*-lint.sh` checks. Report actual passes, failures, and checks that could not run. Do not mark a failed check as complete.

For M/L, preserve any existing `docs/plans/<task-id>.audit.md` and record implementation and verification results if the task uses an audit. Label the separate review `별도 Codex 리뷰 미호출`; never claim zero P0/P1 findings or a passed external review. Continue to `ship` only when the planned work is complete.
