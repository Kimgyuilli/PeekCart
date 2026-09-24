---
name: next
description: Identify the next PeakCart task item and completion criteria from TASKS and ADR status. Use when asked what to do next.
---

# Next task

Follow `.claude/commands/next.md`. Read the relevant rows of `docs/TASKS.md` and active ADR entries in `docs/adr/README.md`. Prefer the first pending item in an active task, then the first pending task. State whether an ADR must precede it. Return the task ID/name, item, completion criteria, and related ADR. This skill gives guidance; start implementation only when requested.
