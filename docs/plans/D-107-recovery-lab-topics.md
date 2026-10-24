---
grade: S
---
# D-107-recovery-lab-topics

D-107 첫 조각. 기준: 2026-10-24, main `e57e294`. D-107 행은 [#237](https://github.com/Kimgyuilli/PeekCart/pull/237) 이 등록한다.

## 명제

approval-recovery lab 이 서비스 기동 순서에 따라 실패하거나, 서비스를 띄우기 전에 선언 토픽이 선언 파티션 수로 있다는 것을
lab 이 확인하지 않으면 미완이다.

배경: #237 의 run 40095843627 attempt 1·2 에서 product 가 payment 보다 4~5초 먼저 떠 `payment.approval-recovery.*` 를
1파티션으로 자동 생성했고, payment 의 기동 중 KafkaAdmin 이 3파티션으로 늘렸다. product 그룹은 파티션 0 만 할당받은 채
metadata 갱신(기본 5분) 전이라, 키 없이 발행한 중복 이벤트가 파티션 1·2 로 가면 처리되지 않아 duplicates 단계가
`TimeoutExpired` 로 끝났다. 같은 코드의 `d5b8679` 는 두 서비스가 0.2초 차로 떠 통과했다. E2E 는 같은 이유(#92)로
`scripts/saga-e2e-smoke.sh` 가 앱 기동 전에 토픽을 만든다. lab Kafka 는 healthcheck 가 있고(`docker-compose.signed-gateway.yml`),
`scripts/kafka-subscription-contract-lint.sh --emit-topics` 는 approval-recovery 토픽 8개를 포함한 30개를 선언 파티션 수와 함께 낸다.

## 작업 항목

- [x] P1. `scripts/approval-recovery/lab.py`: `kafka()` 의 브로커 소유 확인과 exec 를 임의 argv·timeout 을 받는 헬퍼로 나눈다.
  일괄 생성은 CLI 30회라 기존 30초 timeout 에 들지 않는다.
- [x] P2. 같은 파일 `execute()`: `prepare()` 뒤 `up -d --wait kafka`, `--emit-topics` 목록을 exec 1회 shell 루프로
  `--create --if-not-exists`(saga-e2e-smoke.sh 의 D-048 방식), 전체 `--describe` 로 30개 파티션 수를 대조해 다르면 실패,
  그 뒤 서비스를 띄운다.
- [x] P3. `.github/workflows/approval-recovery.yml` 트리거 경로에 `scripts/kafka-subscription-contract-lint.sh` 를 더한다.
  lab 이 그 목록에 의존하게 되므로 목록이 바뀌면 lab 이 돌아야 한다.
- [x] P4. 목록 읽기(`declared_topics`)와 describe 요약 줄 파싱(`partition_counts`)을 `lab.py` 모듈 함수로 두고,
  `scripts/approval-recovery/test_contract.py` 에 Docker 없이 도는 테스트 둘을 더한다. 목록에 approval-recovery 업무 토픽이
  3파티션으로 있는지, 파서가 파티션별 줄(탭으로 시작)을 세지 않는지 본다. 이 테스트는 워크플로의 기존 계약 테스트 단계에서 돈다.

## 검증

- 로컬: P2 의 루프와 대조를 일회용 `apache/kafka:3.8.1` 컨테이너에 그대로 돌려 30개 생성·일치를 확인하고,
  토픽 하나를 미리 1파티션으로 만들어 두면(자동 생성과 같은 상태) `--if-not-exists` 가 그대로 두므로 대조가 실패하는지 확인한다.
  lab 계약 테스트(`scripts/approval-recovery/test_contract.py`)와 `python3 -B -m py_compile` 을 돌린다.
- CI: approval-recovery lab 통과. 증적의 `ready-product-svc-approval-recovery-*` 가 3, services.log 에 payment 토픽의
  "different partition count" 증설 로그가 없어야 한다(토픽이 이미 3파티션이라 KafkaAdmin 이 늘릴 일이 없다).
  그 run 의 기동 순서(product 와 payment 의 Started 시각)를 함께 적는다.

## 미해결

- GKE 배포의 토픽 사전 생성(또는 브로커 자동 생성 끄기)은 D-107 본체로 남는다.
- D-107 행에 이번 CI 발생과 이 PR 을 적는 일은 #237 이 main 에 들어간 뒤 한다. 그 전에는 main 에 행이 없다.
