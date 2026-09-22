# audit — D-029 base mysql CPU 상한 승격 판정

## 2026-09-22 — 계획 리뷰
- 상태: **의도적 생략**(file: 사용자 지시 2026-09-22 — Codex 리뷰를 호출하지 않는다)
- 게이트: `hpx_codex_allowed` → `blocked` / source `file` / origin `.cache/codex-off`
- 등급 L 이라 §5 대상이었으나 게이트 차단. **미결 아님**(/plan §8).
- 코드 검증(§2)은 그대로 수행 — 전제 11건(F1~F11) + B1 역의존 스윕 4행.
- 검증이 뒤집은 전제: **TASKS.md 트레이드오프 ①(minikube 2 vCPU 수용성)이 거의 해소됐다.**
  `limits` 는 스케줄링에 쓰이지 않고 base requests 합 2100m < 4000m 이며, 현재 limits 합
  7250m 로 이미 1.8배 오버서브스크립션이다. 실제 쟁점은 ②(`requests` 250m vs 실측 1,259m).
  초안 전에 이걸 몰랐으면 ADR Alternatives 순서를 잘못 썼을 것이다.

## 2026-09-22 — diff 리뷰
- 상태: **의도적 생략**(file: 사용자 지시 2026-09-22 — Codex 리뷰를 호출하지 않는다)
- 게이트: `hpx_codex_allowed` → `blocked` / source `file` / origin `.cache/codex-off`
- 등급 L 이라 §5 대상이었으나 게이트 차단. **미결 아님**(/work §9). 재리뷰(§7)도 함께 미해당.
- 검증: **lint 20/20 통과** · `./gradlew test` **BUILD SUCCESSFUL (49 tasks 전부 up-to-date)**
  — diff 에 `src/`·`*.gradle` 변경이 **0건**이므로 up-to-date 가 정상이고, 이 변경에 대한
  실질 검증은 **렌더 스윕 8/8 + 실패 주입 2건**이다. 테스트 통과를 이 변경의 근거로 쓰지 않는다.
- 계획과 다르게 구현된 것 **2건** (둘 다 계획서·PHASE4 에 정정 기록):
  ① 상속 overlay 7개 → **실제 5개** (`gke-d002bc` 는 자체 patch 였다)
  ② 거짓이 될 주석 2곳 → **실제 3곳** (인라인 주석 1곳 누락)
- compound capture 3-질문:
  ① 반복되나 — **Yes.** "기준선에서 patch 값과 base 값이 같아 상속/명시가 구별 안 된다" 는
     kustomize overlay 를 쓰는 동안 계속 재발할 성질이다
  ② 재도출 어렵나 — **No.** base 를 흔들어 보면 즉시 갈라진다. 이번에도 그렇게 갈렸다
  ③ 더 싼 자동검사로 못 바꾸나 — **바꿀 수 있다.** overlay 별 mysql `resources` 출처
     (상속/patch)를 렌더 두 번으로 판정하는 검사기가 가능하다
  → 셋 다 Yes 아님 + 자동화 가능 → **prose 저장 안 함.** 다만 부채로 올릴 만큼 넓지 않아
     (지금 손잡이는 mysql CPU 하나) 계획서 §2 표의 **출처 열**로 국소 처분했다.

## 2026-09-22 — /ship
- PR: **[#131](https://github.com/Kimgyuilli/PeekCart/pull/131)** (base `main`, 커밋 5개)
- preflight `ok` · consistency precheck `ok`(warnings 0) · review health `ok`
- **커밋 5개를 전부 재작성했다.** Step 4-1 문체 lint 가 오류 19건을 냈다:
  `infra(k8s)` 가 허용 type 이 아니었고(feat/fix/refactor/test/docs/chore), em dash·화살표·
  `×`·이모지 `✅` 다수, 커밋 본문 어미가 `~다`(규약은 `~함`/`~임`), 추적 태그가 제목에 있었다.
  push 전이라 `cherry-pick -n` 으로 재작성하고 `git diff d029-backup HEAD` 가 공백임을 확인해
  **트리 무변화**를 보장했다. 백업 브랜치 `d029-backup` 은 push 성공 후 삭제 대상.
  → **pre-commit 훅은 분류만 검사하고 문체는 안 본다**는 사실이 드러났다. 자동화 후보.
- **PR 본문도 오류 25건이었다.** 어미(`~습니다`)와 볼드 상한(구획당 2개) 외에,
  `계획의 전제 2건이 구현 중 틀린 것으로 드러남` 과 `TASKS.md 가 적은 1순위가 1순위가
  아니었다` 두 절을 **PR 에서 제거**했다 — writing.md §3 이 시행착오·자기 서사를 audit 몫으로
  규정하고, 후자는 제목 자체가 주장이었다. 내용은 이 파일과 PHASE4.md 에 있다.
- 갱신: TASKS.md D-029 PR 링크 · PHASE4.md 엔트리 제목 + 미충족 3건 · 이 블록
- 부채 승격 없음. 이월 4건은 D-002/D-026 측정 계열 기존 축에 속해 새 ID 를 만들지 않았다
- Layer 1(01~07) 정정 불필요 — 계획 §2 F9 가 인용 0건을 확인했다
