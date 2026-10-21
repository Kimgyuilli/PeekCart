---
name: done
description: Reconcile completed PeakCart work into TASKS, phase progress, ADR status, and Layer 1 docs. Use when asked to mark a task done or after PR creation.
---

# Record completion

Follow `.claude/commands/done.md`. Verify completion against the actual code and tests before changing checkboxes. Update the relevant `docs/TASKS.md` task, current `docs/progress/PHASE{N}.md`, active ADR status and index, and Layer 1 docs only when the completed work affects them. Keep decision rationale in ADRs, current facts in Layer 1, and history in progress. Use the actual current date and PR URL when one exists. Report what was changed and any remaining work.

Document transfer gate: follow `docs/conventions/docs-preservation.md`. Fix the final PR comparison base before editing; preserve task bodies except the final status cell, source row order, done/footer history, and ordered source ownership. Stage explicit files and new `docs/preservation/*.json` manifests, then run `python3 -B scripts/docs-preservation-ci.py --base <PR-base-SHA>` again after completion updates. An archive candidate is not whole-plan completion: use only approved complete stems with `plans-archive.sh --apply --select <stems> --base <same-base> --manifest docs/preservation/<unique-name>.json`. No unselected bulk apply or blanket path waiver. Existing authorization remains valid; this gate does not introduce another approval requirement.
