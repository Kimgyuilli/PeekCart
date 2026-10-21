---
name: sync
description: Summarize the current PeakCart phase, tasks, ADRs, plans, and next work using the repository harness. Use for a project status sync.
---

# Project sync

Run from the repository root. Follow `.claude/commands/sync.md`; it has no separate Codex review call. Reuse `scripts/plans-archive.sh` (preview first), `scripts/plans-index.sh` after an archive move, and `scripts/harness-context.sh` for the status digest. Read full source documents only for entries the digest identifies or when its parser warns.

Report the current phase, active task and completed items, recent decisions and ADR states, implemented files, and the next item. Never infer that a plan is merged without a PR or git fact.

Document transfer gate: follow `docs/conventions/docs-preservation.md`. Fix the final PR comparison base before editing; preserve task bodies except the final status cell, source row order, done/footer history, and ordered source ownership. Stage explicit files and new `docs/preservation/*.json` manifests, then run `python3 -B scripts/docs-preservation-ci.py --base <PR-base-SHA>` again after completion updates. An archive candidate is not whole-plan completion: use only approved complete stems with `plans-archive.sh --apply --select <stems> --base <same-base> --manifest docs/preservation/<unique-name>.json`. No unselected bulk apply or blanket path waiver. Existing authorization remains valid; this gate does not introduce another approval requirement.
