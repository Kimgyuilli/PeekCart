# D-104 하네스 근거 재현 범위

D-103 numeric 수정 `2f16266706d0e15c085e9eed6d4f7bbd608c73f4`의 순수 후속 문서/주석 정정이다. PR은 main을 대상으로 하되 D-103이 선행하며, 이 SHA와 비교한 diff가 D-104 독립 범위다. renderer 실행 계약과 CI workflow는 변경하지 않는다.

## audit 요약식의 모집단

```bash
python3 -B docs/plans/evidence/D-104/recalculate.py > /tmp/d104-source-scan.json
cmp /tmp/d104-source-scan.json docs/plans/evidence/D-104/source-scan.json
python3 -B docs/plans/evidence/D-104/verify-scope.py
```

고정 source는 [6b6dd69의 parent 6a5a47b](https://github.com/Kimgyuilli/PeekCart/tree/6a5a47bbb8d7f717604883e8b9981430190867aa/docs/plans)다. `git ls-tree`로 그 시점의 `docs/plans/*.audit.md` **직하 18개**를 고른다. done 하위/다른 문서/실제 raw 리뷰를 합친 모집단이 아니다. 원래 집계 script를 되찾은 것이 아니라 그 git 원문에서 보고 수치를 재현하는 식을 구성했다. `source-scan.json`은 각 파일 hash와 모든 매치의 파일/줄/값을 포함한다.

| 정규식 | 재현값 | 해석 제한 |
|---|---|---|
| `항목: (\d+)건 \(P0:(\d+), P1:(\d+), P2:(\d+)\)` | 56출현, 합442/P0 9/P1 297/P2 136 | 고유 라운드/고유 항목 수가 아니다. `항목: **10건**`처럼 다른 표기는 빠진다 |
| `반영 (\d+)건 / 기각 (\d+)건` | 41출현, 합306/1 | 앞 식과 다른 문구 모집단이다. 307 중1(0.3%)을 전체 프로젝트 기각률로 부르지 않는다 |
| `라운드 (\d+)` | 73출현, 1=25/2=19/3=20/0=2/4=3/5=3/6=1 | heading/본문/실패·미실행도 세며 실제 실행·도달 횟수가 아니다 |

중복을 제거하지 않았다. 예를 들어 [c2b audit의 계획 라운드3 미실행](https://github.com/Kimgyuilli/PeekCart/blob/6a5a47bbb8d7f717604883e8b9981430190867aa/docs/plans/task-impl4-c2b-dlq-replay.audit.md#L303)과 [cache fallback audit의 실행 불가/본문/후속 실행](https://github.com/Kimgyuilli/PeekCart/blob/6a5a47bbb8d7f717604883e8b9981430190867aa/docs/plans/task-impl5-cqrs-cache-fallback.audit.md#L68)이 같은 문자열 집계에 들어간다. 따라서 3라운드20회 전부 미수렴/상한 종료 또는 수렴 기여 없음이라는 결론은 이 추출에서 나오지 않는다. PHASE4의 당시 `미수렴7회`는 집계 기준이 없어 이번 재현 수치로 쓰지 않는다. 호출 상한과 처분 규칙의 선택은 이 효과 측정과 구분한다.

## renderer 시점과 원자료 한계

[당시 완료 계획의 정정 기록](../../plans/done/task-codex-review-render.md#7-정정-이력)은 특정 시점 282파일/238정형/44빈파일/null0, 사용량 한도9/MCP transport9/OAuth5/capacity1/기타20을 보고한다. 초기45실패/null1/전부usage 주장은 그 기록에서 철회됐다. 현재 원본 `.cache/codex-reviews` JSON/stderr 집합이 없어 이 수치나 원인 분류를 전수 재검증하지 못했다.

`renderer-history.json`은 root blog33 작업기록에서 선택한 **당시 도구 출력**이다. 원본 cache corpus가 아니다. source JSON hash와 원래 세션 파일/줄/UTC timestamp를 보존했으며 당시 문서 날짜는 한국시간 2026-09-20이다.

- source line91: 초기 선택 집합239파일/1267항목/scope0/disposition0/null1 보고. 후속282파일의 정정과 같은 추출 모집단으로 합치지 않는다.
- line198: 정형238/실패44의 당시 selftest 출력.
- line288/292: 후속 호출 실패 추가 뒤의 분류/ERROR 문자열 출력. 첫 ERROR 문자열이 실제 최종 원인임을 보장하지 않으며 보고된44개 분류를 독립 복원한 근거가 아니다.
- line310: 이후283파일/정형238/실패45. 앞282/44와 시점이 다르다.
- line319: required 수정 뒤 `schema-probe` 합성 1항목의 exit0 및 scope/disposition 반환. 전체 실제 리뷰나 과거1267항목 결측 원인의 인과 증명이 아니다.

schema 변경 자체는 [6026d5e](https://github.com/Kimgyuilli/PeekCart/commit/6026d5e5f6bcd23bc9eaf0d755c6361a2d5928c9)에서 확인된다. 원raw 없이 모든 호출이 실패했다거나 모든 결측이 같은 원인이라고 일반화하지 않는다. D-103의 합성 타입 검증은 별도 현재 계약이며 역사 모집단 복원이 아니다.

## PR3d 기각 수의 미확정

[고정 audit 원문](https://github.com/Kimgyuilli/PeekCart/blob/6a5a47bbb8d7f717604883e8b9981430190867aa/docs/plans/task-impl3-pr3d-internal-token.audit.md#L9)은 리뷰24건/기각10건으로 요약하지만 열거는 다음11개다.

| chunk | 서로 다른 열거 ID | 수 |
|---|---|---:|
| c1 | P0 1/2/3/4, P1 5/6 | 6 |
| c2 | P1 4 | 1 |
| c3 | P1 3/4, P2 5/9 | 4 |

원래 raw 리뷰와 판정 원문이 없어 실제 기각이10인지11인지 확정하지 않는다. `실제 결함7`은 audit의 중복 제거한 반영 결함 수이므로24에서 단순 차감해 맞추지 않는다. 원문 audit은 고치지 않고 PHASE4와 rationale에서 충돌과 한계를 드러낸다.

## 순수 문서 정정 범위

`scope.txt`는 선행 D-103 대비 shell 주석 외 본문 동일, Python 실행 AST 동일(clip docstring만 사실 문구 정정), helper/workflow byte동일을 확인한 기록이다. `clip.__doc__` 문자열의 변경은 숨기지 않으며 함수 동작/조건/입력 fixture는 변경하지 않는다. PHASE4의 두 PR3d 문단과 위임된 counts/renderer 이력 구간 밖 원문도 동일하다. shell syntax/whitespace와 보존 gate는 경량 확인하며 전체 CI는 Actions에서 실행한다. 원자료 부재 부분의 재검증 완료를 주장하지 않는다. 별도 Codex 리뷰 미호출.

선행 D-103 evidence manifests는2f162 source의 스냅샷이다. D-104의 역사 주석/docstring 정정 때문에 source hash가 달라지는 부분은 이 작업의 별도 manifest와 scope 기록으로 구분하며 선행 검증 결과를 새 SHA의 검증으로 바꾸지 않는다.

## 도구 위치 (2026-10-26, D-116 P3a)

이 폴더는 `docs/plans/evidence/D-104/` 에서 `docs/evidence/D-104/` 로 옮겼다. 위 명령의 `docs/plans/evidence/D-104/` 는 옮기기 전 경로이고, 지금은 `docs/evidence/D-104/` 로 바꿔 실행한다. 옛 자리에는 `docs/progress/PHASE4.md` 의 링크를 위한 안내 파일이 있다.
