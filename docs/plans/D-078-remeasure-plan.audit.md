# D-078 remeasure plan audit

## 2026-10-25 04:08 — 계획 리뷰
- 상태: 해당 없음(등급 M)
- 게이트: blocked(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.) — 등급과 무관하게 호출하지 않았다.
- 등급 M 근거: loadtest 하네스·측정 overlay·lint 의 overlay 목록(판정 기준 1), 계약 표면 무변화, 되돌림이 PR 안에서 끝난다.
- 해석 결정: 사용자 결정의 "반복 p95 시간(워밍업 run 관측값, 여유 포함)" 을 워밍업이 끝난 run(2026-10-24 r3, 1.639s)의 값으로
  읽어 USERS 450 으로 정했다. 버리는 run(r1, 5.637s)으로 읽으면 1,450 이다. 이유는 계획 §2.2(r1 의 반복 시간은 order CPU 한도와
  콜드 JVM 이 만든 것, setup 순차 로그인 약 6분, setup 데이터의 VU 별 복사). root 보고에 같은 내용을 올린다.

## 2026-10-25 04:18 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 구현 중 발견(계획 초안 전 코드 확인에서 드러나 계획에 반영):
  - spread 의 상품 선택이 `__ITER`(VU 별 번호)라 상품 1,000개 중 23개만 쓰인다. 전역 순번으로 바꿨다(P1).
  - k6 `setupTimeout` 기본 60초인데 지난 세션 200명 setup 이 약 50~55초였다. USERS 450 이면 setup 에서 죽는다(P1).
  - 상품 시드가 admin 라우트 40건/1초 창에 가깝게 돈다(지연 0 대역에서 초당 36건). 간격과 전파 대기를 넣었다(P2).
  - 오타 env 는 앱이 조용히 기본값 1 로 뜬다(Spring jar 검사). 셀이 Pod env 를 기대값과 대조한다(P3).
- 검증 P1: `node --test loadtest/scripts/test_d002bc_contention.mjs` 8 tests 8 pass. 원 요약 JSON 4개로 r1(5,763)·r2(1,871) 무효,
  r3(89)·spread(metric 없음) 유효, 한도 102, 경계 102 유효·103 무효, DUR 3m·1m30s 한도 306·153, `usersFor` 가 r3 450·r2 750·
  r1 1,450·spread 100. origin/main 스크립트에 같은 검사를 돌리면 8개 중 7개 실패(spread 상품 23/1,000). 변이 7종(한도 10%,
  `count<`, `__ITER`, setupTimeout 삭제, 0건 무효, 여유 1.0, PRODUCT_IDS 검사 삭제)이 각각 1개 검사로 실패. 로컬에 k6 가 없어
  `k6 inspect` 는 하지 않았다. `__ITER`(VU 별)·`exec.scenario.iterationInTest`(전역)·`setupTimeout` 기본 60s 는 k6 문서,
  exit 99(threshold)·100(setup timeout)은 k6 소스 `errext/exitcodes/codes.go` 로 확인했다.
- 검증 P2: `users.csv` 를 449행으로 바꾸면 count 검사가 실패하고 450 으로 되돌리면 통과. 시드 스크립트를 지연 0 인 curl·kubectl
  대역(1초 창 40건 초과 시 429 응답)으로 `--count 1000`: exit 0, 93초, PRODUCT_IDS 1,000개(3,921바이트), 캐시 조회 IN 절 id
  1,000개, 초당 최대 생성 12건, 429 0건, 전파 대기 100초. `sleep 0.05` 를 지운 사본은 30초, 초당 최대 36건(한도 40 아래),
  429 0건. 실제 환경은 네트워크 지연이 더해지므로 간격은 여유 장치다. 초기화·정합성 SQL 은 상품 수에 의존하지 않아 바꾸지 않았다.
- 검증 P3: 상한 1(`gke-d002a-mysql-2000m`)과 4(`gke-d078-read-bulkhead-4`) 렌더 diff 는 env 값 `"1"`/`"4"` 한 줄. origin/main
  트리 대비 8개 overlay 렌더: `gke-d002a`·`-mysql-500m`·`-mysql-2000m` 만 env 2줄 추가, `gke`·`minikube`·`gke-d002bc`·
  `-mysql-2000m`·`gke-d078-contention` 무변화. `d026-run-cell.sh` kubectl 대역: Pod 1·기대 4 와 Pod 미설정·기대 4 는 측정 전
  exit 1(`!! bulkhead 상한 불일치`, meta 에 1·unset), Pod 4·기대 4 와 Pod 1·기대 없음은 다음 단계(LB IP 조회)로 진행.
  Spring Boot 3.5.12·spring-core 6.2.17 jar 로 `StandardEnvironment` + `ConfigurationPropertySources.attach` 에 env 를 넣어 풀면
  `PEEKCART_CACHE_DBMAXCONCURRENCY=4` 와 `PEEKCART_CACHE_DB_MAX_CONCURRENCY=4` 는 4, 오타는 1, 없으면 1·풀 10.
  새 overlay 에 namespace 없는 ConfigMap 을 넣으면 namespace lint exit 1(`namespace missing (k8s/overlays/gke-d078-read-bulkhead-4)`),
  ServiceMonitor 를 지우면 SM lint exit 1(`ServiceMonitor 집합 불일치 (gke-d078-read-bulkhead-4)`). 되돌린 뒤 둘 다 exit 0.
- 검증 공통: ci.yml lint job 의 "Run CI policy lints" 명령 48개를 로컬에서 실행해 48개 통과(kubectl 1.35.3·kustomize 5.7.1·
  promtool 로컬). docs-preservation-ci(base `6905952`) PASS, docs-preservation-selftest·plans-archive self-test·harness-context
  `--check --base 6905952`·harness-context-selftest·D-099 check-mutants 통과. 기존 loadtest 검사 test_d026_collect 9·
  test_d078_capture 10·test_d078_latency 5 통과. `bash -n` 3개, `node --check`, writing-lint `--commits` 6건 통과.
  DLQ contract lab(Docker)과 `./gradlew test` 는 돌리지 않았다. Gradle 소스 세트에 들어가는 변경이 없다(스크립트·overlay·문서).
  모듈 전체와 E2E 는 PR CI 로 확인한다.
- 새 검사의 CI 배선: `test_d002bc_contention.mjs` 는 `scripts/*-lint.sh` 가 아니라 `ci-lint-wiring-lint.py` 의 배선 요구 대상이
  아니다. 기존 `loadtest/scripts/test_*.py` 와 같이 로컬 검사로 두었다(계획 §2.3).

## 2026-10-25 04:22 — /ship
- PR: https://github.com/Kimgyuilli/PeekCart/pull/243 (base main. 브랜치 기준 `6905952`, PR 생성 때 main `2ffa8d6`, 겹치는 파일 없음)
- precheck: ship preflight ok, consistency ok(warnings 0. 첫 실행에서 이 브랜치에 없는 ADR-0045 참조 1건이 걸려 계획서 문구를
  D-075 로 바꿨다), review health ok, writing lint(커밋 7건·본문·제목) 통과, docs-preservation 은 브랜치 트리(base `6905952`)와
  main `2ffa8d6` 을 병합한 트리(base `2ffa8d6`, `--no-commit` 병합 뒤 abort) 모두 통과. 병합 트리에서 harness-context
  `--check`·자기검증, plans-index `--check` 도 통과
- 커밋: `cbab2bf` `f044e71` `b62f988` `0ed2134` `82edb40` `fa1e61b` `6fcfb5e`, audit 갱신 1건
- 갱신: 없음. TASKS·PHASE5·done 이동은 캠페인 규칙대로 root 가 기록 PR 에서 한다. 이 계획서는 세션 실행 전까지 아카이브하지 않는다
