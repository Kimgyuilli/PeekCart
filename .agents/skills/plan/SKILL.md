---
name: plan
description: Create or update a PeakCart task plan with grade, code-verified premises, ADR decision, stable work items, and verification. Use for planning a repository task.
---

# Plan a task

Run from the repository root. Reuse `.claude/commands/plan.md` sections 1–4 for grading and plan format. Its sections 5–8 call `codex exec`, render review findings, and request review rounds; do not run those steps or call a second Codex reviewer.

1. Resolve a task ID from the request or `docs/TASKS.md`. Validate it with `hpx_task_id_validate` from `.claude/scripts/shared-logic.sh` before using it in a path. If the next task is ambiguous, identify the candidates.
2. Judge S/M/L by the original three impact criteria: modules touched, shared contract change, and rollback scope. Record `grade: S|M|L` in `docs/plans/<task-id>.md`; `hpx_plan_grade` is the shared reader. Check code premises at every grade.
3. Inspect current code and relevant docs/ADR before drafting. Verify referenced components, dependency direction, and already completed parent-plan items. For structural changes, work through applicable checks in `docs/plans/PLAN-BLINDSPOTS.md`, including inbound references in tests and string identifiers. If evidence changes scope, revisit the grade.
4. Apply the ADR decision criteria in the original plan command and `docs/adr/README.md`. Make required ADR work concrete before implementation; surface a material unresolved decision to the user.
5. Write the grade-specific plan shape from section 4 of the original command. Every plan needs a negative completion proposition, stable `P1.`/`P2.` work-item IDs, and verification that can expose a wrong implementation. Record known out-of-scope items and their disposition. Check the plan against code and required sections before finishing.

No separate plan review is performed. Preserve an existing audit file. If recording a planning audit for M/L, say `별도 Codex 리뷰 미호출` and record code checks and unresolved items; do not invent findings, severity counts, or a pass result.
