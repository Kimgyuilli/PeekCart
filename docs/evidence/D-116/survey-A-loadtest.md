# Survey A — `loadtest/reports/` 인벤토리와 처분 후보 (읽기 전용 조사, 2026-10-26)

## 0. 전제·총량·방법

- 총량: `du` 44.6MB · 889 파일. 바이트 합은 추적 830 파일 39.14MB + 비추적 59 파일 2.34MB. 비추적 59개는 전부 `*.log`
  (`.gitignore:30 *.log`). `*.k6.log` 51개 0.60MB, 나머지 `.log` 8개 1.74MB.
- git 이력: `loadtest/reports` blob 781개·38.4MB(비압축)가 24개 커밋에 들어 있고 전부 HEAD 에 살아 있다(과거 삭제 0).
  `.git` 67MB. LFS 없음(`.gitattributes` 는 jar 만 binary). **작업트리에서 지워도 `.git` 은 줄지 않는다.**
- 참조 조사 범위: `docs/`(learning 제외), `loadtest/`, `scripts/`, `k8s/`, `.github/`, 서비스 테스트 코드. `docs/learning/`(gitignored)과
  `../blog` 는 따로 셌다(§2.11).
- 각 종류의 대표 파일을 열어 확인했다(head/jq/wc). 지연 TSV 는 `loadtest/scripts/d078-latency.py` 를 읽기 전용으로 돌려 집계를
  scratchpad 에 남겼다: `/private/tmp/claude-501/-Users-kimgyuill-dev-projects-PeakCart/260df7d9-a147-4b8a-8bc3-1f712b9e25a7/scratchpad/latency-aggregates.txt`
  (21 run · 133줄 · 약 10KB). 전체 파일 목록(크기·추적 여부)은 같은 디렉터리의 `inventory.tsv`.
- 범위 밖이지만 같은 디스크: `.claude/worktrees/agent-*/loadtest/reports` 4개(각 13MB, 10-25 이전 체크아웃) 52MB.

분류 기호: **A** 보존 · **B** 요약(집계)만 남기고 원본 삭제 가능 · **C** 삭제 가능 · **?** 소유자 결정(§5 질문 번호).

## 1. 요약 표 (디렉터리별)

| 디렉터리 | 크기 | 파일 | 분류 | 근거 한 줄 | 절감 |
|---|---|---|---|---|---|
| `TEMPLATE.md` | 3KB | 1 | A | `loadtest/README.md` §E·PHASE3 가 진입점으로 인용 | 0 |
| `2026-04-09/REPORT.md` | 7KB | 1 | A | 세션 B 수치 정본(TPS 265→612.7). PHASE3·tool-evaluation·04-29 REPORT 인용 | 0 |
| `2026-04-09/grafana/` (png 6) | 2.1MB | 6 | A 4장 / **?** 2장 | REPORT §정상 관측이 01·02·04-1·04-2 를 근거로 씀. `03-k8s-pod-1/2.png`(0.69MB)는 REPORT 가 "kafka pod selector, 보조 자료로만 보관" | 0.69MB (Q8) |
| `2026-04-09/ngrinder-raw/` (tgz) | 116KB | 1 | A | 유일 원시 데이터(테스트 1·2·3 report/*.data + output.csv + 로그). REPORT 가 "p95/p99 사후 계산 가능" 근거로 지목 | 0 |
| `2026-04-09/ngrinder-ui/` | 112KB | 4 | C(**?**) | 4개 모두 tgz 안 파일과 sha256 동일(완전 중복). 단 PHASE3:521 이 `ngrinder-{raw,ui}/` 로 언급 | 0.10MB (Q9) |
| `2026-04-29/REPORT.md` | 18KB | 1 | A | 저장소에서 가장 많이 인용되는 리포트(PHASE3 5·TASKS-archive 6·D-018·L-022·blog drafts) | 0 |
| `2026-04-29/grafana/` (png 4) | 1.3MB | 4 | A | REPORT 첨부 4장 각각 인용. `03-pod-resources-hpa-scaleout.png` 은 Task 3-5 핵심 산출물(TASKS-archive:420) | 0 |
| `2026-04-29/run1/`, `run2/` | 148KB | 4 | A 3 / **?** 1 | `run2/k6-summary.json` 은 throughput 판정 1차 근거, verify 2개는 정합성 근거. `run2/k6-stdout.log`(130KB, 비추적)는 REPORT 가 "로컬에만 보존" 명시, 수치(EOF 519 등)는 REPORT 에 있음 | 0.13MB 디스크 (Q7) |
| `2026-10-24/` 직속 3 | 11KB | 3 | A | `session-issues.md` 정본(20곳 인용), `environment-fingerprint.txt`(evidence 인용), `cleanup.txt`(ADR-0004 증적) | 0 |
| `2026-10-24/grafana/` | 5.5MB | 76 | A jpg 36 / A json 36 / **?** json 4 | captures.md 규약(jpg=그림 근거, json=클러스터 삭제 뒤 유일한 시계열). `*-kafka-lag-p1.json` 4개가 1.63MB(93 series×56점) | 1.63MB (Q5) |
| `2026-10-24/s2-runs/` | 92KB | 15 | A(소) / `incomplete/` C | S2 "미측정" partial 4 run. evidence §S2 블록 A 표의 원자료. `incomplete/`(1.2KB)는 중단 run meta+빈 로그, 어디서도 인용 없음 | ~0 (Q13) |
| `2026-10-24/s3/` | 4.3MB | 49 | **B** TSV 12 / **?** json 4 / A 나머지 | TSV 3.52MB → 집계(p50/p95/p99 saga·p50/p95 outbox)는 evidence 표에 있음. k6 json 4개 457KB 중 440KB 가 `setup_data.tokens`(JWT 200개) | 3.52MB (Q1) + 0.43MB (Q2) |
| `2026-10-24/s4/` | 328KB | 14 | A / **?** json 1 | `d065-5-topic-configs-all.txt` 는 **`scripts/tests/test_kafka_topic_state_guard.py:26` 의 fixture(코드 의존)**. `d065-5-effective-sample.txt` 는 `KafkaTopicConfigMechanismIntegrationTest.java:340` 주석·D-106 계획 인용. HPA·D-092·⑧ 증적. `e-hpa.json` 117KB 중 110KB 토큰 | 0.11MB (Q2) |
| `2026-10-25/` 직속 11 | 51KB | 11 | A | timeline/issues 정본, `s2-summary.tsv`(S2 집계 SSOT), 배포 로그 = D-107 래퍼 첫 실사용 증적 | 0 |
| `2026-10-25/grafana/` | 4.5MB | 168 | A S2(jpg 17·json 89) / **?** S3(jpg 18·json 42) | S2 export 는 ④ 노드 CPU 84% 판정의 정본("판정은 export 값으로 했다"). S3 run 은 전부 무효(관찰값) | 2.52MB (Q6; kafka-lag 0.62 는 Q5 와 겹침) |
| `2026-10-25/s2-runs/` | 712KB | 130 | A / `warmup/` **?**(80KB) | 측정 24 run 의 json·meta·hikari·pods. evidence 는 중앙값·범위만 적어 per-run 값은 여기만. `warmup/` r1 은 사전 규칙상 버리는 회차 | ~0.08MB (Q13) |
| `2026-10-25/s3/` (warmup 제외) | 1.5MB | 26 | **?** s1 raw 1.1MB / A 나머지 | s1(spread)도 무효. `topic-guard/`·`-after-probes/` 2 dirs 344KB 중 `configs.txt`·`topics.txt` 가 서로 동일 | 0.74MB TSV(Q1·Q3) + 0.25MB JSON(Q2) + 0.15MB dedupe(Q10) |
| `2026-10-25/s3/warmup/` | 4.5MB | 85 | **?** / B | w1~w6 전부 무효 run. 그러나 이 세션 S3 의 **유일한** 데이터이고 evidence·TASKS D-108 이 w5·w6 관찰값 인용, D-108 §8 검증이 w3~w6 `.json` 을 입력으로 씀 | 최대 4.5MB (Q3) |
| `2026-10-25/s4/` | 196KB | 9 | A / dedupe C | D-065 ②⑤ 통과 증적. `topic-guard/configs.txt` 는 다른 4개와 동일, `auth-gate.txt` 는 `s3/auth-gate.txt` 와 동일 | 0.11MB (Q10) |
| `2026-10-25/session-scripts/` | 32KB | 7 | A | `s3-run/s3-export/mysql-sampler` 는 `loadtest/scripts/d078-*` 로 승격됐지만 D-108 P2 가 "10-25 원본은 원자료라 고치지 않는다". `s2-block/s2-export/s4-deploy/session-pin` 은 유일본 | 0 |
| `2026-10-26/` 직속 19 | 56KB | 19 | A | timeline/issues 정본, fingerprint, cleanup, S0~S5 단계 로그(`s3-capture.txt`·`s3-export-*.txt` 는 캡처/export 수행 기록) | 0 |
| `2026-10-26/grafana/` | 4.9MB | 54 | A / **?** kafka-lag 3 | 전부 유효 run(c1·c2·s1)의 ⑤⑥·JVM 캡처+export = D-078 ⑤⑥ 최종 근거. `kafka-lag-p1.json` 3개 0.59MB | 0.59MB (Q5) |
| `2026-10-26/s3/` 직속 + `topic-guard*/` | 374KB | 21 | A / dedupe C | 시드·게이트·프로브·run 콘솔 7개. `topic-guard/`·`-after-recovery/` configs·topics 동일. `seed-products.log`(비추적)는 10-25 `seed-products.txt` 와 바이트 동일 | 0.15MB (Q10) |
| `2026-10-26/s3/c0/` 직속 | 4.1MB | 50 | **B** TSV 9 / **?** json 3·log 1 / A 37 | 측정 c1·c2·s1 원자료. TSV 2.90MB(집계는 D-108 §5·timeline 에 p50/p99 만, §3 참고). k6 json 757KB 중 730KB 토큰. `c2-payment-service-2100.log`(344KB 비추적)는 D-112 원 로그. `order-pool-utilization.tsv`·`run-window-metrics.tsv`·`payment-lag-wait.tsv`·`c2-tfull-substitute.txt`·`c1-mysql-sample.txt` 는 TASKS/D-108 인용 또는 유일 수치 | 2.90MB (Q1) + 0.73MB (Q2) + 0.34MB 디스크 (Q7) |
| `2026-10-26/s3/c0/warmup/` | 4.4MB | 59 | **?** / B | w1~w4. 계획상 "분석에 쓰지 않는다" 이지만 w4 는 사용자 결정 편차 1(수렴 판정)의 근거이고 `*-warmup.txt` 판정 줄·`.json` 수락 수가 그 입력 | 최대 4.4MB (Q3) |
| `2026-10-26/s3/c0/invalid/contend-c3-exhausted/` | 1.2MB | 15 | **?** | c3 무효 run. 건수·드롭·DLQ 는 timeline 에 있고 지연 집계는 어디에도 없음 | 1.2MB (Q4) |
| `.../invalid/contend-retry1/` | 1.2MB | 15 | **?** | 첫 c2 무효 run. 같은 상황 | 1.2MB (Q4) |
| `.../invalid/contend-retry2/` (stall-logs 제외) | 1.0MB | 21 | A 증적 16 / **?** run raw 5 | `integrity-anomaly.txt`(D-113 인용)·`recovery-drain.tsv`·`*-after-recovery.txt`·`recovery-groups-2042.txt` 는 사고 증적. run raw(tsv/json/k6.log 0.85MB)는 디스크 가득 참으로 중단된 run | 0.85MB (Q4) |
| `.../contend-retry2/stall-logs/` | 1.35MB | 14 | A txt/tsv 9 / **?** log 5 | `mysql-disk.txt`(TASKS D-114 인용)·`mysql-errors-excerpt.txt`·`mysql-vars.txt`·`mysql-pvc-used.tsv` 등 70KB 는 D-114 근거. `.log` 5개 1.27MB 는 비추적 원 로그(발췌 파일이 대신함) | 1.27MB 디스크 (Q7) |
| `2026-10-26/session-scripts/` | 24KB | 6 | A | `run.sh`·`capture.sh`·`recovery-drain.sh`·`wait-payment-lag.sh`·`run-window-metrics.py` 유일본(D-115 ①~④ 가 고치려는 동작의 원본), `session-pin.sh` 는 digest 표 | 0 |

절감 합계(겹침 제거 전, 추적분 기준): TSV 전량 B 15.11MB · 토큰 제거 4.56MB · warmup 전체 8.35MB(TSV 5.49·json 2.5 포함) ·
invalid 전체 4.62MB(비추적 1.32 포함) · 10-25 S3 grafana 2.52MB · kafka-lag json 2.79MB · 중복 약 0.7MB · April 0.79MB.
비추적 `.log` 2.34MB 는 디스크만 준다.

## 2. 파일 종류별 상세

### 2.1 `*-outbox-order.tsv` / `*-outbox-product.tsv` / `*-saga.tsv` (63개, 15.11MB, 전부 추적)
- 무엇: `mysql -B` TSV. outbox 는 `event_type / status / publish_wait_us` 한 행 = 이벤트 1건(created_at→published_at, 10,000~14,000행/run).
  saga 는 `id / status / reserve_roundtrip_us` 주문 1건(ordered_at→reservation_confirmed_at). `loadtest/sql/d078-outbox-latency.sql`·
  `d078-saga-latency.sql` 출력, `d078-s3-run.sh:123-125` 가 쓴다. `captures.md` 가 "TSV 원본도 같은 디렉터리에 둔다" 로 규정.
- 재생성: 불가(클러스터·DB 삭제). 집계(nearest-rank p50/p95/p99/max/n/excluded)는 `d078-latency.py` 로 TSV 에서 언제든 다시 낸다.
  **집계 결과 파일은 저장소 어디에도 없다** — 수치가 evidence/D-108/timeline 산문에 일부만 옮겨 적혔다(§3).
- 참조: 경로 인용은 `docs/plans/done/D-075-outbox-throughput.md:8`(`2026-10-24/s3/*`), `D-078-remeasure-plan.md`(`s3/*.json`, `warmup/`),
  D-108 §5(`c0/...`). 파일명 단위 인용은 스크립트(`d078-s3-run.sh`, `test_d078_latency.py` 의 합성 fixture 이름)만.
- 지표: ADR-0045 근거 ③·재검토 조건 1(사가 왕복 p99, 만료 취소), D-078 A(사가 왕복)·B(발행 대기), D-110 입력. 10-24 r1~r3·spread,
  10-26 c1·c2·s1 이 측정 run(6.42MB). 10-25 s1·w1~w6, 10-26 w1~w4·invalid 3 run 은 무효/관찰(8.68MB).

### 2.2 k6 `--summary-export` JSON (S3/S4 22개 4.86MB + S2 46개 0.2MB)
- 무엇: `metrics`(21개: `peekcart_order_accepted`, `dropped_iterations`, `iteration_duration`, `http_req_duration` 등 전 백분위)·`root_group`
  (check 통과/실패)·**`setup_data`**. S3/S4 파일은 95% 가 `setup_data.tokens` — 사용자 450(또는 200)명의 RS256 JWT
  (`kid peekcart-dev-2026`, claims `sub/role/family_id/iat/exp`, 30분 만료). 22개 파일에 4.56MB. S2(`d002a-product-read.js`) JSON 에는 없다.
- 재생성: 불가. 수락·드롭·반복 p95·바쁜 VU 는 evidence/timeline 표에 run 마다 적혀 있다. `d078-warmup-check.py` 와
  `test_d002bc_contention.mjs`(D-078-remeasure-plan §7 "원 요약 JSON 4개로 r1·r2 무효, r3·spread 유효")·D-108 §8("10-25 w3~w6 요약") 이
  **이 JSON 들을 검증 입력으로 썼다**(테스트 코드가 경로를 고정하진 않음 — `test_d002bc_contention.mjs` 는 합성값 사용, 확인 필요 시 grep).
- 참조: `D-078-remeasure-plan.md:10,50`(`s3/*.json`), D-108 §2 표(`--summary-export` JSON 의 키).
- 지표: 부하 판정(D-106 ⑤), USERS 산식 T95, 워밍업 수렴(D-108 §3.1), 10-24 주문 p50/p95.
- 토큰은 클러스터·서명키(`peekcart-user-jwt-signing-key`, 10-26 S5 삭제)가 없어 검증 불가한 만료 토큰이지만 자격증명 형태 데이터가
  git 에 있다(Q2).

### 2.3 `grafana/*.json` (197개, 5.06MB)
- 무엇: `d078-capture.py export` 산출 — 패널 1개의 Prometheus `query_range`(step 15s, `$__rate_interval`=1m) 결과. 키:
  `dashboard_uid/panel_id/title/start/end/step_seconds/rate_interval/targets[].{expr,legendFormat,result[]}`. 대시보드 정의가 아니라
  **패널별 원 시계열**이다. 크기는 series 수에 비례: `kafka-lag-p1`(`kafka_consumer_fetch_manager_records_lag_max`, client_id×partition
  76~93 series) 12개가 2.79MB 로 전체의 55%. `api-jvm-p5`(JVM Heap, 48 series) 4개 0.47MB. 나머지 181개는 평균 10KB.
- 재생성: 불가("클러스터를 지운 뒤에는 이것만 남는다" — captures.md §도구 3).
- jpg 짝: 10-24 는 36/40 짝(json-only 4 = S2 blockA partial). 10-25 는 **run 마다 export, 블록 마다 jpg** 라 129/131 json 이 jpg 없음
  (evidence §배포 편차 3 에 명시). 10-26 은 26/26 완전 짝.
- 참조: 파일명 단위 인용은 `2026-10-26/s3-export-*.txt`(세션 로그)만. 디렉터리 단위 `grafana/` 인용은 evidence 2건·D-078-remeasure-plan.
- 지표: `d078-1/2`(S2 상한 비교: RPS·p95·MySQL CPU·스로틀·Pod CPU·Hikari 대기) · `d078-4`(④ 노드 CPU 84% — "export 값으로 판정") ·
  `d078-5`(⑤ 전달 실패율·DLQ·예약 결과·lag) · `d078-5-drain`(outbox 발행률·backlog, ⑥ 정지 구간 판정 `d078-6-stall-check.txt` 의 입력) ·
  `d078-6`(⑥ taskScheduler active/queued) · `d078-jvm`(D JVM 표) · `d078-hpa`(E).

### 2.4 `grafana/*.jpg` (97개, 8.95MB) · April `*.png` (10개, 3.34MB)
- 무엇: Grafana d-solo 패널 캡처(10-26 은 headless Chrome → sips JPEG, `capture-plan*.tsv` 가 URL·파일명 표). April 은 대시보드 전체 PNG.
- 재생성: 불가. captures.md: "수치만 남기고 그림을 남기지 않으면 README 와 블로그에 실을 근거가 없다".
- 참조: April PNG 는 두 REPORT.md 가 파일명으로 인용(04-29 는 TASKS-archive:420 도). 10월 jpg 는 파일명 인용 없음(디렉터리 인용만).
  `../blog` 두 글(peekcart-16, -17)과 `docs/learning/16·17` 은 어떤 이미지도 삽입하지 않았다(blog 는 썸네일만).
- 10-25 S3 jpg 18장은 전부 무효 run 블록(`contend-obs-block` w4~w6, `s1-spread`, `w6` JVM).

### 2.5 run 보조 파일 (`-prom-before/after/delta.tsv`, `-drain.tsv`, `-times.tsv`, `-counts.txt`, `-verify.txt`, `-reset.txt`, `-restarts.txt`, `*.console.txt`, `*.run.txt`, `-warmup.txt`) — 각 run 10~13개, 총 ≈0.5MB
- `prom-before/after`: product·order Pod 의 `/actuator/prometheus` 원값 6개 metric 패밀리(44~50줄). run N 의 after = run N+1 의 before 로
  13쌍이 바이트 동일(§4). `prom-delta` 는 그 차분 = ⑤ 전달 실패·회복·예약 성공 건수의 **정본**(captures.md "수치의 정본은 캡처가 아니다").
- `drain.tsv`: 2초 간격 outbox PENDING 추이(200행 안팎). 문서에는 소요 시간만 있음.
- `times.tsv`: t0/t1/t_outbox0/t_full(/k6_exit) — export 구간의 입력(`d078-s3-export.sh`), 배수 시간 표의 원본.
- `counts.txt`/`verify.txt`: 상태별 건수·정합성(음수 재고·diff). evidence 표의 만료 취소·RESERVED 합의 원본.
- `reset.txt`: 초기화 SQL 출력(92바이트, 17개 동일). `restarts.txt`: 10-26 run 앞뒤 재시작 수(§3.1 수렴 조건).
- 전부 소형·유일·인용(D-075 계획이 `drain.tsv`·`counts.txt` 를 지목). A.

### 2.6 S2 셀 파일 (`s2-runs/*.json|.meta|.k6.log`, `hikari/*.json`, `pods/*.txt`, `runs.tsv`, `b*.txt`, `s2-summary.tsv`, `s2-collect.txt`)
- `.meta`: `d026-run-cell.sh` 가 쓰는 run 조건·cgroup usage/throttled·캐시 hit/miss 전후·pod uid·`db_max_concurrency`(10-25). 상한 대조 게이트의 증거.
- `hikari/*.active|pending.json`: Prometheus `query_range` 15초 5점(172바이트). evidence 는 최대값만.
- `s2-summary.tsv`(10-25): 셀별 med/min/max 집계 — S2 결론 표의 직접 원본. `s2-collect.txt`: `d026-collect.py` 경고(캐시 counter 비수치 제외).
- 지표: ADR-0037 상한 4 증액 근거(네 조건), ③ product 캐시 hit 차분, ④ 노드 CPU. A.

### 2.7 `topic-guard*/` (5 dirs, 21 files, 0.78MB)
- `kafka-topic-state-guard.py --evidence-dir` 산출: `topics.txt`(describe), `groups.txt`(consumer-groups describe), `configs.txt`
  (`kafka-configs --describe --all`, 1,085줄 111KB), `verdict.txt`(시각·exec·violations). README 가 "원 출력과 판정이 증적으로 남는다" 로 규정.
- `configs.txt` 5개 바이트 동일(브로커 기본값+선언값이 세션 간 같음), `topics.txt` 는 세션 안 2개씩 동일. `groups.txt` 는 모두 다름(할당 상태).
- 지표: D-106 ⑩·D-107 재검토 조건·D-065 ⑤(10-25 s4). 판정은 evidence 표에 있음.

### 2.8 10-24 `s4/` D-065·D-092·E 파일
- `d065-5-topic-configs-all.txt`(111KB): **`scripts/tests/test_kafka_topic_state_guard.py` 의 SNAPSHOT fixture** — 삭제·이동 시 테스트가 깨진다.
  `d065-5-effective-sample.txt`: Java 통합테스트 주석·`D-106-topic-config-race.md:28` 인용. `d065-5-topic-configs.txt`(동적 설정만)·
  `*-table.tsv` 2개는 손 대조 표. `d092-canary.txt`: D-092 두 채널 전달 증적(주문번호 포함). `e-hpa-*`: HPA 1→3 타임라인(5초 watch 128행, events, pods).
  `d078-8-base-apply.txt`: ⑧ 렌더=실행 Pod limits 대조(evidence 인용). 전부 A.

### 2.9 10-26 전용 분석 파일
- `c0/run-window-metrics.tsv`(10 run × 13열, Prometheus 유래) · `c0/order-pool-utilization.tsv`(D-108 ① 결론 표의 원본, TASKS 인용) ·
  `c0/payment-lag-wait.tsv`(65표본, D-112) · `c0/c2-tfull-substitute.txt`(편차 3 기록) · `c1-mysql-sample.txt`(판정 run 표본) ·
  `c2-groups-2100.txt`/`groups-before-c3.txt`(consumer group 상태 33KB×2) · `invalid/contend-retry2/{integrity-anomaly.txt,recovery-*.tsv/txt,
  c2-*-after-recovery.txt}` · `stall-logs/{mysql-disk,mysql-vars,mysql-errors-excerpt,top-*,events,collected-at}.txt`, `mysql-pvc-used.tsv`(30초 294행),
  `run-window-metrics.tsv`(c0 판의 7행 부분 선행본). TASKS D-112~D-115 의 증거 열이 이 파일들을 지목. A.

### 2.10 비추적 `.log` (59개 2.34MB)
- `*.k6.log` 51개(k6 화면 출력; 수치는 요약 JSON 에 동일 — evidence 10-25 머리말이 "남기지 않았다" 고 적었지만 디스크에는 있음).
- `stall-logs/{product-service,mysql-since-1750,mysql,kafka,order-service}.log` 1.27MB — `mysql-errors-excerpt.txt` 가 발췌를 대신.
  `product-service.log` 에 `OptimisticLockingFailureException` 2건, 예외 줄 329건.
- `c0/c2-payment-service-2100.log` 344KB(318줄) — D-112 "결제 정보를 찾을 수 없습니다" 재시도의 원 로그, 발췌 없음.
- `2026-04-29/run2/k6-stdout.log` 130KB — REPORT 첨부 목록에 "로컬 디스크에만 보존" 명시.
- `2026-10-26/s3/seed-products.log` — 10-25 `seed-products.txt` 와 바이트 동일.

### 2.11 참조 지도 (요약)
- 경로 인용이 가장 많은 파일: `2026-04-29/REPORT.md`(PHASE3·TASKS-archive·D-018·blog32/33 스냅샷 다수), `2026-10-24/session-issues.md`
  (TASKS·ADR-0045·evidence·PHASE5·D-075/D-106 계획), `2026-10-24/s4/d065-5-*`(D-106 계획 2건·테스트 2건), `2026-04-09/REPORT.md`.
- 10월 세션의 jpg/json/tsv 개별 파일을 경로로 인용하는 문서는 **없다**(세션 내부 로그 제외). 디렉터리(`loadtest/reports/<날짜>/`, `s3/`, `grafana/`,
  `warmup/`, `invalid/contend-retry2/`) 단위 인용만 있다.
- 코드 의존: `scripts/tests/test_kafka_topic_state_guard.py:26` → `2026-10-24/s4/d065-5-topic-configs-all.txt`. 그 밖의 스크립트는 경로를
  주석/예시로만 적는다(`order-concurrency.js`, `d002bc-order-create.js`, `d078-*.sh` 의 사용법 주석). `.github/` 참조 0.
- `docs/learning/`(gitignored): `loadtest/reports` 인용 34곳이지만 전부 `drafts/tools/blog32·33` 의 TASKS 스냅샷·패치 사본, `12-shedlock.md`,
  `learning-roadmap-phase4-prep.md` 가 04-29 REPORT.md 를 인용. 10월 세션 파일 인용 0. `../blog` 두 글은 이미지·파일 인용 0.

## 3. 원본에만 있고 어디에도 적히지 않은 수치

(※ 아래 집계값은 scratchpad `latency-aggregates.txt` 에 전부 있다. 이 파일을 저장소에 들이면 TSV 없이도 재독 가능)

1. **10-26 측정 run 지연의 p95·max·표본수** — 문서(D-108 §5·timeline·TASKS D-110)는 사가 p50/p99, order outbox p50/p99, product outbox p99 범위만.
   원본에만: 사가 p95 c1 239.5s·c2 241.3s·s1 157.0s, max 241.2/250.5/170.3s, 제외(미확정) 5/7/0건; order.created 대기 p95 132.5/142.3/146.2s·
   max 136.9/147.3/150.7s; product 대기 p50/p95 c1 0.50/0.93s·c2 0.49/0.83s·s1 1.58/2.17s; order.cancelled 대기(c1 3건 0.29s, c2 7건 0.42s).
2. **10-24 outbox 대기 p99·max, order.cancelled 대기** — evidence 는 p50/p95 만. order.created p99 r1 76.3·r2 103.3·r3 113.7·spread 101.5s;
   stock.reservation.result p99 64.7/240.7/323.7/383.2s; order.cancelled 대기 p50 r2 4.26·r3 4.25·spread 4.78s(만료 취소 경로).
3. **10-25 w1~w5 지연 전부**(w6·s1 만 evidence 에) 와 w1~w6 의 outbox p95/max.
4. **10-26 무효 run 지연** — retry1 c2 사가 p50 142.1/p99 247.4s, c3 133.8/252.9s, retry2(디스크 가득) 확정 2,830건 p50 90.8s·PENDING 7,039 제외.
5. **S2 per-run 값**(10-25 24 run·10-24 4 run): 200 rps·p95·cgroup usage/throttled·Hikari active/pending 시계열. 문서·`s2-summary.tsv` 는 셀 중앙값/범위.
6. **c1 MySQL 표본 차분**(D-108 §3.2 가 "커밋 대기·fsync 의 비교 근거" 로 두기로 한 것) — 어느 문서에도 수치 없음: Com_commit +29,600,
   Innodb_os_log_fsyncs +31,074, Innodb_data_fsyncs +35,708, row_lock_waits +100·row_lock_time +539ms (w6 는 evidence 에 있음).
7. **배수 곡선**(`*-drain.tsv` 2초 간격 PENDING, `recovery-drain.tsv`, `payment-lag-wait.tsv` 65표본 10,173→0) — 문서는 소요 시간·최고점만.
8. **`run-window-metrics.tsv` 열 대부분**(w_iops, w_MiBps, util, hk_pend_avg/max, mysql_cpu, order_cpu, order_thr per run)·
   **`order-pool-utilization.tsv` per-run 값**(acq_per_s, hold_ms, capacity) — 문서는 범위(26.4~27.0ms, 371~379/s)만.
9. **E HPA 5초 watch**(`e-hpa-watch.tsv` 128행)·`e-hpa.json` 전 metric — evidence 는 결정·Ready 시각과 p95 2.77s 만.
10. **grafana export 전 시계열** — 문서는 유도값(⑥ active 최대, 정지 step 수, JVM 최대, 노드 CPU 최대)만. 10-24 ⑥ 유도 과정은
    `s3/d078-6-stall-check.txt` 에 있지만 10-26 ⑥ 는 D-108 §5 문장만.
11. **k6 요약 전 metric**(http_req_* 백분위, data_sent/received, checks 세부) — 문서는 수락·드롭·반복 avg/p95·HTTP 실패·429 만.
12. **April nGrinder 2초 간격 시계열**(tgz 안 `output.csv` 144행×37열) — REPORT 는 TPS 평균/최고·MTT·총 테스트만, p95/p99 미계산.
13. **10-25 `hikari/*.json` 15초 시계열** — evidence 는 최대값만.

## 4. 중복·유출 파일

**sha256 완전 중복(쌍/묶음)**
- `topic-guard*/configs.txt` 5개 동일(10-25 s3 ×2, s4, 10-26 s3 ×2) — 111KB × 4 중복 = 0.42MB. `topics.txt` 10-25 s3 2개 동일, 10-26 s3 2개 동일(17KB×2).
- `prom-after.tsv`(run N) = `prom-before.tsv`(run N+1) 13쌍(10-24 3, 10-25 6, 10-26 4) — 각 6~8KB.
- grafana `d078-1-*-saga-p16.json` = `d078-2-*-saga-p16.json` 12쌍(같은 "CPU 스로틀" 패널을 두 항목이 export) 와 jpg 2쌍
  (`d078-1-s2-L1-off-block-saga-p16.jpg`=`d078-2-...`, L4 도) — json ≈75KB, jpg ≈0.2MB.
- `2026-04-09/ngrinder-ui/*` 4개 = tgz 내부 `perftest/0_999/{3,2}/report/output.csv`·`logs/loadgen--log.zip`.
- `2026-10-25/s3/seed-products.txt` = `2026-10-26/s3/seed-products.log`(비추적).
- `2026-10-25/s3/auth-gate.txt` = `2026-10-25/s4/auth-gate.txt`. `s2-runs/pods/b2-L4-after.txt` = `b3-L1-before.txt`.
- `*-reset.txt` 17개 동일(92바이트). 
- 10-26 `invalid/contend-retry2/c2-prom-after.tsv` = `c0/c1-prom-after.tsv`… 등 체인 중복은 위 13쌍에 포함.

**근사 중복**
- `2026-10-25/session-scripts/s3-run.sh` ↔ `loadtest/scripts/d078-s3-run.sh`: 51줄 차이(SESSION_DIR/CONFIG/USERS/RPS/DUR 인자화, DRY_RUN, PREV_RUN 수렴 판정, ROOT 산출).
  `s3-export.sh` ↔ `d078-s3-export.sh`: 15줄(경로 인자화). `mysql-sampler.sh` ↔ `d078-mysql-sampler.sh`: 주석 3줄만 추가(동작 동일).
  `session-pin.sh` 10-25 ↔ 10-26: digest 6줄·주석 1줄.
- `stall-logs/run-window-metrics.tsv`(7행) ⊂ `c0/run-window-metrics.tsv`(10행; 같은 run 이 `c2` → `c2orig` 로 개명).
- `2026-10-24/s4/d065-5-topic-configs.txt`(동적 설정만) 는 `-all.txt` 의 부분 뷰(다른 명령 출력, 바이트 중복은 아님).

**유출·비정형**
- `2026-10-26/s3/seed-products.time`(59바이트, 시작/종료 시각) — 유일한 `.time`.
- `2026-10-26/s3/c0/invalid/contend-retry2/c2-reset.txt` 0바이트(53분 정지 중 끊긴 초기화의 흔적 — 증적으로 볼 수도 있음).
- k6 요약 JSON 22개의 `setup_data.tokens`(JWT 4.56MB) — §2.2.
- 비추적 `.log` 59개(§2.10). `__pycache__`·`.DS_Store`·편집기 임시파일·`.orig/.rej` 는 없음.
- 범위 밖: `.claude/worktrees/agent-{a69830…,a869a8…,ab66e4…,ac4c30…}/loadtest/reports` 4개 사본(각 13MB, 10-24 시점 체크아웃).

## 5. 질문 목록 (소유자 결정 필요)

- **Q1. 지연 TSV 15.11MB(63개)의 처분 범위** / 왜 애매: 재생성 불가한 원자료이지만 "재독" 에 필요한 백분위는 `d078-latency.py` 집계
  (133줄)로 전부 대체되고, 그 집계 파일은 저장소에 없다(§3-1·2 의 p95/max 는 지금 산문에도 없다). 반면 분포 그림·다른 백분위·
  이벤트 타입별 재집계는 TSV 가 있어야 한다. 선택지: (a) 전부 보존 (b) 측정 run 7개(10-24 r1~r3·spread, 10-26 c1·c2·s1, 6.42MB)만 보존하고
  무효/워밍업 run 의 TSV 8.68MB 삭제 (c) 집계 파일을 들이고 TSV 전부 삭제(−15.1MB). (b)(c) 모두 집계 파일을 어디에 둘지는 재구성 단계.
- **Q2. k6 요약 JSON 의 `setup_data.tokens` 4.56MB** / 왜 애매: 지우면 "원자료를 고치지 않는다" 원칙(D-108 P2)에 어긋나고, 두면
  만료·검증불가 토큰 450개×22파일이 git 에 남는다. metrics 부분은 run 당 7~10KB. 선택지: (a) 그대로 (b) 22개 전부 `setup_data` 키만 제거한
  사본으로 교체(−4.5MB, 교체 사실을 session-issues 등에 기록) (c) 측정 run 8개만 (b), 무효/워밍업 14개(3.45MB)는 삭제.
- **Q3. `warmup/` 세 곳 8.35MB(10-25 s3 4.5MB·10-26 c0 4.4MB·10-25 s2 80KB)** / 왜 애매: 계획은 "분석에 쓰지 않는다" 지만
  ① 10-25 S3 는 run 전부가 무효라 warmup 이 그 세션 S3 의 유일한 데이터이고 evidence·TASKS D-108 ①②·ADR-0045 조건 2 관찰이 w5·w6 수치를 인용,
  D-108 §8 검증이 w3~w6 `.json` 을 입력으로 썼다 ② 10-26 w4 는 편차 1(수렴 판정)의 근거이고 `run-window-metrics.tsv`(별도 파일)가 w 행을 포함.
  선택지: (a) 전부 삭제 (b) `.json`(토큰 제거 여부는 Q2)·`-counts/-times/-prom-delta/-warmup.txt` 만 남기고 TSV 5.49MB 삭제 (c) 10-25 w6·10-26 w4 만 보존.
- **Q4. `invalid/` 3 dirs 4.62MB** / 왜 애매: `contend-retry2/` 의 사고 증적(txt/tsv 70KB + stall-logs txt/tsv)은 TASKS D-112~D-114 가 지목해 A 가
  확실하지만, 세 무효 run 의 run raw(TSV·JSON·k6.log 3.3MB)는 어디에도 집계되지 않았고 timeline 에 건수만 있다. 선택지: (a) 전체 보존
  (b) run raw 3.3MB 만 삭제, 사고 증적 보존 (c) `contend-retry2/` 전체만 보존, 나머지 두 dir 삭제(−2.4MB).
- **Q5. grafana `*-kafka-lag-p1.json` 12개 2.79MB** / 왜 애매: captures.md 가 export 를 "클러스터 삭제 뒤 유일하게 남는 것" 으로 규정했지만,
  lag 값은 문서에서 drain 시간(`times.tsv`)과 jpg 로만 쓰였고 json 의 93 series 는 client_id×partition 전개다. 선택지: (a) 유지 (b) 10-25 무효 run 5개
  (0.62MB)만 삭제 (c) 전부 삭제하고 jpg 만(−2.79MB).
- **Q6. 10-25 S3 grafana jpg 18장(1.42MB)·json 42개(1.10MB)** / 왜 애매: 전부 무효 run 의 관찰 블록이고 evidence §읽는 법 ② 가 "측정값이 아니다"
  라 함. 그러나 "contend 를 접었다" 는 판단의 그림 근거이고 ⑥ 모양 비교("지난 세션과 같은 모양")에 쓰였다. 선택지: 유지 / 삭제(−2.52MB, Q5 와 0.62 겹침).
- **Q7. 비추적 `.log` 59개 2.34MB(디스크만)** / 왜 애매: git 에 없어 저장소 크기와 무관하고 지우면 영구 소실. `k6.log` 수치는 JSON 에 있음,
  `stall-logs/*.log` 는 발췌 파일 있음, `c2-payment-service-2100.log`(D-112 원 로그)와 `2026-04-29/run2/k6-stdout.log`(REPORT 가 "로컬 보존" 명시)는
  대체물 없음. 선택지: (a) 현상 유지 (b) 전부 삭제 (c) 대체물 없는 2개만 보존(또는 발췌를 만들어 추적).
- **Q8. `2026-04-09/grafana/03-k8s-pod-1/2.png` 0.69MB** / 왜 애매: REPORT 가 "kafka pod 데이터, 보조 자료로만 보관" 이라 적었고 수치 근거로 쓰이지
  않았다. 지우면 REPORT §수집 산출물 목록(이력 문서)과 어긋난다. 선택지: 삭제(+목록 각주) / 유지.
- **Q9. `2026-04-09/ngrinder-ui/` 0.10MB** / 왜 애매: tgz 와 완전 중복이지만 PHASE3:521 과 REPORT 가 "UI 다운로드(방법 A)" 산출물로 기록.
  크기 효과는 작다. 선택지: 삭제 / 유지.
- **Q10. `topic-guard*/configs.txt` 4중복 0.42MB·`topics.txt` 2중복** / 왜 애매: 가드 규약이 "원 출력과 판정" 을 디렉터리 묶음으로 남기고
  `verdict.txt` 가 그 출력을 전제한다. dedupe 하면 묶음이 깨진다. 선택지: 유지 / 1개만 남기고 나머지에 "동일(sha256 …)" 메모.
- **Q11. git 이력** / 왜 애매: 작업트리 삭제로는 `.git`(67MB, reports blob 38.4MB)이 줄지 않는다. 이력 재작성(filter-repo + force-push)은 공개
  저장소·PR 링크·보존 manifest 에 영향. 이번 정리의 목표가 "체크아웃 크기" 인지 "저장소 크기" 인지. 선택지: 작업트리만 / 이력도 / 범위 밖 명시.
- **Q12. `.claude/worktrees/*` 4개 사본 52MB** / 왜 애매: 이번 범위(`loadtest/reports`) 밖이지만 같은 디스크의 오래된 체크아웃(브랜치 debt/d106…,
  docs/campaign…, debt/d075…, debt/d107…). 선택지: 그대로 / worktree 제거.
- **Q13. 소형 "버리는 회차" 파일** — `2026-10-24/s2-runs/incomplete/`(1.2KB), `2026-10-25/s2-runs/warmup/`(80KB, 사전 규칙상 버리는 r1),
  `2026-10-24/grafana/d078-1-s2-blockA-partial-*.json`(18KB, S2 미측정) / 왜 애매: 크기는 무의미하고 규칙 일관성(무효 run 처분과 같은 기준을 적용할지)만 문제.
- **Q14. 비정형 소파일** — `seed-products.time`(59B), 0바이트 `c2-reset.txt`, 비추적 `seed-products.log`(10-25 와 동일) / 왜 애매: 지워도 손실 없지만
  `c2-reset.txt` 0바이트는 53분 정지의 흔적이라 증적으로 볼 수도 있다.
- **Q15. 집계 파일의 정본 위치** — Q1·Q3 에서 TSV 를 지우면 `latency-aggregates.txt`(또는 run 별 `*-latency.txt`)가 "원본" 역할을 맡는다. 어느
  디렉터리에 두고 어떤 문서가 가리킬지는 재구성 단계 결정이라 여기서는 묻기만 한다.
