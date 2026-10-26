# 문서 이동과 완료 이력 보존

D-098은 완료 행과 선언한 문서 이동의 원문·중복·순서·문서 소속을 비교한다. 일반 현행 문서의 내용 편집 전체를 불변으로 만들지 않는다. 현행 corpus의 완전성·사실 정확성·전체 링크 건강 검사는 별도 D-099/D-104 범위다.

## 비교 기준과 완료 행

편집 전에 최종 PR의 base SHA를 기록한다. CI는 PR의 `pull_request.base.sha`, main push의 `before`를 사용하며 읽을 수 없는 base는 실패한다. 검사 통과를 위해 편집 후 HEAD를 새 base로 고르지 않는다. 연속 로컬 기록도 최종 PR base와 비교한다.

```bash
python3 -B scripts/docs-preservation-ci.py --base <PR-base-SHA>
```

TASKS와 TASKS-done 첫 column의 D-ID는 각각 한 번만 존재해야 한다. 기존 ID는 사라질 수 없고 새 ID는 TASKS에만 등록한다. 완료 이동은 TASKS → TASKS-done이며 기존 done 행 뒤에 append한다. 여러 이동 행은 비교 base의 TASKS 원래 순서대로 추가한다. 기존 done 행과 남은 TASKS 행 순서도 보존한다.

완료 행의 마지막 상태 cell은 ✅와 실제 GitHub PR 링크로 갱신할 수 있다. 그 앞의 본문은 그대로 보존한다(`rsplit('|', 2)`로 code 안의 pipe도 보존). 이동 때문에 상대 Markdown 링크가 달라지면 같은 파일·앵커·query를 가리켜야 한다. 비교할 때 세 값을 JSON 배열로 구조화해 구분자가 포함된 fragment/query도 서로 혼동하지 않는다. 기존 done footer/정정 이력은 기존 행 사이의 위치와 순서를 함께 보존하며 뒤에 추기할 수 있다. 마지막 줄에 LF가 없던 원문에도 기존 byte prefix를 유지한 추기가 가능하다. 상태 외 본문 사실 정정은 아래 좁은 예외를 선언한다.

## 선택 아카이브

```bash
bash scripts/plans-archive.sh
bash scripts/plans-archive.sh --apply --select D-085,D-090 \
  --base <PR-base-SHA> --manifest docs/preservation/<고유이름>.json
bash scripts/plans-index.sh
# 이동·참조 변경·manifest·생성 index를 명시적으로 stage한 뒤 실행한다.
python3 -B scripts/docs-preservation-ci.py --base <같은-PR-base-SHA>
```

첫 명령은 후보 미리보기다. merged PR과 open PR 부재만으로 전체 계획 완료를 증명하지 않는다. 전체 완료를 확인하고 이미 승인된 작업 범위의 stem만 선택한다. D-084 같은 부분 완료나 전체 미완 orchestration을 후보 전체 apply로 옮기지 않는다. 이런 계획은 frontmatter에 `archive: hold <PR...>`를 적어 후보에서 빼고(`유지`), 선택해도 이동 전에 전체 실패한다. 적힌 번호 밖의 PR이 머지되면 다시 후보가 된다. `--apply`는 선택/base/manifest 없이 실패한다. 선택 중 미머지·근거 없는 항목이 있으면 이동 전에 전체 실패한다. PR 조회 후보 판정의 의미는 기존 로직을 유지한다.

선택한 plan/audit를 한 번에 이동하고 Markdown 링크의 상대 목적지만 고친다. 코드·일반 prose 경로와 과거 GitHub blob URL은 역사 원문이다. 기존 source 이후의 추기는 새 literal part로 기록한다. source의 기존 본문을 고쳤다면 정확한 사실 정정을 먼저 선언해야 한다. 원래 현행 preimage는 `.cache/docs-archive-backup-*`에 보관한다. 최종 검증 실패 시 일부 파일이 이동된 상태일 수 있으므로 이 백업과 git diff를 검토해 수정/복구한다. commit/push는 수행하지 않는다.

## 분리·병합 manifest

`docs/preservation/*.json` 새 파일은 명시 stage해야 CI wrapper가 발견한다. 과거 manifest는 수정·삭제할 수 없다. 원본 hash는 비교 base 파일의 UTF-8 원문 바이트 SHA256이다. CRLF·빈 줄·반복 줄도 정규화하지 않는다. raw checker에 `--manifest <파일>`을 반복 전달하면 stage 전에도 검사할 수 있다.

```json
{
  "version": 1,
  "sources": [{"path": "docs/source.md", "sha256": "<base-file-sha256>"}],
  "destinations": [
    {"path": "docs/part-a.md", "parts": [{"source": "docs/source.md", "start": 1, "end": 10}]},
    {"path": "docs/part-b.md", "parts": [
      {"literal": "# 새 문서 설명\n"},
      {"source": "docs/source.md", "start": 11, "end": 20}
    ]}
  ]
}
```

모든 원본 줄을 정확히 한 번 배정한다. start/end는 아래 사실 정정을 적용한 source의 1부터 시작하는 양끝 포함 줄 번호다. 목적지별 같은 source의 구간은 원래 순서대로 나열한다. source 전체 순서를 여러 분리 문서 사이에 강제하지 않으므로 문서 소속은 명시한 mapping과 대조한다. 기존 목적지에 병합하려면 그 목적지도 source로 선언해 기존 내용을 보존한다. source는 목적지로도 선언한 경우 외에는 남아 있을 수 없다. 선언 없이 추적 Markdown 문서를 삭제하면 실패한다.

원문에서 재작성하는 부분은 의미상 같은 local Markdown 링크 target뿐이다. 링크 대상과 앵커가 실제 존재해야 하며 inbound 링크도 선언한 문서/앵커로 이동해야 한다. 분리한 문서의 whole-file 링크는 원래 첫 줄을 소유한 목적지로, heading 링크는 해당 heading 줄을 소유한 목적지로 간다. 병합으로 중복 heading suffix가 바뀌면 그 줄의 새 앵커를 사용한다. 새 framing literal은 새 내용이므로 manifest에도 명시해 검토한다. literal로 source 보존 누락을 대신할 수 없다.

## 역사 추기와 좁은 사실 정정

기존 `docs/plans/done/*.md`(생성 INDEX 제외)와 `docs/progress/TASKS-archive-*.md`는 원문 prefix를 보존하고 뒤에 추기한다. 사실 오류 수정은 version 1 manifest의 `corrections`에 아래 필드를 모두 선언한다. source 이동과 함께 쓰거나 corrections만 담을 수 있다.

```json
{"path":"docs/plans/done/example.md", "base_sha256":"<whole-base-file-sha256>",
 "old":"정확한 기존 한 문장", "new":"근거를 확인한 새 한 문장",
 "old_sha256":"<old-text-sha256>", "reason":"확인한 원본과 정정 이유"}
```

old는 정확히 한 번 나타나야 하고 실제 변경·이유가 필요하다. 같은 파일의 old 합계는 20줄/2000자 이하이며 원본 절반 미만이어야 한다. 중복 source, 미사용 예외, 잘못된 hash/범위, whole-file 대체는 실패한다. 광범위 path 제외나 모든 차이를 허용하는 bypass는 없다. 의미상 정당한 정정인지는 PR 검토로 판단하며 hash가 그 사실을 증명하지 않는다. source 제목 변경처럼 inbound 앵커를 재해석해야 하는 변경은 자동 archive로 처리하지 말고 역사 추기 등 별도 명시 변경을 검토한다.

## 디렉터리 이동과 비-Markdown 경로 대응

Markdown 밖의 파일(json·gz·로그·도구)은 내용 비교 대상이 아니지만, 폴더째 옮기면 그 파일을 가리키는 링크의 해석이 바뀐다. manifest 의 `routes` 로 디렉터리 대응을 선언한다.

```json
{"version": 1, "sources": [...], "destinations": [...],
 "routes": [{"from": "docs/plans/evidence/D-075", "to": "docs/evidence/D-075"}]}
```

비교할 때 원본 쪽 링크가 `from` 아래(폴더 자체 포함)를 가리키면 `to` 아래 같은 상대 경로로 읽는다. 그래서 README 의 형제 파일 링크와 보호 이력의 비-Markdown 링크를 옮긴 위치로 보일 수 있다. 검사는 base 의 `from` 아래 파일이 하나 이상 있고, 그 가운데 Markdown 은 선언한 source 이며, 나머지는 `to` 아래 같은 경로에 있고 옛 경로에는 없다는 것이다. 경로는 `docs/` 로 시작하는 정규형이어야 하고 route 끼리 겹칠 수 없다. 비-Markdown 내용과 인바운드 비-Markdown 링크의 재표적은 검사하지 않는다. 그대로 둔 문서의 깨진 비-Markdown 링크는 PR 안에서 따로 대조한다.

## Markdown 지원 경계와 검증

표준 라이브러리만 사용하는 제한된 링크/heading 해석이다. inline `[label](path#anchor)`와 한 줄 reference definition, ATX/setext heading, 단순 명시 HTML anchor를 지원한다. fenced/same-line inline code를 링크로 재작성하지 않는다. 모호한 escaped/nested link와 entity-escaped target, 여러 줄 backtick span, 들여쓴 code/list의 link, HTML comment/link/block(quoted/unquoted href/src 모두 포함), 복잡한 heading, 내부 canonical marker 문자열은 관련 비교에서 실패한다. 짝 없는 backtick은 Markdown의 문자 원문으로 유지한다. 글머리표 목록 항목은 별개 블록이라 그 앞에서 문단이 끝난 것으로 보므로, 한 항목의 짝 없는 backtick이 다음 항목의 backtick과 짝을 이루지 않는다. parser를 우회하려고 원문을 바꾸지 말고 별도 검토 가능한 명시 이전 방식으로 처리한다. D-116 P3a 의 D-104 README 가 그 예다. 원문을 새 위치에 그대로 두고 옛 자리에 안내 파일을 남겨, 파싱되지 않는 PHASE4 의 링크가 안내로 이어지게 했다. 인바운드 링크를 찾는 사전 필터는 소스 이름을 링크 문맥에서 찾되, 소스 폴더 밖의 문서에서는 상위 폴더 이름이 파일 이름 앞에 있어야 후보로 본다. 폴더 밖에서 소스에 닿는 링크는 그 폴더 이름을 반드시 적기 때문이다. 이 제한은 일반 문서 전체의 Markdown lint가 아니다. 동일한 보호 archive 원문은 다시 해석하지 않는다.

```bash
python3 -B scripts/docs-preservation-selftest.py
python3 -B scripts/docs-preservation-real-selftest.py
bash scripts/plans-archive.sh --self-test
```

첫 검사는 임시 git 저장소의 정상 이동·행/반복 줄/순서/소속/footer/링크/예외/선택 archive 음성 대조다. 둘째는 고정 `a7ffa11f24be2bd281d8952ac5fc5d351fb6119c`의 실제 architecture 분리와 D-097 plan/audit archive 원문을 사용한다. 원본 worktree는 수정하지 않는다. CLI는 정상 0, 보존/config 위반 1, 읽을 수 없는 base/인자 오류 2를 반환한다. CI lint job은 같은 검사와 자기검증을 실행한다.
