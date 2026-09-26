# D-033 audit

## 2026-09-25 — 계획 검토

- 상태: 별도 Codex 리뷰 미호출
- 코드 확인: `e2e.needs: images`, 순차 실행 2단계, 독립 `E2E_RUN_ID`, 시나리오 증적 게이트, 대조군 실패 종료를 확인했다.
- 결정: PR에서 음성 대조군 전량을 유지하고, 시나리오와 별도 매트릭스 러너에서 병렬 실행한다. ADR-0028의 후속 판정이며 새 ADR은 필요하지 않다.
- 미해결: CI 벽시계는 실제 PR 실행 전까지 미측정이다.

## 2026-09-25 — 구현 검토

- 상태: 별도 Codex 리뷰 미호출
- 코드 확인: 두 모드가 동일 이미지 artifact를 읽고 서로 다른 run ID와 증적 이름을 쓴다. 시나리오 증적 게이트는 `scenarios` 모드에 남고, 대조군 실패는 해당 매트릭스 잡 실패가 된다.
- 검증: `ci-e2e-parallel-lint.sh --self-test` 7/7, `ci-release-gate-lint.sh --self-test` 6/6, `saga-e2e-smoke.sh --self-test` 9/9, `ci-test-matrix-lint.sh`, `image-contract-lint.sh`, `saga-contract-matrix-lint.sh --structure` 통과.
- 전체 테스트: `./gradlew test --no-daemon --console=plain` 재실행 `BUILD SUCCESSFUL`(27분 54초, 49 tasks). 첫 실행은 payment-service의 Kafka 재접속 로그가 대량 발생하는 동안 중단(exit 130)했고, 출력을 파일로 저장한 재실행은 끝까지 통과했다.
- 미충족: 실제 PR CI 실측은 PR 실행 전이라 별도 기록이 필요하다.

## 2026-09-25 — /ship 실행·PR CI 실측

- 커밋: `40b83f3`(계획/진행), `6a0e4f7`(CI 구현). 브랜치 `feat/D-033-e2e-parallel` 푸시, PR [#140](https://github.com/Kimgyuilli/PeekCart/pull/140) 생성.
- PR CI [run 36075940872](https://github.com/Kimgyuilli/PeekCart/actions/runs/36075940872): 전체 성공. e2e 시나리오와 음성 대조군 모두 00:08:58 UTC 시작, 각각 00:14:11·00:19:52 UTC 성공(5분 13초·10분 54초).
- 전체 run: 00:05:56~00:20:06 UTC, 14분 10초. D-032 PR 실측 20분 49초보다 6분 39초 짧다. 단일 run 비교이며 캐시·러너 변동을 분리한 인과 추정은 아니다.
- 후속 병목: product-service 테스트 00:05:59~00:19:43 UTC(13분 44초), 대조군과 9초 간격으로 종료. gate 는 00:19:46~00:20:06 UTC.
- 완료 기록: `docs/TASKS.md`, `docs/progress/PHASE5.md`를 실측에 맞춰 갱신했다. ADR-0028 상태는 기존 Accepted 유지. Layer 1 테스트 전략의 "음성 대조군을 매 CI에서 실행" 계약은 유지돼 변경이 없다.
- 별도 Codex 리뷰 미호출.
