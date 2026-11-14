# Phase 6 진행 보고서 — 수요 기반

> Phase 6 작업 이력, 주요 결정 사항, 이슈 기록
> 작업 상태 추적은 `docs/TASKS.md §개발 부채 / 작업`

---

## Phase 6 은 무엇이 다른가

Phase 6 도 Phase 5 와 같은 수요형이다. 순서표·작업 목록·Exit Criteria 를 두지 않고, 필요하다고 판단한 시점에 항목을 추가하고
그때 착수한다. 추적 축은 `docs/TASKS.md` 의 D- 단일 표이고 번호는 이어 쓴다(see ADR-0047).

Phase 5 와 다른 점은 셋이다:

1. **단계를 닫는 규칙과 이월 규칙이 처음부터 있다.** 단계는 사용자 결정으로 닫고 그 결정을 ADR 로 남긴다. 단계를 바꿀 때 열린
   행은 ID·본문·상태를 그대로 `docs/TASKS.md` 에 두고, 새 단계 파일의 이월 표가 그 시점의 상태를 적는다. 단계를 닫는 PR 의
   기록은 닫히는 단계 파일 끝의 종결 엔트리에 둔다
2. **문서 구조가 정리된 상태로 시작한다.** 문서의 위치와 역할은 `docs/README.md` 맵에 있다. Phase 5 마감 정리(D-116)가 맵을
   만들고 Layer 1 을 현행으로 다시 썼다
3. **엔트리 형식과 방향이 하나다.** `## YYYY-MM-DD — <제목>` 한 형식으로 오래된 것부터 아래로 쌓는다. 다이제스트가 날짜 있는
   `## ` 헤딩을 엔트리로 세므로 틀 절(이 절, 이월 항목, 작업 이력)의 헤딩에는 날짜를 넣지 않는다

## 이월 항목 (Phase 5 → 6)

이월 시점(2026-10-27)의 상태다. 현황은 `docs/TASKS.md` 가 정본이다. D-116 은 Phase 5 의 마지막 PR 에서 완료 행으로 옮기므로
넘기지 않는다.

| ID | 영역 | 이월 시 상태 |
|---|---|---|
| D-065 | DLQ / Replay | ⏸ 보류(2026-10-25 사용자 결정). 남은 것은 ③ 운영 몫과 ④ 운영 표본 |
| D-078 | Performance / Measurement | ◐ 부분 완료. 남은 것은 ⑦ 결제 콘솔 확인(사용자 직접) |
| D-084 | Payment / Operations | ⏸ 보류(2026-10-25 사용자 결정). 재개 조건은 영구 404 의 PG 발급 무과금 증빙 원천·형식 확보 |
| D-108 | Performance / Measurement | ◐ 부분 완료. 남은 것은 ③ base·운영 프로브 정책(2026-10-25 사용자 결정으로 범위 밖) |
| D-109 | Product / Cache | 🔲 대기. ⑤ 의 GKE 검증이 전제한 "D-108 과 같은 과금 세션"은 #250 세션 범위에 들지 않았다. 검증 방법은 착수할 때 다시 정한다(see ADR-0047) |
| D-110 | Performance / Saga | 🔲 대기 |
| D-111 | Inventory / Concurrency | 🔲 대기 |
| D-112 | Payment / Messaging | 🔲 대기 |
| D-113 | Product / Inventory | 🔲 대기 |
| D-114 | Infra / Measurement | 🔲 대기 |
| D-115 | Loadtest / Harness | 🔲 대기 |

부채 ID 없이 넘기는 판단 항목이다. D- 행을 만들지 않고 사용자 결정을 기다린다.

- 측정 run 의 kafka-lag export 7개(2.2MB)를 남길지. D-116 P1 이 결정 1-3 의 범위 밖으로 보고 남겼다(#251 확인 요청)
- 쓰이지 않는 분산 락 코드(`DistributedLockManager`·`RedissonConfig`·lease 테스트)를 지울지(#256 확인 요청 6)
- ERD·API·토픽·ADR 색인 대조를 lint 로 둘지(#256 확인 요청 7)
- `loadtest/scripts/d002bc-order-create.js` 의 setup 은 순차 로그인이라 USERS 를 늘리면 k6 기본 60초 setup 제한에 걸린다. D-078
  재측정은 이 스크립트를 쓰지 않아 고치지 않았고 추적 행이 없다(`docs/plans/done/D-078-remeasure-plan.md` §9, 계획 `D-116` §6)

---

## 작업 이력

> 엔트리 형식: `## YYYY-MM-DD — <제목>`. 오래된 것부터 아래로 쌓는다(see ADR-0047).

## 2026-10-27 — 루트 README 포트폴리오 개편

[PR259](https://github.com/Kimgyuilli/PeekCart/pull/259) · 계획 `D-117`.
루트 README 를 결과 수치와 대표 사례 중심으로 다시 썼다. 맨 위에 GKE 측정 결과 표와 HPA 그래프, mermaid 시스템 구성도와
주문 사가 시퀀스를 두고, 대표 사례 네 편(MySQL CPU 상한, 재고 경로의 분산 락 제거, outbox 발행 설정, 메시징 신뢰성)을 ADR 과
증적으로 연결했다. 출발점은 2026-10-22 레퍼런스 분석이고, 그때 부족했던 측정은 D-078 로 마쳤다.

- 수치는 증적 원문과 대조한 값만 실었다. 미해결 수치(D-110~D-115 의 사가 왕복 지연, DLQ 비율 등)는 싣지 않기로 사용자가 정했다.
- 문체는 `../blog` 의 blog-post-pipeline humanize 절차로 검사했다(humanize-korean light, 위험도 low). ADR 번호는 README 의
  탐색 링크라 두고 나머지 지적은 반영했다. humanize 가 지운 쉼표 가운데 긴 문장에서 주어가 바뀌는 8곳은 체크리스트 규칙대로 남겼다.
- 검증: 링크 48개, 수치 주장 37건, 다이어그램 화살표 14개를 검사기마다 실패를 먼저 주입해 동작을 확인한 뒤 통과했다.
  `lint:post` 는 ADR 번호 12건 밖에 error 와 warn 이 없다.
- 같은 PR 에서 블로그 글 링크를 더했다(사용자 추가 요청). 사례 A·B·D, 모놀리식에서 MSA 로 절, 개선 표에 PeekCart 학습 기록
  글 9개를 걸었다. 링크마다 HTTP 200, 앵커 id, 회차를 확인했고 글 원문에 README 수치가 그대로 있다. 분산 락을 전제로 한
  학습 기록 9 와 19 는 ADR-0025 이전 판단이라 걸지 않았다.

미충족과 범위 밖:
- `docs/02-architecture.md` 의 다이어그램은 토픽 4개만 그린다. README 다이어그램은 코드에서 새로 뽑았고 02 정합은 따로 다룬다.
- 분산 락 잔존 코드 삭제는 이월 판단 항목에 그대로 있다.
- 블로그 연재 전체 링크는 두지 않았다. 블로그 20~33 에 `peekcart` 태그가 없어 태그 페이지가 0~19 와 회고만 보여 준다.
  사례 C 에 맞는 글은 아직 없다.
- D-117 행은 이 PR 에서 처음 등록한 ID 라 같은 PR 에서 TASKS-done 으로 옮기지 못했다(`docs-preservation-check.py` 의
  새 ID 규칙). 상태 칸에 PR 링크만 달았고, 완료 이동은 머지 뒤 계획서 아카이브와 함께 한다.
- humanize-korean 의 golden 검사가 README 의 날짜 조각 "26)" 을 각주로 읽어 원문끼리 비교해도 FAIL 을 낸다. 이번 판정은
  오탐으로 보고 finalize 를 생략했다.

## 2026-11-13 — 측정 하네스의 멈춤과 오판 차단

[PR261](https://github.com/Kimgyuilli/PeekCart/pull/261) · 계획 `D-115`.
2026-10-26 세션에서 드러난 하네스 문제를 고쳤다. `d078-s3-run.sh` 는 MySQL·Kafka 호출마다 `CALL_TIMEOUT`(기본 300초)에서 끊고,
초기화가 실패하거나 시간을 넘기면 exit 4 로 끝난다. lag 조회가 비거나 실패하면 0 이 아니라 `?` 로 보고 배수를 계속 기다린다.
`d026-run-block.sh` 워밍업 SQL 의 컬럼 오류(`quantity` → `stock`)를 고치고 실패하면 블록을 멈춘다. 세션 스크립트에만 있던 캡처
실행기와 S2 블록 기록을 `d078-capture-shots.sh`·`d078-s2-block.sh` 로 옮겼다(2026-11-13 사용자 결정).

- 시간 제한 범위를 초기화에서 run 스크립트의 MySQL·Kafka 호출 전체로 넓혔다. 디스크가 가득 차면 배수 루프의 조회도 같은
  이유로 멈춰 `DRAIN_LIMIT` 검사에 닿지 못한다.
- 검증: 가짜 `kubectl`·Chrome 으로 정지·빈 출력·컬럼 오류를 주입했다. 고치기 전 스크립트에서 각 실패가 재현되는지 먼저 보고,
  고친 뒤 exit 코드와 경고 줄을 확인했다.

미충족과 범위 밖:
- 실제 GKE 세션에서는 아직 돌리지 않았다. 다음 측정 세션이 첫 실사용이다.
- 시간 초과 때 끊는 것은 로컬 `kubectl` 이고 MySQL 서버 쪽 쿼리는 계속될 수 있다. exit 4 면 다음 run 전에 DB 상태를 확인한다.
- shellcheck 는 로컬에 없어 돌리지 않았다.

## 2026-11-13 — 상품 조회 캐시의 로딩 묶기와 장애 시 Pod 사본 응답

[PR262](https://github.com/Kimgyuilli/PeekCart/pull/262) · 계획 `D-109` · ADR-0046 D2~D6 구현.
`ResilientRedisCache` 가 Redis 캐시를 감싸 `@Cacheable(sync = true)` 의 `get(key, loader)` 에서 처리한다. 같은 키 miss 는 Pod 안에서
한 로딩으로 묶고(기다림 상한 3초, 넘으면 `PRD-005`), 재고는 soft 3초 뒤 한 요청만 갱신하며 상한에 거절되면 hard 5초 안에서 기존 값을
낸다. Redis 가용성 오류 때는 허용 범위(정보 30분, 목록 10분, 재고 5초) 안의 Pod 사본으로 응답한다. DB 진입 상한 1(ADR-0037)과
advice 순서는 그대로다.

- 값의 나이를 DB 를 읽은 시각으로 재려고 Redis 값을 `CacheEnvelope` 로 싼다. 같은 키에 새 형식을 쓰면 롤링 배포 중 옛 Pod 가
  `SerializationException`(500)을 내므로 키 접두사를 `cache:v2:` 로 바꿨다. 배포 중 evict 가 서로의 키를 지우지 못하는 창은 런북 §6.3 에 적었다.
- Boot 의 캐시 메트릭 바인딩은 감싼 캐시를 알아보지 못한다. `CacheMeterBinderProvider` 를 따로 등록해 `cache_gets_total`(S7)을 유지했다.
- 기다림 상한 3초는 ADR 에 없던 값이다. 로딩이 멈췄을 때 같은 키 요청이 요청 스레드를 다 쓰지 않게 한다.
- 검증: 옛 캐시 코드로 새 통합 테스트 7건이 실패하는 것을 먼저 확인했다. 바인더 빈을 빼면 S7 테스트 2건이 실패한다.
  product-service 270건 0 실패.

미충족과 범위 밖:
- GKE 측정(캐시 ON 고부하 503 비율, Redis 중단 시 DB 조회 수)은 다음 과금 세션으로 넘긴다. D-114 가 선행이다. D-109 는 부분 완료다.
- 무응답 Redis 에서는 사본을 내기 전에도 요청마다 명령 타임아웃을 한 번 기다린다. 회로 차단기는 ADR-0046 범위 밖이다.
- 새 지표의 Grafana alert 는 GKE 측정 값을 본 뒤 정한다.

## 2026-11-13 — Docker 29 러너의 원격 이미지 검사 수정

[PR263](https://github.com/Kimgyuilli/PeekCart/pull/263) · 계획 `D-118`.
`ubuntu-latest` 가 ubuntu-26.04(Docker 29.4.2)로 바뀐 뒤 main push 의 이미지 publish 6개가 실패했다. containerd 이미지 저장소에서는
`docker image inspect .Id` 가 manifest digest 라, 기록 ID 를 원격 config digest 와 비교하던 `ci-image-identity.sh verify-remote` 가 같은
이미지를 불일치로 판정했다. 검사가 `docker info` 의 `DriverStatus` 로 저장소를 판정해 containerd 면 push manifest digest 와 비교한다.

- 검사가 서비스마다 `docker info` 를 한 번 더 부르므로 `ci-publication-state-selftest.py` 의 docker 호출 수 단정(18회)을
  manifest 조회 18회와 저장소 확인 6회로 나눴다. 첫 PR CI 가 이 단정에서 실패했다.

미충족과 범위 밖:
- publish 는 PR CI 에서 돌지 않는다. 실제 확인은 머지 뒤 main push CI 다.

## 2026-11-14 — 부하 중 order outbox 발행 저하의 경계 분리

[PR264](https://github.com/Kimgyuilli/PeekCart/pull/264) · 계획 `D-110` · ADR-0045 재검토 조건 1·2.
2026-10-26 GKE 에서 order outbox 가 부하 60초 동안 초당 약 15건만 발행하고 부하 뒤 약 71건으로 빠졌다. 조건 2(자원 경계 먼저)에 따라
opt-in probe `OrderPoolContentionLocalProbeTest` 로 실제 장바구니 추가와 주문 생성 64개 스레드 아래 실제 poller 의 발행률을 쟀다.
풀 10·20·64 에서 부하 중 발행비(부하 중 ÷ 배수 발행률)는 0.03·0.04·0.13 이었다. 풀 64 에서 획득 대기는 0.9ms 로 사라졌지만 점유가
13ms 에서 61ms 로 늘었다. poller 는 이벤트마다 트랜잭션을 쓰는 순차 클라이언트라 포화된 공유 자원을 부하 스레드와 같은 몫으로 나눠 쓴다.

- 결론: 풀 상향과 poller 전용 풀은 단독 수정안이 아니다. B(비동기 pipelining)는 저장 횟수를 줄이지 않아 별도 ADR 을 쓰지 않는다.
  이벤트당 트랜잭션을 줄이는 C(저장 묶기)를 ADR 후보로 D-119 에 넘겼다.
- 테스트 프로파일의 `show-sql` 이 64개 스레드에서 `System.out` 으로 직렬화되어 점유를 부풀렸다. probe 에서 껐고 그 run 은 버렸다.
- 판정 규칙은 계획서 §3 에 결과 전에 고정했다. 1차 결과(풀 64 에서도 회복 안 함)는 규칙에 없던 경우라 재실행 전에 J4 를 더했다.
- 검증: 3 run 모두 풀 크기 단언, 타이머와 DB 발행 수 대조, poller 를 끈 음성 대조, 반복별 정합성을 통과했다. `./gradlew test` 1,525개 0 실패.
- 같은 PR 에서 D-118 계획서를 아카이브하고 완료 행을 옮겼다.

미충족과 범위 밖:
- 점유 증가가 DB 쪽인지 앱 쪽인지(J4)는 미판정이다. 공유 Testcontainers MySQL 은 기본 `my.cnf`(buffer pool 16M, redo 5M, binlog 없음,
  `performance_schema` 없음)라 `performance_schema` 계측 run 3개가 모두 실패했고, 설정은 전 통합 테스트에 영향이라 바꾸지 않았다. GKE 확인은 D-119(D-114 선행).
- 주문번호(날짜 + 32비트)가 같은 날 1만 건에서 약 1.2% 확률로 충돌해 500 이 된다. D-120 으로 등록했다.


## 2026-11-14 — 재고 비관적 락 승격 게이트의 허용 범위

[PR265](https://github.com/Kimgyuilli/PeekCart/pull/265) · 계획 `D-111` · ADR-0048(신규), ADR-0025 Alternative C.
ADR-0025 는 낙관락 충돌률 또는 DLQ 유입이 허용 범위를 넘으면 C 로 승격한다고 적었지만 허용 범위에 숫자가 없었다.
2026-10-25 UTC 세션(D-108 §5)의 contend 유효 run 은 DLQ/주문 0.049%·0.069%(중앙값 0.059%)였고 무효 run 은 0.071%·0.082% 였다.

- 결정: contend 유효 run 2개 이상의 DLQ/주문 중앙값이 0.15% 를 넘으면 C 승격 절차를 시작한다. 충돌률(주문 대비 0.6~0.9%)은 기록만 한다.
  0.15% 는 기준선의 약 2.5배다. run 당 DLQ 5~8건의 Poisson 잡음에서 0.1% 는 기준선 그대로여도 run 1개 약 4.3%, 2개 중앙값 약 1.2% 확률로 넘는다.
- 현재 판정: 미달이라 승격하지 않는다. 넘으면 C 의 DB 비용을 같은 시나리오로 잰 뒤 D1 부분 무효화 ADR 을 쓴다.
- 판정이 무효가 되는 변경: 동시 writer 수(파티션·replicas·listener concurrency), 예약 트랜잭션 구성, 재시도 예산과 backoff, contend 상품 수.
- 검증: ADR 표 수치를 `s3/c0` 원자료에서 다시 셈해 일치했고, 판정 규칙에 값을 주입해 단일 초과·중앙값 초과·표본 부족이 갈리는 것을 확인했다.
- 같은 PR 에서 D-110 계획서를 아카이브했다.

미충족과 범위 밖:
- 0.15% 에는 외부 근거(SLO)가 없다. 기준선 표본은 유효 run 2개다.
- 게이트 계산을 측정 하네스에 넣지 않았다. 다음 contend 세션 계획서가 ADR-0048 산식을 인용해야 한다.
