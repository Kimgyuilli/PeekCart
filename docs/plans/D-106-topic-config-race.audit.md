# D-106 topic config race audit

## 2026-10-24 20:46 — 계획 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)

## 2026-10-24 21:16 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 검증: `./gradlew test --continue` BUILD SUCCESSFUL(13m31s), JUnit 1463 tests · 0 failures · 0 errors · 1 skipped(order-service, 기존). 변경 입력이 없는 모듈(gateway 등)은 UP-TO-DATE 재사용. 실행 뒤 `:order-service:compileTestJava` 가 UP-TO-DATE 라 최종 소스로 돈 결과다.
- lint: `docs-preservation-ci.py --base e57e294`, `ci-lint-wiring-lint.py`, `ci-lint-health-selftest.py`, `integration-test-container-lint.sh`(+`--self-test`), `kafka-subscription-contract-lint.sh`, `scheduler-lock-contract-lint.sh`, `dead-letter-schema-parity-lint.sh`, `saga-contract-matrix-lint.sh`, `ci-test-matrix-lint.sh`, `dockerfile-module-sync-lint.sh`, `plans-index.sh --check`, `harness-context.sh --check` exit 0. `replay-entrypoint-lint.sh` 는 gitignore 된 로컬 블로그 초안 2건(`docs/learning/drafts/tools/blog33/…`)으로 exit 1 이다. 추적 파일 0건이라 CI checkout 에는 없다(직전 D-106 audit 과 같은 2건).
- 실패 주입(계획 §검증 방법):
  - P1: 첫 실행에서 자동 생성 헬퍼의 `partitionsFor` 가 빈 목록을 돌려 실패했다. KRaft 브로커가 생성 완료 전 `UNKNOWN_TOPIC_OR_PARTITION` 으로 답하고 클라이언트가 재시도하지 않기 때문이다(소스 확인, 계획 §2.2 추가). 토픽이 보일 때까지 반복하도록 고친 뒤 한 번의 기동 중 적용에서 A(7키 동적·파티션 3), B(4키 동적·파티션 3), C(동적 0·파티션 1)가 나왔고 C 의 생성 결과는 `TopicExistsException` 이었다.
  - P4: 재적용 본문을 지운 변이에서 ReadyEvent 뒤 `race-raced-… 의 retention.bytes 가 선언값이어야 한다 expected "8388608" but was "-1"` 로 실패했고, 복원 후 통과했다.
  - P3: `OrderKafkaConfig` 의 `@Bean` 을 지운 변이에서 `registersTopicReconciler` 가 실패했고, 복원 후 통과했다.
  - P5: try/catch 를 지운 변이에서 `failureDoesNotPropagate` 가 실패했고, 복원 후 통과했다.
- 테스트 주석: `D-102/check.py --scan` 20건으로 기준선과 같다. 새 주석의 `V-P4-1` 표식은 메서드 참조로 바꿔 후보에서 뺐다.
- 계획 대비: P1 테스트를 새 클래스 대신 기존 `KafkaTopicConfigMechanismIntegrationTest` 에 넣었다(헬퍼 재사용, 계획서 먼저 정정). P6 의 TASKS·progress 갱신은 `/ship` 에서 한다.

## 2026-10-24 21:40 — /ship
- PR: https://github.com/Kimgyuilli/PeekCart/pull/237 (base `main`). 분류별 커밋 4개(`bced35c` plans, `9dc5882` src, `0e4395a` test, `fa18a35` adr)와 done 갱신 커밋 1개.
- precheck: `hpx_ship_preflight` 는 처음에 blocked 였다. P6 가 PR URL 뒤에만 닫히는 TASKS·progress 갱신을 포함했기 때문이고, 계획서에서 그 기록을 §완료 조건으로 옮겨 정정한 뒤 ok. consistency precheck ok(warnings 0), `hpx_review_health` ok, `writing-lint.sh` 커밋 4건·본문·제목 통과.
- ADR-0020 Update Log 에서 브로커 자동 생성 비활성화 문장을 뺐다. 대안 추가는 Update Log 로 우회하지 않는다(`docs/adr/README.md` §원칙). 그 내용은 계획서 §미해결과 PR 본문 §미충족에 둔다. GKE 원인 문장은 코드 경로와 로컬 재현으로 한정해 다시 적었다(예외가 DEBUG 라 GKE 기록이 없다).
- 갱신: TASKS D-106 ⑩ 상태와 PR 링크, D-065 ⑤ 재확인 조건(파티션 수 포함), PHASE5 이력. D-106 은 ①⑦·⑥·②⑤ 가 남아 완료 이동하지 않았다.

## 2026-10-24 21:55 — 머지 전 후속 정리
- 사용자 요청으로 후속 후보 두 가지가 실제 문제인지 근거로 다시 검토했다. 확인한 것: `apache/kafka:3.8.1` 의 `num.partitions=1`(설정 파일 3곳), E2E 토픽 사전 생성(`scripts/saga-e2e-smoke.sh`, #92 실측 203초), kafka-clients 3.9.2 `Metadata` 가 토픽 단위 `InvalidMetadataException` 에 metadata 를 다시 요청함(backoff 100ms~1s), `metadata.max.age.ms` 기본 5분(서비스 미설정).
- GKE S3 lag 원자료(`loadtest/reports/2026-10-24/grafana/*kafka-lag*.json`): 트래픽이 있던 `stock.reservation.result`·`order.created` 는 측정 동안 세 파티션이 모두 할당돼 소비됐다. payment 토픽은 측정 동안 값이 전부 NaN(트래픽 없음)이라 파티션별 지표로 파티션 수를 추론할 수 없어 근거에서 뺐다.
- 계획서: §2.2 에 두 행(브로커 기본 파티션, E2E 사전 생성) 추가, §2.3 부수 효과 정정(발견 지연 누락과 "E2E 도 빈 브로커" 서술), §미해결 처분 갱신.
- 처분: PR 기록 보완(반영), 측정 전 토픽 상태 가드(D-106 ⑩ 남은 항목), 생성 경로 처방(D-107 등록, 재검토 조건 명시), E2E 자동 대조(따로 하지 않음).
- 주석: `KafkaTopicReconciler` Javadoc 에 파티션 증설의 대가를 더했다. `docker-compose.e2e.yml` 의 자동 생성 파티션 주석은 "기본값 3" 과 부하 원인 서술을 이미지 실제 값과 #92 의 사전 생성 근거로 바꿨다. `docker compose config -q`, `e2e-network-contract-lint.sh`, `ci-e2e-parallel-lint.sh`, `:common:compileJava` 통과.
