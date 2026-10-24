---
grade: L
---
# D-106 ⑩·② — 측정 전 토픽 상태 가드와 CPU 스로틀 패널

기준: 2026-10-25, main `0ab5de6`, 브랜치 `debt/d106-d107-followup`. 원자료: `loadtest/reports/2026-10-24/session-issues.md` #2·#10,
`loadtest/reports/2026-10-24/s4/d065-5-*`, `docs/plans/done/D-106-topic-config-race.md` §미해결.

## 1. 명제

다음 중 하나라도 성립하면 미완이다.

- 측정 run 전에 실행할 가드가 없거나, 가드가 다음 넷 중 하나를 놓친다: 선언 토픽 30개 중 부재, 파티션 수 불일치,
  계약 7키의 실효값 불일치, 업무 group 이 구독 토픽의 파티션 중 하나라도 할당받지 못한 상태.
- 가드가 명령 실패·빈 출력·형식 불명을 통과로 처리한다(fail-closed 가 아니다).
- GKE S4 스냅샷(`d065-5-topic-configs-all.txt`)을 입력으로 줬을 때 손 대조 결과(24개 일치, payment 6개 불일치)와 다르게 판정한다.
- 가드의 config 기대값이 `KafkaTopicConfigs`·서비스 yml 과 갈라져도 CI 가 잡지 못한다.
- saga 대시보드 "CPU 스로틀 (컨테이너별)" 패널이 kube-prometheus-stack 기본 설정에서 수집되지 않는 메트릭을 쓰거나,
  캡처 도구(`d078-capture.py`)가 그 패널을 찾지 못한다.
- TASKS D-106 행이 #237 을 아직 "PR CI·머지 대기"로 적고 있다.

등급 L: 새 가드 스크립트, lint 의 emit 모드 추가, CI 워크플로, 대시보드, 측정 절차 문서를 건드린다. lint 출력과 가드 CLI 는
다른 곳에서 부르는 표면이다. 모듈 이동·경계 변경은 없다. ADR 선행 신호(새 외부 의존성, 경계 변경, 새 인프라) 없음.
패널 단위 변경은 대시보드 파일 하나에서 되돌릴 수 있어 ADR 대상이 아니다.

## 2. 배경

### 2.1 코드 검증

| 전제 | 확인 | 결과 |
|---|---|---|
| 측정 전 토픽·group 가드가 이미 있나 | `kafka-configs`·`kafka-consumer-groups` 를 부르는 스크립트 grep | 없다. `replay-drain-preflight.sh` 는 replay lag 만, `approval-recovery/lab.py` 는 토픽 사전 생성만 본다 → 새로 만든다 |
| 토픽·파티션 정본 | `kafka-subscription-contract-lint.sh --emit-topics` | 30개(업무 15 × 3파티션, DLQ 15 × 1). E2E·lab 이 같은 출력을 쓴다 |
| 업무 group 정본 | `--emit-groups` | 27개. group→토픽 대응은 내보내지 않는다. 출처는 `DlqTopology.CONSUMPTION`(lint 의 `business_expected`), 지금은 group 마다 토픽 1개 |
| config 정본 | `common/.../KafkaTopicConfigs.java`, 3서비스 `application.yml` | 7키. 업무 8 MiB·4 MiB, DLQ 4 MiB·2 MiB, `segment.ms` 1d, `delete`, `CreateTime`. `retention.ms` 는 `app.idempotency.floor.kafka-topic-retention`(7d), `message.timestamp.before.max.ms` 는 `app.idempotency.retention`(9d, `topicMessageTimestampBeforeMax()` 가 그대로 반환). order·product·payment 값이 같고 프로파일 yml 재정의가 없다 |
| 토픽별 config 종류 | 3서비스 `*KafkaConfig.java` 의 `TopicBuilder` | 전부 `.configs(businessConfigs())`·`.configs(dlqConfigs())` 이고 `.dlq` 접미사와 일치한다 |
| 손 대조 원자료의 형식 | `d065-5-topic-configs-all.txt` | `kafka-configs.sh --describe --entity-type topics --all` 한 번의 출력, 31섹션(30 + `__consumer_offsets`). Kafka 3.8.1 실제 출력이라 fixture 로 쓴다 |
| E2E readiness 가 이미 할당을 보나 | `scripts/e2e/saga_e2e.py` `groups_ready` | member ≥ 1, 할당 파티션 ≥ 1 만 본다. "모든 파티션" 이 아니다 |
| group 할당을 읽을 뷰 | `scripts/tests/replay-drain-docker-test.py:162` | 3.8.1 의 `--members --verbose` ASSIGNMENT 는 토픽 이름 없이 `(0)` 만 나올 수 있다 → offsets 뷰(`--describe`)의 TOPIC·PARTITION·CONSUMER-ID 를 쓴다 |
| Kafka 접근 | `replay-drain-preflight.sh:301`, `saga-e2e-smoke.sh:356` | k8s 는 `kubectl -n $PEEKCART_NAMESPACE exec deploy/kafka -- ... --bootstrap-server localhost:29092`(REPLAY-ENTRY-007 이 리스너와 대조), E2E 는 `docker compose ... exec -T kafka` 에 같은 포트 |
| 측정 스택의 서비스 | `loadtest/README.md` D-002b'/c §스택 | `gke-d002bc` 는 notification 제외 → notification group 5개가 없다. 서비스 범위를 지정할 수 있어야 한다 |
| 스로틀 패널 | `k8s/monitoring/shared/saga-messaging-dashboard.json` | id 16 "CPU 스로틀 (컨테이너별)", unit `s`, `rate(container_cpu_cfs_throttled_seconds_total{...})`. 레포의 유일한 사용처(과거 리포트 export 제외) |
| 패널이 빈 원인 | 세션 차트 `kube-prometheus-stack-91.4.0` 의 values(OCI `ghcr.io/prometheus-community/charts/kube-prometheus-stack:91.4.0`, digest `sha256:0032315f2580f488ce433ff9d18de24ce631857ff7f1d73693171eeeb7affd52`) | `kubelet.serviceMonitor.cAdvisorMetricRelabelings` 첫 규칙이 `container_cpu_(cfs_throttled_seconds_total\|load_average_10s\|system_seconds_total\|user_seconds_total)` 를 drop 한다. `container_cpu_cfs_periods_total`·`container_cpu_cfs_throttled_periods_total` 은 대상이 아니다(세션 기록 #2 의 "확인 필요" 해소). `install.sh` 는 차트 버전을 고정하지 않는다 |
| 패널 참조처 | grep | `loadtest/scripts/d078-capture.py:40,42` 가 제목으로 찾고 `loadtest/captures.md:36,38` 이 이름으로 부른다 → 제목 유지 |
| 스로틀 수치 정본 | `loadtest/scripts/d026-run-cell.sh:155` | cgroup `cpu.stat` 의 `throttled_usec` 차분. 패널과 독립이다 |
| 대시보드 lint | `scripts/observability-promql-lint.sh` | alert 와 api-jvm·kafka-lag 변수만 본다. saga 패널식은 대상이 아니다 |
| CI 배선 위치 | `.github/workflows/ci.yml` lint job | `replay-drain-preflight.sh --self-test`(164), unittest(183) 선례. lint job 은 `changes` 판정과 무관하게 항상 돈다 |
| lint 를 고치면 | `.github/workflows/approval-recovery.yml` | 트리거 경로에 lint 가 있어 lab 이 돈다(D-107 첫 조각 P3) |

### 2.2 대안과 선택

**config 기대값.** (가) 가드가 실행 때 `KafkaTopicConfigs.java`·yml 을 파싱해 유도한다. (나) 가드는 업무·DLQ 2종 × 7키 표를 갖고,
CI 테스트가 같은 값을 소스에서 유도해 대조한다. (나)를 고른다. 유도 파서는 어느 쪽이든 필요한데, (가)는 Java 산술식·Duration
파싱이 과금 세션 도중에 실패할 수 있다. (나)는 소스를 바꾼 PR 의 CI 에서 실패해 표를 함께 고치게 한다. 토픽 목록·파티션 수·구독은
지금처럼 실행 때 lint emit 으로 유도한다(E2E·lab 과 같은 정본).

**group 할당 판정.** offsets 뷰에서 구독 토픽의 파티션마다 CONSUMER-ID 가 `-` 가 아닌 행이 있어야 통과한다. 할당도 커밋도 없는
파티션은 행이 없으므로 행 없음은 미할당이다. D-107 ②(구독 뒤 늘어난 파티션을 metadata 갱신 전까지 받지 못함)가 이 판정에 걸린다.

**스로틀 패널.** (가) 패널식을 `throttled_periods / periods` 비율로 바꾼다. (나) values 에 차트 기본 relabel 목록 전체를 복제하고
seconds drop 만 뺀다. (가)를 고른다. 차트가 seconds 를 버리는 것은 기본 설정이고, 차트에 들어 있는 kubernetes-mixin 대시보드도
periods 비율을 쓴다. (나)는 버전이 고정되지 않은 차트의 기본 목록을 values 두 벌에 복제해 업그레이드 때 갈라진다.
대가는 단위가 초/초에서 % 로 바뀌어 하네스의 초 단위 수치와 겹쳐 읽지 못한다는 점이다. 수치 정본은 계속 하네스다.
구현 중 확인: 기존 패널 description 이 "ADR-0027 의 MySQL 스로틀 147.6s → 0.1s 와 같은 축" 이라고 적고 있어, 초 단위 축이
처음부터 의도였다. 이 사실과 (나)의 비용(values 복제 + 차트 버전 고정)을 놓고 사용자가 (가)를 확정했다(2026-10-25).

### 2.3 PLAN-BLINDSPOTS

- B2: 가드와 `--emit-subscriptions` 는 없다(2.1) → P1·P2 로 만든다.
- B4: 파일·명령은 §3 에 적었다.
- B11: 대시보드 JSON 의 식은 `namespace=\"peekcart\"` 처럼 escape 돼 있다. 패널 검증은 grep 이 아니라 JSON 파싱으로 하고,
  잔존 sweep 은 따옴표가 없는 메트릭 이름으로 레포 전체를 본다.
- B12: 가드는 진입 시점 검사다. 통과 뒤 재기동·리밸런스가 일어나면 run 중 상태는 보장하지 않는다. 남은 창은 가드 통과부터
  run 종료까지이고, 그 사이 할당이 빠지면 그 run 의 소비가 지연된다. 측정 블록의 첫 run 직전(배포·재기동 직후 포함)에 돌리고,
  run 뒤 배수·lag export 로 사후 확인한다. README 에 fence 가 아니라고 적는다.
- B1·B1b·B3·B5~B10·B13: 이동·추출·공유 테스트 인프라·스키마 변경·기준선 재현이 없어 해당 없음.

## 3. 작업 항목

- [x] P1. `scripts/kafka-subscription-contract-lint.sh --emit-subscriptions`: `business_expected` 를 `service<TAB>topic<TAB>group`
  으로 내보낸다(서비스는 소문자). `--emit-groups` 처럼 위반이 있으면 exit 1. 기존 `--emit-topics`·`--emit-groups` 출력은 바꾸지 않는다.
- [x] P2. `scripts/kafka-topic-state-guard.py`:
  - 선언: `--emit-topics`(토픽·파티션), `--emit-subscriptions`(`--services` 로 거른다. 기본 order,product,payment,notification),
    config 기대값 표(업무·DLQ × 7키, `.dlq` 접미사로 종류를 정한다).
  - 수집: exec 접두사(기본 `kubectl -n ${PEEKCART_NAMESPACE:-peekcart} exec deploy/kafka --`, `--exec` 로 교체) 안에서
    `kafka-topics.sh --describe`, `kafka-configs.sh --describe --entity-type topics --all`,
    `kafka-consumer-groups.sh --describe --all-groups` 를 `localhost:29092` 로 1회씩 실행한다.
  - 판정(순수 함수): 토픽 부재, 파티션 수 ≠ 선언, 7키 실효값 ≠ 기대, group 부재, 구독 토픽 파티션의 행 없음 또는 CONSUMER-ID `-`.
    명령 실패·빈 출력·형식 불명은 위반이다.
  - `--evidence-dir` 를 주면 원 출력 3개와 판정을 저장한다. exit 0 통과 / 1 위반·수집 실패 / 2 전제 미충족(lint emit 실패, 인자 오류).
- [x] P3. `scripts/tests/test_kafka_topic_state_guard.py`(unittest, Docker 없음): §검증 V1~V4. `.github/workflows/ci.yml`
  lint job 에서 preflight self-test 옆에 실행한다(구현 중 정정: 초안은 guards job 이라 적었으나 그 줄들은 lint job 에 있다).
- [x] P4. `loadtest/README.md` D-002b'/c §절차: 측정 블록 첫 run 직전 가드 실행(`gke-d002bc` 는 `--services order,product,payment`),
  증적 경로, 실패 때 처리(부분 할당은 metadata 갱신 최대 5분을 기다리거나 그 서비스를 재기동한 뒤 재실행, config·파티션 불일치는
  그 토픽의 소유 서비스를 재기동한 뒤 재실행), B12 의 한계. 손으로 하던 D-065 ⑤ 대조를 대체한다고 적는다.
- [x] P5. `saga-messaging-dashboard.json` id 16: 식을
  `sum by (container) (rate(container_cpu_cfs_throttled_periods_total{…}[$__rate_interval])) / sum by (container) (rate(container_cpu_cfs_periods_total{…}[$__rate_interval]))`
  로, unit 을 `percentunit` 로 바꾸고 제목은 유지한다. 패널 description 에 차트 기본 drop 과 수치 정본(cgroup `cpu.stat`)을 적는다.
- [x] P6. TASKS D-106 행: #237 을 "병합 `0ab5de6`, main CI40105508535 성공"으로 정정한다. 이 PR 의 상태·링크·progress 이력은
  `/ship` 의 done 갱신에서 남긴다.

## 검증 방법

- V1 (P2, 실제 스냅샷): `d065-5-topic-configs-all.txt` 와, 30개 토픽이 선언 파티션이고 27개 group 이 전 파티션을 할당받은 합성
  topics·groups 출력을 판정에 넣는다. 위반이 정확히 payment 6개 토픽(`payment.completed`·`payment.requested`·DLQ 4)의 config 이고
  나머지 24개는 통과해야 한다. 손 대조 결과(`docs/progress/evidence/d078-gke-20261024.md` §S4)와 같아야 한다.
- V2 (P3, 기대값 드리프트): 소스(`KafkaTopicConfigs.java` 의 상수·리터럴·put 키 집합, 3서비스 yml 의 두 값, `*KafkaConfig` 의 인자 경로,
  `topicMessageTimestampBeforeMax()` 의 `return retention`)에서 유도한 값이 가드 표와 같다. 유도 값은 스냅샷에서 7키가 전부 동적인
  토픽(예: `order.cancelled`)의 동적 값과도 같다(앱이 실제로 쓴 값). 임시 사본에서 상수 하나를 바꾸면 테스트가 실패하고,
  파서가 찾는 줄을 지우면 "유도 실패"로 실패한다.
- V3 (P2, 음성): 합성 입력에 하나씩 주입한다 — 파티션 1(선언 3), 토픽 부재, group 의 파티션 0만 할당, CONSUMER-ID `-` 행,
  group 부재, 빈 출력, 형식이 다른 출력. 각각 해당 위반이 나오고 exit 1 이다. 주입 없는 입력은 exit 0 이다.
- V4 (P2 진입점): 가짜 exec(argv 로 명령을 가려 고정 출력을 내는 스크립트)로 실제 CLI 를 돌려 exit 0·1 을 확인하고, exec 가
  0 이 아닌 코드로 끝나면 exit 1 이다. `--services order,product,payment` 에서는 notification group 부재가 위반이 아니다.
- V5 (P2, 실제 브로커, 로컬): `docker-compose.e2e.yml` 의 kafka 만 띄워 30개를 선언 파티션·config 로 만들면 토픽·config 판정이
  통과하고, config 없이 만든 토픽 1개와 1파티션 토픽 1개는 그 토픽만 위반으로 잡힌다. group 판정은 같은 브로커의 실제 출력으로
  확인한다. 자동 생성(1파티션) 토픽을 구독한 console consumer 를 둔 채 3파티션으로 늘리면 파티션 1·2 미할당으로 잡히고(D-107 ②),
  consumer 를 멈춘 group 은 `-` 행으로 잡힌다. 실제 출력 3종이 fixture 와 같은 형식인지 대조한다.
- V6 (P5): JSON 파싱으로 id 16 의 식이 periods 메트릭 둘만 쓰고 제목이 그대로임을 확인하고, `d078-capture.py` 의 제목 조회가
  그 패널을 찾는다. `promtool check rules` 로 식 문법을, `promtool test rules` 로 합성 시계열의 비율 값을 확인한다. 로컬 minikube 에
  같은 차트 91.4.0(`k8s/monitoring/minikube/values-prometheus.yml`)을 올리고 `peekcart` 네임스페이스에 CPU limit 100m busy Pod 를
  띄워, 기존 식은 빈 결과이고 새 식은 그 컨테이너에 0 보다 큰 값을 내는지 Prometheus API 로 확인한다. minikube 기동이 실패하면
  그 사실을 audit 에 남기고 D-078 재측정 캡처로 넘긴다. (구현 중 정정: minikube 의 docker runtime 에서는 cAdvisor 가 컨테이너 라벨
  시계열을 내지 않아 판정할 수 없었다. GKE 와 같은 containerd runtime(`--container-runtime=containerd`)으로 확인한다.)
- 메트릭 잔존: `container_cpu_cfs_throttled_seconds_total` 을 쓰는 쿼리식(대시보드 target `expr`, alert·rule `expr`)이
  `k8s/`·`scripts/`·`loadtest/scripts/` 에서 0 건이다. (구현 중 정정: 초안은 "과거 리포트 밖 0 건" 이었으나, 새 description 과
  TASKS·evidence·이 계획서가 원인 설명으로 메트릭 이름을 적으므로 산문까지 세는 기준은 맞지 않았다.)
- 로컬은 새 unittest·lint self-test·관련 lint 만 돌리고, 모듈 전체는 PR CI 로 확인한다.

## 완료 조건

- P1~P6 완료, V1~V6 결과를 audit 에 기록, PR CI gate 성공(lint 변경으로 approval-recovery lab 포함), 머지 뒤 main CI 성공.
- `/ship` 의 done 갱신: TASKS D-106 ②·⑩ 상태와 PR 링크, D-065 ⑤ 재확인을 가드로 한다는 사실, PHASE5 이력(#237 병합 기록과
  계획서 아카이브 포함).

## 미해결

- 실제 앱 스택에서의 양성 대조(30개 일치, 27개 전 파티션 할당)는 D-078 재측정의 첫 사용에서 한다. E2E CI 자동 실행은
  D-106-topic-config-race 의 결정대로 하지 않고, 실행할 수 있게만 둔다.
- DLQ intake·quarantine group 의 할당은 범위 밖이다(⑩ 은 업무 group). E2E readiness 가 member·할당 ≥ 1 을 본다.
- D-106 ①⑦⑤(측정 설계)와 ⑥(D-075)은 남는다.
- `k8s/monitoring/*/install.sh` 의 차트 버전 미고정은 이번 패널 수정과 무관해 그대로 둔다. 측정 재현성 문제로 볼지는 재측정 계획에서 정한다.
