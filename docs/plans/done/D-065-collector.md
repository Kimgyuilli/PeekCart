---
grade: M
---
# D-065 collector 수집 실패 차단

## 1. 명제와 배경

DB 표적 group 조회 실패 또는 Kafka의 비정상 응답이 drain 완료(exit 0)로 판정되면 미완료다.
단일 배포 도구의 기존 fail-closed 계약을 복구한다. 새 의존성·환경·DB 계약은 없고 ADR-0022 D2를 유지한다.

| 코드 확인 | 현재 상태 | 처분 |
| --- | --- | --- |
| `scripts/replay-drain-preflight.sh` collect | group SQL 실패를 `|| true`로 삼킴 | 실패 레코드로 전달 |
| `consumer_group_lag` | 빈 응답·header만·숫자가 아닌 lag를 awk가 0으로 변환 | group·header·partition 숫자와 행 존재 검증 |
| V11 `last_replay_settled_at DATETIME(6)` | UNIX_TIMESTAMP 결과는 소수초를 포함하나 판정기는 int 변환 | Decimal로 소수초를 보존하여 경계 판정 |
| `collect \| judge` | 조건문 안에서 실행되어 Bash errexit에 의존 불가 | secret·SQL·sleep 실패를 명시적으로 처리 |
| `.github/workflows/ci.yml` | preflight `--self-test` 실행 | 실제 스크립트 adapter fixture를 이 경로에 연결 |

## 2. 작업 항목

- [x] P1. DB 표적 group 조회와 Kafka 두 샘플을 실패 시 차단하고 진단을 보존한다.
- [x] P2. PATH kubectl 대역으로 실제 스크립트 정상 실행 경로를 검증한다. 전체 서비스와 group을 추적하고 성공·실패·빈/잘못된 응답·둘째 샘플 실패를 각각 주입한다.
- [x] P3. 기존 판정 self-test와 관련 lint 결과를 기록하고 CI의 기존 self-test 경로에 수집 회귀 검증을 연결한다.

## 3. 검증 방법

- 정상 다중 partition/group 응답과 replay 이력 없는 DB는 통과해야 한다. 호출 로그로 네 서비스 DB 및 각 target/intake group의 두 샘플을 확인한다.
- 표적 SQL 실패, secret/집계 SQL 실패, 빈/header만/잘못된 group/잘못된 형식/비정수/음수/미할당 Kafka 응답은 exit 1 및 원인 진단이어야 한다.
- 정상 첫 샘플 뒤 둘째 샘플의 실패·빈 응답·lag 증가도 exit 1이어야 한다.
- `bash scripts/replay-drain-preflight.sh --self-test`, `bash scripts/replay-entrypoint-lint.sh` 및 `--self-test`, shell syntax, `git diff --check`.
- `./gradlew test` 및 필수 CI 전체 결과는 병렬 작업 자원 조정 후 확인한다. 미실행을 통과로 기록하지 않는다.

## 미해결

D-065는 부분 완료다. 이 fixture는 실제 수집 코드와 CLI 계약을 검증하며 실제 MySQL/Kafka/GKE 접속을 증명하지 않는다.
로컬 서명 gateway 경유 요청, `record_kind`·`root_record_id` NOT NULL 계약 (writer 준비는 별도 PR201에 병합), 운영 timestamp P22 표본, GKE ACL/좌표 확인과 replay 개방은 별도 작업이다.
공용 TASKS/progress/master plan 문서 갱신과 병합 판단은 주 작업자가 담당한다.

## 검증 결과

- 판정 self-test 16개 통과. 실제 스크립트 adapter 64개 시나리오(8 test methods) 전체 통과, 추가 재관측 대기 실패 시나리오 1개 통과. 최종 runner는 9 methods/65 시나리오다.
- replay entrypoint lint 및 drift self-test, Bash/Python 구문, diff 검사, consistency precheck 통과.
- 전체 Gradle과 GitHub Actions는 draft PR에서 확인할 예정이며 아직 통과로 기록하지 않는다.

## 최신 main 통합 검증

2026-10-21 실제 main `4a4a36a12a3d914d6bc0379ded94bc0123c4d370`를 merge `7c597cd`로 통합했다. 기존 base0e40..e8de의 authored patch와 newbase4a4..7c597의 patch는 byte 동일(SHA256 `7827c0b96be74122cb340186e2fe9b7f2c559897ec7f10ae4ca988d23439e1f8`)하다. 판정16, adapter65시나리오/9methods(218.951초), entrypoint lint/drift9, shell/Python syntax, consistency/whitespace가 통과했다. 최신 전체 CI와 독립 delta 검토는 별도로 필요하다. 실제 MySQL/Kafka collector adapter의 무료 로컬 검증은 후속이며 이번 fixture 결과로 완료 처리하지 않는다.
