# D-106 remeasure prereq audit

## 2026-10-24 04:53 — 계획 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)

## 2026-10-24 05:26 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 검증: `./gradlew test --continue` BUILD SUCCESSFUL(9m8s), JUnit 1458 tests · 0 failures · 0 errors · 1 skipped(order-service). `gateway:test` 는 UP-TO-DATE 재사용. CI 가 호출하는 lint 와 `--self-test` 19종 전부 exit 0. `docs-preservation-ci.py --base 1cc1efc`·`harness-context.sh --check`·`ci-lint-wiring-lint.py`·`ci-lint-health-selftest.py`·`plans-archive.sh --self-test` 통과.
- `replay-entrypoint-lint.sh` 는 본 체크아웃에서 REPLAY-ENTRY-003 2건으로 exit 1 이었다. 두 건 모두 gitignore 된 로컬 블로그 초안(`docs/learning/drafts/tools/blog33/…`)이다. HEAD 에 이 diff 만 얹은 임시 worktree 에서는 exit 0 이었다.
- 실패 주입 순서(계획 §검증 방법):
  - P1: 포트 수정 전 새 drift 검사가 `[REPLAY-ENTRY-007] preflight 는 localhost:9092 로 lag 를 묻는데 k8s Kafka 리스너는 29092 다` 로 실패했고, 수정 후 통과했다. self-test 11종(9092 회귀·k8s 리스너 변이 포함)이 통과했다.
  - P2: 파서 수정 전 `- 0 -` 양성 테스트가 `order: consumer group order-target-a 의 lag 조회 실패` 로 exit 1 이었다. 수정 후 collector 테스트 10종이 통과했고, `- 15 -`·`- 1 -`·`- - -`·`- 0 0`·NaN·음수는 계속 exit 1 이다.
  - P3: 로컬 Docker 의 실제 lab(MySQL 8.0.46·Kafka 3.8.1, 238s)이 12 cases 를 완료했다. 같은 할당 컨슈머로 `order-no-commit collector.no-commit 0 - 0 -` 를 관측해 preflight exit 0, 레코드 1건 뒤 `0 - 1 -` 를 관측해 exit 1(`lag 조회 실패`)이었다. 증거 검증 12 cases 와 음성 변이 9종을 확인했다. 정리는 자기 container 2·network 1·volume 2 이고, 남은 라벨 컨테이너는 0 이다. lab 실행 뒤 `replay-drain-docker.py` 에 빈 줄 하나만 더했다(동작 무변경, hermetic 7 tests 재통과). 최종 소스의 실제 lab 은 PR 의 `replay-collector` workflow 가 다시 돈다.
  - P5: 진입점 수정 전 확장 lint 가 `gke-d002a` 에서 `expected ['product-service'] / actual []` 로 실패했다. 측정 overlay 6개의 SM 은 모두 0개였다. 수정 후 d002a 계열 1개·d002bc 계열 5개가 되고 lint 8개 overlay 가 통과했다. 새 음성 2종은 의도한 위반으로 잡혔고, 운영 overlay 에서 서비스와 SM 을 함께 지운 렌더는 measurement 모드로 판정하면 통과한다. 즉 운영 6개 계약은 모드 분리로 유지된다.
  - P6: payment Deployment 는 `envFrom.secretRef: payment-service-secret`, k8s 프로파일의 무기본값 변수는 `TOSS_SECRET_KEY` 하나다. `kubectl patch --local` 로 merge patch 가 DB 자격증명을 보존한 채 TOSS 키를 더하는 것을 확인했다. `loadadmin` 은 Flyway 에 없고 `loadtest/sql/d002bc-user-seed.sql` 에만 있다.
- 계획과 다르게 구현한 점: hermetic kubectl fixture(`scripts/tests/fixtures/replay-drain-kubectl.py`)도 9092 argv 만 허용해 preflight self-test 가 21건 실패했다. 계획 P1 에 먼저 추가한 뒤 함께 고쳤다. fixture 를 스크립트 값에 맞춘 구조는 drift lint(REPLAY-ENTRY-007)와 각 fixture 의 정확한 argv 검사로 닫았으므로 prose 로 남기지 않는다.
- 미충족: P7 의 PR 일반 CI·`replay-collector` 결과는 `/ship` 이후 확인한다.

## 2026-10-24 — P7 범위 정정
- `/ship` preflight 가 P7 미완으로 막았다. P7 초안은 PR CI·`replay-collector` 결과 확인을 포함해 PR 생성 전에는 닫을 수 없었고, 같은 확인은 §완료 조건에 이미 있다. 머지 뒤에도 CI 확인 체크박스가 열린 채 남는 패턴(D-078-retry-load P4 등, 2026-10-24 sync 아카이브 후보)을 반복하지 않도록 P7 을 로컬 검증으로 좁히고 PR CI 확인은 §완료 조건에 맡겼다. 로컬 검증은 위 블록대로 완료했다.
