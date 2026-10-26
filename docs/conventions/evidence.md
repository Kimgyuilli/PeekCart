# 증적 규약

증적은 판단과 검증의 근거가 되는 원자료와 그 설명이다. 위치는 셋이다(D-116 결정 2-3).

| 위치 | 담는 것 | 이름 |
|---|---|---|
| `docs/evidence/<ID>/` | 계획·검증의 원자료(로그, JUnit XML, json, GitHub Actions 사본, 재현 도구)와 README | 계획 stem 과 같은 이름(`D-087`, `D-087-profile-cache`). 계획이 없는 검토 묶음은 `<주제>-review` |
| `docs/progress/evidence/<이름>.md` | 원자료 없이 혼자 읽히는 산문 증적(세션 기록, 사실 정정) | 새 파일은 소문자 `dNNN-<주제>-<YYYYMMDD>.md` |
| `loadtest/reports/<YYYY-MM-DD>/` | 부하·측정 세션 원자료 | 세션 날짜. 세션 뒤 정리 규칙은 `loadtest/captures.md` |

## README 필수 항목

`docs/evidence/<ID>/README.md` 는 다음을 적는다.

1. 대응 계획 링크.
2. 파일마다 무엇인지. 파일이 많으면 하위 폴더 단위로 적는다.
3. 출처. 로컬 실행인지 GitHub Actions 사본인지, 사본이면 run id 와 head.
4. Actions 사본이면 원 artifact 의 만료일과 그 뒤로 이 사본이 유일본이라는 점. 만료일은 `gh api repos/<repo>/actions/runs/<run>/artifacts` 로 읽는다. 로그 보존은 저장소 기본(90일)이라 run 생성일 기준으로 적는다.
5. 재현 명령이 있으면 저장소 루트 기준 경로.

## 규칙

- CI 나 규약이 실행하는 도구는 증적 폴더에 두지 않고 `scripts/` 에 둔다(D-116 P2). 증적 폴더의 도구는 그 근거를 다시 계산하는 재현용이다.
- 원자료는 고치지 않는다. 고쳐야 하면 README 에 사후 정리 절을 추기하고, 지운 파일은 base 커밋 경로와 함께 기록한다.
- 새 폴더를 만들면 [증적 색인](../evidence/README.md)에 한 행을 더한다.
- `docs/plans/evidence/` 는 옛 위치다. 대응표와 D-104 안내 파일만 있고 새 증적을 두지 않는다.
- 증적 폴더를 옮길 때는 보존 manifest 의 `routes` 로 디렉터리 대응을 선언한다([문서 보존 규약](docs-preservation.md)).
