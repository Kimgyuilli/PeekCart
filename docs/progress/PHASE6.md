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
