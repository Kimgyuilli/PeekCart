# PeakCart project guidance

Read `CLAUDE.md` for this repository's architecture, conventions, document map, and testing rules. It is the shared project guidance for Claude and Codex.

For the project workflow, use the corresponding skill under `.agents/skills/{sync,next,plan,work,ship,done}/`. The `.claude/commands/` files describe the original workflow, but their `codex exec` review steps do not apply to Codex. Do not invoke a second Codex reviewer or present an unrun review as passed.
