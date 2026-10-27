---
grade: L
---
# D-116-phase-close — Phase 5 마감과 Phase 6 개시 (D-116 P5·P6)

상위 계획: `docs/plans/D-116.md` §3 P5·P6. 기준 main `9d3cd957`(P4b #256 머지 뒤). 결정 원문은 `docs/evidence/D-116/decisions.md` 의 0-1·3-3·3-5(P5)와 1-4·2-9·4-1~4-3(P6)이다. 사용자 사전 승인(2026-10-26)에 따라 CI 통과 뒤 머지하고, 새 판단은 보수적 기본값으로 정해 §2.7 과 PR 본문 "확인 요청" 에 남긴다. 원격 브랜치 삭제는 2026-10-27 대화에서 사용자가 승인했다(대상은 §2.6 의 26개). 이 계획의 P9~P14 가 상위 P6 이다.

## 1. 명제

다음 중 하나라도 성립하면 미완이다.

- 마감 ADR(번호 0047)이 없다. 또는 Phase 5 를 닫는 근거, Phase 6 운영 방식(수요형 유지, 이월 규칙), 2026-10-25 범위 결정(D-065·D-084 보류, D-108 ③ 범위 밖) 가운데 하나가 빠졌다. 조회 상한 1 유지의 근거를 ADR-0046 이 아닌 곳에 둔다. 색인에 행이 없거나 다음 번호가 0047 로 남았다.
- `docs/TASKS.md` 의 `## 현재 단계:` 가 Phase 5 다. 열린 행 11개(D-065·D-078·D-084·D-108·D-109~D-115)의 ID·본문·상태가 바뀌었다. D-116 행이 ✅ 와 PR 링크를 달고 `docs/progress/TASKS-done.md` 표 맨 아래로 옮겨지지 않았다.
- `docs/progress/PHASE5.md` 의 엔트리가 날짜 오름차순 한 방향이 아니거나 헤딩이 `## YYYY-MM-DD — <제목>` 한 형식이 아니다. 재정렬 전후 줄 다중집합(빈 줄 포함)이 대응표 밖에서 다르다. 종결 엔트리가 없다.
- 다이제스트가 `## 작업 이력` 앞의 날짜 엔트리를 세지 않거나, 작업 이력이 빈 PHASE 파일에서 개요 헤딩을 엔트리로 보고한다.
- `docs/progress/PHASE6.md` 가 없거나 "무엇이 다른가"·"이월 항목 (Phase 5 → 6)"·`## 작업 이력` 가운데 하나가 없다. 07 §16·`docs/README.md`·루트 `README.md`·01 이 Phase 5 를 현재 단계로 적는다.
- TASKS 의 단계 서술 수정과 D-116 완료 이동이 한 PR 에 있다는 이유로 보존 검사가 실패한다.
- `docs/plans/D-116.md` 에 `archive: hold` 가 남았거나 결과 절이 하위 PR 일곱 개를 다 적지 않았다.
- 결정 1-4·2-9·4-1~4-3 의 정리 대상이 사유 기록 없이 남았다. 또는 대체물이 추적되지 않은 파일을 지웠거나, 지운 브랜치의 이름과 SHA 가 기록되지 않았다.

등급 L: 판정 기준 셋 가운데 둘에 해당한다. 하네스가 읽는 단계 앵커(`## 현재 단계:` 와 `PHASE{N}.md`)와 다이제스트 파서, CI 게이트인 보존 검사기가 바뀐다(기준 2). 되돌리려면 TASKS·PHASE6·하네스·검사기를 함께 되돌려야 한다(기준 3). Gradle 모듈은 건드리지 않는다(기준 1 해당 없음). 그래서 PLAN-BLINDSPOTS 와 B1 역의존 스윕을 한다(§2.4·§2.5).

## 2. 배경

### 2.1 코드 검증 (base `9d3cd957`)

| 전제 | 확인한 곳 | 결과와 반영 |
|---|---|---|
| PHASE5 재정렬·헤딩 변경과 보존 검사 | `scripts/docs-preservation-check.py:454-461`(보호 범위), `:499-504`(삭제 선언), `:471-472`(인바운드 검사는 manifest source 가 있을 때만) | `docs/progress/` 에서 보호 이력은 `TASKS-archive-*.md` 뿐이다. PHASE5 는 지우지도 옮기지도 않으므로 manifest 없이 고치면 검사기는 줄 순서와 헤딩을 비교하지 않는다. 실패하지 않는 대신 보장도 없어서 줄 다중집합·순서·형식 대조를 따로 한다(§4-2). 헤딩 40줄·하위 절 1줄·형식 선언 1줄 말고는 원문을 바꾸지 않는다. parser 를 피하려고 고치는 원문은 없다 |
| TASKS 서술 수정과 완료 이동이 한 PR 에 있을 때 | 같은 파일 `:427-452` | 행이 하나라도 옮겨지면 TASKS 의 행 밖 줄까지 base 와 접두사 비교를 한다. scratchpad 사본 저장소에서 실측했다. 단계 헤딩만 고치면 PASS, D-116 이동만 하면 PASS, 둘 다 하면 FAIL(`Existing row/footer interleave changed: docs/TASKS.md`), 이동과 파일 끝 추기는 PASS 다. 규약 `docs/conventions/docs-preservation.md:13-15` 는 남은 TASKS 행의 순서와 done 의 footer 만 보존 대상으로 적는다. 이 비교를 done 에만 적용하도록 고친다(P1, §2.7-1). 사본에서 고친 뒤 위 결합 PASS, 이동하며 남은 행 순서를 바꾸면 FAIL, 기존 셀프테스트 64건과 실제 원문 셀프테스트 PASS 를 확인했다. 대안인 corrections manifest 선언도 사본에서 PASS 였다 |
| PHASE5 구조 | `docs/progress/PHASE5.md`(1,220줄, `## ` 86개) | 엔트리(날짜 있는 헤딩) 82개, 틀 3개(147 "Phase 5 는 무엇이 다른가", 179 "이월 항목 (Phase 4 → 5)", 188 "작업 이력"), 날짜 없는 하위 절 1개(69, 위 엔트리 67 의 본문)다. 구역은 셋이다. 작업 이력 앞 8개(8~165, 최신이 위, 두 틀 사이에 D-059 하나), 옛 형식 `## <제목> (…, YYYY-MM-DD)` 32개(192~755, 최신부터), 새 형식 `## YYYY-MM-DD — <제목>` 42개(790~1206, 오래된 것부터). 190 행 형식 선언은 옛 형식이다 |
| 다이제스트 파서 | `scripts/harness-context.sh:195-203` | `작업 이력` 뒤 헤딩만 엔트리로 세서 지금 74개로 보고하고 앞 8개를 빠뜨린다. 작업 이력이 비면 모든 `## ` 를 엔트리로 본다. 사본 실측: TASKS 를 Phase 6 으로 바꾸고 PHASE6 을 틀 둘과 빈 작업 이력으로 두면 경고 없이 "전체 3엔트리" 와 틀 헤딩 셋을 최근으로 낸다. `PHASE6.md` 가 없으면 `파서 고장 1건` 과 exit 3 이다(CI `Harness archive health` 실패). 재정렬만으로 앞 8개 누락은 사라지지만 빈 작업 이력 문제는 남는다 |
| 단계·PHASE 소비자 | `harness-context.sh:82,190-192`, `.claude/commands/ship.md:250`, `done.md:21-24`, `sync.md:42,51`, `next.md:5`, `scripts/harness-context-selftest.py:22,35`(fixture), `.github/workflows/ci.yml:233-251` | 문자열로 읽는 곳은 하네스 하나다. `^## 현재 단계:` 줄에서 `Phase\s*(\d+)` 로 N 을 뽑아 `PHASE%s.md` 를 연다. 커맨드는 `PHASE{N}` 을 지시문으로 읽는다. `consistency-hints.sh` 와 `.claude/scripts/lib` 는 둘 다 읽지 않는다 |
| 하네스 셀프테스트·변이 | `scripts/harness-context-selftest.py`, `scripts/harness-context-mutants.py:11-70` | 변이는 원문 문자열로 바꿀 곳을 찾는다(없으면 `mutation source drift`). progress 절 문자열을 쓰는 변이는 없다. 마지막 줄이 "11 CLI and 5" 건수를 문자열로 찍는다. 사본에서 progress 절만 고친 뒤 셀프테스트와 변이가 PASS 였다 |
| 2026-10-25 범위 결정의 출처 | `PHASE5.md:998-1016`(PG·운영 제외: D-064·D-084·D-065 ④·③ 운영 몫), `:1059-1070`(측정을 막는 부채만, 상한 1 유지, D-108 ③ 은 측정 overlay 완화로 처리), `:1079-1083`(D-064 범위를 좁혀 재개, #249), `docs/TASKS.md:52,54,55`(D-065·D-084·D-108 상태 칸) | TASKS 행 상태 칸과 PHASE5 엔트리에만 있다. 출처와 함께 마감 ADR 로 옮긴다. 상한 1 유지는 `docs/adr/0046-product-read-cache-coalescing-and-stale-serving.md:62` 의 D1 이다 |
| ADR 번호와 형식 | `docs/adr/README.md:24`(다음 번호 0047), `docs/adr/template.md`, `scripts/consistency-hints.sh` 항목 2 | 새 ADR 은 0047, 색인의 다음 번호는 0048 이다. `docs/` 안의 `ADR-NNNN` 은 파일이 있어야 하므로 0048 은 접두사 없이 적고, ADR 을 그것을 가리키는 문서보다 먼저 커밋한다 |
| TASKS 구조 | `docs/TASKS.md:17,21-40`, 행 52~63 | 단계 서술은 21~40 행이다. 열린 행은 12개(D-116 포함)이고 D-116 이 파일의 마지막 줄이다 |
| 현재 단계를 적은 곳 | `README.md:50,69`, `docs/01-project-overview.md:5`, `docs/07-roadmap-portfolio.md:39-46`, `docs/README.md:33`, `docs/TASKS.md:17,21,23` | 이것만 고친다. ADR 색인의 Phase 열, session-issues 의 "Phase 5 마감 정리", 셀프테스트 fixture 는 이력이거나 독립 입력이라 둔다. `CLAUDE.md` 에는 현재 단계 서술이 없다(66 행 "Phase 1~4", 88 행 07 포인터) |
| 계획 아카이브 판정 | `scripts/plans-archive.sh:51-59,62-68,72-96`, 현재 dry-run | 지금 D-116 은 숫자 없는 hold 라 `유지` 다. hold 를 지우고 결과 절에 PR URL(#251~#256 과 이 PR)을 적으면 머지 뒤 `후보` 가 된다. 이 계획은 `/ship` 이 audit 에 남기는 `PR:` 줄로 머지 뒤 `후보` 가 된다. 이 계획과 audit 에는 다른 PR 의 URL 을 쓰지 않는다 |
| 완료 기록 위치 | `ship.md:244-265`, `D-116.md:138-140` | 8단계는 `PHASE{N}.md` 에 PR URL 을 적는데 단계를 바꾼 뒤의 N 은 6 이다. 상위 계획은 PHASE5 종결 엔트리를 요구한다(§2.7-3) |
| CI 분류 | `ci.yml:31-42`, `scripts/ci-docs-only.sh:19-25` | `scripts/**` 를 바꾸므로 code=true 다. test·guards·images·e2e·gate 가 모두 돈다. lint 잡은 분류와 무관하게 돈다 |
| 준비물 | `.cache/d116-p5-prep/`(P4b 세션) | 재정렬·대조 도구와 초안이 있다. 현재 PHASE5 로 시험하면 엔트리 82, 틀 2, 바뀌는 줄 42 이고 그 밖의 비어 있지 않은 줄의 다중집합이 같다. 다만 절 끝 빈 줄을 하나로 줄여 빈 줄 3개가 빠지고, 제목의 `D-NNN — ` 를 `D-NNN ` 으로 바꾼다. 두 곳을 고쳐 쓴다(P4). 로컬 정리(P13)가 `.cache` 를 지우므로 사본을 세션 scratchpad 의 `plan/prep-copy/` 에 두었다 |

### 2.2 PHASE5 재정렬 규칙

- 방향은 날짜 오름차순이다. 새 형식 구역(2026-10-21 부터)이 작업 이력 끝에 붙여 온 방향과 같다.
- 같은 날짜는 원래 구역의 순서를 따르되 최신부터 쌓인 두 구역(작업 이력 앞, 옛 형식)은 뒤집는다. `git blame` 의 도입 커밋 시각과 대조하면 이 순서와 다른 쌍이 셋 있다. 둘은 한 커밋에서 함께 들어와 시각이 같고(#138 의 D-032·D-036, 2026-10-17 의 "10건 완료"·"최종 병합·검증·완료 기록"), 하나는 2026-10-24 의 두 엔트리로 병합 순서가 커밋 시각과 반대다. 셋 다 구역 순서를 따른다.
- 구역 사이의 순서는 옛 형식(2026-09-22~09-30), 작업 이력 앞(2026-10-14~10-21), 새 형식(2026-10-21~10-27)이다. 구역끼리 날짜가 겹치는 곳은 2026-10-21 하나이고, 작업 이력 앞의 엔트리가 새 형식 엔트리보다 먼저다.
- 헤딩은 `## YYYY-MM-DD — <제목>` 이다. 옛 형식은 괄호 안의 날짜만 앞으로 옮기고, 제목 문자열과 괄호의 나머지(D-ID, ADR, PR 링크)는 순서대로 둔다. 괄호에 날짜만 있었으면 괄호를 지운다.
- 틀 두 절은 `## 작업 이력` 앞에 둔다. 날짜 없는 하위 절(69)은 `###` 로 내려 위 엔트리(67)의 본문으로 둔다. 190 행 형식 선언을 새 형식과 방향으로 바꾼다.
- 절은 빈 줄까지 원문 그대로 옮긴다. 끝 빈 줄이 0 인 마지막 절(4b)은 재정렬 뒤에도 마지막이라 빈 줄 수가 같다.
- 대응표는 42줄(헤딩 40, 하위 절 1, 형식 선언 1)이다.
- 인바운드: 저장소에 `PHASE5.md#` 링크 0건, 파일 안 `](#` 0건, ADR 의 PHASE5 언급 0건이다. 줄 번호 인용(`PHASE5.md:NNN`)은 조사 보고서 셋(`docs/evidence/D-116/survey-B·C·D`)과 `docs/plans/done/D-052.md`(보호 이력)에 있고 각자의 base 커밋 기준이라 둔다.

### 2.3 다이제스트 수정 범위

- 엔트리는 날짜(`YYYY-MM-DD`)가 있는 `## ` 헤딩이다. `작업 이력` 앞뒤를 가리지 않고, 날짜 없는 틀 헤딩은 세지 않는다.
- 최근 넷은 날짜로 정렬(같은 날짜는 파일 순서)한 뒤의 마지막 넷이다. 엔트리가 없으면 `(없음)` 한 줄을 낸다. 빈 작업 이력은 경고가 아니다.
- 경고 조건(읽기 실패, `## ` 0개, PHASE 파일 없음)은 그대로다.
- `--phase 1`·`--phase 2`·`--phase 4` 로 옛 파일을 읽으면 날짜 없는 절(PHASE1 의 옛 템플릿 절, PHASE4 의 목차 헤딩)을 더는 엔트리로 세지 않는다. PHASE1·2 는 날짜 엔트리를 `###` 로 적어 와서 0엔트리 `(없음)` 이 되고, PHASE4 는 67 에서 41 이 된다. PHASE3 은 그대로다. 기본 다이제스트는 현재 단계 파일만 읽는다(P2 구현 중 확인, 처음 계획은 PHASE2 를 빠뜨렸다).
- 198·201 행 주석을 새 규칙으로 바꾼다.

### 2.4 B1 역의존 스윕

| 바꾸는 것 | 인바운드 | 처분 |
|---|---|---|
| `## 현재 단계:` 줄 | `harness-context.sh:82` 정규식과 `:190-191` 의 N 추출, 셀프테스트 fixture `:22`, `/sync`·`/next` 의 현재 Phase 보고 | `## 현재 단계: Phase 6 — …` 형식을 지킨다. fixture 는 독립 입력이라 둔다 |
| `PHASE{N}` 의 N(5 에서 6) | `harness-context.sh:192`, `ship.md:250`, `done.md:21`, `sync.md:51`, CI `Harness archive health` | `PHASE6.md` 를 TASKS 전환과 같은 커밋에 둔다(없으면 exit 3). 이 PR 의 8단계 기록만 PHASE5 로 둔다 |
| PHASE5 헤딩 40개와 그 앵커 | 앵커 링크 0건, 줄 번호 인용 4파일(§2.2) | 재표적할 링크가 없다. 줄 번호 인용은 base 기준 이력이라 둔다 |
| PHASE5 형식 선언(190) | `/done` 7단계가 이 파일을 읽고 엔트리를 더한다 | 새 형식과 방향으로 바꾼다. PHASE6 에도 같은 선언을 둔다 |
| 다이제스트 progress 절 | `/sync`·`/next`·`/done` 의 다이제스트, 셀프테스트, 변이(원문 문자열 대조), CI `Harness archive health` | 시험 2건과 변이 1건을 더하고 변이 출력 건수를 고친다. 기존 변이 문자열은 그대로다 |
| 보존 검사기 `tasks()` | CI `Document preservation`, `/ship` 2·8단계, `/done` 10단계, `/sync` 아카이브(`docs-archive-apply.py` 가 이 모듈을 불러 쓴다), 셀프테스트 두 파일, `plans-archive.sh --self-test` | 행 순서·본문·이동 순서·done footer 검사는 그대로다. 셀프테스트와 규약 문장을 더한다 |
| ADR 색인 | `harness-context.sh` 의 행 형식과 셋째 칸 Status, `consistency-hints.sh` | 표 형식을 지키고 0047 행을 표 끝에 둔다. 0048 은 접두사 없이 적는다 |
| 07 §16 | 앵커 링크 0건, `README.md:69` 와 PHASE5 "무엇이 다른가" 의 "§16" 산문 | 절 번호를 지킨다 |
| `D-116.md` frontmatter 와 결과 절 | `plans-archive.sh` 의 `hold_prs`·`plan_prs` | §2.1 "계획 아카이브 판정" 대로 8단계에서 바꾼다 |
| D-116 행 이동 | 보존 `tasks()`, `harness-context.sh --check --base` | 상태 칸만 ✅ 와 PR 링크로 바꾸고 본문은 그대로 done 표 맨 아래에 둔다 |

### 2.5 PLAN-BLINDSPOTS 점검

- B1·B1b: §2.4. 문자열 결합은 `## 현재 단계:` 정규식, `PHASE%s.md`, `작업 이력` 헤딩 문자열(다이제스트가 더는 위치 기준으로 쓰지 않는다), 변이 원문과 출력 건수 문자열이다. ci.yml 단계 이름은 바꾸지 않는다.
- B2: 마감 ADR 은 코드 목표를 적지 않는다. ADR 이 적는 기록 규칙은 이 PR 의 PHASE 파일과 다이제스트로 확인하고, 범위 결정 문장은 §2.1 의 출처와 대조한다.
- B4: 바꿀 줄 번호, 명령, 목록 파일을 §3 와 §2.6 에 적었다.
- B11: 단계 문구 0건과 앵커 링크 0건 검사는 가짜 문구·링크를 넣어 잡히는지 먼저 본다(§4-2·4-5). 로그 목록 확인기는 계획 단계에서 가짜 행으로 실패를 확인했다.
- 나머지(B3·B5~B10·B12·B13): 해당 없음. 코드 모듈, 런타임 빈, 이벤트, 측정 경로를 바꾸지 않는다.
- ADR 선행 판단(`/plan` §3): 새 외부 의존성·아키텍처 경계·새 환경이 없어 자동 통과다. 마감 ADR 은 결정 3-5 의 산출물이라 작업 항목 P3 으로 가장 먼저 쓴다.

### 2.6 로컬 정리 대상 (상위 P6, 2026-10-27 목록)

목록과 확인 도구는 세션 scratchpad 의 `plan/p6/` 에 있다(`order.txt`, `logs.tsv`, `check_logs.py`, `worktrees.tsv`, `local-branches.tsv`, `remote-branches.tsv`, `cache.tsv`). 저장소에는 두지 않는다. 실행 전에 다시 산출해 대조한다.

| 대상 | 수 | 근거와 처분 |
|---|---|---|
| `loadtest/reports` 비추적 `.log` | 59 | 삭제 57개(1,966,934B). `*.k6.log` 50개는 같은 이름의 `.json` 요약이, 중단 run 의 `2026-10-24/s2-runs/incomplete/…r2.k6.log`(431B, 시작 배너뿐)는 추적된 `.meta` 가, `stall-logs/*.log` 5개는 `mysql-errors-excerpt.txt` 가, `2026-10-26/s3/seed-products.log` 는 바이트가 같은 `2026-10-25/s3/seed-products.txt` 가 대신한다(조사 A §2.10, `docs/evidence/D-116/survey-A-loadtest.md:136-142`). 유지 2개(484,936B)는 `2026-10-26/s3/c0/c2-payment-service-2100.log` 와 `2026-04-29/run2/k6-stdout.log` 이고, 발췌 `…-excerpt.txt` 는 #251 로 추적된다 |
| `docs/plans/.archive/`(결정 2-9 의 상태 파일 46개) | 0 | 이미 없다. P2 세션에서 지웠다(#252 확인 요청 4). 확인만 한다 |
| worktree | 4 | 모두 HEAD 가 origin/main 의 조상이고 추적 변경 0, 열린 파일 0(lsof)이다. 둘(`agent-a69830a2…`, `agent-a869a8e3…`)은 pid 95296 이 잠갔고, 그 pid 는 살아 있는 이 세션의 claude 프로세스(지금 셸의 부모)다(§2.7-4). 안의 비추적 내용은 빌드 산출물과 `.cache`(PR 본문, consistency 로그, 레인 B 작업 사본)이고 결과는 각 PR 과 audit 에 있다 |
| 로컬 브랜치 | 26 | main 과 이 브랜치를 뺀 전부가 origin/main 에 머지됐다(일반 22, `worktree-agent-*` 4). 넷은 worktree 에 체크아웃돼 있어 worktree 를 먼저 지운다 |
| 원격 브랜치 | 26 | `git branch -r --merged origin/main` 에서 main 을 뺀 전부다. D-116 하위 PR 브랜치 6개를 포함한다. 2026-10-27 사용자 승인. 이 PR 의 브랜치는 머지할 때 지운다 |
| `.cache/` | 77 | 유지 2(`codex-off`, `d078-s0.txt`), 삭제 75(약 10MB). 조사 사본 `survey-20261026/` 8개는 다섯이 추적본과 바이트가 같고, `survey-C-docs.md` 는 결번 표기만 다르고, `latency-aggregates.txt` 의 71행은 날짜별 `latency-summary.txt` 에 있고, `decisions.md` 는 초안이다(§2.7-8). `codex-reviews/` 를 지우면 `codex-review-render-selftest.sh` 의 V9 는 CI 에서처럼 건너뛴다 |
| `.gradle-work/` | 1 | 527MB 의 임시 `GRADLE_USER_HOME` 이다. 설정 참조 0, Gradle 프로세스 0, 열린 파일 0 |
| 남기는 것 | | `.gradle/`, `build/`, `local-keys/`, `docs/learning/`, `k8s/overlays/gke-probe-state1/`(상위 계획), `.idea/`, `.sdkmanrc`, `.claude/settings.local.json`, `.claude/scheduled_tasks.lock` |

### 2.7 해석과 기본값 (PR 본문 "확인 요청")

1. TASKS 결합 실패는 보존 검사기를 고쳐 푼다. interleave 비교를 done 에만 두고, 남은 TASKS 행의 순서·본문·이동 순서 검사는 그대로 둔다. 행 이동이 없는 PR 은 지금도 TASKS 서술을 고칠 수 있으므로 빠지는 보장은 없다. 대안은 (가) TASKS 의 바뀐 줄을 corrections manifest 로 선언하기(검사기 무변경, 현행 서술 갱신을 "정정" 으로 남긴다), (나) D-116 이동만 별도 PR 로 나누기다.
2. PHASE5 재정렬은 §2.2 대로 한다. 제목 문자열은 바꾸지 않고 빈 줄까지 원문 그대로 옮긴다. "Phase 5 는 무엇이 다른가" 의 "단계가 종결되지 않는다" 는 그 시점의 기록이라 두고, 종결 엔트리와 마감 ADR 이 닫는다.
3. 이 PR 의 `/ship` 8단계 기록은 PHASE5 종결 엔트리에 단다(N=6 이 아니다). 이 PR 은 Phase 5 의 마지막 PR 이다. PHASE6 작업 이력은 비워 두고 첫 엔트리는 다음 작업이 쓴다. 같은 규칙을 마감 ADR 에 적는다.
4. 잠긴 worktree 2개는 상위 계획의 "잠금 pid 가 죽었으면" 과 다르게 처리한다. 잠금 pid 가 이 세션 자신이고 열린 파일 0, 추적 변경 0, HEAD 머지됨이면 unlock 뒤 지운다. 조건이 하나라도 깨지면 남기고 종결 엔트리에 적는다.
5. 부채 ID 없이 남은 판단 항목은 PHASE6 이월 절에 따로 적고 새 D- 행은 만들지 않는다. 측정 run 의 kafka-lag export 7개 유지(#251 확인 요청), 쓰이지 않는 분산 락 코드의 삭제와 Layer 1 대조의 lint 화(#256 확인 요청 6·7), `d002bc-order-create.js` 의 60초 setup 한계(상위 §6)다.
6. 마감 ADR 의 관련 Phase 는 Phase 6 이다. 본체가 Phase 6 의 운영 규칙이다. 색인의 "관련 Layer 1 문서" 는 07 이다.
7. `CLAUDE.md` 는 고치지 않는다. 현재 단계 서술이 없다. 88 행 "Phase별 구현 방식은 07 참고" 는 P4a 뒤로 낡았지만 Layer 1 밖이라 §6 에 둔다.
8. `.cache/survey-20261026/decisions.md` 초안은 추적 정본 `docs/evidence/D-116/decisions.md` 가 대신한다. 초안에만 있는 메모 넷(2-3 의 "보호 이력 58파일", 3-7 의 `TASKS-done.md:34`, 4-1 의 "세션 뒤", 사본을 마지막에 지운다는 문장)은 수치·줄 번호·시점 메모이고, 결정은 정본과 상위 계획에 있다.
9. `/ship` 과 머지 전 재검증이 만드는 `.cache` 산출물(consistency 로그, PR 본문, lint 산출물)은 머지 직후 지운다. `/ship` 을 다시 돌 때 PR 본문을 다시 쓰기 때문이다.

## 3. 작업 항목

순서대로 한다. 커밋은 분류별로 나눈다(`scripts/*` 는 chore, `docs/adr/*` 는 adr, 나머지는 docs). 마감 ADR 을 그것을 가리키는 문서보다 먼저 커밋한다. P4 재정렬은 다른 변경과 섞지 않고 단독 커밋으로 둔다(§4-2 의 대조 기준). P8 뒤에 §4 의 1~5·7 을 돌고, 통과하면 P9 로 간다.

- [x] P1. 보존 검사기. `scripts/docs-preservation-check.py` 의 interleave 비교(427~452 행)를 done 에만 적용한다. `scripts/docs-preservation-selftest.py` 에 시험 2건을 더한다. TASKS 의 단계 서술 줄을 고치면서 행을 옮기면 통과(수정 전 실패), 행을 옮기면서 남은 행의 순서를 바꾸면 실패다. 규약 `docs/conventions/docs-preservation.md` §비교 기준과 완료 행 에 "TASKS 의 행 밖 줄은 현행 서술이라 완료 이동과 같은 PR 에서 고칠 수 있다" 는 문장을 더한다.
- [x] P2. 다이제스트. `scripts/harness-context.sh` 의 progress 절을 §2.3 대로 고친다. `scripts/harness-context-selftest.py` 에 시험 2건(작업 이력 앞의 날짜 엔트리를 센다, 빈 작업 이력에서 `(없음)` 을 내고 틀 헤딩을 내지 않는다)을, `scripts/harness-context-mutants.py` 에 변이 1건(날짜 거르기를 빼면 둘째 시험이 잡는다)을 더하고 마지막 줄의 건수를 고친다.
- [x] P3. 마감 ADR `docs/adr/0047-phase5-closure-and-phase6-operation.md`. template 형식, Status Accepted, Date 2026-10-27, Deciders 는 프로젝트 오너(2026-10-26 질문지 0-1·3-5), 관련 Phase 6. Context 는 Phase 5 가 "종결 조건 없음" 을 결정으로 적어 둔 것(PHASE5 "Phase 5 는 무엇이 다른가", TASKS 단계 절, 07 §16)과 2026-10-26 마감 요청의 충돌, 범위 결정이 TASKS 행과 PHASE5 엔트리에만 있다는 사실이다. 줄 번호는 재정렬로 바뀌므로 절 이름으로 가리킨다. Decision 은 아래 앞의 다섯 항목이다.
  - Phase 5 는 Exit Criteria 가 아니라 사용자 결정으로 2026-10-27 에 닫는다. 마감 정리(D-116)가 끝나는 시점이다.
  - Phase 6 도 수요형이다. 순서표·작업 목록·Exit Criteria 를 두지 않고, D- 단일 축과 다음 번호 규칙을 잇는다. 단계는 사용자 결정으로 닫고 그 결정을 ADR 로 남긴다.
  - 이월: 열린 행은 ID·본문·상태를 그대로 TASKS 에 둔다. 새 단계 파일의 이월 표가 이월 시점의 상태를 적는다. 완료 행은 단계와 무관하게 `TASKS-done.md` 에 쌓는다.
  - 기록: 단계를 닫는 PR 의 기록은 닫히는 단계 파일의 종결 엔트리에 둔다. 단계 파일의 엔트리는 `## YYYY-MM-DD — <제목>` 으로 오래된 것부터 쌓는다.
  - 2026-10-25 범위 결정(§2.1 출처): D-065 의 ③ 운영 몫과 ④ 보류, D-084 보류(재개 조건 원문), D-108 ③ 범위 밖(측정 overlay 의 완화로 처리하고 base·운영 프로브 정책은 정하지 않는다), D-064 는 범위를 좁혀 재개해 #249 로 완료, 측정을 막는 부채만 해결하고 과금 세션 1회로 측정을 끝냈다(#250). 조회 DB 진입 상한 1 유지는 ADR-0046 D1 을 가리킨다.
  - Alternatives 는 셋이다. Phase 5 를 닫지 않고 기록만 분리(조사 C Q1 (a)), Phase 6 에 로드맵·Exit Criteria 를 두기, 열린 행을 아카이브하고 Phase 6 에서 다시 등록하기. 셋째는 ADR 이 D- 번호로 행을 가리키는 관계(`TASKS-done.md` 머리말)를 끊는다.
  - 색인 표 끝에 행(Accepted, Phase 6, 관련 Layer 1 문서 07)을 더하고 24 행의 다음 번호를 0048 로 바꾼다.
- [x] P4. PHASE5 재정렬(단독 커밋). §2.2 규칙대로 한다. 준비 도구 사본 `plan/prep-copy/phase5_reorder.py` 에서 절 끝 빈 줄 정규화와 `D-NNN — ` 치환을 빼고 쓴다. 대응표(42줄)와 대조 결과는 scratchpad 에 남긴다.
- [x] P5. `docs/TASKS.md`. 17 행에 `PHASE6.md` 를 더한다. 21 행을 `## 현재 단계: Phase 6 — 수요 기반 (로드맵 없음, 2026-10-27~, …)` 형식으로 바꾸고 괄호 끝에 마감 ADR 참조를 `see ADR-NNNN` 형식으로 단다. 23 행의 "Phase 5 는 순서표가 없다" 를 Phase 6 으로 고친다. 35 행 "단계 종결 조건 없음" 의 설명을 "순서표 소진이 아니라 사용자 결정으로 닫고, 열린 행은 그대로 다음 단계로 넘긴다" 로 바꾸고 같은 참조를 단다. 39~40 행 "지난 단계" 에 Phase 5(2026-09-22~2026-10-27, 이력 `docs/progress/PHASE5.md`, 완료 행 `docs/progress/TASKS-done.md`)를 더한다. 표의 행은 건드리지 않는다.
- [x] P6. `docs/progress/PHASE6.md` 를 새로 쓴다. 머리말(PHASE5 와 같은 두 줄)과 구분선, "Phase 6 은 무엇이 다른가"(Phase 5 와 같은 수요형이다. 단계를 결정으로 닫는 규칙과 이월 규칙이 처음부터 있고, 문서 구조가 `docs/README.md` 맵으로 정리된 상태로 시작하며, 엔트리 형식과 방향이 하나다), "이월 항목 (Phase 5 → 6)" 표(열린 행 11개의 ID·영역·이월 시 상태, 현황은 TASKS 가 정본이라는 한 줄)와 그 아래 부채 ID 없는 판단 항목(§2.7-5), 구분선, `## 작업 이력` 과 형식 선언을 둔다. 틀 헤딩에는 날짜를 넣지 않는다(다이제스트가 엔트리로 센다).
- [x] P7. 현재 단계 문구. 07 §16 은 Phase 5 절을 2026-09-22~2026-10-27 완료로 줄이고 마감 ADR 과 PHASE5 를 가리킨다. Phase 6 절은 지금 Phase 5 절의 형식으로 두되 작업 목록과 Exit Criteria 는 적지 않는다(현황 TASKS, 이력 PHASE6). `docs/README.md:33` 은 지금 단계를 PHASE6 으로, 루트 `README.md:50,69` 와 `docs/01-project-overview.md:5` 는 PHASE1~PHASE6 와 Phase 6 으로 고친다.
- [x] P8. PHASE5 종결 엔트리를 끝에 붙인다(`## 2026-10-27 — Phase 5 마감 정리 5단계: Phase 5 마감과 Phase 6 개시`). 계획·결정 줄, 마감 결정(ADR 참조), 재정렬 결과(엔트리 82, 바뀐 줄 42), 다이제스트·검사기 수정, PHASE6 개시와 이월 11행, D-116 하위 PR 일곱 개를 적는다. PR 링크와 로컬 정리 결과는 P14 와 8단계에서 채운다.
- [x] P9. 로컬 정리 준비. §4 의 1~5·7 통과, `git status --porcelain` 이 빈 것, #251~#256 이 MERGED 인 것을 확인한다. `git fetch --prune origin` 뒤 `plan/p6/` 목록을 다시 산출해 대조하고, 다르면 멈추고 보고한다.
- [x] P10. 비추적 로그. `check_logs.py` 가 exit 0 이면 삭제 57개를 경로를 하나씩 넘겨 지운다(글롭 금지). 유지 2개와 발췌가 그대로인지 본다. `docs/plans/.archive/` 가 없는지 확인한다.
- [x] P11. worktree 4개를 조건(§2.6, §2.7-4)을 확인한 뒤 `git worktree unlock`(잠긴 것만)·`git worktree remove`·`git worktree prune` 으로 지운다. 비추적 내용이 §2.6 의 분류(빌드 산출물과 `.cache`)뿐이면 `remove --force` 를 쓰고, 지우기 전에 비추적 목록을 `plan/p6/` 에 남긴다. 그 밖의 비추적 파일이 있으면 그 worktree 는 남긴다. 조건이 깨진 worktree 는 남기고 사유를 P14 기록에 넣는다. 이어서 로컬 브랜치 26개를 `git branch -d` 로 지운다(`-D` 금지, 남긴 worktree 의 브랜치는 둔다).
- [x] P12. 원격 브랜치 26개를 `git push origin --delete --force-with-lease=<브랜치>:<기록 SHA> <브랜치>` 로 하나씩 지우고 `git fetch --prune origin` 한다.
- [x] P13. `.gradle-work/` 를 지우고 `.cache/` 를 `codex-off`·`d078-s0.txt` 두 파일만 남긴다(로컬 lint 산출물 포함).
- [x] P14. 결과 기록. 사후 목록을 `plan/p6/after.txt` 로 남긴다. 종결 엔트리에 로컬 정리 결과(건수, 크기, 남긴 것과 사유)를, audit 에 지운 브랜치의 이름과 SHA 표(로컬·원격)를 적는다. §4 의 1·3 검사를 다시 돈다.

`/ship` 8단계(PR URL 확정 뒤, 상자 없음)에서 할 일:

- TASKS 의 D-116 행 상태 칸을 ✅ 와 이 PR 링크로 바꾸고(본문은 그대로, 하위 PR 일곱 개와 로컬 정리 완료를 상태 칸에 적는다) `TASKS-done.md` 표 맨 아래로 옮긴다.
- PHASE5 종결 엔트리의 첫 줄에 이 PR 링크를 달고 미충족을 함께 남긴다. `PHASE6.md` 에는 적지 않는다(§2.7-3).
- `docs/plans/D-116.md` 에서 frontmatter 의 `archive: hold` 를 지운다. §3 표의 P5 칸을 이 PR 번호로, P6 칸을 완료(로컬)로 바꾸고 P5·P6 상자를 채운다. "결과" 절을 더해 하위 PR 일곱 개(#251~#256 과 이 PR)를 URL 로 적고, 계획한 다섯에서 P3·P4 를 둘로 나눠 일곱이 됐다는 것을 적는다.
- 이 계획의 audit 에 `/ship` 블록(PR URL 포함)을 더한다.
- 같은 base 로 보존 검사와 `harness-context.sh --check --base` 를 다시 통과한 뒤 커밋하고 push 한다.

머지 직후(오케스트레이터, 상자 없음): `gh pr merge <번호> --merge --delete-branch` 로 이 PR 의 원격·로컬 브랜치를 지우고 main 으로 동기화한 뒤 §2.7-9 의 `.cache` 산출물을 지워 두 파일만 남긴다.

## 4. 검증 방법

base 는 `9d3cd957` 이다. 실패를 먼저 주입한다. 대조 도구는 scratchpad `plan/prep-copy/` 의 사본을 쓰고 저장소에 두지 않는다.

1. 보존 검사기(P1): 임시 폴더에 base 검사기(`git show 9d3cd957:scripts/docs-preservation-check.py`)와 새 셀프테스트를 나란히 두고 새 양성 시험을 돌리면 실패하고, 수정본에서는 통과한다. 셀프테스트 전체, 실제 원문 셀프테스트, `plans-archive.sh --self-test` 가 PASS 다. `docs-preservation-ci.py --base 9d3cd957` 을 P8 뒤와 8단계 커밋 뒤에 돌려 PASS 다. 8단계 뒤 사본에서 D-116 행 본문을 한 글자 바꾸거나 남은 행 둘의 순서를 바꾸면 FAIL 이다.
2. PHASE5 재정렬(P4): 재정렬 커밋의 PHASE5 와 base PHASE5 의 줄 다중집합(빈 줄 포함)이 대응표 42줄 밖에서 같다. 엔트리 82개의 날짜가 오름차순이고 같은 날짜의 순서가 §2.2 와 같다(`git blame` 시각 대조 포함). 작업 이력 뒤의 `## ` 헤딩이 모두 새 형식이다. 사본에서 줄 하나를 지우거나, 두 엔트리를 맞바꾸거나, 옛 형식 헤딩 하나를 되살리면 각각 실패한다. 최종 PHASE5 는 재정렬 커밋의 PHASE5 를 바이트 접두사로 가진다(뒤는 종결 엔트리뿐이다). PHASE5 의 상대 링크가 모두 해석된다. `PHASE5.md#` 와 파일 안 `](#` grep 이 0건이고, 사본에 가짜 링크를 넣으면 1건이 된다.
3. 다이제스트(P2): 새 셀프테스트 2건이 base 스크립트(`HARNESS_CONTEXT_TEST_SCRIPT` 로 지정)에서 실패하고 수정본에서 통과한다. 셀프테스트 전체 PASS, 변이 전부 REJECTED 다. 실제 다이제스트는 단계 Phase 6, `PHASE6.md` 전체 0엔트리와 `(없음)`, 열린 행 12(8단계 뒤 11)이다. `--phase 5` 는 83엔트리를 세고 최근 첫 줄이 종결 엔트리다. `--check --base 9d3cd957` 이 ok 다. `PHASE6.md` 를 잠시 치우면 exit 3 이다.
4. ADR·색인(P3): ADR 색인 대조 도구로 0047 행 제목이 H1 과 같고 열이 인용 계산(07)과 같다. `consistency-hints.sh` 가 종료 0 이다. 사본에 접두사를 붙인 0048 참조를 넣으면 종료 1 이다. ADR 의 범위 결정 문장이 §2.1 출처의 내용과 같다.
5. 단계 문구(P5~P7): 현행 문서(루트 README, `docs/README.md`, 01, 07, TASKS 단계 절)에서 Phase 5 를 현재로 적은 문구가 0건이다. 옛 문구 하나(`지금 단계는 [PHASE5.md]`)를 사본에 되살리면 잡힌다. 열린 행 11개의 ID·본문·상태 칸이 base 와 같다(8단계 뒤에는 D-116 만 빠진다). 07·README·TASKS·PHASE6 의 상대 링크가 해석된다.
6. 로컬 정리(P9~P14): `check_logs.py` 가 목록 전체를 통과한다. 대체물 없는 가짜 행을 넣으면 exit 1 인 것을 계획 단계에서 확인했다. 지운 브랜치 31개 이름(로컬 26 과 원격 26 의 합집합)의 SHA 가 audit 에 있고 `git cat-file -e` 로 객체가 있다. lease 삭제가 틀린 SHA 에서 거부되는 것은 scratchpad 의 임시 bare 저장소에서 확인했다. 사후 목록은 worktree 가 본 체크아웃 하나, 로컬 브랜치가 main 과 이 브랜치, 원격이 main 과 이 브랜치, `.cache` 가 두 파일, `.gradle-work` 없음, 남길 것 그대로여야 한다.
7. CI lint 잡: ci.yml lint 잡의 run 블록을 원문대로 로컬에서 돌려 PASS 다(준비 도구 사본 `run_lint_job.py`). DLQ 계약 단계는 앞 세션이 남긴 `.cache/dlq-contract/evidence` 가 있으면 시작하지 못하므로 `.cache/dlq-contract/` 를 먼저 지운다(결정 4-2 의 대상이다). `plans-index.sh --check` 최신, writing-lint(커밋) 통과, 앵커 7곳과 `docs/preservation/` 기존 파일의 이동·삭제 0건이다. Java·Gradle 입력 변경은 0건이지만 `/work` §8 대로 `./gradlew test --continue` 를 로컬에서 돌려 결과를 그대로 적는다(P4b 와 같다).

## 5. 완료 조건

P1~P14 상자가 채워지고 §4 결과를 audit 에 남기면 `/ship` 한다. 8단계 갱신 뒤 같은 base 로 보존·하네스 검사를 다시 통과하고 PR CI(전체 매트릭스)가 성공하면 머지한다. 머지 뒤 다음 `/sync` 가 D-116 과 이 계획을 함께 아카이브한다(§2.1 "계획 아카이브 판정").

## 6. 범위 밖과 미해결

- `.claude/commands/ship.md`·`done.md` 지시문은 고치지 않는다. 단계 전환 PR 의 기록 위치와 엔트리 방향·형식은 마감 ADR 과 PHASE 파일의 형식 선언이 적는다.
- Phase 5 완료 행을 `TASKS-archive-phase5.md` 로 옮기지 않는다. D- 축이 이어지고 `TASKS-done.md` 는 단계와 무관한 완료 표다.
- §2.7-5 판단 항목의 처분. PHASE6 이월 절에 남겨 사용자 결정을 기다린다.
- `CLAUDE.md:88` 의 낡은 07 포인터와 Flyway 경로(P4b 기록). Layer 1 밖이다.
- `--phase 1`·`--phase 2`·`--phase 4` 로 옛 파일을 읽을 때 날짜 없는 절을 세지 않게 되는 변화는 되돌리지 않는다.
- 재정렬·대조 도구와 로컬 정리 확인기는 저장소에 두지 않는다(P4b 와 같은 판단).
- P10 실행 중 결정 1-4 가 남기기로 한 원본 2개가 함께 지워졌다. 되살릴 수 없고 #251 의 발췌는 남아 있다. 경위와 처리는 audit 의 로컬 정리 블록과 PHASE5 종결 엔트리에 있다.
