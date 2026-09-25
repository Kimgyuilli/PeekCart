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
