# D-043 audit

## 2026-09-25 — 로컬 구현과 검증

- 상태: 별도 Codex 리뷰 미호출. P1~P6 구현·설정 적용. PR/main 실행 실측은 아직 없다.
- 격리: `origin/main` 에서 `/private/tmp/peakcart-d043-ci-release` 작업 트리와 `feat/D-043-ci-release-gate` 브랜치를 만들어, 기본 작업 트리의 기존 변경과 분리했다.
- CI 계약: `gate` 가 lint/test/guards/images/e2e 의 실패·skip 을 전파하고 `publish` 는 성공한 gate 뒤에만 실행하도록 연결했다. e2e 두 모드는 유지했다.
- 이미지 계약: artifact 의 tar checksum 과 이미지 ID를 e2e/publish 로드 시 검사한다. 게시 전 원격 SHA manifest 의 config digest 를 이미지 ID와 대조한다. `latest` 를 SHA manifest digest 에서 승격하고 승격 메타데이터와 원격 태그를 다시 조회해 digest 를 비교한다.
- 로컬 확인: `ci-release-gate-lint --self-test` 24/24, `ci-e2e-parallel-lint --self-test` 7/7, `ci-image-identity --self-test` 정상 2·불일치 3, `image-contract-lint` 6/6, `ci-test-matrix-lint` 10모듈/6샤드, `saga-contract-matrix-lint --structure` 26행, harness/plans index check, shell 구문, `git diff --check` 통과. 실제 Docker daemon 에서 Alpine image tar 저장·checksum·로드·ID 검사를 통과했다. 게시 스텝의 정상/원격 config 불일치/승격 메타데이터 digest 불일치/원격 latest digest 불일치 mock 실행을 확인했다. 실제 gate 조건의 5개 결과 243조합을 독립 계산해, 전원 success 외에는 gate 성공이 없음을 확인했다.
- 저장소 보호: GitHub API 에서 필수 체크 `gate`(GitHub Actions 앱 ID 15368), `strict=true` 를 확인했다. `enforce_admins=true` 를 적용하고 재조회했다. 이 설정은 현재 저장소에 이미 반영됐다.
- 전체 테스트: `./gradlew test --no-daemon` 통과 (30분 8초, 49 tasks executed). product-service 종료 시 Kafka 리스너 정리 로그가 길었으나 최종 종료 코드 0을 확인했다.
- 미충족: PR CI 에서 두 e2e 모드 완료 후 `gate` 판정, main push 에서 gate 뒤 publish 순서, GHCR SHA/`latest` digest 동일성은 브랜치 게시와 병합 후 확인해야 한다.

## 2026-09-26 — /ship 실행

- 사전 확인: `hpx_ship_preflight` 통과, 등급 L, consistency precheck 경고 0건. 커밋 3건의 문체 lint 와 PR 본문·제목 lint 통과.
- 커밋: `226c4cb` (ADR), `82076eb` (CI), `ac9b7de` (계획·검증 문서). 브랜치 `feat/D-043-ci-release-gate` 푸시, PR [#142](https://github.com/Kimgyuilli/PeekCart/pull/142) 생성.
- 완료 기록: `docs/TASKS.md` 에 PR 링크와 실측 대기 상태를 반영하고 `docs/progress/PHASE5.md` 에 구현 이력을 추가했다. PR CI/main push/GHCR 실측 전이므로 D-043 은 진행 중으로 유지한다.
- 별도 Codex 리뷰 미호출.

## 2026-09-26 — PR CI 실측

- [run 36169884411](https://github.com/Kimgyuilli/PeekCart/actions/runs/36169884411) 의 lint/test/guards/images 6개/e2e 두 모드/gate 가 모두 성공했다. PR 의 publish 는 skipped 로 보고됐다.
- e2e 시나리오는 18:02:14 UTC, 음성 대조군은 18:08:35 UTC 에 끝났고 gate 는 18:08:40 UTC 에 시작해 18:08:54 UTC 에 성공했다. 두 모드를 실제로 기다리는 최종 gate 경로를 확인했다.
- main push 뒤의 GHCR SHA/`latest` digest 와 publish 순서는 아직 실측 전이다.
