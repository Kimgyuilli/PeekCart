# Phase 4 진입 전 기술부채 해소 로드맵

> 작성: 2026-06-07 · Phase 3 종결 후, Phase 4(MSA 분리) 착수 전 기술부채 트리아지 결과.
> 입력: 18편 학습 과정에서 도출된 L-001~L-022 + `docs/TASKS.md §개발 부채`(D-002 추적) + 2026-06-10 인프라 도식 정합성 검토.
> 이 문서는 **실행 로드맵**(승격·시퀀싱·게이트)이다. 학습/회고와 별개로, 저장소에서 추적하는 현행 기준은 본 문서와 `docs/TASKS.md` 다.
> 항목이 모두 승격/이관/폐기되면 본 문서의 참조 상태를 함께 정리한다.

---

## 0. 트리아지 의사결정 (2026-06-07 확정)

| 결정 | 결과 | 영향 |
|---|---|---|
| 착수 순서 | **로드맵 문서 먼저 확정 → 구현** | 본 문서가 선행 산출물 |
| JWT 서명 비대칭(RS256/ES256) 전환 | **Phase 4 에서 전환 예정** | L-001/L-002/L-003/L-019 를 Phase 4 "Gateway/IdP 보안 강화" 한 D- 단위로 묶어 이관 (의사결정 해소 → 버킷 2 확정) |
| Order 동시성(L-007/L-013) 선제 적용 | **측정 후 결정** | 버킷 3 유지. 17편 후속 부하 세션 측정을 명시적 게이트로 |

---

## 1. 현황

- **D-001~D-014**: D-001/005/006/007/008/009/010/011/012/013/014 해결 완료, D-003 Won't Fix, D-004 운영지식. → **D-002 만 기존 추적 중**(버킷 2).
- **D-015~D-018**: 2026-06-10 인프라 도식 정합성 검토에서 신규 도출. D-015/017/018 은 버킷 1, D-016 은 버킷 2 로 분류.
- **L-001~L-022**: 전부 미승격 후보 → 본 로드맵에서 3버킷으로 분류.
- **Phase 4 범위**(`07-roadmap-portfolio.md §16`): ① Gradle 멀티모듈 ② 서비스별 DB 분리 ③ Spring Cloud Gateway ④ Choreography Saga ⑤ CQRS 로컬 캐시 ⑥ Cursor 페이지네이션.

---

## 2. 버킷 1 — Phase 4 전에 해결 (우선순위순)

> 원칙: ⓐ 명백한 오류라 즉시 정정 가능(문서/주석/쿼리)하거나, ⓑ Phase 4 대규모 리팩토링 *전에* 안전망으로 깔아두는 게 가치 있는 것.

### 작업 1 — Tier A: 즉시 정정 (완료, D- 승격 절차 없이)

금전 누수·거짓 판정 등 명백한 오류. 한 PR 로 일괄 처리하고 본 로드맵/TASKS 에서 완료 처리.

| ID | 영역 | 내용 | 작업량 |
|---|---|---|---|
| L-018 | Docs/Cost | cleanup 문서↔`loadtest/cleanup.sh` 불일치(`--region`/VM명) → 삭제 실패 → **크레딧 누수**. ADR-0004 §운영 체크리스트 + `k8s/overlays/gke/README.md` 를 `cleanup.sh` 기준으로 일치(가능하면 `bash loadtest/cleanup.sh` 단일 진입점 참조) | 문서 2곳 |
| L-021 | Testing | `loadtest/sql/verify-concurrency.sql` A쿼리 상태필터를 LEFT JOIN ON → INNER JOIN/WHERE 또는 `SUM(CASE WHEN o.id IS NOT NULL ...)` | SQL 1줄 |
| L-022 | Docs | `loadtest/reports/2026-04-29/REPORT.md` CPU 400% 해석을 'Pod request 500m 대비 400% = 2 vCPU = Pod limit 도달' 로 정정 | 문구 1곳 |
| L-016(b) | Deploy | `k8s/overlays/gke/patches/peekcart-deployment.yml` `imagePullPolicy` 주석을 사실(Always)로 정정 | 주석 1곳 |
| L-020(1) | Observability | `k8s/monitoring/shared/kafka-lag-dashboard.json` legend `{{kafka_consumer_group_id}}` → `{{client_id}}` 파생 | 대시보드 1곳 |

> L-016(a digest 고정), L-020(2 group 라벨)는 버킷 2 로 분리(아래).

### 작업 2 — Tier B: CI 품질 게이트 강화 → **D-012 완료**

Phase 4 에서 서비스/매니페스트가 N배로 늘기 *전에* 게이트를 세운다. 세 항목이 "CI 가 검증·강제·산출물까지 본다" 한 묶음.

| ID | 영역 | 내용 |
|---|---|---|
| L-015 | CI/Deliverable | PR 에서 `docker build`(push 없이) + 컨테이너 기동 후 `/actuator/health` 200 smoke test 추가. **18편 "가장 큰 사각지대"** |
| L-014 | CI | `main` branch protection — `build`(및 신설 image job)를 required status check 로 지정 |
| L-017 | Deploy/CI | `kubectl kustomize overlays/<env> \| kubeconform` 또는 `--dry-run=server` 게이트로 `metadata.namespace` 누락(default NS 누출) 차단 |

### 작업 3 — Tier C: 발행 경로 resilience 하드닝 → **D-013 완료**

발현은 드물지만 메시지 유실/워커 잠식을 차단. 비용 거의 0.

| ID | 영역 | 내용 |
|---|---|---|
| L-012 | Resilience | `KafkaConfig` recoverer `setFailIfSendResultIsError(true)` — DLQ 발행 실패 시 원본·DLQ 양쪽 유실 차단 |
| L-010 | Resilience | `OutboxPollingService.kafkaTemplate.send().get(timeout)` + producer `max.block.ms`/`delivery.timeout.ms` 명시. ⚠️ `@SchedulerLock(lockAtMostFor=PT5M)` 과 **사이클 상한 정합**까지 함께(BATCH_SIZE=100 순차 `.get()` → 건당 상한이 곧 사이클 상한 아님). 단순 한 줄 아님 |

### 작업 4 — Tier D: 관측성 선결 표면 (선택, 시간 여유 시) → **D-014 완료**

버킷 2 결정(L-006 fallback, 처리량)의 *측정 기반*. 선행하면 Phase 4 결정이 데이터 기반이 됨. 우선순위 최하.

| ID | 영역 | 내용 | 상태 |
|---|---|---|---|
| L-005 | Observability | `RedisCacheManager.enableStatistics()` — 캐시 적중률. Spring Boot 자동 바인딩 사용(수동 `CacheMetricsRegistrar` 미도입). L-006 발동 빈도 추적 선결 표면 | ✅ PR #38 |
| L-009 | Observability | `OutboxPollingService` Micrometer 계측(`outbox.backlog` gauge `status=pending\|failed` / `outbox.publish` Timer `result` 태그) — 처리량 부채화 판단 선결 표면 | ✅ 완료 |

> Tier D 는 ADR-0009 §Decision 표에 surface 행(cache/outbox) 추가를 동반. Phase 4 멀티모듈 표 갱신과 묶어도 무방하므로 "선택".

### 작업 5 — Tier E: 인프라 도식 정합성 검토 신규 부채 (2026-06-10)

도식 검토 중 발견된 repo 내부 계약 불일치. Phase 4 멀티모듈/서비스 분리 전에 닫아야 배포·관측성 문서가 더 이상 드리프트하지 않는다.

| D- | 영역 | 내용 | 권장 처리 |
|---|---|---|---|
| **D-015** | Deploy/CI | CI 는 `ghcr.io/${owner}/peekcart` 를 push하지만 K8s base/GKE image rewrite 원본은 `ghcr.io/kimgyuilli/peakcart` 를 참조. 이미지 repository 명 계약이 깨져 GHCR → Artifact Registry 복사 또는 base 배포가 실패할 수 있음 | 단일 image repository 이름 결정 후 CI/K8s/문서 일괄 정렬. 가능하면 CI lint 로 `IMAGE_NAME` 과 Kustomize image source 불일치 검출 |
| **D-017** | Observability | Grafana alert rule 과 values 주석은 존재하지만 Slack contact point/provisioning 파일이 없음. "Grafana alert → Slack" 경로가 repo 선언 상태로는 완성되지 않음 | Slack contact point/notification policy 를 provisioning 하거나, Slack 발송을 앱 내부 알림으로 한정하도록 values/문서/도식 정정 |
| **D-018** | Docs | `loadtest/reports/2026-04-29/REPORT.md` 는 Redis PVC 를 1Gi 로 기록하지만 현재 매니페스트는 512Mi. 리포트와 배포 SSOT 드리프트 | 리포트 환경 표를 매니페스트 기준(512Mi)으로 정정하거나, 실제 세션 당시 값이 달랐다면 "당시 실측 / 현행 매니페스트" 를 분리 표기 |

**버킷 1 권장 시퀀스**: 기존 완료분(작업1~4) 이후, 작업5는 D-015 → D-017 → D-018 순으로 처리. D-015 는 배포 실패 가능성이 있어 최우선.

---

## 3. 버킷 2 — Phase 4 로 이관

자연 해소·자연 묶음. Phase 4 task 에 편입하며 해당 시점에 D- 승격하거나 task 항목으로 흡수.

| ID | 영역 | Phase 4 연결고리 | 편입 대상 |
|---|---|---|---|
| **L-001/L-002/L-003/L-019** | Security/Obs | **JWT RS256 전환 확정** → Gateway + 시크릿 저장소(KMS/Vault) + DelegatingPasswordEncoder + Reuse Detection(`family_id`) + 인증 실패 관측성을 **한 D- 단위**로 | Phase 4 ③ Gateway |
| L-004 | Observability | Slack 단일채널 → 운영 알림 채널 재설계(PagerDuty 등) | Phase 4 운영 관측성 |
| L-006 | Resilience | Redis 조회 캐시 fallback(`CacheErrorHandler`) — Redis 가 서비스간 공유 인프라화 시. L-005(작업4) 선결 | Phase 4 ⑤ CQRS/캐시 |
| L-008 / L-011 | Operations | `outbox_events`/`processed_events` retention(§9-7 코드화) — DB 서비스별 분리 시 N배. 같은 작업 단위. 보존기간=멱등성 창 상한 결정 동반 | Phase 4 ② DB 분리 |
| L-016(a) | Deploy | gke `images.newTag` digest 고정 — L-015 의 CI 이미지 운반 자동화와 맞물림 | Phase 4 ① 멀티모듈 |
| **D-016** | Deploy/Release | GHCR → Artifact Registry 복사(`crane copy`) + `kustomize edit set image` + `git restore` 가 수동 절차. 서비스 수가 늘면 image promotion 재현성·감사성이 급격히 낮아짐 | Phase 4 ① 멀티모듈 / 배포 자동화 |
| L-020(2) | Observability | consumer group 독립 라벨(kafka-exporter 배포/Micrometer tag) — consumer group N개화 시. ADR-0009 §Decision 표 Kafka lag surface 행 추가 | Phase 4 ① 멀티모듈 |
| **D-002** | Performance | 2차 병목(MySQL 풀/Redis 락 contention) 분리 — Order Service 분리 후 격리 측정 | Phase 4 ① 분리 후 재측정 |

---

## 4. 버킷 3 — 보류 (측정 후 결정)

분류 자체가 실측 데이터에 달린 항목. **게이트: 17편 후속 부하 세션**에서 아래 시나리오를 돌려 실측이 나오면 모놀리스 단계 선제 승격, 아니면 Phase 4 분리 시 자연 해소(L-007)/필수 승격(L-013).

| ID | 영역 | 측정 게이트 | 승격 시 동반 결정 |
|---|---|---|---|
| L-007 | 주문 *생성* 경로 "락 ⊃ 트랜잭션" 불변식 + 재고 차감 retry 정책 미정 | 동일-상품 경합 시나리오에서 재고 차감 `PRD-004`/`OptimisticLockingFailureException` 응답률 유의 관측. 현재 구현은 lock/optimistic-lock 충돌을 서버 재시도 없이 409로 노출하는 fail-fast 정책 | `REQUIRES_NEW` 분리 시 부분커밋 보상, 주문단위 다중 락 선획득(productId 정렬 데드락 방지), 또는 bounded retry(짧은 backoff+jitter) 도입 여부 |
| L-013 | 주문 *상태 전이* 동시성(`Order @Version` 부재) | `payment.completed`/`payment.failed` ↔ 타임아웃이 같은 주문에 동시 적용되는 시나리오에서 상태 모순 실측 | **트리거 충돌 우선순위(결제완료 vs 취소)** + 패배 consumer 재처리/DLQ 정책 |

> 현재 정합성은 `@Version` + 격리수준의 *조건부* 백스톱으로 유지 중(오버셀링/모순 미관측). 선제 적용 여부만 보류.

---

## 5. D- 승격 매핑 요약

| 신규 D- | 묶음 | 버킷 | 상태 |
|---|---|---|---|
| (승격 없음) | Tier A 5건(L-018/021/022/016b/020-1) | 1 | 즉시 정정 후 폐기 |
| **D-012** | CI 품질 게이트(L-014/015/017) | 1 | 완료 |
| **D-013** | 발행 경로 resilience 하드닝(L-010/012) | 1 | 완료 |
| **D-014** | 관측성 선결 표면(L-005/009) | 1 | 완료 |
| **D-015** | CI/K8s image repository 이름 계약 불일치 | 1 | 완료 |
| **D-017** | Grafana alert Slack contact point/provisioning 부재 | 1 | 완료 |
| **D-018** | Redis PVC 용량 리포트 드리프트 | 1 | 완료 |
| Phase 4 task | 보안 묶음(L-001/002/003/019), L-004/006/008/011/016a/020-2, D-002, D-016 | 2 | Phase 4 편입 |
| (보류) | L-007, L-013 | 3 | 측정 게이트 대기 |

---

## 6. 다음 단계

1. ~~신규 버킷 1(D-015/D-017/D-018) 처리. D-015 는 배포 계약 불일치라 최우선.~~ ✅ 완료 (도식검토 부채 완결, PR #40/#41/#42).
2. 버킷 2(Phase 4 이관) 진행 시 D-016 을 멀티모듈/배포 자동화 작업에 편입.
3. 버킷 3 게이트(17편 후속 부하 세션)는 별도 task 로 추적.
