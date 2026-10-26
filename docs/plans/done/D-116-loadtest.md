---
grade: M
---
# D-116-loadtest — 측정 산출물 정리 (D-116 P1)

상위 계획: `docs/plans/D-116.md` §3 P1. 기준 main `91cfa1f`. 결정 원문은 `docs/evidence/D-116/decisions.md` 의 1-1~1-6 이다.

## 1. 명제

다음 중 하나라도 성립하면 미완이다.

- 지운 파일이나 고친 파일 가운데 날짜별 `pruned.tsv` 에 경로·크기·base blob 이 없는 것이 있다. 또는 기록된 blob 이 base 커밋의 그 경로와 다르다. 이 경우 되살릴 수 없다.
- 워밍업·무효 14 run 의 지연 백분위(p50/p95/p99/max, 표본 수, 제외 건수)가 `latency-summary.txt` 에 없는 채로 TSV 가 사라졌다.
- 추적 파일 가운데 하나라도 지운 파일을 경로나 링크로 가리킨다. 정리 기록(`pruned.tsv`)과 정리 전 인벤토리(`docs/evidence/D-116/`)는 예외다.
- k6 요약 JSON 에 JWT 가 남았다. 또는 `setup_data.tokens` 밖의 값이 하나라도 바뀌었다.
- 대체물 없는 로컬 로그 2개의 발췌가 추적되지 않았다.
- 테스트가 `loadtest/reports/` 의 파일을 읽는다.

등급 M: 측정 원자료를 지우는 작업이라 되돌림 비용은 낮지만(base 커밋에서 복원) 테스트 fixture 경로라는 계약 표면이 하나 바뀐다. 코드 모듈은 건드리지 않는다.

### 코드 검증 (main `91cfa1f`)

| 전제 | 확인 | 결과 |
|---|---|---|
| 지연 TSV 수와 측정 run | `git ls-files 'loadtest/reports/*-saga.tsv'` 외 2종 | 21 run × 3 = 63개. 측정 7(10-24 `s3/contend-r1`·`r2`·`r3`·`spread-r1`, 10-26 `s3/c0/c1`·`c2`·`s1`), 워밍업·무효 14(10-25 `s3/s1`·`s3/warmup/w1`~`w6`, 10-26 `s3/c0/warmup/w1`~`w4`, `s3/c0/invalid/{contend-c3-exhausted/c3,contend-retry1/c2,contend-retry2/c2}`) |
| 집계 도구 | `loadtest/scripts/d078-latency.py` | `saga`·`outbox` 하위 명령, nearest-rank, NULL 은 제외 건수로 센다. 조사 때 같은 도구로 21 run 을 집계했다(`.cache/survey-20261026/latency-aggregates.txt`) |
| k6 요약의 토큰 | JSON 파싱 | `setup_data` 를 가진 파일 22개. 내용은 `startedAt`(문자열)과 `tokens`(200 또는 450개). 들여쓰기는 k6 형식(4칸 기준) |
| 토큰을 읽는 코드 | `git grep -n -E "setup_data\|tokens" -- loadtest/scripts scripts` | k6 스크립트가 `setup()` 에서 만들고 쓸 뿐 요약 파일을 읽는 코드는 없다. `test_d002bc_contention.mjs:150` 은 합성 토큰을 쓴다 |
| 10-25 S3 grafana | `loadtest/reports/2026-10-25/grafana/`(S2 제외) | 61파일 2.53MB. jpg 18 = `contend-obs-block` 8 + `s1-spread` 8 + `w6` JVM 2. kafka-lag export 5 = `s1`·`w3`·`w4`·`w5`·`w6`(각 약 130KB). 나머지 json 은 2~20KB |
| fixture 의존 | `scripts/tests/test_kafka_topic_state_guard.py:26` | `SNAPSHOT = ROOT / "loadtest/reports/2026-10-24/s4/d065-5-topic-configs-all.txt"`. CI lint 잡이 돌린다 |
| 대체물 없는 로그 | 디스크 | `2026-10-26/s3/c0/c2-payment-service-2100.log`: JSON 줄 318개(INFO 251·ERROR 66·WARN 1), 크기는 대부분 `stack_trace`. `2026-04-29/run2/k6-stdout.log`: 1,256줄, 진행 줄·`level=warning` 822줄·끝 요약. `2026-04-29/REPORT.md:109,221,284` 가 이 로그를 원본으로 적는다 |
| 커밋 분류 | `.claude/scripts/lib/ship.sh:118-126` | 측정 데이터는 src, `*.md` 는 docs, `scripts/*` 는 chore. 커밋을 셋으로 나눈다 |
| 지운 원자료를 가리키는 문서 | 조사 레인 A §2.11 | 10월 세션의 jpg/json/tsv 개별 파일을 경로로 인용하는 문서는 없다. 세션 로그(`*-capture*.txt`, `s3-capture-plan.tsv`)만 파일명을 적는다. 이 생성 기록은 참조 검사의 예외로 두고 PR 본문에 목록을 적는다 |

## 2. 작업 항목

- [x] P1. D-116 기반: `docs/TASKS.md` 에 D-116 행(🔄), `docs/plans/D-116.md`, 이 계획서와 audit, `docs/evidence/D-116/`(README, 조사 보고서 5개, `decisions.md`, 정리 전 `loadtest/reports` 인벤토리)
- [x] P2. 지연 집계: 21 run 전부를 `d078-latency.py` 로 집계해 `loadtest/reports/<날짜>/latency-summary.txt` 3개를 만든다. 머리에 생성 명령, base 커밋, 측정/비측정 구분을 적는다
- [x] P3. lag 요약: 지울 kafka-lag export 4개(10-25 `s1`·`w3`·`w4`·`w5`)의 series 별 최댓값·그 시각·마지막 값을 `loadtest/reports/2026-10-25/grafana/kafka-lag-summary.tsv` 로 남긴다
- [x] P4. 삭제: 워밍업·무효 14 run 의 지연 TSV 42개, 10-25 S3 관찰 블록 jpg 16개, kafka-lag export 4개. 날짜별 `pruned.tsv` 에 경로·동작·바이트·base blob·근거 결정을 적는다
- [x] P5. 토큰 제거: 22개 k6 요약에서 `setup_data.tokens` 배열을 텍스트 단위로 걷어내고 그 자리에 `"tokens_removed": <개수>` 를 둔다. 다른 바이트는 그대로 둔다. `pruned.tsv` 에 같이 적는다
- [x] P6. 로그 발췌 2개: `c2-payment-service-2100-excerpt.txt`(INFO 는 메시지별 건수·첫/끝 시각, ERROR·WARN 은 전 레코드, `stack_trace` 는 예외 줄과 `Caused by` 줄만), `run2/k6-stdout-excerpt.txt`(머리 설정, 10초 간격 진행 줄, 경고 메시지별 건수·첫/끝 시각, 끝 요약 전부). 머리에 원본 크기·줄 수·sha256 과 발췌 규칙을 적는다. 원본은 로컬에 둔다
- [x] P7. fixture: `scripts/tests/fixtures/d065-5-topic-configs-all.txt` 로 복사하고 테스트 경로를 바꾼다. reports 의 원본은 증적이라 그대로 둔다
- [x] P8. 기록: 세 날짜 `session-issues.md` 와 `2026-04-29/REPORT.md` 에 `## 사후 정리 (2026-10-26, D-116)` 절을 추기한다. 무엇을 왜 지웠는지, 되살리는 명령, D-108 P2 "원자료를 고치지 않는다" 의 예외라는 점을 적는다. `loadtest/captures.md` 에는 같은 기준을 다음 세션 규칙으로 적는다

## 3. 검증 방법

1. 복원: `pruned.tsv` 의 모든 행에 대해 `git rev-parse 91cfa1f:<경로>` 가 기록된 blob 과 같고 `git cat-file -e <blob>` 이 성공한다. 먼저 blob 한 글자를 바꾼 사본으로 검사가 실패하는지 본다.
2. 집계 재현: 남은 7 run 의 TSV 로 `d078-latency.py` 를 다시 돌린 출력이 `latency-summary.txt` 의 해당 블록과 바이트 단위로 같다. TSV 한 행을 지운 사본으로 돌리면 다르게 나오는지 대조한다. 비측정 14 run 은 삭제 전 base 의 TSV 로 같은 대조를 끝낸 뒤 지운다.
3. 참조 0: 추적 텍스트 파일 전체에서 지운 경로를 찾는다. 전체 경로, 날짜 폴더 기준 상대 경로, Markdown 링크 해석 결과를 본다. 예외 3종(정리 기록, D-116 증적, 세션 생성 기록) 밖에서 0건이어야 한다. 삭제 대상 하나를 가리키는 임시 링크를 넣어 검사가 잡는지 먼저 본다.
4. 토큰: 22개 모두 파싱한다. `tokens` 키 0, `eyJ` 로 시작하는 문자열 0 이어야 한다. `tokens` 를 뺀 구조 전체가 base 원본과 같아야 하고 `tokens_removed` 는 원본 배열 길이와 같아야 한다. 원본의 metrics 값 하나를 바꾼 사본을 넣으면 검사가 실패하는지 먼저 본다.
5. fixture: `python3 -B scripts/tests/test_kafka_topic_state_guard.py` 가 통과한다. 새 fixture 를 임시로 치우면 실패하고, reports 원본을 임시로 치우면 통과한다.
6. 발췌: ERROR·WARN 레코드 수(67)와 경고 줄 수(822)가 발췌의 건수 합과 같다.
7. CI lint 잡 명령 전체를 로컬에서 돌린다. 보존 검사(`--base 91cfa1f`)와 하네스(`--check --base 91cfa1f`)를 포함한다.
8. 크기: 정리 전후 `loadtest/reports` 의 추적 바이트 합을 PR 본문 표로 남긴다.
