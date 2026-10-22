---
grade: M
---
# D-078 README 보강 지표 A~E 와 지연 원자료 도구 (부분 작업)

## 1. 명제와 기존 측정 대조

README 에 실을 MSA 이후 수치 중 비어 있는 것만 과금 세션에 더한다. 기존 증적에 이미 있는 것을 다시 재면
과금 시간만 쓴다. 세션 중에 쿼리를 짜면 그만큼 과금 시간을 쓰므로, 원자료 추출 SQL 과 집계를 실제
스키마에서 미리 검증한다. 세션을 실행하지 않으며 어느 부채도 닫지 않는다.

기준 코드 `4785396`(#228 머지 후 main).

| 후보 | 기존 측정 | 출처 | 처분 |
|---|---|---|---|
| 사가 지연 분포 | 없다. Phase 4 계획 P7 이 "사가 완결 지연을 따로 낸다" 고 요구했으나 증적은 처리율(14.3·16.3·77.3건/초)만 냈고 P7 은 미체크로 남았다 | `task-d002-bc-session.md` P7·P8, `d002bc-gke-20260917.md` §② | **A 로 수집** |
| outbox 발행 지연 분포 | GKE 는 "이벤트당 약 13ms 가 순차 동기 발행과 정합" 이라는 추정뿐. D-075 분석은 로컬 300개 fixture | `d002bc` §②, `D-075.md` | **B 로 수집** |
| 배수 시간 | 폴링 기본값·2분 부하에서 552초. 완화 조건 값은 없다 | `d002bc` §정합성 | **C 로 수집** |
| gateway 오버헤드 | 인증 경로 p95 23.61ms vs 공개 13.67ms(+9.9ms), gateway 전체 p95 26.9ms. 2000m 에서 병목이 order·gateway CPU 로 이동 | `PHASE4.md`, `pr3d-b2-gke-20260915-0854.md` | **제외** — 기존 수치를 쓴다 |
| JVM 힙·GC | 모놀리식 캡처만 | 2026-04-09 리포트 | **D 로 캡처만** |
| HPA 확장 | 모놀리식 1→3·65초 Ready. MSA 는 설정 배포만 | 2026-04-29 리포트, `pr3d-b2-gke-20260914-1320.md` | **E 로 수집** |

| 코드 전제 | 대조 |
|---|---|
| 사가 왕복 | `orders.ordered_at`·`reservation_confirmed_at` 둘 다 order-service 가 기록한다(`Order.java`). 경합 overlay 는 order replicas 1 이라 Pod 간 시계 차이가 없다 |
| 발행 대기 | order·product `outbox_events` 에 `event_type`·`status`·`created_at`·`published_at`(DATETIME(6)) |
| run 경계 | `d002bc-reset.sql` 이 run 마다 orders·outbox 를 지운다. 시간 창 없이 run 단위 전체 행을 내보내면 시간대 문제가 없다 |
| 폴링 | 경합 overlay 는 order 만 500ms·500건, product 는 기본 5s·100건. 왕복에 두 폴링 대기가 한 번씩 들어 있다 |
| HPA | order-service min 1 · max 3 · CPU 60%, scaleUp 안정화 30s |

등급 M: 측정 스크립트·SQL 과 공유 캡처 표를 바꾼다. 앱 코드·정책 변경은 없어 새 ADR 은 없다.

## 2. 작업 항목

- [x] P1. `loadtest/sql/d078-saga-latency.sql`(order), `d078-outbox-latency.sql`(order·product). NULL 은 버리지 않고 내보낸다.
- [x] P2. `loadtest/scripts/d078-latency.py saga|outbox`: nearest-rank p50/p95/p99, NULL 은 제외 건수, 음수 지연 거부, 표본 0 은 `-`. 테스트 `test_d078_latency.py`.
- [x] P3. `d078-capture.py` 에 `d078-jvm`·`d078-hpa`, `captures.md` 표와 원자료 절차.
- [x] P4. `D-078-session-plan.md` S3 10·11(A·B·C), S4 8(E), 시간·비용, 8시간 한도 시 E 를 먼저 접는 규칙.

## 3. 검증 방법

- 단위 테스트 5건(백분위, NULL 제외, 빈 표본, 음수 거부, 파일·이벤트 타입별 집계)과 캡처 테스트 10건.
- 로컬 MySQL 8.0 에 운영과 같은 DB·계정(`scripts/e2e/mysql-init/01-init-databases.sql`)을 만들고 order(v14)·product(v13)
  Flyway 마이그레이션을 실제 적용했다. 지연을 미리 정한 행을 넣고 SQL → `mysql -B` TSV → 집계가 손 계산과 같은지 대조했다.

## 4. 범위 밖

- 세션 실행과 수치. 장애 주입(Pod kill, 브로커 재시작)은 세션 뒤 로컬 E2E 장애 주입 기반(ADR-0031)으로 따로 한다.
