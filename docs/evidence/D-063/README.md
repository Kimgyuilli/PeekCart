# D-063 실제 로컬 평가 증거

2026-10-17 무료 로컬 Prometheus 3.2.1 / Grafana 11.6.0에서 실제 2개 exporter를 스크레이프하고 원래 평가 간격 1분과 for를 유지했다. run3는 138개 관측, 실제 2099.6초 뒤 PASS/exit 0이며 본인 컨테이너 4개와 네트워크 정리 후 project 잔존 0개를 확인했다. 이는 로컬 evaluator 증거이며 필수 CI·최종 독립 검증·병합 완료를 대신하지 않는다.

## 실행 및 실패 이력

| 실행 | 소스 | 세션 / project | 결과 |
|---|---|---|---|
| run1 | b47bd1bffe771208e17c7279bdc1ebc7ab3bbe64 | 55392 / peakcart-alert-1792191745 | UID 41자 provisioning 거절, 평가 0건, exit 1 |
| run2 | c406204c9cede0ea36c5eac0761c78c2949a4ad8 | 80307 / peakcart-alert-1792192171 | 112개 관측 뒤 instance 상태 어휘 오류, 최종 복귀 미실행, exit 1 |
| run3 | 460217ec3dd4b869f87d1199191d49a57b006b68 | 83995 / peakcart-alert-1792194274 | 전체 전이 및 최종 Normal 복귀 PASS, exit 0 |

실행 명령은 `python3 scripts/monitoring/verify_alerts.py --output /private/tmp/peakcart-d063-20261017-run3 --run`이며 각 실행은 root의 전역 heavy 슬롯 단독 승인 뒤 수행했다. 이전 실패는 원인 수정·독립 재검토 후 새 소스로 실행했으며 재시도 성공으로 덮지 않았다.

## 실제 관측

| 규칙 | 원래 for | sustained Pending부터 첫 Alerting 관측까지 | 검증 인스턴스 |
|---|---:|---:|---|
| compensation backlog | 900초 | 904.25초 | order-service |
| DLQ backlog | 600초 | 602.81초 | notification/order/payment/product |
| outbox failed | 300초 | 301.60초 | notification/order/payment/product |
| cache fallback get | 600초 | 602.81초 | product-service의 product/products/productStock |
| cache fallback evict | 0초 | Pending 없음 | product-service의 product/products/productStock |

15초 간격 API 표본의 관측 차이이며 정확한 내부 타이머 지연 측정값으로 확대하지 않는다. evict 즉시는 pending 대기 없이 다음 평가 주기에 발화한다는 의미다. 최종 표본은 다섯 규칙 모두 inactive, 모든 개별 인스턴스 Normal, 존재하는 query vector의 값 0이다. NoData를 정상으로 인정하지 않았다. 원본 API 인스턴스 라벨과 상태 전이는 `run3/instance-transitions.json`, 모든 query/vector/평가 시각은 압축 원문에 있다.

잘못된 application/status/operation의 양성 series를 실제로 조회하고 정상 규칙이 발화하지 않는 음성 대조를 수행했다. 두 exporter의 instance 라벨을 확인했다. 90초 짧은 breach는 Pending 후 정상으로 복귀했고 지속 breach는 원래 for가 지난 뒤 발화했다. cache의 rate/increase 5분 창이 사라진 뒤 최종 정상 복귀를 관측했다.

## 정본과 최종 통합 경계

실행 source `460217e` 전체 파일 SHA256은 `b8effdda5853605f47bc9b13db2c9006bdaf35893c06a5c72bdc99266c38a09e`, 추출 alerts.yaml은 `757f36e3e81c672e135ef0d072997e3e2ea43008569f7bdbb803149273b8630a`다. 각각 `evaluated-grafana-alerts.yml`, `provisioned-alerts.yaml`에 보존했다. 실제 Grafana provisioning API와 모델/labels/condition/for를 대조했다.

최종 통합은 D-084의 별도 approval-stale 규칙에 recovery_pending을 추가하므로 전체 파일 해시가 달라질 수 있다. 독립 검증자는 실행 source `460217ec3dd4b869f87d1199191d49a57b006b68`와 통합 `8928ed4a6e116855d923cb17b0f0eca0ccbca5c9`를 이중 YAML 파싱해 대상 다섯 UID의 전체 rule model이 모두 같음을 확인했다(보고 원본 `/private/tmp/peakcart-saga-review.md`, Integrated local head 절). 이 대조는 전체 파일 동일 주장과 다르며 최종 PR head에서도 재확인해야 한다. 본 실험은 새 RECOVERY_PENDING 경보의 실제 평가 증거가 아니다.

## 바이너리와 원문

- Prometheus: `prom/prometheus@sha256:6927e0919a144aa7616fd0137d4816816d42f6b816de3af269ab065250859a62`
- Grafana: `grafana/grafana@sha256:62d2b9d20a19714ebfe48d1bb405086081bc602aa053e28cf6d73c7537640dfb`
- Exporter Python: `python@sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4`

각 manifest에 실제 API version, 이미지 inspect/digest와 컨테이너 환경이 있다. `artifact-index.json`은 압축 전 원문 SHA256/크기를 기록한다. gzip은 원문 보존용이며 `gzip -dc run3/observations.jsonl.gz`로 읽을 수 있다. cleanup 및 execution 로그, 실제 provisioning 응답과 음성 대조 query 원문도 보존한다.

manifest의 `local-fixture` 사용자/암호는 이 loopback 실험만을 위한 공개 고정값이고 Python 이미지의 GPG_KEY는 공개 키 fingerprint다. 실 PG·사용자 인증 비밀을 사용하지 않았다. 원문 로그에서 개인키/Bearer/AWS/GitHub token 패턴이 없음을 확인했다.

## 검증하지 않은 범위

GKE ServiceMonitor discovery, ConfigMap sidecar 전달, namespace/network routing, 실제 앱 메트릭 방출, 운영 장애, 외부 Slack/PagerDuty 전달과 GKE 바이너리 동등성은 검증하지 않았다. `environment-equivalence.md`는 실행 전 동등성 판단이다. `independent-pre-execution-review.md`는 실행 전 검토 이력과 후속 run3 실제 원문 독립 검증을 각각 구분해 보존한다. 초기 baseline 첫 두 표본은 평가 전 빈 alert 인스턴스였으며, baseline 마지막과 최종 복귀 표본에서 strict Normal을 확인했다. 138개 모두가 Normal이었다는 의미가 아니다.

## 최종 통합 증거 연결

실행460의 대상5rule full parsed model과 최종 PR180 head2dde324를 독립 재대조해 완전 일치를 확인했다. PR CI38987011757 및 main1178071의 CI38988468800은 전체 gate를 통과했다. 전체 파일 hash 동일성이나 D084 RECOVERY_PENDING 알림 실제 평가로 확대하지 않는다. 상세 병합·검증 증거는 `docs/plans/done/D-063.audit.md`에 있다.
