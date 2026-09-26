---
grade: M
---

# D-037 — main 필수 체크를 실제 CI gate 에 연결

## 1. 명제

`main` 이 존재하지 않는 `build` 체크를 계속 요구하거나, `gate` 실패를 보고도 비관리자 병합을 허용하면 미완이다.

## 2. 배경과 작업 항목

| 전제 | 확인 결과 (2026-09-25) |
|---|---|
| GitHub `main` 보호 설정 | `strict=true`, 필수 `build` (GitHub Actions app `15368`), `enforce_admins=false` |
| `.github/workflows/ci.yml` | `build` job 없음. `gate` 는 `test` 와 `guards` 에 의존하고 실패 전파 스텝이 있음 |
| PR #140 head `453e849` 체크 | `gate` 성공, 발행 앱 `15368` |

등급 M: 제품 모듈 변경은 없지만 저장소의 공유 병합 계약이 바뀐다. 변경은 GitHub 보호 설정의 필수 체크 한 곳에 한정되고 같은 API 로 되돌릴 수 있다. 새 외부 의존성이나 아키텍처 경계가 없어 ADR 은 필요하지 않다.

- [x] P1. GitHub 설정, 실제 `gate` 체크 이름과 발행 앱, CI 실패 전파를 확인한다.
- [x] P2. `main` 의 필수 체크를 GitHub Actions 앱의 `gate` 로 바꾸고 `strict=true` 및 다른 보호 설정을 유지한다.
- [x] P3. GitHub 설정을 다시 읽어 체크 이름과 앱을 확인하고, CI 게이트 lint 의 실패 주입 자체 검사를 실행한다.

## 3. 검증 방법

| ID | 검증 | 잘못된 변경을 드러내는 신호 |
|---|---|---|
| V1 | 변경 전후 보호 설정을 GitHub API 로 조회 | `build` 잔존, `gate` 누락, 앱 ID/strict/다른 보호 설정 변화 |
| V2 | PR #140 head 의 실제 check-run 과 변경 후 필수 체크 대조 | 이름이나 발행 앱이 달라 체크가 충족될 수 없음 |
| V3 | `scripts/ci-release-gate-lint.sh --self-test` 및 실제 workflow lint | `gate` 의 테스트 의존 또는 실패 전파가 끊겨도 검사가 통과함 |

**실행 결과 (2026-09-25)**: GitHub API `PATCH .../required_status_checks` 로
`checks=[{context: gate, app_id: 15368}]` 를 적용했다. API 재조회에서 `strict=true`,
`contexts=[gate]`, 앱 ID `15368` 을 확인했다. 전체 보호 설정의
`enforce_admins=false`, `required_conversation_resolution=false`,
`allow_force_pushes=false` 도 변경 전과 같았다. PR #140 head 의 실제 `gate` check-run 은
같은 앱 ID 에서 `success` 였다. CI gate lint 실제 워크플로와 자체 검사 6/6 통과.

## 미해결

- 관리자 우회(`enforce_admins=false`)는 기존 정책으로 유지한다. 현재 `gate` 의 선행은 `test` 와 `guards` 이므로 lint·이미지·e2e 실패는 이 필수 체크 하나로 막히지 않는다. 관리자까지 강제할지와 이 검사들을 최종 게이트에 포함할지는 D-043 에서 판단한다.
- 실제 실패 PR 을 새로 만들어 병합 버튼 상태를 실험하지 않는다. 원격 설정의 정확한 읽기 결과와 실제 GitHub Actions 체크, 실패 전파 lint 를 각각 확인한다.
