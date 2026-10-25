---
grade: S
---
# sync-context-cost

기준: 2026-10-25, main `4293cba`. `/sync` 한 번에 컨텍스트가 40% 가까이 차고 몇 분이 걸린다는 사용자 질문에서 시작했다.

## 명제

다음 중 하나라도 성립하면 미완이다.

- `/sync` 0단계가 이미 부분 완료로 판정된 계획서를 sync 마다 다시 연다. 과거 sync 두 번(2026-10-25)의 트랜스크립트에서
  첫 도구 호출 때 약 54K 토큰이던 컨텍스트가 후보 4개의 완료 판정을 거치며 104~110K 로 늘었다. 스크립트 출력은 합계 2.3KB 였다.
- `plans-archive.sh` 가 PR 마다 `gh pr view` 를 직렬로 불러, 후보 4개(PR 42개)에 23초가 걸린다.
- 부분 완료 표시를 푸는 일이 머지 뒤 사람의 수동 단계에 기댄다. 사용자는 PR 머지 뒤 그 세션을 이어가지 않으므로
  머지 뒤에만 알 수 있는 상태는 다음 `/sync` 가 기계적으로 감지해야 한다.

등급 S: 코드 변경은 `scripts/` 하나다. 새 frontmatter 키 `archive:` 는 `plans-archive.sh` 만 읽고, `/sync`·`/done`
설명은 사람이 읽는 절차다. 되돌림은 이 PR 안에서 끝난다.

TASKS 열린 행과 묶는 판정은 쓰지 않았다. `docs/plans/done/` 에 D-078 계열 계획서 9개(`D-078.md` 포함)가 D-078 행이
열린 채로 아카이브돼 있어, 열린 행이 계획 미완을 뜻하지 않는다.

## 작업 항목

- [x] P1. `plans-archive.sh`: PR 상태를 `gh pr list --state all --limit 1000` 한 번으로 받는다. 목록에 없는 번호는 UNKNOWN 이다.
- [x] P2. `plans-archive.sh`: frontmatter `archive: hold <PR...>` 를 읽는다. 적힌 PR 밖에 새 머지 PR 이 없으면 `유지` 로
  분류해 후보에서 빼고, 생기면 후보로 되돌리며 그 PR 을 보인다. 열린 PR 이 있으면 지금처럼 진행중이다.
- [x] P3. `.claude/commands/sync.md` 0단계에 `유지` 는 열지 않는다는 항목과, 판정 결과가 부분 완료면 hold 에 PR 번호를
  적는다는 규칙을 더한다. `done.md` 에 부분 완료 PR 에서 hold 를 거는 규칙을 더한다. 지우는 단계는 두지 않는다.
- [x] P4. `docs/conventions/docs-preservation.md` 선택 아카이브 절에 hold 를 적는다.
- [x] P5. 본문에서 아카이브하지 않는다고 선언한 `D-084`(hold 181), `task-debt-orchestration`(hold 177 179 180 181)에 표시한다.

## 검증

- `plans-archive.sh --self-test`: gh 스텁(776·777 MERGED, 888 OPEN, 999 CLOSED)으로 hold 777 + PR 777 은 유지,
  hold 777 + PR 776·777 은 후보, 번호 없는 hold + PR 777 은 후보, PR 없는 hold 는 유지, 본문에만 있는 hold 줄은 무시,
  목록에 없는 PR 555 는 보류여야 한다. 기존 판정 다섯 건도 그대로다.
- `docs-preservation-selftest.py`: hold 계획을 선택에 섞으면 exit 1 이고 어떤 파일도 옮기지 않아야 한다.
- 실제 저장소 dry-run 이 바꾸기 전과 같은 후보 PR 목록을 내고, 시간은 23초에서 약 2초로 준다. 두 계획서에 hold 를 단 뒤
  `후보 2 · 유지 2` 가 된다. `D-078-remeasure-plan` 에 임시로 `hold 243` 을 달면 `hold 이후 새 머지 PR 246` 으로 후보가 된다.
- `docs-preservation-real-selftest.py`, `harness-context-selftest.py`, `docs-preservation-ci.py --base origin/main` 통과.

## 미해결

- 남은 후보 `D-078-remeasure-plan`·`task-debt-20261021` 은 다음 `/sync` 에서 한 번 판정한다. 그 판정은 이 PR 범위가 아니다.
- 고정 비용(첫 턴 약 54K)은 MCP 커넥터·플러그인 목록이 대부분으로 보이며 저장소 밖 설정이다. 처분: 사용자 판단.
