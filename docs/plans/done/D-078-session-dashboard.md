---
grade: M
---
# D-078 측정 세션 대시보드와 캡처 기준 (부분 작업)

## 1. 명제와 코드 전제

D-078 ⑨ 는 세션마다 README 에 실을 근거 이미지를 남길 기준이 없다는 항목이다. 기준을 세워도 찍을
패널이 없으면 소용이 없다. 기존 대시보드 3개(API·JVM, Kafka lag, Pod 자원)에는 outbox·DLQ·재시도·
스케줄러·MySQL 스로틀·노드 CPU 패널이 없다. 이 작업은 대시보드 하나와 캡처 기준 문서를 둔다. 측정
결과를 만들지 않으며 ⑨ 는 세션에서 실제로 캡처를 남긴 뒤에 닫는다.

기준 코드 `b0c5e8b`.

| 전제 | 코드 대조 |
|---|---|
| 메트릭 이름 | `outbox_publish_seconds_*{result}`, `outbox_backlog{status}`, `dlq_backlog`, `dlq_oldest_age_seconds`, `saga_reservation_result_total{outcome}`, `kafka_listener_delivery_failed_total{topic,cause}`, `kafka_listener_recovered_total{topic}`, `executor_*{name="taskScheduler"}` 을 PrometheusMeterRegistry 로 직접 노출해 확인했다(임시 테스트, 커밋하지 않음). |
| 대시보드 배포 | `k8s/monitoring/shared/kustomization.yml` 의 configMapGenerator 가 `grafana_dashboard: "1"` 라벨로 ConfigMap 을 만들고 Grafana sidecar 가 읽는다. |
| 변수 lint | `observability-promql-lint.sh` 는 등록된 대시보드 파일마다 `application` 변수 집합을 기대 집합과 대조한다. 새 파일은 등록하지 않으면 검사 밖이다. |
| scrape 간격 | 서비스 ServiceMonitor 15s. 기존 대시보드의 `[5m]` 창은 60초~3분 런을 뭉갠다. |
| 카운터 생성 시점 | 재시도 카운터는 첫 실패 때 생성된다. Prometheus `increase()` 는 생성 직후 첫 1건을 놓친다. |

등급 M: 공유 lint 의 기대 집합과 모니터링 kustomization 을 바꾼다. 앱 코드·정책 변경은 없어 새 ADR 은
없다. ADR-0009 S8 의 "alert/dashboard 미도입" 은 결정 당시의 사실이며, 측정 대시보드가 S8 메트릭을
읽는 것은 S8 의 결정(메트릭 정의 위치)을 바꾸지 않는다.

## 2. 작업 항목

- [x] P1. `saga-messaging-dashboard.json`(uid `peekcart-saga-messaging`): Saga·Outbox, 재시도·DLQ(⑤), 스케줄러(⑥), 자원(①②④) 4행 14패널. 창은 `$__rate_interval`. 같은 원장을 Pod 마다 세는 gauge 는 max 로 집계한다.
- [x] P2. kustomization configMapGenerator 와 promql lint 의 대시보드 기대 집합(소비 4서비스)에 등록한다.
- [x] P3. `loadtest/captures.md`: 수치 정본(원값 차분)과 그래프(보조)의 구분, 절대 시간 범위, annotation, 저장 경로와 파일명, 항목별 패널 표.

## 3. 검증 방법

- 패널 쿼리 18개를 rule 파일로 감싸 `promtool check rules` 로 문법 검사.
- Grafana 11.6.0 로컬 컨테이너에 import 해 저장·조회되는지 확인. 실제 시계열 렌더링은 세션에서만 확인할 수 있다.
- promql lint 통과, self-test 통과, `application` 집합을 틀리게 바꾼 사본에서 lint 실패(음성 대조).
- `kubectl kustomize k8s/monitoring/shared` 렌더, namespace·SSOT lint, d092 fixture, verify_alerts 단위 테스트.

## 4. 범위 밖

- 세션 계획서(셀 순서, product replicas 3 고정, 사전 단계와 cleanup)는 후속 작업이다.
- 기존 대시보드의 `[5m]` 창은 바꾸지 않는다. 운영 관찰용이고 이번 측정은 새 대시보드로 본다.
