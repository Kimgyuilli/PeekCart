---
grade: L
---
# D-116-evidence — 증적 통합과 보존 검사기의 디렉터리 이동 지원 (D-116 P3a)

상위 계획: `docs/plans/D-116.md` §3 P3(앞의 세 항목). 기준 main `7d4fa4d`(P2 #252 머지 뒤). 결정 원문은 `docs/evidence/D-116/decisions.md` 의 2-3·2-6·2-7 이다. 사용자 사전 승인(2026-10-26)에 따라 CI 통과와 리뷰 수렴 뒤 머지하고, 새 판단은 보수적 기본값으로 정해 §2.4 와 PR 본문 "확인 요청" 에 남긴다.

## 1. 명제

다음 중 하나라도 성립하면 미완이다.

- `docs/plans/evidence/` 에 증적 원자료가 남았다. 남아도 되는 것은 옛 경로와 새 경로의 대응표(`README.md`)와 D-104 안내 파일(§2.4-2)뿐이다. 또는 `D098` 이 `D-098` 이 아니다.
- 옮긴 Markdown 24개 가운데 줄이 하나라도 새 위치에 정확히 한 번 들어가지 않았다. D-104 README 는 manifest 밖이므로 링크 해석 기준 원문 동등성을 따로 보인다.
- 추적 문서의 링크 가운데 옮긴 증적의 옛 경로를 가리키는 것이 남았다. 예외는 고칠 수 없는 파싱 불가 문서(`docs/progress/PHASE4.md`)의 D-104 링크이고, 그 링크는 안내 파일로 이어진다.
- 보존 검사기가 디렉터리 이동을 선언할 수단이 없다. 또는 그 수단이 옮기지 않은 파일, 남은 파일, 선언 안 된 Markdown 을 통과시킨다.
- 보존 검사기의 사전 필터가 다른 폴더의 같은 이름 파일 링크 때문에 무관한 문서를 파싱한다. 또는 정밀화한 필터가 실제로 소스를 가리키는 링크를 놓친다.
- `docs/evidence/README.md` 색인에 없는 증적 폴더가 있다. README 없는 증적 폴더가 있다. GitHub Actions 사본에 원본 만료일 표기가 없다.
- `docs/conventions/evidence.md` 에 세 증적 위치의 역할·이름 규칙·README 필수 항목이 없다.

등급 L: 증적 364파일을 옮기는 구조 변경이고, CI 게이트인 보존 검사기의 manifest 형식과 판정이 바뀐다. 되돌리려면 이 PR 밖의 보호 이력 링크와 규약을 같이 고쳐야 한다.

## 2. 배경

### 2.1 코드 검증 (base `7d4fa4d`)

| 전제 | 확인한 곳 | 결과와 반영 |
|---|---|---|
| 증적 규모 | `git ls-files docs/plans/evidence` | 26 폴더, 364파일(gz 225, json 67, txt 31, md 24, py 7, zip 6, patch 2, tsv 1, jsonl 1). 추적되면서 무시되는 파일 0. `docs/evidence/` 의 6 폴더(D-063·D-076·D-080·D-084·D-093·D-116)와 이름이 겹치지 않는다 |
| 사전 필터 | `scripts/docs-preservation-check.py:122-133` `references_source`, 호출 `:431`, `scripts/docs-archive-apply.py:100` | 소스 파일 **이름**만 링크 문맥에서 찾는다. 소스가 `README.md` 면 어떤 `README.md` 링크든 걸려 `links()` 로 넘어간다 |
| 파싱 불가 문서 | `links()` 를 추적 md 전체에 실행 | 14개가 `Unsupported multiline backtick span` 등으로 실패한다. 그중 ADR-0028(265행이 옮기지 않는 `docs/evidence/D-080/README.md` 링크)과 PHASE4(2858행이 `D-104/README.md` 링크, 2249행 이전의 여러 줄 backtick)가 이름 필터에 걸린다. 지금 검사기로는 어떤 증적 README 도 manifest 로 옮길 수 없다 |
| 필터 정밀화 시험 | 상위 폴더 이름이 파일 이름보다 앞에 있어야 하는 변형으로 같은 corpus 를 다시 셈 | ADR-0028 은 빠지고 PHASE4 는 D-104 README 하나에만 걸린다. 파싱 가능한 문서의 실제 링크(해석 결과가 소스) 가운데 놓친 것 0건 |
| 비-md 링크 비교 | `transfers()` `:300-336`, `resolve(before=True)` `:247-253` | 원본 쪽 링크는 md 소스에만 경로 대응(`entry_routes`)을 적용한다. README 가 같은 폴더의 json·gz 를 링크하면 원본은 옛 경로, 목적지는 새 경로로 해석돼 `Destination ordered content differs` 가 난다. 보호 문서의 비-md 링크도 같은 이유로 고칠 수 없다 |
| 보호 문서 | `protected()` `:405-421` | 내용이 그대로인 보호 문서는 검사하지 않는다. 바뀌면 원본 정규형이 접두어로 남아야 하고 모든 local 링크가 실재해야 한다 |
| 보호 문서의 증적 링크 | 링크 해석 집계 | md 링크만 있는 done 문서 12개, md·비-md 를 함께 가진 것 `D-065-constraints.md`(78행 README, 90행 `cleanup-fix/results.json`), 비-md 만 `D-065-dlq-diagnostic.md`(49행 폴더 링크). 14개 모두 지금 깨진 링크가 없다 |
| 자유 문서의 증적 링크 | 같은 집계 | `docs/conventions/harness-rationale.md`(D-104), `docs/conventions/test-comments.md`(D-102), `docs/runbooks/ci-publication-recovery.md`(D-089), 증적 README 들 사이 링크. TASKS·TASKS-done·PHASE5 에는 증적 링크가 없다 |
| 증적 안 도구 | `git grep -E 'parents\[\|__file__'` | `D-078/check-legacy.py:11`·`check-mutants.py:9` 만 `parents[4]` 다. `D-075` 는 자기 폴더 기준, `D-104` 두 도구는 저장소 루트에서 실행하는 전제, `D-065-lineage-writer` 는 컨테이너 절대경로라 이동과 무관하다 |
| docs 밖 참조 | `git grep -F plans/evidence -- ':!docs/'` | `scripts/codex-review-render.sh:10` 주석 하나다. 이 PR 은 스크립트를 고치므로 같이 고친다 |
| Actions 사본 | 조사 B §4.2 | run id 를 가진 폴더 12개. artifact 만료일은 `gh api` 로 다시 읽어 색인에 적는다. 로그 보존(저장소 기본 90일)은 API 로 읽히지 않아 run 생성일 기준 추정으로 적는다 |

### 2.2 B1 역의존 스윕

| 옮기는 경로 | 인바운드 | 처분 |
|---|---|---|
| `docs/plans/evidence/<dir>/**` 26 폴더 | 위 표의 링크 전부, 증적 README 의 바깥 링크(`../../done/…`) | 자유 문서는 새 경로로 다시 쓴다. 보호 문서의 md 링크는 `entry_routes`, 비-md 링크는 이 PR 의 `routes` 로 같은 대상임을 보인다. 백틱·산문 경로는 역사 원문이라 그대로 둔다 |
| `D098` → `D-098` | `docs/plans/done/D-098.md` 의 백틱 언급 | 링크가 아니라 그대로 둔다. 대응표에 적는다 |
| `D-104/README.md` | `docs/progress/PHASE4.md:2858`(파싱 불가), `harness-rationale.md` | §2.4-2 |
| `D-078/check-*.py` | 없음(재현 명령은 README) | `parents[3]` 로 고친다 |

### 2.3 PLAN-BLINDSPOTS 점검

- B1·B1b: §2.2. 링크가 아닌 문자열 결합은 `codex-review-render.sh:10` 주석 하나다.
- B2: 보존 규약(`docs/conventions/docs-preservation.md`)은 "parser를 우회하려고 원문을 바꾸지 말고 별도 검토 가능한 명시 이전 방식으로 처리한다" 고 한다. PHASE4·ADR-0028 원문은 고치지 않는다.
- B4: manifest 이름, 경로, 명령을 §3 에 적었다.
- B11: 새 필터와 `routes` 는 셀프테스트의 양성·음성 대조로 고정하고, 실제 corpus 에서 놓친 링크 0건을 다시 센다.
- B12: `routes` 검증은 검사 시점의 트리 기준이다. 이 PR 의 CI 와 main push 가 같은 검사를 다시 돈다.
- 나머지: 해당 없음(런타임 코드·이벤트·측정 경로 무변경).

### 2.4 해석과 기본값 (PR 본문 "확인 요청")

1. 보존 검사기를 넓힌다. 상위 계획은 검사기 변경을 예정하지 않았지만, 지금 형식으로는 증적 README 를 옮길 수 없다(§2.1). 필터 정밀화는 소스 폴더 밖에서 소스에 닿는 링크가 그 폴더 이름을 반드시 적는다는 점에 기댄다. `routes` 는 비-md 경로의 대응만 선언하며 비-md 내용 비교는 지금처럼 계약 밖이다.
2. D-104 README 는 명시 이전한다. PHASE4 가 파서로 읽히지 않아 그 링크의 재표적을 검사기가 확인할 수 없고, 규약은 원문을 고치지 말라고 한다. 원문을 새 위치에 그대로 두고, 옛 자리에는 새 위치를 가리키는 안내 파일을 둔다. 동등성은 검사기의 링크 정규형 비교로 보인다.
3. 보호 문서의 비-md 링크 2건(`D-065-constraints.md:90`, `D-065-dlq-diagnostic.md:49`)은 corrections 가 아니라 `routes` 로 새 경로를 가리키게 한다. 사실 정정이 아니라 위치 이동이기 때문이다.
4. 상위 계획은 P3 을 문서 전용 CI 로 예정했지만 이 PR 은 스크립트를 고쳐 전체 매트릭스를 돈다(상위 계획 §3 에 기록).

## 3. 작업 항목

- [x] P1. 사전 필터 정밀화. `references_source(text, sources, path=None)` 가 문서 경로를 받는다. 소스 폴더 밖의 문서는 링크 문맥 안에서 소스의 상위 폴더 이름이 파일 이름보다 앞에 있어야 걸린다. 폴더 안(하위 포함)의 문서와 `path` 를 안 준 호출은 지금처럼 이름만 본다. 두 호출(`inbound_links`, `docs-archive-apply.py`)이 경로를 넘긴다.
- [x] P2. `routes`. manifest 선택 필드 `routes: [{"from": "docs/…", "to": "docs/…"}]`(디렉터리). 경로 검증(`docs/` 시작, 정규형, `..`·절대경로 금지, 서로 포함 금지, 중복 금지). 검사 단계 `routes()`: base 의 `from` 아래 파일이 하나 이상 있어야 하고, 그 가운데 md 는 선언한 source 여야 하며, 나머지는 `to` 아래 같은 상대 경로에 있고 옛 경로에는 없어야 한다. `resolve(before=True)` 가 md 대응 다음에 `routes` 를 적용한다(폴더 자체 링크 포함). sources 없이 routes 만 담은 manifest 도 허용한다.
- [x] P3. 셀프테스트(`scripts/docs-preservation-selftest.py`). 필터: 다른 폴더의 같은 이름 README 를 링크하는 파싱 불가 문서는 이동을 막지 않는다, 폴더 밖에서 우회 경로(`dir/sub/../README.md`)로 소스를 가리키는 링크는 여전히 재표적을 요구한다, 소스를 실제로 가리키는 파싱 불가 문서는 실패한다. routes: 형제 파일 링크를 가진 README 의 폴더 이동이 routes 로 통과하고 routes 없이 실패한다, 옮기지 않은 파일·옛 경로에 남은 파일·선언 안 된 md 는 실패한다, 보호 문서의 비-md 링크를 routes 대상으로 고치면 통과하고 다른 곳으로 고치면 실패한다.
- [ ] P4. 증적 이동. `docs/plans/evidence/<dir>/` 26개를 `docs/evidence/<dir>/` 로 `git mv`(`D098` 은 `D-098`). manifest `docs/preservation/d116-evidence-20261026.json` 에 md 23개(D-104 README 제외)의 sources·destinations 와 25개 폴더의 routes(D-104 제외)를 쓴다. 옮긴 README 의 바깥 링크, 자유 문서 3개, 보호 문서 14개의 링크를 새 경로로 고친다.
- [ ] P5. D-104 명시 이전. 원문을 `docs/evidence/D-104/README.md` 로 두고, 옛 `docs/plans/evidence/D-104/README.md` 는 새 위치와 이유(PHASE4 파싱 불가)를 적은 안내 파일로 바꾼다. 나머지 D-104 파일은 함께 옮긴다. `harness-rationale.md` 와 `codex-review-render.sh:10` 은 새 위치를 가리킨다.
- [ ] P6. D-078 재현 도구 두 개의 `parents[4]` 를 `parents[3]` 으로 고친다.
- [ ] P7. 대응표 `docs/plans/evidence/README.md`: 26 폴더의 옛 경로·새 경로·이동일과, 보호 이력의 백틱 경로는 옛 경로로 남는다는 안내.
- [ ] P8. 색인과 README. `docs/evidence/README.md` 에 32 폴더(옮긴 26 + 기존 6)의 계획·내용·원자료 종류·Actions 사본 만료일을 적는다. 고아 6개(`D-065-access-denied-mvc`, `D-065-signed-gateway`, `D-078-product-actions`, `D-084-signed-recovery`, `D-088`, `D-103`)는 대응 계획을 찾아 적는다. README 없는 5개(`D-065-access-denied-mvc`, `D-065-collector-adapter`, `D-084-signed-recovery`, `D-076`, `D-084`)에 README 를 만든다.
- [ ] P9. 규약. `docs/conventions/evidence.md`(세 위치의 역할·이름 규칙·README 필수 항목·Actions 사본 만료 표기), `docs/conventions/docs-preservation.md` 에 `routes` 와 필터 규칙을 추기한다.

## 4. 검증 방법

base 는 `7d4fa4d` 다. 실패를 먼저 주입한다.

1. 셀프테스트: `python3 -B scripts/docs-preservation-selftest.py` 와 `docs-preservation-real-selftest.py`, `plans-archive.sh --self-test` 통과. 새 사례는 필터·routes 구현을 하나씩 되돌린 사본(이름만 보는 필터, `resolve` 의 routes 적용 삭제, routes 검증 삭제)에서 각각 실패해야 한다.
2. 보존: `docs-preservation-ci.py --base 7d4fa4d` PASS. manifest 의 route 하나를 빼면 FAIL, destination 줄 하나를 빼면 FAIL.
3. corpus 대조: 파싱 가능한 추적 md 전체에서 옛 증적 경로로 해석되는 링크 0건, 모든 증적 링크의 대상 실재. 옛 경로 문자열은 백틱·산문과 대응표·안내 파일·조사 보고서에만 남는다.
4. 파일 대응: base 의 364파일이 새 경로에 하나씩 있다. 내용은 md 23개(manifest), D-104 README(정규형 동등), D-078 도구 2개(`parents` 한 줄 차이) 말고는 blob 이 같다.
5. D-104: 옛 README 와 새 README 의 링크 정규형이 같고, PHASE4 의 링크가 안내 파일로 해석된다.
6. 도구: D-078 두 도구가 새 위치에서 이동 전과 같은 결과를 낸다. D-102·D-099 README 명령은 P2 에서 `scripts/` 로 옮긴 경로 그대로 동작한다.
7. CI lint 잡 단계를 ci.yml 원문대로 로컬 실행하고, `harness-context.sh --check --base 7d4fa4d`, `plans-index.sh --check`, consistency precheck 를 돈다.
8. 앵커 7곳과 기존 manifest 의 이동·삭제 0건.

## 5. 완료 조건

P1~P9 상자가 채워지고 §4 결과를 audit 에 남기고 PR CI(전체 매트릭스)가 성공하면 머지한다. 머지 뒤 다음 단계의 /sync 가 이 계획을 아카이브하고 상위 계획 §3 의 P3a 칸을 채운다.

## 6. 범위 밖과 미해결

- 이력 잡다 문서 7개 이동, ADR 의 `docs/learning` 인용 삭제, D-024 행 정정, `docs/README.md` 문서 맵은 P3b(`D-116-docs-move`)다. D-100 README 의 `docs/learning` 경로도 P3b 가 새 위치에서 지운다.
- 파싱 불가 문서 14개를 고치는 일은 하지 않는다(규약). 그 문서들이 링크하는 대상을 옮길 때마다 같은 명시 이전이 필요하다.
- `routes` 는 비-md 인바운드 링크의 재표적을 강제하지 않는다. 바뀐 문서는 모든 링크 실재를 검사받지만, 그대로인 문서의 깨진 비-md 링크는 이번처럼 corpus 대조로 따로 본다.
