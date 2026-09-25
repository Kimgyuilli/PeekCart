---
grade: M
---
# D-033 — e2e 시나리오와 음성 대조군 병렬 실행

## 명제

PR에서 시나리오 4종과 음성 대조군 중 하나라도 빠지거나, 두 검사가 같은 러너에서 직렬 실행되거나, 어느 한쪽 실패가 CI 실패로 드러나지 않으면 미완이다.

현재 `ci.yml`의 단일 `e2e` 잡은 시나리오와 음성 대조군을 순서대로 실행한다. D-032 실측에서 e2e 16.1분이 단독 병목으로 확인됐다. ADR-0028은 음성 대조군을 push 전용으로 돌리는 안의 PR 검증 손실을 후속 판정 대상으로 남겼다. 병렬 실행은 그 검증을 유지한다.
대신 별도 러너에서 이미지 로드·runner 빌드·인프라 기동을 한 번 더 수행하므로 전체 러너 사용 시간은 증가한다. 벽시계 개선 폭은 실제 CI에서 판정한다.

| 전제 | 코드 확인 |
|---|---|
| 이미지 빌드 뒤 e2e 실행 | `ci.yml`의 `e2e.needs: images` |
| 대조군은 별도 cold start 필요 | `ci.yml`의 `-nc` run ID와 `saga-e2e-smoke.sh`의 volume 검사 |
| 시나리오 증적 게이트 | `ci.yml`의 `saga-contract-matrix-lint.sh --e2e-evidence` |
| 대조군 실패 신호 | `saga-e2e-smoke.sh --negative-control`이 실패 시 exit 1 |

등급 M: CI 워크플로의 공유 검증 계약 한 곳이 바뀐다. 제품 모듈, 외부 의존성, 아키텍처 경계는 바뀌지 않고 되돌림은 해당 워크플로와 검사기에 국한된다. ADR-0028의 기존 결정을 유지하며 새 ADR은 필요하지 않다.

## 작업 항목

- [x] P1. `e2e`를 `scenarios`와 `negative-control` 매트릭스로 분리하고 각 실행에 고유한 run ID와 증적 artifact 이름을 준다. 시나리오 증적 게이트는 `scenarios` 실행에 남긴다.
- [x] P2. 워크플로 검사기를 추가해 두 모드, 이미지 의존, 각 실행 명령, 증적 게이트와 고유 증적 이름이 사라지면 실패하게 한다. CI lint 잡에서 검사기와 자체 변이 검사를 실행한다.
- [x] P3. 로컬 검증과 전체 Gradle 테스트를 실행하고 결과를 기록한다.

## 검증 방법

| 항목 | 실패 주입 | 기대 |
|---|---|---|
| V1 | 매트릭스에서 `negative-control` 제거 | 검사기 실패 |
| V2 | `--negative-control` 실행 명령 제거 | 검사기 실패 |
| V3 | 모드별 조건을 제거해 한 러너에서 둘 다 실행 | 검사기 실패 |
| V4 | 이미지 의존 또는 시나리오 증적 게이트 제거 | 검사기 실패 |
| V5 | 증적 artifact 이름에서 모드 구분 제거 | 검사기 실패 |
| V6 | 실제 PR CI 실행 | 두 e2e 매트릭스 잡 성공, 전체 시간 및 병렬 시작 시각 확인 |

로컬 결과: V1~V5는 `ci-e2e-parallel-lint.sh --self-test`의 변이 7종으로 통과했다.
`ci-release-gate-lint.sh --self-test` 6종, `saga-e2e-smoke.sh --self-test` 9종,
`ci-test-matrix-lint.sh`, `image-contract-lint.sh`, `saga-contract-matrix-lint.sh --structure`도 통과했다.
`./gradlew test --no-daemon --console=plain`은 2026-09-25 재실행에서 `BUILD SUCCESSFUL`(27분 54초, 49 tasks)이었다.
V6은 PR 실행 전이므로 미실측이다.

## 미해결

- `publish`가 e2e를 기다리지 않는 기존 계약은 이번 변경에서 유지한다. 릴리스 게이트 정책은 별도 결정이 필요하다.
- 로컬 Docker API 접근이 거부되어 실제 스택 e2e는 CI에서 검증한다. V6 전까지 벽시계 단축을 실측 완료로 표시하지 않는다.

별도 Codex 리뷰 미호출. 위 전제는 현재 워크플로와 실행 스크립트에서 직접 확인했다.
