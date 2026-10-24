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
