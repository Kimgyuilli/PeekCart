# D-116-docs-move audit

## 2026-10-27 00:05 — 계획 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 등급 L 근거: 에이전트 지시 표면인 CLAUDE.md 가 바뀌고, 되돌리려면 옮긴 문서의 인바운드 링크와 보존 manifest 를 같이 고쳐야 한다. 코드·CI·하네스는 건드리지 않는다.
- 사용자 사전 승인(2026-10-26): CI 통과·리뷰 수렴 뒤 직접 머지, 새 판단은 보수적 기본값으로 진행하고 기록.

## 2026-10-27 00:40 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 검증(계획 §4, 대조군은 괄호):
  1. 보존: `--base 5261510` PASS (옮긴 문서 한 줄 삭제 → `Destination ordered content differs`, D-024 정정의 `old` 변조 → `Correction old text is not unique`).
  2. 링크: `docs/02-architecture.md` 의 링크가 새 문서의 같은 앵커로 해석되고 대상이 실재한다. 파싱 가능한 추적 md 에서 옛 7개 경로로 해석되는 링크 0건. 새 문서 3개와 CLAUDE.md 의 링크 대상 실재.
  3. 행: 행 정규식이 보는 완료 행 92 → 93, D-024 가 원래 순서(16번째)에 보인다. 다이제스트의 "완료 표기 행 93" 은 ✅ 표기로 세는 값이라 원래부터 D-024 를 포함해 그대로다. `harness-context.sh --check --base 5261510` ok.
  4. 인용: ADR 4개 본문과 D-100 README 의 `docs/learning` 인용 0건. 남은 언급은 ADR Update Log 의 삭제 기록, gitignore 설명, 계획·이력 문서다.
  5. 문서 맵: `docs/` 아래 폴더 전부(learning 포함)가 `docs/README.md` 에 있다.
  6. CI lint 잡 단계를 ci.yml 원문대로 로컬 실행: 6단계 전부 PASS. `plans-index.sh --check` 최신, consistency precheck ok, 앵커·기존 manifest 이동/삭제 0건.
- `./gradlew test --continue`: BUILD SUCCESSFUL(Java 입력 무변경).
- 계획과 달라진 것: 검증 4의 기준을 "본문 인용 0건, Update Log 의 삭제 기록은 남김" 으로 바로잡았다. 결정 3-6 은 Update Log 에 지운 사실을 적으라고 하므로 지운 경로를 기록에 적었다.

## 2026-10-27 00:50 — /ship
- PR: https://github.com/Kimgyuilli/PeekCart/pull/254 (base main `5261510`)
- 커밋 8개(docs 7 · adr 1). 분류 검사 통과, writing-lint(커밋 8건·본문·제목) 통과. CI 는 문서 전용(`ci-docs-only.sh` code=false)
- precheck: consistency ok, `hpx_review_health` ok, 보존 검사 `--base 5261510` PASS, `harness-context.sh --check --base 5261510` ok
- 갱신: TASKS D-116 행에 PR 링크(진행 중 유지), PHASE5 엔트리. 상위 계획의 P3b 칸과 P3 상자는 머지 뒤 다음 단계가 채운다
