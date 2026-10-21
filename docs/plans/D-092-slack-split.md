---
grade: M
---
# D-092 P2: 사용자와 운영 Slack webhook 분리

## 1. 명제와 코드 근거

notification 사용자 메시지와 운영 경보가 같은 endpoint로 전달되거나, k8s에서 한쪽 자격증명 누락을 legacy/no-op으로 통과하면 미완이다.

사용자가 별도 webhook 분리를 선택했다. 기존 `NotificationCommandService`와 Kafka DLQ 및 공통 Outbox/DLQ 호출은 같은 `SlackPort`를 사용했다. 사용자 메시지만 notification application의 `UserNotificationPort`로 옮기고 공통 포트와 HTTP 전송 구현은 재사용한다. 다른 서비스의 설정·no-op 동작은 변경하지 않는다. 이벤트·DB·외부 의존성과 모듈 경계는 그대로이며, 배선과 notification 배포 입력 변경을 함께 되돌리는 M 작업이다. 새 ADR은 필요하지 않다.

## 2. 작업 항목

- [x] P1. 사용자 포트를 별도로 주입하고 notification k8s에서 사용자/운영 endpoint를 모두 필수로 받는다. 빈값·잘못된 URI·동일 endpoint를 시작 시 거부한다. `SLACK_WEBHOOK_URL` legacy fallback을 두지 않는다.
- [x] P2. loopback HTTP receiver에 사용자 command와 운영 DLQ handler를 실행하여 교차 전달이 없음을 검증한다. 누락·동일 endpoint 음성 대조군과 기존 common no-op 계약을 확인한다.
- [x] P3. smoke 입력과 Secret 주석을 새 계약에 맞추고 rollout/rollback 조건을 기록한다. shared TASKS/master/progress는 Root가 관리한다.

## 3. 검증 방법

`NotificationSlackRoutingTest`는 실제 notification YAML의 k8s profile을 로드한다. synthetic 사용자 메시지 저장/전송 후 user receiver 한 건과 ops 0건을 확인한다. 실제 Kafka error handler의 재시도 소진 후 ops receiver 한 건과 user 추가 0건을 확인한다. Kafka 브로커·DB만 mock이며 HTTP는 loopback 실제 요청이다. legacy 단독, 각 credential 누락, 빈값, 동일 endpoint와 URI 정규화 동치 endpoint는 컨텍스트 시작 실패를 확인한다. common `SlackPortConfigTest`가 다른 서비스의 no-op/실 포트 계약을 검증한다. 전체 Gradle test와 정책 lint는 PR CI를 정본으로 기록한다.

## 배포와 재시작 영향

- notification k8s는 `SLACK_USER_WEBHOOK_URL`과 `SLACK_OPS_WEBHOOK_URL`을 외부 Secret에서 받아야 한다. 기존 `SLACK_WEBHOOK_URL`만 있으면 새 pod는 시작 실패한다. 저장소에는 실제 값이나 Secret placeholder를 추가하지 않는다.
- 두 endpoint는 별개 webhook이어야 한다. URI 비교는 동일 URL만 거부하며, 서로 다른 webhook이 같은 Slack 채널에 연결되는지는 증명하지 못한다. 채널 분리는 소유자가 non-production canary로 확인해야 한다.
- base/local 기본값은 서로 다른 loopback port 9 endpoint다. 외부로 전송하지 않으며 receiver가 없으면 기존 전송 클라이언트처럼 오류를 기록한다. 실제 local 수신이 필요하면 두 변수를 명시한다.
- 적용 순서: 운영 소유자가 목적지/Secret 공급을 승인하고 두 값을 먼저 공급한 뒤 새 이미지를 rollout한다. envFrom 값은 기존 프로세스에 자동 반영되지 않으므로 Secret 변경 후 재시작이 필요하다. Secret은 kustomize 적용으로 덮어쓰이지 않도록 외부 소유자가 관리해야 한다.
- rollback은 이전 이미지와 `SLACK_WEBHOOK_URL`을 함께 유지/복원한 뒤 수행한다. 신규 변수만으로 이전 이미지는 정상 시작하지 않는다. 실제 Secret 값·소유자·rollout·재시작은 이 PR에서 수행하지 않는다.

## 미해결

실제 Slack 전달, 채널 소유자 승인, Secret 주입, GKE rollout은 외부 운영 조건으로 남는다. PR217의 Grafana/runbook 파일은 이 PR 범위 밖이다. 코드/무료 fixture 성공을 운영 전달 성공이나 D-092 전체 완료로 표시하지 않는다.
