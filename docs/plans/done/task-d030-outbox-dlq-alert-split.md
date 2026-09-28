---
grade: M
---
# task-d030-outbox-dlq-alert-split

## 1. 명제

**Outbox 발행 소진(`FAILED`)이 나도 운영자가 받는 신호가 없으면 미완이다.** DLQ 와 구분되는 별도 신호여야 한다.

TASKS 의 D-030 은 "두 알림이 같은 Slack 채널에 섞인다" 와 "DLQ 적재량 메트릭이 없다" 를 전제로 한다.
코드로 확인해 보니 두 전제 모두 현재 상태와 다르다. 그래서 범위를 다시 정했다.

| 전제 | 현재 코드 | 귀결 |
|---|---|---|
| DLQ 적재량 메트릭 부재 | `dlq.backlog` · `dlq.oldest.age` gauge (`global/deadletter/DeadLetterMetrics.java`), alert `peekcart-dlq-backlog` (`grafana-alerts.yml:323`) | 이미 해소됨. 범위에서 뺀다 |
| 두 알림이 한 채널에 섞인다 | 실제 웹훅은 notification-service 하나뿐이다. order·payment·product 는 `slack.noop-fallback.enabled=true` 라 두 알림 모두 로그로만 남는다 | 앱이 Slack 으로 직접 보내는 경로는 운영 신호로 신뢰할 수 없다 |
| (서술 없음) | Outbox FAILED 는 `outbox.backlog{status="failed"}` gauge 가 4서비스(`*/global/outbox/OutboxPollingService.java:59`)에 있지만 **alert 규칙이 없다** | **실제 공백은 이것이다** |
| (서술 없음) | FAILED 는 종착 상태다. 자동 재시도가 없고 `OutboxEventCleanupScheduler` 도 이 행은 지우지 않는다 | gauge 가 저절로 0 으로 돌아가지 않아 alert 입력으로 안정적이다 |
| (서술 없음) | alert 식은 `scripts/observability-promql-lint.sh` 의 `METRIC_ALERT_CONTRACTS` 에 정확한 문자열로 고정돼 있다 | 규칙을 추가하면 lint 정본과 self-test 도 함께 고쳐야 한다 |

채널 분리는 **메트릭 alert 경로**에서 한다. 규칙을 따로 두고 라우팅 라벨을 붙인다.
contact point 와 notification policy 설정은 이월한다(§4).

## 2. 작업 항목

- [x] P1. `k8s/monitoring/shared/grafana-alerts.yml` 에 `peekcart-outbox-failed` 규칙을 추가한다.
  - 식: `sum by (application)(outbox_backlog{application=~"notification-service|order-service|payment-service|product-service", status="failed"})`, 조건 `$A > 0`, `for: 5m`
  - annotation 에 DLQ 와 반대인 대응을 적는다: 발행 실패이므로 브로커를 복구한 뒤 재발행한다. FAILED 는 자동 재시도가 없는 종착 상태다
- [x] P2. 라우팅 라벨을 붙인다: `peekcart-outbox-failed` 에 `signal: outbox-publish`, `peekcart-dlq-backlog` 에 `signal: dlq`. 나중에 contact point 를 분리할 때 쓸 기준이다
- [x] P3. `scripts/observability-promql-lint.sh`
  - `METRIC_ALERT_CONTRACTS` 에 `peekcart-outbox-failed` 를 등록한다 (metric `outbox_backlog`, 소유 4서비스, 식 정확 일치)
  - self-test 에 음성 케이스 2개를 추가한다: ① 규칙 삭제 ② `status="failed"` matcher 를 `pending` 으로 바꾸거나 제거
- [x] P4. 사실과 달라진 서술을 고친다.
  - `grafana-alerts.yml` 머리 주석 ("Outbox FAILED 통지는 앱 Slack 경로")
  - `docs/04-design-deep-dive.md` §9-3 ("두 경로 모두 Slack 알림으로 운영자에게 통지") → 메트릭 alert 가 운영 신호이고, 앱 Slack 은 notification-service 에서만 발송되는 보조 신호라고 정정
  - ADR 본문(0009 S8 "alert 미도입")은 immutable 이라 고치지 않는다. progress 에 기록한다

## 3. 검증 방법

- **P1·P3 결함 주입**: `bash scripts/observability-promql-lint.sh --self-test` 를 돌린다. 새 음성 케이스 2개(규칙 삭제, status matcher 변조)가 각각 **실패로 검출**되는지 확인한다. 원본은 `bash scripts/observability-promql-lint.sh` exit 0 이어야 한다. promtool 로 syntax 도 검증된다
- **P3 역검증**: lint 에 계약만 추가하고 alert 는 넣지 않은 상태에서 lint 가 exit 1 을 내는지 한 번 확인한다. 계약이 실제로 작동한다는 증거다
- **P2**: `python3 -c` 로 ConfigMap 을 파싱해 두 uid 의 `labels.signal` 값이 서로 다른지 확인한다
- **P1 식의 실효성**: 메트릭 이름과 태그가 실제 노출 형식(`outbox_backlog{status="failed"}`)과 맞는지 `ObservabilityMetricsIntegrationTest` 의 기존 단언(ADR-0009 S8 검증 행)과 대조한다. 실제 스택 발화 시험은 하지 않는다(§4)

## 4. 미해결 (범위 밖)

- **contact point / notification policy provisioning**: `signal` 라벨로 채널을 실제로 나누는 설정이다. 웹훅 Secret 운영 방식을 새로 정해야 해서 ADR 판단 대상이다. 운영 클러스터를 상시 운영하게 되면 재검토한다
- **Outbox FAILED 재발행 runbook**: FAILED → PENDING 수동 전환 절차가 문서화돼 있지 않다. alert annotation 에는 대응 방향만 적는다. FAILED 가 실제로 발생하면 재검토한다
- **notification-service 웹훅에 사용자 알림(`NotificationCommandService`)과 운영 알림이 섞이는 문제**: 운영 신호를 메트릭 alert 로 옮기면 영향이 줄어든다. 앱 Slack 경로 정리는 재발하면 부채로 올린다
- **실제 스택에서 alert 발화 시험**: Grafana 평가까지는 GKE 측정 세션에서만 가능하다
