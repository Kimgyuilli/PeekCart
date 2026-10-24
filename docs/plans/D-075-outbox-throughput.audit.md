# D-075 outbox throughput defaults audit

## 2026-10-25 03:48 — 계획 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 게이트: `hpx_codex_allowed` 1행 `blocked`, source `file`, origin `.cache/codex-off`(이 워크트리). 등급 `hpx_plan_grade` = L.

## 2026-10-25 04:00 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 대체 점검: diff 전체를 직접 다시 읽었다. 배경 스케줄링을 켠 테스트 4곳(payment `ShedLockIntegrationTest` 는 풀 1),
  E2E 대조군 ① 의 ShedLock 주입(해제 SQL 이 `locked_by` 를 보므로 실행 도중 주입도 덮이지 않음), signed-gateway 의
  before/after outbox 스냅샷(seed 가 대상 서비스 outbox 행을 만들지 않음), approval-recovery lab(PUBLISHED 를 기다리기만 함),
  배포 매니페스트의 설정 위치 덮어쓰기(없음)가 짧아진 주기로 깨지지 않는지 코드로 확인했다.
- 검증 V1·V2 (`:peekcart-common-messaging:test`): 모듈 전체 33 tests, 0 failures. 새 `OutboxPollingPropertiesTest` 11건
  (불변식 9, placeholder 2), `OutboxPollingSchedulerWiringTest` 2건. 키 누락 문맥의 실패 원인은
  `Could not resolve placeholder 'app.outbox.polling.delay'` 였다(속성 검증보다 placeholder 해석이 먼저 막는다).
- 검증 V3 (실제 발화): 300ms·200ms·batch 37·`probeOutboxPollingJob` 으로 등록 작업 1개, `FixedDelayTask` 300ms,
  ShedLock 이 받은 lock 설정 (`probeOutboxPollingJob`, PT5M, PT0.2S), `findPendingEvents(37)` 을 관측했다.
- 검증 V4: 4서비스 `*OutboxPollingPolicyTest` 각 2건 통과(3서비스 500ms·400ms·500, notification 5s·4s·100, 프로파일 yml 비선언).
  실행하면서 4서비스 테스트 소스 전체가 컴파일됐다(probe·주석 수정 포함).
- 변이 7종(각각 적용 후 해당 테스트 실행, `git checkout` 으로 복구, 복구 뒤 작업 트리 깨끗함):
  - M1 `lockAtLeastFor < delay` 검사 제거: 실패 2건(D-075 조건 500ms·4s, 경계 500ms·500ms).
  - M2 `${app.outbox.polling.delay:5s}` 인라인 기본값 복원: placeholder 테스트 실패.
  - M3 `lockAtLeastFor = "PT4S"` 하드코딩: placeholder 테스트와 배선 테스트 실패(`expected 0.2S but was 4S`).
  - M4 `fixedDelayString = "5s"` 하드코딩: 배선 테스트 실패(`expected 0.3S but was 5S`).
  - M5 `findPendingEvents(100)` 고정: 배선 테스트 실패(`Wanted findPendingEvents(37)`).
  - M6 product yml delay 5s: product 정책 테스트 실패(`expected 0.5S but was 5S`).
  - M7 product `application-k8s.yml` 에 `app.outbox.polling.delay` 추가: 프로파일 비선언 테스트 실패.
- 검증 V5: 렌더 차이가 변경 전 order env 2개 + product replicas 에서 변경 뒤 product replicas 1곳만 남았고 `APP_OUTBOX_POLLING` 0건.
  `kustomize-namespace-lint.sh`(overlay 8개)·`servicemonitor-selector-lint.sh` 통과.
- 검증 V6: 현행 파일에서 outbox poller 를 5초로 전제하는 문장 0건. 남은 적중은 이력(`docs/progress/`·`docs/plans/done/`·
  `loadtest/reports/`), root 소유 `docs/TASKS.md`, 상품 재고 캐시 TTL(별개 5s), DLQ reconciler 자신의 `PT4S`(바꾸지 않음)다.
- lint: `scheduler-lock-contract-lint` OK, `ci-lint-health-selftest` 11종 거부, `observability-ssot-lint`·`replay-entrypoint-lint`·
  `hpa-replicas-ownership-lint`·`ci-lint-wiring-lint`·`ci-test-matrix-lint`·`dockerfile-module-sync-lint`·
  `integration-test-container-lint` 통과, `test_kafka_topic_state_guard.py` 23 tests OK, D-102 `check.py --scan` 20건(baseline, 변경 파일 0),
  writing-lint 커밋 9건 통과, consistency precheck ok, docs-preservation(base `6905952`) PASS, harness-context check ok.
- 로컬에서 돌리지 않은 것: Testcontainers 통합 테스트와 E2E(사용자 지시 2026-10-21). PR CI 로 확인한다.
