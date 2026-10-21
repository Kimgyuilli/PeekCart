# D-092 P2 구현·검증 기록

- 기준: origin/main `797fc30d1661353aaf3d0d878c0e34f3d0347007`.
- 사용자 선택: 별도 webhook 분리. shared 완료 문서는 Root 소유.
- 별도 Codex 리뷰 미호출.
- 검증: 진행 중. 로컬 첫 Gradle 호출은 cache lock 쓰기 sandbox 권한 부족으로 실행되지 않았고 권한 있는 focused test로 재시도했다.
- 실제 Slack/GKE/Secret 조회·변경 없음. 외부 전달·rollout 미완료.
- focused test 통과: notification routing 7건, command service 2건, Kafka retry 1건, common Slack config 4건. 이후 invalid endpoint 음성 대조군 1건을 추가해 재검증한다.
- `bash -n scripts/docker-health-smoke.sh`, `docs-preservation-ci.py --base 797fc30d1661353aaf3d0d878c0e34f3d0347007` 통과.
- 전체 test/정책 lint는 PR Actions에서 수행하며 결과 전까지 전체 CI 검증 미완료.
- 최종 focused 재검증 통과: routing 8건(실 HTTP 분리 1, 시작 실패 음성 대조군 7), command 2건, Kafka retry 1건, common config 4건(변경 없음으로 UP-TO-DATE). notification 재실행 seed `1792592268510`.
- ship preflight/consistency precheck 통과. `git diff --check`, integration-test-container-lint 통과.

## 2026-10-22 PR217 반영 정렬

- PR219의 기존 head `67585c0cffe8869d0e80714c15cd68481578d9b9`에서 main `7320ffdd4a72a1bdde77bc9424c8d11ecff18ba3`을 충돌 없이 merge했다.
- 기존 PR219 일반 CI `39615843352`, signed admission `39615843351`은 성공했다. Root가 보고한 독립 리뷰 `6318948804`는 무차단이며, 이번 정렬 delta는 별도 검토 대기다.
- PR217은 monitoring/runbook 및 notification Outbox MySQL fixture를 추가했다. 사용자 포트·notification 설정·smoke·Secret 파일과 겹치는 변경은 없고 PR219 배선 구현은 변경하지 않았다.
- 정렬 후 focused Gradle 검증 성공: notification routing 8, command 2, retry 1건 재실행(seed `1792597793746`); common Slack config 4건은 변경 없이 UP-TO-DATE다. 전체 테스트는 새 PR Actions로 확인한다.
- `d092-ops-fixture.py`, `docs-preservation-ci.py --base 7320ffdd4a72a1bdde77bc9424c8d11ecff18ba3`, smoke syntax, diff check 통과.
- Root의 최신 main CI/publish 확인과 독립 delta 리뷰 전에는 병합하지 않는다. 실제 Slack·Secret 공급·rollout은 계속 미검증이다. 공유 TASKS/master/progress는 수정하지 않았다.
