---
grade: M
---
# D-107-deploy-topics — 배포 래퍼의 kafka 선행 기동과 토픽 사전 생성

기준: 2026-10-25, main `6905952`, 브랜치 `debt/d107-deploy-topics`. 선행: [#238](https://github.com/Kimgyuilli/PeekCart/pull/238)
(approval-recovery lab 사전 생성, D-107 첫 조각), [#237](https://github.com/Kimgyuilli/PeekCart/pull/237)(기동 뒤 토픽 재적용),
[#239](https://github.com/Kimgyuilli/PeekCart/pull/239)(측정 전 토픽 상태 가드). 사용자 결정(2026-10-25): D-107 의 재검토 조건을
풀고 배포 전 토픽 사전 생성으로 처리한다. 브로커 자동 생성 끄기는 하지 않는다.

## 1. 명제

다음 중 하나라도 성립하면 미완이다.

- `scripts/deploy-overlay.sh` 가 kafka 와 앱을 한 번의 `kubectl apply -k` 로 올려, 빈 브로커에서 선언 토픽이 생기기 전에
  컨슈머가 뜰 수 있다(D-107 ①②③ 의 경로).
- 래퍼가 만드는 토픽 목록·파티션 수가 E2E·lab 과 다른 출처다(`kafka-subscription-contract-lint.sh --emit-topics` 가 아니다).
- 토픽 생성 실패, 생성 뒤 파티션 수 불일치(부재 포함), kafka 선행 적용 실패, kafka 롤아웃 실패·시간 초과 중 하나가 일어나도
  앱 apply 가 실행되거나 exit 0 이다. 정본 emit 실패와 kafka 리소스가 없는 overlay 가 exit 2 가 아니다.
- 이미 떠 있는 스택에 재배포할 때 기존 토픽 때문에 실패하거나, 래퍼가 브로커 설정(`auto.create.topics.enable`)이나
  토픽 config 를 바꾼다.
- 기존 순서(drain preflight, apply, 인증 게이트)나 Exit 코드 계약(0 성공, 1 위반·실패, 2 사용법·전제 오류)이 바뀌거나,
  새 단계를 사유 없이 건너뛸 수 있다.
- 위 동작을 가짜 kubectl 로 실제 래퍼를 돌려 확인하는 테스트가 없거나 CI lint job 에서 돌지 않는다.
- 배포 문서가 이 단계와 적용 overlay, raw `kubectl apply -k` 우회 한계, config 적용 주체(KafkaAdmin)와 측정 전 가드의 관계를
  적지 않는다.

등급 M: Gradle 모듈과 k8s 매니페스트는 바꾸지 않는다. 배포 래퍼의 동작 계약(새 단계, 막는 조건, 건너뛰기 변수)이 바뀌고
운영 문서·runbook 이 그 래퍼를 부른다. 되돌림은 이 작업 파일 안에서 끝난다. ADR 선행 신호(새 외부 의존성, 경계 변경,
새 인프라) 없음. ADR-0022 §D3 의 "preflight 뒤 apply" 서술은 그대로 참이라 고치지 않는다.

## 2. 배경

### 2.1 코드 검증

| 전제 | 확인 | 결과 |
|---|---|---|
| 래퍼의 현재 흐름 | `scripts/deploy-overlay.sh` | 인자 확인(exit 2) → drain preflight(`DRAIN_PREFLIGHT_SKIP_REASON` 로 건너뜀, 막으면 exit 1) → `kubectl apply -k <overlay>` → `GW_URL` 이 있으면 인증 왕복 게이트. 토픽 단계 없음 |
| kafka 배포 단위 | `k8s/base/infra/kafka/kafka.yml`, `k8s/base/kustomization.yml`, `k8s/base/namespace/namespace.yml` | PVC `kafka-pvc`(라벨 없음), Deployment `kafka`(`app.kubernetes.io/name: kafka` 등), Service `kafka`(라벨 없음)가 앱과 같은 base 에 있다. Namespace `peekcart` 도 라벨 없음 |
| overlay patch | `k8s/overlays/gke/patches/pvc-storageclass.yml` 외 측정 overlay 2곳 | gke 계열은 PVC 전부에 `storageClassName: standard-rwo` 를 더한다. PVC spec 은 생성 뒤 바꿀 수 없으므로, patch 가 빠진 PVC 를 먼저 만들면 기본 StorageClass 가 다른 클러스터에서는 뒤의 overlay apply 가 실패한다 → overlay 렌더에서 골라야 한다 |
| 렌더 가능성 | `kubectl kustomize`(v1.35.3, 오프라인, 기본 load restrictor)로 overlay 9개 렌더 | 앱 overlay 8개(`kustomize-namespace-lint.sh` 대상: gke, minikube, gke-d002a, gke-d002a-mysql-500m/2000m, gke-d002bc, gke-d002bc-mysql-2000m, gke-d078-contention) 렌더 성공, 전부 Deployment `kafka` 포함. 출력은 Namespace 가 맨 앞이고 kafka 는 Service, PVC, Deployment 순이다. `minikube-rotation-drill` 은 기본 restrictor 에서 실패하고 kafka 가 없다(드릴 스크립트가 raw apply) |
| 래퍼 사용처 | grep `deploy-overlay` | `docs/02-architecture.md` 배포 순서 4단계(minikube·gke), `k8s/overlays/gke/README.md` 4·4', `docs/runbooks/dlq-recovery.md` §6-R 롤백 배포, `docs/runbooks/slack-webhook-secret.md`, D-078 S4. 측정 overlay 는 `loadtest/README.md` D-002b'/c 가 `kubectl kustomize --load-restrictor LoadRestrictionsNone` 출력을 `kubectl apply -f -` 로 넘기고, `loadtest/scripts/d026-run-block.sh` 가 gke-d002a 계열을 같은 식으로 적용한다. 그 flag 는 지금 렌더에 필요 없다(위 행) |
| 토픽 정본 | `bash scripts/kafka-subscription-contract-lint.sh --emit-topics` | 30줄(업무 15 × 3, DLQ 15 × 1). 3서비스 `TopicBuilder.name(..).partitions(..)` 에서 유도하고 선언은 전부 `.replicas(1)` |
| E2E·lab 방식 | `scripts/saga-e2e-smoke.sh` `topic_create`, `scripts/approval-recovery/lab.py` `TOPIC_CREATE_LOOP` | 브로커 healthcheck(`kafka-broker-api-versions.sh`) 통과 뒤, exec 1회 안에서 토픽마다 `--create --if-not-exists --partitions <선언> --replication-factor 1`(lab 은 첫 실패에서 중단), 이어 전체 `--describe` 1회의 요약 줄로 파티션 수를 대조 |
| k8s kafka readiness | `kafka.yml` readinessProbe, Kafka 3.8.1 `BrokerServer.scala`·`SocketServer.scala` | probe 는 29092 TCP. 3.8.1 은 고정 포트 리스너 소켓을 `Acceptor.start()` 에서야 열고, 그 호출(`enableRequestProcessing`)은 "the broker to be unfenced" 대기 뒤다 → Ready 는 브로커가 요청을 받는 상태다. `rollout status deploy/kafka` 가 compose healthcheck 의 자리를 맡는다 |
| Kafka 접근 관례 | `scripts/kafka-topic-state-guard.py`, `scripts/replay-drain-preflight.sh` | `kubectl -n <ns> exec deploy/kafka -- /opt/kafka/bin/... --bootstrap-server localhost:29092`. 광고 주소가 `kafka:29092`(Service)라 pod 안 CLI 도 그 Service 로 다시 접속한다 → Service 도 kafka 와 함께 먼저 있어야 한다 |
| 라벨 셀렉터 동작 | kubectl v0.35.3 `pkg/cmd/apply/apply.go`, cli-runtime `pkg/resource/builder.go` | `apply -k <dir> -l <sel>` 는 kustomize 출력에 `FilterByLabelSelector` 를 건다. 걸러진 객체가 0개면 "no objects passed to apply" |
| config 적용 주체 | spring-kafka 3.3.14 `KafkaAdmin.addOrModifyTopicsIfNeeded`, 3서비스 `modify-topic-configs: true`, `KafkaTopicReconciler`(#237) | 이미 있는 토픽은 `checkPartitions`(같으면 그대로)와 `checkTopicsForConfigMismatches` 뒤 `incrementalAlterConfigs` 로 선언과 다른 키만 SET 한다. 사전 생성 토픽은 소유 서비스 기동 때 4키가 동적으로 바뀌고 나머지 3키는 브로커 기본값이 선언과 같다. 가드는 실효값을 보므로 통과한다. 토픽이 이미 있으면 createTopics 의 `TopicExistsException` 경합(③)이 생기지 않는다 |
| 측정 전 가드 | `scripts/kafka-topic-state-guard.py`(#239), `loadtest/README.md` D-002b'/c §절차 | run 직전 30개 파티션·7키 실효값·업무 group 전 파티션 할당을 판정한다. 배포 단계와 별개의 진입 시점 검사 |
| drain preflight 와 측정 overlay | `replay-drain-preflight.sh` `SERVICES=(order product payment notification)` | notification 이 없는 gke-d002bc 계열은 `notification-service-secret` 조회가 항상 실패해 막힌다 → 측정 overlay 를 래퍼로 배포하려면 `DRAIN_PREFLIGHT_SKIP_REASON` 이 필요하다 |
| 네임스페이스 변수 | 래퍼 70행, preflight·가드 | 래퍼는 `${NAMESPACE:-peekcart}`, preflight·가드는 `PEEKCART_NAMESPACE`. 매니페스트는 `peekcart` 고정(`kustomize-namespace-lint.sh`). 새 단계는 래퍼 관례를 따른다 |
| CI 배선 | `.github/workflows/ci.yml` lint job, `scripts/ci-lint-wiring-lint.py` | lint job 은 `azure/setup-kubectl@v4` 와 pyyaml 설치 뒤 "Run CI policy lints" 에서 `test_kafka_topic_state_guard.py` 를 돈다(167행). wiring lint 의 대상은 `scripts/*-lint.sh` 뿐이라 새 python 테스트에 요구가 없다(이름을 `-lint.sh` 로 짓지 않는다) |
| 가짜 kubectl 선례 | `scripts/tests/fixtures/replay-drain-kubectl.py`, `scripts/tests/replay-drain-collector-test.py` | argv 를 엄격히 대조하고 호출을 JSONL 로 남기는 fixture 를 PATH 앞에 두고 실제 진입점을 돈다 |

### 2.2 대안과 선택

**kafka 만 먼저 올리는 방법.**

| 안 | 내용 | 처분 |
|---|---|---|
| (가) 라벨 셀렉터 | `kubectl apply -k <overlay> -l <라벨>` | 기각. Namespace·PVC·Service 에 라벨이 없어 base 객체 4개에 새 라벨 규약을 더해야 한다. 한 셀렉터로 잡으려면 Namespace 에도 같은 키가 필요하고, 8개 overlay 렌더와 live 객체 metadata 가 함께 바뀐다 |
| (나) base kafka 를 따로 적용 | `kubectl apply -k k8s/base/infra/kafka` | 기각. overlay patch 가 빠진 PVC 가 먼저 생긴다(2.1) |
| (다) 렌더에서 골라 적용 | 래퍼가 `kubectl kustomize <overlay>` 출력에서 Namespace 와 PVC `kafka-pvc`·Deployment `kafka`·Service `kafka` 를 종류·이름으로 골라 `kubectl apply -f` | 채택. 매니페스트를 바꾸지 않고, 래퍼·가드·preflight 가 이미 `deploy/kafka` 이름에 기댄다. 고를 kafka 객체가 하나라도 없으면 exit 2 로 막는다. 대가는 래퍼 안의 렌더 선택 코드(표준 라이브러리 python)다. 테스트는 pyyaml 로 따로 읽어 같은 렌더의 객체와 대조한다 |
| (라) kafka 를 별도 kustomization 으로 분리 | base·overlay 구조 변경 | 기각. 8개 overlay 와 측정 스크립트를 함께 고쳐야 한다 |

**생성·대조 방식.** E2E·lab 과 같다. 정본 목록을 stdin 으로 넘겨 exec 1회 안에서 토픽마다 `--create --if-not-exists` 를
돌리고(첫 실패에서 중단, lab 과 같다), 전체 `--describe` 1회의 요약 줄로 대조한다. 선언보다 많거나 적거나 없으면 막는다
(가드와 같은 기준). 재배포에서도 CLI 30회를 돈다. E2E 실측은 CLI 1회 약 1초이고 GKE kafka 는 CPU 상한 500m 라 더 걸릴 수
있다. 이미 있는 토픽을 건너뛰는 최적화는 하지 않는다.

**대기.** `kubectl -n <ns> rollout status deploy/kafka --timeout=300s`(인증 게이트 대기의 기본값 300s 와 같다). 시간 조정 변수는
두지 않는다. 래퍼가 멱등이라 다시 실행하면 된다.

**Exit 매핑.** 0·1·2 의 뜻은 유지한다. 정본 emit 실패·형식 불명, python3 부재, 렌더 실패, 렌더에 kafka 리소스 없음은 2.
kafka 선행 적용 실패, 롤아웃 실패·시간 초과, 생성 실패, describe 실패, 파티션 불일치는 1.

**건너뛰기.** `TOPIC_PRECREATE_SKIP_REASON` 에 사유를 주면 이 단계 전체를 건너뛰고 `!!` 줄로 사유와 위험을 남긴다
(drain preflight 와 같은 방식). kafka 없는 overlay 의 exit 2 메시지가 이 변수를 안내한다.

**적용 범위.** 래퍼에 넘긴 overlay 전부다. 운영 문서의 gke·minikube 에 더해, 측정 절차 `loadtest/README.md` D-002b'/c 의
배포 명령을 래퍼로 바꿔 측정 overlay 도 덮는다. 레인 A 가 측정 절차를 고치므로 그 파일은 배포 명령 한 곳만 바꾼다.

### 2.3 남는 창 (PLAN-BLINDSPOTS B12)

배포 시점 검사다. 래퍼가 끝난 뒤 누가 토픽을 지우거나 바꾸면 막지 않는다. raw `kubectl apply -k` 나 렌더를 바로
`kubectl apply -f -` 로 넘기는 배포는 이 단계를 거치지 않는다. 두 경우 모두 측정 run 직전의 토픽 상태 가드가 잡는다.

## 3. 작업 항목

- [ ] P1. `scripts/deploy-overlay.sh`: drain preflight 와 `kubectl apply -k` 사이에 토픽 사전 생성 단계를 넣는다.
  `--emit-topics` 결과 확인(토픽<TAB>파티션, 10줄 이상) → `kubectl kustomize` 렌더에서 Namespace·kafka 3종 선택(kafka 3종 중
  하나라도 없으면 exit 2) → `kubectl apply -f` → `rollout status deploy/kafka --timeout=300s` → `exec -i deploy/kafka -- sh -c`
  생성 루프 → `exec deploy/kafka -- kafka-topics.sh --describe` 와 awk 대조. `TOPIC_PRECREATE_SKIP_REASON` 건너뛰기.
  헤더 주석에 흐름·사용법·Exit 예시·우회 한계를, 단계 주석에 이유·config 주체·가드 관계를 적는다.
- [ ] P2. `scripts/tests/fixtures/deploy-overlay-kubectl.py`: argv 를 엄격히 대조하는 가짜 kubectl. `kustomize` 만 실제 kubectl 로
  오프라인 렌더(`KUBECONFIG=/dev/null`)하고 `apply -f`·`apply -k`·`rollout status`·`exec deploy/kafka` 를 흉내 낸다. exec 는
  `/opt/kafka/bin/` 을 가짜 디렉터리로 바꿔 래퍼의 실제 생성 루프를 로컬 sh 로 돌린다. 같은 파일이 `kafka-topics.sh` 이름으로
  불리면 브로커 상태(JSON)를 다루는 가짜 CLI 다(`--if-not-exists` 없이 기존 토픽을 만들면 실패, describe 는 3.8.1 형식).
  `get` 은 실패한다(drain preflight 차단 재현). 호출은 JSONL, 실패 주입은 JSON.
- [ ] P3. `scripts/tests/test_deploy_overlay.py`(unittest): §검증 방법 V1~V6. `.github/workflows/ci.yml` lint job 의
  `test_kafka_topic_state_guard.py` 다음 줄에 배선한다.
- [ ] P4. 문서: `docs/02-architecture.md` 배포 순서 4단계에 사전 생성 단계·적용 범위·우회 한계·config 주체·가드 관계,
  `k8s/overlays/gke/README.md` 4단계 주석 두 줄, `loadtest/README.md` D-002b'/c §스택의 배포 명령을 래퍼로 바꾸고 이유 한 문장
  (그 밖의 측정 절차는 레인 A 범위라 고치지 않는다).

## 검증 방법

가짜 kubectl 로 실제 래퍼를 돌린다. kind·minikube·실제 Kafka 는 쓰지 않는다.

- V1 빈 클러스터(gke, gke-d002bc): 가짜 브로커가 비어 있다. exit 0, 호출 순서가 `kustomize` → `apply -f` → `rollout status` →
  exec(생성 루프) → exec(describe) → `apply -k` 이고, 생성 호출은 전부 describe 앞이다. 끝난 브로커 상태가 emit 30개·선언
  파티션과 같다. `apply -f` 객체를 pyyaml 로 읽으면 정확히 Namespace/peekcart·Service/kafka·PVC/kafka-pvc·Deployment/kafka 이고
  같은 렌더의 객체와 같으며 Namespace 가 맨 앞이다.
- V2 재배포: 30개와 `__consumer_offsets` 가 이미 있다. exit 0, 같은 순서, 브로커 상태 불변.
- V3 실패 주입. 각각 exit 코드와, 그 뒤 단계와 `apply -k` 가 0회인지 본다.
  - a. 토픽 생성 실패(`order.created`): 1, describe 0회.
  - b. 파티션 불일치. `payment.requested` 1파티션(자동 생성 상태), `order.created.dlq` 3파티션이 이미 있다: 1, 메시지에
    `<토픽>: <실제> (선언 <N>)`.
  - c. describe 실패, 빈 출력, 형식이 다른 출력: 1.
  - d. kafka 롤아웃 실패(시간 초과): 1, exec 0회.
  - e. kafka 선행 적용 실패: 1, rollout 0회.
  - f. 정본 emit 실패·형식 불명·10줄 미만(래퍼를 임시 레포 사본에서 돌리고 lint 를 가짜로 둔다): 2, kubectl 0회.
  - g. kafka 없는 overlay, 렌더 실패 overlay: 2, `apply` 0회. kafka 없는 경우의 메시지는 건너뛰기 변수를 안내한다.
  - h. drain preflight 차단(`DRAIN_PREFLIGHT_SKIP_REASON` 없음, 가짜 kubectl 의 secret 조회 실패): 1, kafka 단계 0회.
- V4 적용 범위: `kustomize-namespace-lint.sh` 의 overlay 목록 전부에서 kafka 선행 객체가 V1 과 같은 넷이고 그 overlay 렌더의
  객체와 같다(롤아웃 실패를 주입해 선행 단계까지만 본다). gke 의 PVC 에는 `standard-rwo` 가 있다.
- V5 건너뛰기: `TOPIC_PRECREATE_SKIP_REASON` 이 있으면 호출은 `apply -k` 하나, 사유 줄 출력, exit 0.
- V6 사용법: 인자 없음·없는 디렉터리는 2 이고 kubectl 0회(기존 계약).
- V7 변이(로컬, audit 기록): 대조 제거, `apply -k` 를 사전 생성 앞으로 이동, `--if-not-exists` 제거, 롤아웃 실패 무시,
  정본 실패를 exit 1 로 매핑. 각각에서 해당 테스트가 실패해야 한다.
- 정적: `bash -n scripts/deploy-overlay.sh`, `python3 -B scripts/ci-lint-wiring-lint.py`, lint job 의 관련 lint. PR CI lint job 에서
  새 테스트가 돈다.

## 완료 조건

- P1~P4 완료, V1~V7 결과를 audit 에 기록, PR CI 성공.
- TASKS D-107 행과 PHASE5 기록은 root 가 마지막 기록 PR 에서 한다.

## 미해결

- 실제 클러스터 관통(GKE 첫 배포의 생성·대조 출력)은 하지 않았다. D-078 재측정의 첫 배포가 첫 실사용이고, 측정 전 토픽 상태
  가드가 결과를 확인한다.
- raw apply 경로는 남는다: `loadtest/scripts/d026-run-block.sh`(gke-d002a 계열, 읽기 경로 측정), 회전 드릴(kafka 없음), 손으로 친
  `kubectl apply -k`. 측정 블록에서는 가드가 잡는다.
- 목록은 래퍼를 실행한 체크아웃의 소스에서 나온다. 배포 이미지와 같은 커밋에서 실행해야 한다(가드도 같다).
- 브로커 자동 생성 끄기(D-107 의 다른 처분 후보)는 하지 않는다. 소유 서비스 초기화 실패 처리와 새 ADR 이 필요한 별도 사안이다.
- 재배포마다 CLI 30회 비용이 든다. 문제가 되면 describe 결과로 없는 토픽만 만들도록 줄일 수 있다.
