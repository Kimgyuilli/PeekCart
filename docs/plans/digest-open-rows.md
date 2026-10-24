---
grade: S
---
# digest-open-rows

기준: 2026-10-25, main `6905952`. `/sync` 에서 발견했다.

## 명제

다음 중 하나라도 성립하면 미완이다.

- `scripts/harness-context.sh` 다이제스트가 `docs/TASKS.md` 의 열린 D행(상태가 ✅ 로 시작하지 않는 행)을 하나라도 빠뜨린다.
  지금은 🔄·🔲 만 골라, ◐ 를 처음 쓴 2026-10-17 부터 ◐ 행이 경고 없이 빠졌다. 2026-10-25 기준
  5행(D-064·065·078·084·107)이다.
- TASKS 범례에 ◐ 가 없다.
- TASKS D-106 행이 #239 를 "PR CI·머지 대기"로 적고 있다. #239 는 `6905952` 로 병합됐고 main CI40115061869 가 성공했다.
- 전체 완료한 `D-106-topic-guard-throttle` 계획서(audit 포함)가 `docs/plans/` 루트에 남아 있다.
- `scripts/plans-index.sh` 가 계획서·audit 에 인용된 PR 중 가장 큰 번호를 그 계획의 PR 로 표기해, 다른 계획의 PR 이
  INDEX 에 실린다(D-106-topic-config-race 에 #238, D-107-recovery-lab-topics 에 #237, D-093 에 #217, ci-critical-path 에 #233).

등급 S: 코드 변경은 `scripts/` 하나다. 다이제스트 출력 문구를 검사하는 곳은 selftest 뿐이고(grep 확인),
`/sync` 는 사람이 읽는 설명이다. 되돌림은 이 PR 안에서 끝난다.

## 작업 항목

- [x] P1. `harness-context.sh`: 열린 행을 TASKS.md D행 중 상태가 ✅ 로 시작하지 않는 전부로 바꾼다. 라벨은 범례
  (🔲 대기, 🔄 진행중, ◐ 부분, ⏸ 보류)를 따르고, 범례 밖 표기는 그 첫 글자를 그대로 보인다. 제목과 집계 문구를 "열린 행"으로 바꾼다.
- [x] P2. `harness-context-selftest.py`: 집계 문구를 갱신하고, 상태별 행을 한 번에 넣는 음성 대조 테스트를 더한다.
- [x] P3. TASKS 범례에 `◐ 부분 완료` 를 더하고, `.claude/commands/sync.md` 의 다이제스트 설명을 "열린 행"으로 맞춘다.
- [x] P4. TASKS D-106 행의 #239 를 "병합 `6905952` 뒤 main CI40115061869 성공"으로 정정한다.
- [x] P5. `D-106-topic-guard-throttle`(audit 포함)을 base `6905952` 와 보존 manifest 로 아카이브하고 INDEX 를 다시 만든다.
  완료 근거: P1~P6 체크, V1~V6 audit 기록, PR CI(approval-recovery lab 포함) 성공, main CI40115061869 성공.
- [x] P6. `plans-index.sh`: `/ship` 이 남기는 `PR:` 줄이 있으면 그 번호(여럿이면 가장 큰 번호)를 쓰고, 없을 때만 인용된
  최대값을 쓴다. 자기 PR 링크가 없는 `D-107-recovery-lab-topics` 에는 끝에 `PR: [#238]` 완료 기록을 추기한다(원문 보존).
  병렬 캠페인 착수 뒤 사용자 승인으로 범위에 더했다.

## 검증

- 새 테스트에 ◐·⏸·범례 밖 표기·상태 본문에 ✅ 가 섞인 🔄 행과, ✅ 로 시작하는 행을 함께 넣는다. 앞의 넷은 라벨과 함께
  목록에 나오고 마지막 행은 빠져야 한다. 기존 구현(`6905952`), 범례 표기만 고르는 변이, `'✅' in` 으로 완료를 판정하는
  변이를 `HARNESS_CONTEXT_TEST_SCRIPT` 로 넣으면 이 테스트가 각각 실패해야 한다.
- 기존 selftest 전체와 D-099 `check-mutants.py` 가 그대로 통과한다(변이 원문 drift 없음). `harness-context.sh --check --base 6905952` 통과.
- 실제 TASKS 로 다이제스트를 돌리면 열린 행 7개(D-064·065·075·078·084·106·107)가 라벨과 함께 나온다.
- `docs-preservation-ci.py --base 6905952` 와 `plans-index.sh --check` 통과. `plans-archive.sh` 미리보기 후보가 3개로 준다.
- P6: 임시 fixture(`PR:` 줄과 더 큰 인용 PR 이 함께 있는 계획서, `PR:` 줄 없는 계획서, audit 에 `PR:` 줄 둘)에서 새 스크립트는
  `PR:` 줄의 번호를, 없으면 최대값을 고르고, 기존 스크립트(`6905952`)는 인용된 최대값을 골라야 한다. 실제 INDEX 는 위 네 행이
  자기 PR 로 바뀌고, 여러 PR 에 걸친 구현 계획 3건(impl1·impl3·pr3d-b2)은 `/ship` 이 마지막으로 기록한 자기 PR 로 바뀐다.
- Gradle 소스 세트 변경이 없어 `./gradlew test` 는 PR CI 로 확인한다.
