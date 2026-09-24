---
name: sync
description: Summarize the current PeakCart phase, tasks, ADRs, plans, and next work using the repository harness. Use for a project status sync.
---

# Project sync

Run from the repository root. Follow `.claude/commands/sync.md`; it has no separate Codex review call. Reuse `scripts/plans-archive.sh` (preview first), `scripts/plans-index.sh` after an archive move, and `scripts/harness-context.sh` for the status digest. Read full source documents only for entries the digest identifies or when its parser warns.

Report the current phase, active task and completed items, recent decisions and ADR states, implemented files, and the next item. Never infer that a plan is merged without a PR or git fact.
