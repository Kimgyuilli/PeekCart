---
grade: L
---
# D-116-layer1 — 현행 문서 재기준선 (D-116 P4b)

상위 계획: `docs/plans/D-116.md` §3 P4(P4b). 기준 main `c65205d`(P4a #255 머지 뒤). 결정 원문은 `docs/evidence/D-116/decisions.md` 의 3-1(현행 재작성)·3-4·3-8(루트 README)과 기본값(`loadtest/README.md` 재작성)이다. 낡은 서술 목록은 `docs/evidence/D-116/survey-C-docs.md` §5·§6 이다. 사용자 사전 승인(2026-10-26)에 따라 CI 통과와 리뷰 수렴 뒤 머지하고, 새 판단은 보수적 기본값으로 정해 §2.4 와 PR 본문 "확인 요청" 에 남긴다.

## 1. 명제

다음 중 하나라도 성립하면 미완이다.

- `docs/01~07` 에 현재 코드와 다른 서술이 남았다. 조사 C §5 의 낡은 서술 24건, 문서 안 모순 2건(04 §9-6 전략 A, 04 §10-6 분산 락 fallback), §2.1 에서 코드로 찾은 불일치가 대상이다.
- ADR-0027·0045·0046 이 Layer 1 에 없다. 또는 구현되지 않은 ADR-0046 D2~D6 을 현재 동작처럼 적었다.
- P4a 가 남긴 줄이 그대로다. 한 줄 안에 현행과 단계 서술이 섞인 줄(01:5, 03 §6-4·§7-6, 07 §15 표 두 행)과 D-053 아카이브 옛 경로 포인터(02 §5, 05 §11)다.
- 05 의 ERD 가 마이그레이션 최종 스키마와 테이블·컬럼이 다르다.
- ADR 색인 제목이 파일 H1 과 다르거나, "관련 Layer 1 문서" 열이 실제 인용과 다르거나, 결번 0038·0039 와 다음 번호가 없다. 템플릿에 `Partially Superseded` 와 Phase 5·6 이 없다.
- 루트 `README.md` 가 Phase 1~4 기준이다. `loadtest/README.md` 가 Phase 3 모놀리스 절차로 시작한다. 또는 옮긴 원문 줄이 아카이브에 정확히 한 번 들어가지 않았다.
- `/sync` 아카이브가 보존 검사기 오탐으로 막힌다.

등급 L: 보존 검사기(CI 게이트)를 고치고, 하네스가 읽는 ADR 색인과 Layer 1 전체가 바뀐다. 되돌리려면 ADR 이 가리키는 앵커와 하네스의 색인 파서를 함께 봐야 한다.

## 2. 배경

### 2.1 코드 검증 (base `c65205d`)

| 전제 | 확인한 곳 | 결과와 반영 |
|---|---|---|
| 분산 락 | `common` 의 `DistributedLockManager`·`RedissonConfig`, 호출부 grep | 운영 코드 호출 0건이다. product 의 lease 테스트만 쓴다. Redis 역할 서술에서 분산 락을 지우고 잔존 사실은 01 에 한 줄 남긴다 |
| Redis 사용처 | 서비스별 `RedisTemplate`·`@Cacheable`·limiter | gateway(rate limit, 토큰 deny 조회)·user(블랙리스트·family deny)·product(조회 캐시)만 쓴다. 나머지는 `common` 의 Redisson 빈 때문에 연결 설정만 있다 |
| HPA | `k8s/overlays/gke/hpa.yml` | order-service 1~3, gateway 2~4 |
| 프로파일 | `*/src/main/resources/application*.yml` | `application.yml`·`application-local.yml`·`application-k8s.yml`(gateway 는 local 없음). dev·prod 는 없다 |
| outbox 주기 | 서비스 base yml | order·product·payment 500ms·400ms·배치 500, notification 5s·4s·100. ADR-0045 구현됨 |
| MySQL 상한 | `k8s/base/infra/mysql/mysql.yml` | requests 250m, limits 2000m. ADR-0027 구현됨 |
| 조회 캐시 | `CacheConfig`, `ProductReadBulkheadConfig`, 빌드 의존성 | TTL 목록 10m·상세 30m·재고 5s. DB 진입 상한 `peekcart.cache.db-max-concurrency` 기본 1, 초과는 PRD-005. Caffeine 의존성이 없어 ADR-0046 D2~D6 은 미구현(D-109 대기) |
| 토픽 | `kafka-subscription-contract-lint.sh --emit-topics`, `@KafkaListener` | 업무 토픽 15개(ADR-0040 승인 복구 4개 포함, 파티션 3)와 DLQ 15개(파티션 1). 02 §5 의 "11개" 와 04 §8-1 표가 4개를 빠뜨렸다 |
| 컨슈머 그룹 | `DlqTopology`, 그룹 상수 | `<서비스>-svc-<토픽 요약>-group` 이다. 04 §8-4 예시는 맞고 규칙 문장만 다르다 |
| 주문 상태 | `OrderStatus`, `Order`, `OrderEventConsumer`, `OrderTimeoutScheduler` | 결제 실패는 `PAYMENT_REQUESTED` 에서 `CANCELLED` 로 바로 간다(`order.cancelled` reason `PAYMENT_FAILED`). `PAYMENT_FAILED`·`PREPARING`·`SHIPPED`·`DELIVERED` 는 전이 규칙만 있고 진입 경로가 없다. 취소 잡은 셋(결제 요청 후 15분, 예약 미확정 5분, 예약 lease 만료)이다 |
| 예약 lease | product base yml | TTL 30m, sweeper 유예 5m |
| 토큰 유예 | `AuthService`, user `V2` | grace 10초는 DB `grace_until` 이 처리한다. Redis 는 블랙리스트와 family deny 다. 04 §9-11 의 Redis TTL 10초 보관은 낡았다 |
| rate limit | gateway `application.yml`, `FailClosedRedisRateLimiter` | 1초 고정 윈도우다. 인증 전 경로 IP당 10, 상품 조회 IP당 100, 인증 경로 사용자당 40, DLQ 관리자 경로 사용자당 20. Redis 오류는 503(fail-closed) |
| API | 컨트롤러 매핑, gateway 라우트 | 03 §6-6 표와 맞는다. 표에 없는 것은 DLQ 관리자 경로(`/api/v1/admin/deadletter/{서비스}` 조회, `/{id}` 전이)와 승인 복구 prefix(아래 절에 있음)다 |
| 에러 | `ErrorCode`, `GlobalExceptionHandler`, `ErrorResponse` | `SystemException` 클래스가 없다. SYS 코드는 핸들러가 붙인다(미처리 SYS-001, 입력 SYS-003, 권한 SYS-004). 04 §9-12 예시는 ORD-001 에 재고 부족 메시지를 붙였다 |
| 추적 | `MdcFilter`, `MdcRecordInterceptor`, 빌드 의존성 | Micrometer Tracing 의존성이 없다. HTTP 는 서비스가 요청마다 traceId 를 만들고, Kafka 는 outbox trace 헤더를 잇는다(ADR-0008). k8s 프로파일 로그는 JSON 이다 |
| 커버리지 | 루트 `build.gradle` | JaCoCo 플러그인은 있으나 목표치 게이트는 없다 |
| ERD | 서비스별 Flyway 최종 스키마(스크립트 대조) | refresh_tokens(`token` 제거, `family_id` 등 6개 추가), `products.version`, stock_reservations 4개, orders 2개, `payments.reservation_expires_at` 이 빠졌다. `payment_approvals`·`approval_recoveries`·`approval_recovery_audit` 은 ERD 밖이다. 메시징 테이블 5개(`outbox_events`·`processed_events`·`dead_letter_records`·`dead_letter_record_ids`·`shedlock`)는 order·product·payment·notification 에서 컬럼이 같고, notification 도 `outbox_events` 를 가진다(05:8 은 낡았다) |
| 측정 환경 | `d078-gke-20261024.md`, `2026-10-26/session-timeline.md`, `k8s/overlays/gke/README.md` | GKE Standard `asia-northeast3-a`, `e2-standard-4` 2대(S4 는 3대)와 monitoring-pool `e2-standard-2` 1대, loadgen `e2-medium` 에 k6 v2.2.0. 이미지는 GHCR 에서 Artifact Registry 로 승격한다 |
| 측정 결과 | `d078-gke-20261024.md`·`20261025.md` | 경합 run 정합성(음수 재고 0, 품목별 차이 0), HPA 1→3 결정까지 42초. 07 의 자리표시자는 이 근거로 바꾼다 |

### 2.2 B1 역의존 스윕

| 바꾸는 것 | 인바운드 | 처분 |
|---|---|---|
| Layer 1 제목 | ADR-0010:150(02 §4-5·§5·§12 앵커), ADR-0012:183(05 의 "Phase 4 — 서비스별 DB 분리 (MSA)"·Product DB·Notification DB 앵커). 다른 추적 md 에는 Layer 1 앵커 링크가 없다 | 이 제목들은 바꾸지 않는다. 절 번호는 다른 문서가 산문으로 인용하므로 다시 매기지 않는다 |
| 04 §10-7 | `docs/03:85`, `loadtest/reports/TEMPLATE.md:4`(옮긴 "Phase 3 — 부하 테스트 단계" 를 가리킨다) | 03 은 그대로 유효하다(§10-7 에 현재 측정 환경을 더한다). TEMPLATE 은 아카이브를 가리키게 고친다 |
| `loadtest/README.md` | `docs/02:204`(측정 overlay 산문) | 새 README 가 측정 overlay 를 적는다 |
| ADR 색인 | `harness-context.sh`(인덱스 행 형식과 셋째 칸 Status), `consistency-hints.sh`(파일 없는 ADR 번호) | 표 형식과 Status 칸을 유지한다. 결번과 다음 번호는 접두사 없이 적는다 |
| 05 의 반복 ERD 상자 | ADR-0012 의 Product DB·Notification DB 앵커 | 제목은 남기고 메시징 테이블 상자만 공통 절로 모은다 |

### 2.3 PLAN-BLINDSPOTS 점검

- B1·B1b: §2.2. 하네스가 문자열로 읽는 곳은 ADR 색인 하나다.
- B2: ADR 의 목표를 현재로 옮겨 적지 않는다. ADR-0046 은 D1 만 현재고 나머지는 D-109 대기로 적는다. 서술마다 §2.1 의 코드 근거를 둔다.
- B4: 바꿀 절과 근거를 §3 에 파일별로 적는다.
- B11: 낡은 문구 0건 검사는 문구를 하나 되살려 잡히는지 먼저 본다. ERD·API·토픽 대조는 스크립트 출력으로 한다.
- 나머지: 해당 없음. 코드 모듈, 런타임 빈, 이벤트, 측정 경로를 바꾸지 않는다.

### 2.4 해석과 기본값 (PR 본문 "확인 요청")

1. ADR 이 앵커로 가리키는 제목은 단계 표기가 있어도 둔다(05 "Phase 4 — 서비스별 DB 분리 (MSA)"). 앵커가 없는 02 의 "Phase 4 — MSA" 소제목 둘은 현재 구조 이름으로 바꾼다.
2. 계획 내부 식별자(구현 ④-d-2 등)는 제목과 표의 이름 칸에서만 지운다. 본문의 출처 표기(V 번호와 구현 단계)는 이력 근거라 둔다.
3. 05 ERD 는 도메인 테이블을 마이그레이션 최종 컬럼으로 맞추고, 네 서비스에서 같은 메시징 테이블 5개는 한 절에 한 번 적는다. 컬럼의 정본은 마이그레이션이라고 적는다.
4. ADR 색인 "관련 Layer 1 문서" 열은 `docs/01~07` 과 `CLAUDE.md` 가운데 그 ADR 을 인용하는 문서로 정의하고, 재작성 뒤 스크립트로 다시 만든다. `ADR-0011/0014/0033` 처럼 이어 적은 번호도 인용으로 센다.
5. `loadtest/README.md` 는 원문 전체(Phase 3 절과 D-002b'/c Phase 4 절)를 `docs/progress/archive/loadtest-readme-phase3-4.md` 로 옮기고, 지금 쓰는 절차(측정 overlay, 측정 전 게이트, run 규칙, D-078 도구, 캡처·정리)를 새로 쓴다. 옮긴 절의 규칙 가운데 지금도 쓰는 것은 새 README 에 다시 적는다.
6. 07 포트폴리오 문장은 측정 증적에 있는 수치만 쓰고 출처를 단다.
7. 쓰이지 않는 분산 락 코드(`DistributedLockManager`·`RedissonConfig`·lease 테스트)는 지우지 않고 01 에 잔존 사실만 적는다(§6).

## 3. 작업 항목

- [x] P1. 보존 검사기 보정. `visible()` 의 문단 꼬리 탐색을 글머리표 항목에서 끊고 셀프테스트 2건(목록 경계 통과, 한 항목 안 여러 줄 스팬의 실패 유지)을 더한다. 보존 규약의 Markdown 지원 경계 절에 한 문장을 더한다.
- [x] P2. `/sync` 아카이브. `D-116-layer1-archive` 를 `--base c65205d6` 로 옮기고(manifest `docs/preservation/plans-d116-layer1-archive-20261027.json`) INDEX 를 다시 만든다. 상위 계획 §3 의 P4a 칸을 채운다.
- [x] P3. `docs/01-project-overview.md`. 작성 기준 줄, §1 서비스 구조·인프라, §2-1 부하 도구, §3-1 Redis 역할·부하 도구·Flyway, §3-2 분산 락 행(재고 동시성 행으로, see ADR-0025), §4 현재 환경(로컬 compose·minikube, CI, GKE 측정, 이미지 경로, MySQL 상한 see ADR-0027).
- [x] P4. `docs/02-architecture.md`. §4-3 HPA 둘과 MySQL 상한, §5 옛 경로 포인터·토픽 수(15, see ADR-0040)·다이어그램의 부하 도구와 Redis 역할·연결, 앵커 없는 "Phase 4 — MSA" 소제목 둘.
- [x] P5. `docs/03-requirements.md`. §6-3·§6-4·§6-5·§7-2·§7-6 의 단계 표기, §6-6 API 표(DLQ 관리자 경로, 승인 복구 prefix), §7-1 목표값 머리말과 측정 도구, §7-2 재고 차감 시점, §7-4 HPA·프로파일, §7-5 rate limit, §7-6 추적.
- [x] P6. `docs/04-design-deep-dive.md`. §8-1 토픽 표(승인 복구 4개), §8-3 DLQ 토픽과 내부 식별자, §8-4 규칙 문장, §8-5 사가 흐름도(예약과 승인 fence 경로), §9-2·§9-3·§9-10·§9-13 의 단계 표기, §9-6 제목과 본문(예약 모델), §9-8 상태 전이도, §9-9 파티션 설정, §9-10 ShedLock 예시, §9-11 grace, §9-12 예시와 예외 계층, §9-13 조회 캐시(ADR-0037·0046 의 상한 1, D-109 대기), §10-1 폴링 주기(ADR-0045), §10-2·§10-3 의 단계 표기와 식별자, §10-6 Redis 장애 서술, §10-7 현재 측정 환경(ADR-0027, 측정 overlay, 토픽 상태 가드).
- [x] P7. `docs/05-data-design.md`. 옛 경로 포인터, notification outbox, 도메인 ERD 동기화, 공통 메시징 테이블 절, 단계 표기, `(see ADR-0036)`, 인덱스 표(refresh_tokens).
- [x] P8. `docs/06-testing-strategy.md`. §13-1 Redis 행과 식별자, §13-1-a 제목, §13-2 게이트가 없다는 사실, §14 성능 시나리오(현재 k6 스크립트와 조건).
- [x] P9. `docs/07-roadmap-portfolio.md`. §15 스토리라인(증적 수치와 출처), 표의 단계 진화·Saga·재고·Redis 행, 꼬리말.
- [x] P10. ADR 색인과 템플릿. 제목을 H1 과 맞추고, 열의 정의를 적고 다시 만들며, 결번 0038·0039 와 다음 번호 0047 을 적는다. 템플릿 Status 에 `Partially Superseded by ADR-XXXX`, 관련 Phase 에 Phase 5·6 을 더한다.
- [x] P11. 루트 `README.md`. 현재 구조(도메인 서비스 5개와 gateway), 문서 맵·TASKS·ADR·runbook 진입점, 클론 뒤 설정을 둔다.
- [x] P12. `loadtest/README.md`. 원문을 `docs/progress/archive/loadtest-readme-phase3-4.md` 로 옮기고 아카이브 색인에 더한다. 새 README 는 현재 절차를 적는다. `loadtest/reports/TEMPLATE.md:4` 의 04 §10-7 참조를 아카이브로 고친다. `docs/README.md` 의 아카이브 설명을 갱신한다.

## 4. 검증 방법

base 는 `c65205d6` 이다. 실패를 먼저 주입한다.

1. 보존: `docs-preservation-ci.py --base c65205d6` PASS. 셀프테스트 64건 PASS(보정 전에는 새 양성 케이스가 실패했다).
2. 낡은 문구: Layer 1 과 루트 README 에서 문구 목록(nGrinder, 분산 락 역할 서술, 전략 A, Phase 2 이후·Phase 2+·Phase 2~·Phase별, 예정), X → Y, 1~5초, application-dev, 분당 60회, 11개 토픽, Micrometer Tracing, SystemException, Redis TTL 10초 보관, design-archive 옛 경로, v5.0) 0건. 목록의 문구를 하나 되살려 검사가 잡는지 먼저 본다.
3. ERD: 마이그레이션 최종 스키마와 05 ERD 의 테이블·컬럼 차이 0. ERD 에서 컬럼 하나를 빼고 돌리면 차이가 보인다.
4. API·토픽: 컨트롤러 매핑과 gateway 관리자 라우트가 03 §6-6 과 그 아래 절에 모두 있다. `--emit-topics` 의 업무 토픽 15개와 구독이 04 §8-1 표와 같다. 표에서 한 행을 빼면 대조가 실패한다.
5. ADR 색인: 44개 제목이 H1 과 같고 열이 인용 계산과 같다. `harness-context.sh --check --base c65205d6` ok, 다이제스트의 ADR 줄이 그대로다. `consistency-hints.sh` 종료 0. 제목 하나를 바꾸면 대조가 실패한다.
6. loadtest: 옛 README 줄 다중집합과 아카이브 본문(머리말 제외) 줄 다중집합이 같다. 줄 하나를 빼면 실패한다.
7. 링크: 바꾼 문서의 상대 링크와 앵커 대상이 실재한다. ADR-0010·0012 의 Layer 1 앵커 대상이 그대로 있다.
8. CI lint 잡 단계를 ci.yml 원문대로 로컬 실행, `plans-index.sh --check`, consistency precheck, 앵커 이동·삭제 0건, `./gradlew test --continue`(Java 입력 무변경).

## 5. 완료 조건

P1~P12 상자가 채워지고 §4 결과를 audit 에 남기고 PR CI(보존 검사기 수정으로 전체 매트릭스)가 성공하면 머지한다. 머지 뒤 다음 단계가 이 계획을 아카이브하고 상위 계획 §3 의 P4b 칸과 P4 상자를 채운다.

## 6. 범위 밖과 미해결

- 쓰이지 않는 분산 락 코드와 그 테스트의 삭제. 코드 변경이라 따로 판단한다.
- ADR-0046 D2~D6 구현과 그때의 04 갱신은 D-109 다.
- PHASE5 재정렬, Phase 6 개시, 07 §16 의 단계 표기, `docs/README.md`·루트 README 의 Phase 6 반영은 P5 다.
- ADR 본문(immutable)의 옛 줄 번호·절 인용(ADR-0010:84, 138)은 고치지 않는다.
- `CLAUDE.md` 의 낡은 줄(Flyway 경로가 모놀리스 기준)은 Layer 1 밖이라 이 PR 에서 고치지 않는다.
- ERD 와 마이그레이션의 대조 도구는 저장소에 두지 않는다. 재발 방지가 필요하면 부채로 올린다.
