---
grade: M
---
# D-078 세션 캡처 도구와 세션 범위 확장 (부분 작업)

## 1. 명제와 코드 전제

세션 중에 캡처 경로가 깨진 것을 발견하면 그만큼 과금 시간을 쓴다. 클러스터를 지운 뒤에는 다시 찍을 수
없다. 이 작업은 캡처 경로를 과금 전에 로컬에서 끝까지 돌려 보고, 같은 클러스터로 확인할 수 있는 D-065·
D-092 항목을 세션 계획에 넣는다. 세션을 실행하지 않으며 D-078·D-065·D-092 의 어느 항목도 닫지 않는다.

기준 코드 `577afbd`(#227 머지 후 main).

| 전제 | 코드 대조 |
|---|---|
| 패널 id | 대시보드 4개의 패널 32개에 `id` 가 없다. 단독 화면 URL(`d-solo`)은 `panelId` 로 패널을 찾는다. |
| 배포 래퍼 | `deploy-overlay.sh` 는 `replay-drain-preflight.sh` 를 먼저 돌리고, preflight 는 수집 실패를 fail-closed 로 막는다. 빈 namespace 첫 배포는 막힌다. `DRAIN_PREFLIGHT_SKIP_REASON` 이 사유를 남기고 건너뛴다. |
| D-065 ②⑤ | ② 는 preflight 수집 계층의 실제 스택 관통, ⑤ 는 GKE 실적용(`ALTER_CONFIGS`·실제 좌표 읽기, `task-impl4-c2b-dlq-replay.md` §10 R2). 좌표 읽기(`OriginalRecordReader`)는 replay 경로에서만 불린다. |
| D-092 | notification-service 는 서로 다른 `SLACK_USER_WEBHOOK_URL`·`SLACK_OPS_WEBHOOK_URL` 이 있어야 시작한다. `order.created` 소비가 사용자 webhook 으로 보낸다. Grafana 운영 contact point 는 `grafana-ops-webhook` Secret 의 `url` 을 env 로 받는다. |
| 캡처 형식 | 브라우저 자동화 도구는 스크린샷을 JPEG 로 저장한다. |

등급 M: 공유 대시보드 4개를 고치고 측정 스크립트를 추가한다. 앱 코드·정책 변경은 없어 새 ADR 은 없다.

## 2. 작업 항목

- [x] P1. 대시보드 4개 패널에 1부터 고정 `id`. 패널마다 한 줄만 늘어나게 넣는다(기존 포맷 유지).
- [x] P2. `loadtest/scripts/d078-capture.py`: `annotate`(대시보드 4개에 region annotation), `plan`(절대 시간 고정 단독 화면 URL 과 파일명), `export`(같은 패널의 Prometheus 원 시계열 JSON). 패널은 제목으로 찾아 id 로 가리키고, 못 찾거나 id 가 없으면 실패한다. 테스트 `test_d078_capture.py`.
- [x] P3. `loadtest/captures.md` 에 도구 절차와 `.jpg` 파일명 규칙.
- [x] P4. `D-078-session-plan.md`: S0 실행 기록, S1 Grafana 운영 webhook Secret 과 port-forward, S2·S3 단계 끝 캡처, S4 에 D-065 ②⑤·D-092 canary, S5 진입 조건(캡처·export 저장).

## 3. 검증 방법

- 단위 테스트 10건: 캡처 항목 전부가 id 있는 패널 하나로 풀리는지, 대시보드별 id 유일성, 제목 변경·id 누락 시 실패, URL 의 절대 시간(ms), 변수 치환 후 `$` 잔존 0, annotation 형식, 파일명, 역순 범위 거부.
- 로컬 리허설: Prometheus 3.2.1·Grafana 11.6.0·정적 exporter 컨테이너. 대시보드 4개 import → `annotate`(4개 성공) → `export`(fixture 메트릭이 있는 패널만 시계열이 잡힘) → `plan` URL 을 브라우저로 열어 단독 패널·절대 범위·annotation 구간이 찍히는지 확인. 컨테이너·네트워크 제거.
- promql lint, consistency precheck.

## 4. 범위 밖

- 세션 실행, 증적, 부채 종결.
- D-065 의 replay 개방과 ①③④, 좌표 읽기. D-092 의 Outbox `FAILED` 운영 재발행.
