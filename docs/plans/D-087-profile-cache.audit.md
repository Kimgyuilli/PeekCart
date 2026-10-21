# D-087 profile cache audit

- Root approved M narrow follow-up after original PR214/189 image failures. Own branch fix/d087-profile-export-cache/basec9e12d6. Original PR189/214 analyzer correctly refused detached report export.
- Added only export's existing service cache-from input/comment plus original workflow selftest assertions and2actual YAML mutation controls. At initial8019 the analyzer, Dockerfile/runtime, cache-to and fresh/cached semantics were byte-unchanged.
- Minimal local2methods PASS0.502s: real workflow/runtime contract and original detached-rebuild rejection. Full10methods/12mutants and actual6images are Actions-only pending. No local Gradle/Docker/full suite. Raw2jobs/artifacts and hashes are in evidence/D-087-profile-cache.
- 별도 Codex 리뷰 미호출. No shared TASKS/PHASE/master edits. Draft-pending CI is authorized; plan boxes retain unmet formal verification.

## 첫8019 실패와 좁은 보강

- PR216 CI39607970017의6images 실패를 보존한다. user119249939912 liveCACHED/historyDONE9s+layer materialization/Gradle 출력없음/oldorigin은 원PR214 실제재실행과 구분한다. 첫1line 수정만으로 완료하지 않았다. Reviewer6317356760 P2 뒤 Root가 rawjson cache 증거 보강을 승인했다.
- 동일 exactref의 rawjson/CLI help를 기존 collector에 추가하고 단일 bootJar vertex digest/cache완료/실행log를 좁게 확인한다. plain은 기존원자료로 유지하되 materialization시간을 실행시간과 분리한다. profile origin/hash/window 검증과 진짜detached 실패는 유지한다.
- 최소 로컬3methods PASS2.178s(구조화 materialization, 원detached, CLI rc전파). 완료timestamp/중복vertex 보강후 materialization method1개 PASS0.877s. 전체11methods/14controls/실제6image는 다음 Actions 검증대기다. 로컬 Gradle/Docker/full suite는 실행하지 않았다.
