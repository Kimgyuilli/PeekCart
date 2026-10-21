# D-087 profile cache audit

- Root approved M narrow follow-up after original PR214/189 image failures. Own branch fix/d087-profile-export-cache/basec9e12d6. Existing analyzer correctly refused detached report export; no guard correction was needed.
- Added only export's existing service cache-from input/comment plus original workflow selftest assertions and2actual YAML mutation controls. Analyzer, Dockerfile/runtime, cache-to and fresh/cached semantics are byte-unchanged.
- Minimal local2methods PASS0.502s: real workflow/runtime contract and original detached-rebuild rejection. Full10methods/12mutants and actual6images are Actions-only pending. No local Gradle/Docker/full suite. Raw2jobs/artifacts and hashes are in evidence/D-087-profile-cache.
- 별도 Codex 리뷰 미호출. No shared TASKS/PHASE/master edits. Draft-pending CI is authorized; plan boxes retain unmet formal verification.
