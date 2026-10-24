---
grade: M
---
# D-092-ops-closure: Slack webhook Secret 교체와 FAILED 재발행 소비쌍 조건

## 1. 명제와 코드 근거

Slack webhook 값의 정본·주입 위치·교체 순서·유출 대응이 문서로 정해지지 않았거나, Outbox `FAILED` 재발행 runbook 이 중복 억제가 유지되는 시간 창과 늦은 첫 전달을 견디지 못하는 소비쌍을 거르지 않으면 미완이다. 두 문서가 실제 이름·정책과 어긋나도 CI 가 실패하지 않으면 미완이다.

| 전제 | 확인 결과 (origin/main `6052c2f`) | 반영 |
|---|---|---|
| `D-092.md` P1~P3 | P1 은 PR217 전용 Grafana job 이 D-093 수정 뒤 통과했다(PHASE5 2026-10-22). P2 는 PR219 로 끝났다(`done/D-092-slack-split.md` 완료 기록). P3 의 MySQL 검증은 main CI40031226500(`2c9aa36`) notification shard XML 에서 `failedDomainRecoverySqlIsGuardedByMySql` 를 포함한 5건이 실패·오류·skip 0 이다. 원 계획의 "MySQL test 미실행" 문구는 낡았다 | 남은 범위에서 뺀다. `D-092.md` 에 상태만 추기 |
| Slack 값의 현재 흐름 | D-078 S0·S1·S4 기록: 사용자가 Secret Manager `peekcart-slack-user-webhook`·`peekcart-slack-ops-webhook` 에 값을 넣고, 같은 값으로 `peekcart/notification-service-secret` 의 `SLACK_USER_WEBHOOK_URL`·`SLACK_OPS_WEBHOOK_URL` 과 `monitoring/grafana-ops-webhook` 의 `url` 을 만들었다. 주입 명령, 교체·폐기 순서, 유출 대응은 어디에도 없다 | 새 runbook |
| 배포와 Secret | `scripts/deploy-overlay.sh` 는 client-side `kubectl apply -k` 이고 `secret.yml` 은 Slack 키를 선언하지 않는다. `secret.yml`·`application-k8s.yml` 주석은 이동 전 경로 `docs/plans/D-092-slack-split.md` 를 가리킨다 | Slack 키는 `kubectl patch` 로 넣고 배포 뒤 키 이름으로 확인한다. 주석은 새 runbook 을 가리킨다 |
| 소비자 중복 억제 | 도메인 listener 27개(4서비스 11클래스)가 모두 payload `eventId` 로 `IdempotencyChecker.executeIfNew` 를 거친다. 억제는 `processed_events` 행이 남아 있는 동안만 성립한다. 보존기간 9d 가 `dlq-replay-window` 7d 에 시계 오차 5m 과 정리 지연 1d 를 더한 값 이상인지는 부팅 시 검사한다(`IdempotencyRetentionProperties`, ADR-0020 §D4-3) | runbook 에 `created_at` 시간 창 조건 |
| 늦은 첫 전달 | 이전 발행이 broker 에 닿지 않았다면 재발행은 모든 group 에 대한 늦은 첫 전달이다. DLQ replay 는 같은 위험을 `ReplayPolicyRegistry` 소비쌍 정책(default-deny, ADR-0020 §D5-2 축 5)으로 거른다. 현재 runbook 은 소비자 멱등만 요구한다 | 같은 정책을 재발행 조건으로 쓰고, 토픽별 판정표를 fixture 가 레지스트리와 대조한다 |

등급 M: runbook 두 개, CI fixture, 주석 두 곳을 다룬다. 공개 API·이벤트·DB·설정 키는 바꾸지 않고 되돌림은 이 파일들 안에서 끝난다. 새 정책을 만들지 않고 기존 replay 정책을 재발행 판정에 쓴다. 외부 자원은 호출하지 않는다. ADR 선행 신호(새 외부 의존성, 경계 변경, 새 인프라)는 없다.

## 2. 작업 항목

- [x] P1. `docs/runbooks/slack-webhook-secret.md`: 값별 정본·주입 위치·소비자·반영 조건 표, 소유, 값을 화면·argv 에 내지 않는 주입·확인 명령, 정기 교체와 유출 대응 순서, 롤백을 적는다.
- [x] P2. `k8s/base/services/notification-service/secret.yml` 과 `notification-service/src/main/resources/application-k8s.yml` 의 낡은 계획서 경로 주석을 새 runbook 으로 바꾼다.
- [x] P3. `docs/runbooks/outbox-failed-recovery.md` §2 에 `created_at` 시간 창과 소비쌍 정책 조건을 넣고, 기준 커밋의 토픽별 판정표를 둔다.
- [x] P4. `scripts/d092-ops-fixture.py` 에 판정표와 `ReplayPolicyRegistry`·`DlqTopology` 대조, 시간 창 값 대조, Secret runbook 과 실제 배선 이름 대조를 넣고 변이로 검사기가 실패하는지 확인한다.
- [x] P5. `D-092.md` 에 P1~P3 의 실제 상태를 추기한다. 원문은 바꾸지 않는다.

## 3. 검증 방법

| 항목 | 검증 |
|---|---|
| P1 | 주입 명령을 가짜 값으로 `kubectl patch --local`·`kubectl create --dry-run=client` 에 넣어 기존 DB 키 보존과 끝 개행 제거를 확인한다. 실제 Secret Manager·클러스터·Slack 은 호출하지 않는다 |
| P1·P2·P4 | fixture 가 runbook 의 Secret·env 이름을 `deployment.yml`·`application-k8s.yml`·`values-prometheus.yml` 에서 읽은 값과 대조한다. 이름 하나를 바꾼 변이가 실패해야 한다 |
| P3·P4 | fixture 가 레지스트리와 topology 에서 유도한 토픽별 판정을 runbook 표와 비교한다. 판정 하나를 뒤집은 변이, 행을 지운 변이, 결론을 바꾼 변이가 각각 실패해야 한다. 네 서비스의 `dlq-replay-window` 가 runbook 에 적힌 값과 다르면 실패해야 한다. 기존 SQLite·MySQL parity 검사는 그대로 통과해야 한다 |
| P5 | `git diff` 에서 `D-092.md` 의 기존 줄이 바뀌지 않았는지 본다 |
| 전체 | 주석 외 Java 변경이 없어 로컬 `./gradlew test` 는 돌리지 않고 PR CI 에 맡긴다(병렬 worktree 자원 경합 회피) |

## 미해결

- 소유자 지정: 현재 값을 만들고 넣은 사람은 저장소 소유자다(D-078 S0). 운영자 역할을 그대로 확정할지는 사용자가 정한다.
- 실제 교체 리허설은 Secret Manager 와 클러스터에 접근할 수 있는 세션에서만 할 수 있다. 이 작업은 명령 형태를 로컬에서만 확인한다.
- DENY 소비쌍이 있는 토픽(`order.created` 등)의 `FAILED` 행은 재발행하지 않고 이관한다. 허용하려면 outbox 경로 전용 사전조건을 새로 설계해야 하며 이 범위 밖이다.
- 운영 DB 에서의 실제 재발행은 운영 환경이 없어 외부 조건으로 남는다.
