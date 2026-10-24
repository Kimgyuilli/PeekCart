---
grade: L
---
# D-106 재측정 선행 수정 — ⑨③④⑧⑪

## 1. 명제

다음 중 하나라도 성립하면 미완이다.

- drain preflight 가 실제 k8s 리스너(29092)가 아닌 주소로 lag 를 조회하거나, 레코드가 한 번도 쓰이지 않은 파티션(커밋 없음, LOG-END-OFFSET 0) 때문에 정상 스택에서 항상 막힌다.
- 반대로 레코드가 있는 파티션의 미커밋(`-`), 음수, 비숫자 lag 를 0 으로 통과시킨다.
- collector fixture 가 실제 스택과 다른 리스너·offset 형태로 검증해 위 결함을 다시 숨길 수 있다.
- 측정 overlay 렌더에 앱 Deployment 가 있는데 ServiceMonitor 가 없어 세션마다 수동 apply 가 필요하다.
- 측정 절차가 payment Toss smoke 주입 없이 apply 하게 두거나, 빈 스택에서 사용자 시드 전에 인증 게이트를 돌리게 하거나, 배포 직후 run 을 측정 표본으로 쓰게 둔다.

## 2. 배경과 코드 전제

D-106(2026-10-24 GKE 세션 발견 11건) 중 재측정 최소 조건의 무료 수정분이다. 기준 main `1cc1efc`. 아래는 모두 현재 코드에서 직접 확인했다.

| 전제 | 확인한 사실 | 반영 |
|---|---|---|
| ⑨ 브로커 주소 | `scripts/replay-drain-preflight.sh:301` 이 `--bootstrap-server localhost:9092` 고정. `k8s/base/infra/kafka/kafka.yml:62` 의 리스너는 `PLAINTEXT://0.0.0.0:29092` 뿐이다. e2e·signed-gateway compose 도 29092 | P1 |
| ⑨ fixture 가 숨긴 경로 | `docker-compose.collector.yml` 이 e2e 위에서 리스너를 `0.0.0.0:9092` 로 덮어쓴다. `scripts/replay-drain-docker.py:63·125·271` 이 `localhost:9092` 를 쓰고 shim 은 그 argv 만 허용한다. fixture 를 스크립트에 맞춘 구조라 포트 결함이 green 이었다 | P1·P3 |
| ⑨ 미커밋 파티션의 실제 형태 | Spring Kafka 3.3.14 jar 를 javap 로 확인했다. `ContainerProperties` 기본값은 `AssignmentCommitOption.LATEST_ONLY_NO_TX` 이고, `ListenerConsumer.determineCommitCurrent` 는 `auto.offset.reset` 이 latest 일 때만 할당 시 커밋한다. 4서비스는 `auto-offset-reset: earliest` 라 레코드가 없는 DLQ 파티션에는 커밋이 생기지 않는다. Kafka 3.8.1 describe 는 이를 `<group> <topic> 0 - 0 -` 로 낸다(PR212 lab 실관측) | P2 |
| ⑨ 현재 판정 | `replay-drain-preflight.sh:318-320` 은 offset·LEO·LAG 중 비숫자가 하나라도 있으면 exit 1. 정상 스택의 DLQ intake group 은 쓰인 적 없는 DLQ 파티션을 가지므로 ⓑⓒ 가 구조적으로 실패한다. 세션은 포트 결함에 먼저 막혀 이 단계에 닿지 않았다 | P2 |
| ⑨ lab 이 정상형을 못 본 이유 | `replay-drain-docker.py:175` `reset_group` 이 모든 group 에 `--reset-offsets --to-latest --execute` 로 숫자 offset 을 커밋해 baseline 을 만든다. no-commit 음성(`:261-297`)은 빈 토픽(LEO 0)의 `-` 를 fail-closed 로 기대한다 | P3 |
| ⑨ drift guard 자리 | `scripts/replay-entrypoint-lint.sh` drift_check(REPLAY-ENTRY-006)가 preflight 상수를 코드·yml 과 대조하고, self-test 가 sed 변이를 검출한다 | P1 |
| ③ 측정 overlay | `gke-d002a`·`gke-d002bc` 는 `k8s/base/services/<svc>/kustomization.yml`(측정 재사용 진입점: deployment·configmap·secret)을 쓴다. `servicemonitor.yml` 은 운영 `k8s/base/kustomization.yml` 에만 있다. `kubectl kustomize` 로 `gke-d002a`·`gke-d078-contention` 렌더의 ServiceMonitor 0개를 확인했다. Service(`http`, gateway 는 `gateway-metrics`)는 각 deployment.yml 안에 있다. 진입점 소비자는 두 overlay 와 그 하위뿐이다(`minikube-rotation-drill` 은 파일 단위로 참조) | P5 |
| ③ lint 범위 | `scripts/servicemonitor-selector-lint.sh` 는 minikube·gke 만 렌더하고 SM 이름 집합 = CANONICAL 6개를 강제한다. 측정 overlay 는 검사 밖이라 0개여도 통과한다. `kustomize-namespace-lint.sh` 는 측정 overlay 6개를 이미 렌더한다(D-079) | P5 |
| ④ payment Toss | `payment-service/src/main/resources/application-k8s.yml:29` `secret-key: ${TOSS_SECRET_KEY}` 기본값 없음. `k8s/base/services/payment-service/secret.yml` 은 TOSS 키를 의도적으로 두지 않는다(GP-2 #3). `loadtest/README.md` D-002b'/c 절의 apply 절차에 주입 단계가 없다 | P6 |
| ⑧ 인증 게이트 계정 | `scripts/auth-roundtrip-gate.sh:28` 기본 계정 `loadadmin@peekcart.test` 는 `loadtest/sql/d002bc-user-seed.sql` 에만 있다(Flyway 아님). `k8s/overlays/gke/README.md` 4단계와 `loadtest/README.md:161` 은 시드 전에 게이트를 돌리게 적었다 | P6 |
| ⑪ 워밍업 | S2 하네스는 블록마다 첫 회를 버렸지만 S3 경합 run 에는 규칙이 없었다. r1→r2→r3 주문 p50 1,205→701→151ms, r1 주문 수 4,438(r3 10,111) | P6 |

모듈 이동·경계 변경이 아니어서 `PLAN-BLINDSPOTS.md` 는 대상이 아니다. 새 외부 의존성·아키텍처 경계·인프라가 없어 ADR 선행도 필요 없다. ADR-0022 §D2 ⓑⓒ 의 판정 규칙(lag=0, 2회 관측, fail-closed)은 그대로이고 `-` 를 읽는 방법만 정정하므로 Update Log 에 남긴다.

### 트레이드오프

- `-` 해석: LEO 0 은 레코드가 쓰인 적이 없다는 브로커 응답이라 lag 0 과 같다. LEO 를 알 수 없거나(`-`) 0 보다 크면 계속 막는다. 대안인 DLQ 컨테이너 `assignmentCommitOption=ALWAYS` 는 4서비스 런타임을 바꾸면서도 replay 표적 group 의 빈 파티션은 덮지 못한다. 현행 유지는 정상 스택에서 preflight 가 항상 막혀 skip 사유만 남긴다.
- 측정 진입점에 SM 을 넣으면 ServiceMonitor CRD 가 없는 클러스터에서는 측정 overlay apply 가 실패한다. 측정 세션은 관측 스택을 먼저 설치하므로(D-078 세션 S1) 절차에 선행 조건으로 적는다. 15초 scrape 가 앱 Pod 에 더해져 d026 기준선과 조건이 달라진다. 2026-10-24 세션도 수동 apply 로 이미 그 조건이었고, 기준선 비교 설계는 ① 몫이다.
- Toss smoke 값은 매니페스트에 넣지 않는다. 넣으면 k8s 프로파일의 fail-fast 가 무력화되고 stub 값이 렌더에 섞인다(GP-2 #3). 절차로 주입한다.

## 3. 작업 항목

- [x] P1. preflight 의 브로커 주소를 k8s 리스너 `localhost:29092` 로 바꾼다. hermetic kubectl fixture(`scripts/tests/fixtures/replay-drain-kubectl.py:40`)도 9092 argv 만 허용하므로 함께 바꾼다(구현 중 발견, 계획 초안 누락). `replay-entrypoint-lint.sh` drift_check 에 preflight 주소의 포트 = `k8s/base/infra/kafka/kafka.yml` PLAINTEXT 리스너 포트 대조를 추가하고, self-test 에 9092 변이 음성을 넣는다.
- [x] P2. 파서가 `CURRENT-OFFSET=-`·`LOG-END-OFFSET=0`·`LAG=-` 인 행만 lag 0 으로 센다. 그 밖의 비숫자·음수와 LEO>0 의 미커밋은 exit 1 을 유지한다. `scripts/tests/replay-drain-collector-test.py` 에 빈 파티션 양성과 `- - -`·`- 0 0`·`- 1 -` 음성을 추가하고 기존 `unassigned`(`- 15 -`) 음성은 유지한다.
- [x] P3. collector fixture 를 실제 스택에 맞춘다. `docker-compose.collector.yml` 의 리스너·advertised·healthcheck 덮어쓰기를 지워 e2e(=k8s) 29092 를 물려받는다. `replay-drain-docker.py` 의 kafka 헬퍼·shim 기대 argv·console consumer 주소를 29092 로 바꾼다. no-commit 을 둘로 나눈다: 빈 토픽 미커밋 할당(`0 - 0 -`)은 exit 0 인 `never-written-no-commit`, 레코드 1건 뒤 미커밋(`0 - 1 -`)은 exit 1 인 `no-committed-offset`. `replay-drain-docker-test.py` 의 CASES 와 증거 검사(두 행 모양의 실관측)를 갱신한다.
- [x] P4. ADR-0022 Update Log 에 `-` 해석 정정과 브로커 주소 정렬을 한 단락으로 남긴다.
- [x] P5. 측정 진입점 `k8s/base/services/{gateway,user-service,product-service,order-service,payment-service}/kustomization.yml` 에 `servicemonitor.yml` 을 넣는다. `servicemonitor-selector-lint.sh` 대상에 측정 overlay 6개를 더하고, 기대 SM 집합을 "CANONICAL ∩ 렌더된 Deployment 이름" 으로 일반화한다(minikube·gke 는 6개 그대로). self-test 에 측정 overlay 렌더에서 SM 하나를 지운 음성을 더한다.
- [x] P6. 절차를 고친다. `loadtest/README.md` D-002b'/c 절에 (a) apply 전 관측 스택(ServiceMonitor CRD) 선행, (b) apply 뒤 payment Secret 에 `TOSS_SECRET_KEY` smoke 값 주입과 재기동(실제 자격증명이 아니며 외부 연동은 검증하지 않는다), (c) 빈 스택은 배포 → 사용자 시드 → 인증 게이트 순서, (d) 배포·재기동 직후 같은 시나리오의 워밍업 run 을 측정 전에 돌려 버리고 횟수는 세션 계획에 사전 기록한다는 규칙을 넣는다. `k8s/overlays/gke/README.md` 4·5단계를 빈 namespace 첫 배포 순서(drain skip 사유 + GW_URL 없이 → 시드 → 게이트)로 고친다.
- [x] P7. 로컬 lint·self-test·단위 테스트와 실제 Kafka lab 을 돌리고 결과를 audit 에 남긴다. PR 의 일반 CI 와 `replay-collector` 결과는 §완료 조건에서 확인한다(정정: 초안은 이 확인을 P7 에 넣어 PR 전에 닫을 수 없는 항목을 만들었다).

## 검증 방법

| 항목 | 실패 주입 → 확인 |
|---|---|
| P1 | 수정 전 preflight 로 새 drift 검사를 돌려 9092≠29092 위반을 본 뒤, 수정 후 통과를 확인한다. self-test 가 preflight 를 9092 로 되돌린 사본을 검출한다 |
| P2 | 수정 전 파서에 `- 0 -` 행을 먹여 exit 1(정상 스택 차단)을 재현하고, 수정 후 exit 0 을 확인한다. 같은 수정본에서 `- 15 -`·`- - -`·`- 0 0`·`- 1 -`·NaN·음수는 exit 1 이다 |
| P3 | 실제 Kafka 3.8.1 에서 할당됐지만 커밋 없는 빈 토픽이 `0 - 0 -` 로 관측되고 preflight 가 exit 0, 레코드 1건 뒤 `0 - 1 -` 로 관측되고 exit 1 인지 원 출력으로 확인한다. shim 은 29092 외 argv 를 거부한다. 기존 시나리오의 기대값은 그대로다 |
| P5 | 진입점 수정 전 확장 lint 가 측정 overlay 6개에서 SM 누락으로 실패하는지 본 뒤, 수정 후 통과를 확인한다. self-test 가 측정 렌더의 SM 삭제를 검출한다. minikube·gke 결과는 그대로 6개다 |
| P6 | 절차의 키·이름·계정을 코드와 대조한다. `TOSS_SECRET_KEY` 가 `application-k8s.yml` 의 기본값 없는 변수와 같은지, Secret 이름·namespace 가 매니페스트와 같은지, 게이트 기본 계정이 Flyway 가 아니라 시드 SQL 에만 있는지 본다. 실제 스택 확인은 D-078 재측정 세션 몫이다 |
| 공통 | 기존 lint(`kustomize-namespace`·`gateway-exposure`·`networkpolicy-contract`·`observability-promql`·`replay-entrypoint`), `replay-drain-preflight.sh --self-test`, `replay-drain-docker-test.py` 를 전부 통과해야 한다 |

## 완료 조건

P1–P7 이 통과하고, PR 의 일반 CI 와 `replay-collector` 가 성공하고, 병합 뒤 main CI 를 확인하면 D-106 ⑨③④⑧⑪ 을 해소로 기록한다. ⑨ 는 D-065 ② 결함 수정이기도 하지만 실제 스택 재확인은 과금 세션에서 한다.

## 범위 밖

- D-106 ①⑦(측정 설계), ⑥(D-075), ⑩(payment 토픽 설정 원인), ②(스로틀 패널 메트릭), ⑤(VU 포화)는 D-106 잔여로 남긴다.
- GKE 에서 preflight·SM·절차를 실제로 확인하는 일은 D-078 재측정에서 한다.
- `loadtest/README.md` 의 `--load-restrictor LoadRestrictionsNone` 문구가 지금도 필요한지는 손대지 않는다.

## 완료 기록

[PR232](https://github.com/Kimgyuilli/PeekCart/pull/232) 최종 head `534164b` 의 일반 [CI40024829599](https://github.com/Kimgyuilli/PeekCart/actions/runs/40024829599)와 [replay-collector run40024829634](https://github.com/Kimgyuilli/PeekCart/actions/runs/40024829634)가 성공했다. 병합 `6253429` 의 main CI40028498149 는 직후 [PR231](https://github.com/Kimgyuilli/PeekCart/pull/231) 병합 push 에 밀려 gate·publication-state·publish 가 cancelled 됐다. `6253429` 를 포함한 `2c9aa36` 의 [main CI40031226500](https://github.com/Kimgyuilli/PeekCart/actions/runs/40031226500)은 전체 테스트·E2E·gate·여섯 publish 가 성공했다(2026-10-24). §완료 조건을 충족해 D-106 ⑨③④⑧⑪ 을 해소로 기록한다. §범위 밖 항목과 GKE 실제 스택 확인은 이 완료에 포함하지 않는다.
