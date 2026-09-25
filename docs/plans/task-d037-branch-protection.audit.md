# D-037 audit

## 2026-09-25 — 계획 및 구현 확인

- 상태: 별도 Codex 리뷰 미호출.
- 전제 확인: GitHub 보호 설정은 `build` 필수, `strict=true`, 앱 ID `15368` 이었다. CI 에 `build` job 은 없고 `gate` 는 `test`·`guards` 결과를 전파한다. PR #140 head 에서 같은 앱의 `gate` 성공 체크를 확인했다.
- 적용: GitHub API 의 필수 체크를 `gate`(앱 ID `15368`)로 변경했다. 다른 보호 설정은 보존했다.
- 검증: 변경 후 GitHub API 재조회에서 `gate`·앱 ID·strict 및 인접 보호 설정을 확인했다. `ci-release-gate-lint.sh` 실제 워크플로 검사와 자체 검사 6/6 통과.
- 전체 테스트: `./gradlew test` 통과 (4분 36초, 49 tasks 중 2 executed · 47 up-to-date).
- 미충족: 실제 실패 PR 의 병합 UI 는 재현하지 않았다. 기존 관리자 우회 정책과 `gate` 밖의 lint·이미지·e2e 검증 범위는 D-043 에 남긴다.
