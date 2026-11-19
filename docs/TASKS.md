# PeekCart — Task 관리

> 현행 작업을 **PR 단위**로 추적한다. PR 1개 = 부채/기능 1묶음.
> 상태: `🔲 대기` / `🔄 진행 중` / `◐ 부분 완료` / `✅ 완료` / `⏸ 보류`

## 문서 맵

| 대상 | 경로 |
|---|---|
| 전체 문서 맵 (레이어·폴더·하네스 앵커·증적 위치) | `docs/README.md` |
| Phase 1~3 task 이력 (아카이브) | `docs/progress/TASKS-archive-phase1-3.md` |
| Phase 4 task 이력 (아카이브) | `docs/progress/TASKS-archive-phase4.md` |
| 완료된 D- 행 | `docs/progress/TASKS-done.md` |
| Phase 4 설계·실행 로드맵 (ADR 시퀀싱·구현 순서) | `docs/progress/archive/phase4-design-roadmap.md` |
| 진입 전 부채 해소 로드맵 (버킷 1 완결, 버킷 2/3 이관·게이트) | `docs/progress/archive/phase4-prep-debt-roadmap.md` |
| 부채 후보 분류·승격 매핑 (L-001~L-022) | `docs/progress/archive/phase4-prep-debt-roadmap.md §2~5` |
| Phase별 작업 이력 | `docs/progress/PHASE1.md` · `PHASE2.md` · `PHASE3.md` · `PHASE4.md` · `PHASE5.md` · `PHASE6.md` |

---

## 현재 단계: Phase 6 — 수요 기반 (로드맵 없음, 2026-10-27~, see ADR-0047)

> **Phase 1~4 로 사전 로드맵은 끝났다.** Phase 5 에 이어 Phase 6 도 순서표가 없다. 필요하다고 느낀
> 시점에 항목을 추가하고 그때 착수한다.
>
> **추적 축은 아래 §개발 부채 / 작업 표 하나다.** 기존 D- 번호를 그대로 이어 쓴다
> (다음 번호 = 이 표와 `docs/progress/TASKS-done.md` 의 최대 번호 + 1). "부채 해소"와 "새로 하고 싶은 것"을 표에서 구분하지 않는 것은
> 의도다 — 수요 기반에서는 둘 다 *지금 필요하다고 판단한 작업* 이라는 같은 성격이고,
> 표를 나누면 어느 쪽에 적을지부터 정해야 해서 추가 비용이 생긴다.
>
> **항목 추가 규칙**: `영역` · `요약`(왜 필요한지가 요약에 들어간다) · `묶음`(발견 맥락 —
> 세션/PR/문서) · `상태`. 로드맵이 없으므로 **동기를 요약에 적어 두지 않으면 나중에
> 왜 적었는지 복원할 수 없다.** 착수 시 `/plan`, 머지 시 `/done`.
>
> **단계 종결 조건 없음.** 순서표 소진이 아니라 사용자 결정으로 닫고, 열린 행은 그대로 다음 단계로 넘긴다(see ADR-0047). 열린 표가 곧 현황이다.
>
> 열린 항목은 아래 표에 있다. 완료된 행은 `docs/progress/TASKS-done.md` 로 옮긴다.

지난 단계: Phase 5(2026-09-22~2026-10-27)는 이력 `docs/progress/PHASE5.md` 와 완료 행 `docs/progress/TASKS-done.md` ·
Phase 4 는 `docs/progress/TASKS-archive-phase4.md` ·
Phase 1~3 은 `docs/progress/TASKS-archive-phase1-3.md`.

---

## 개발 부채 / 작업 (Tech Debt & Backlog)

> 여기서는 **열린 행**만 추적한다. 완료된 행은 `docs/progress/TASKS-done.md`(D-001~D-012 상세는 `TASKS-archive-phase1-3.md §개발 부채`)에 있다.

### Live / 신규

| ID | 영역 | 요약 | 묶음 | 상태 |
|---|---|---|---|---|
| D-065 | DLQ / Replay | **DLQ replay 가 개방된 적이 없고 개방 전제 검증 다섯 가지가 남아 있다** — kill-switch `APP_DEAD_LETTER_REPLAY_ENABLED` 는 4개 ConfigMap 모두 `"false"` 이며 계획 §9 4단계(닫힌 채 배포 → P22 증적 → 게이트웨이 관리자 라우트 → 개방 → 리허설)는 미실행. 미검증·미수행: ① 게이트웨이 경유 진입점 실제 스택 관통(ADMIN 토큰 → `/api/v1/admin/deadletter/*` → `ROLE_ADMIN`, ROLE_USER 403 — V-40, 라우트 계약과 엔드포인트 가드를 각각만 관측) ② `scripts/replay-drain-preflight.sh` 의 수집 계층(`kubectl exec` 기반 DB·Kafka 조회, 판정 계층만 fixture 관통) ③ `record_kind`·`root_record_id` `NOT NULL` contract 마이그레이션(계획 §10 R1, 그때까지 N16 은 코드 경로로만 강제) ④ 원장 `original_timestamp` NULL 비율(P22, 운영 표본 0 → 미측정, 처분은 ADR-0022 §D6 분기표) ⑤ GKE 실적용(계획 §10 R2 — `ALTER_CONFIGS` 권한·실제 좌표 읽기). 개방 세션에서 함께 처리될 묶음 | 블로그 25편 작성 중 재확인(2026-10-12) · 출처 PHASE4 ④-c-2b-4a 미충족 2·4 · ④-c-2b-4b 미충족 · 계획 §9·§10 R1/R2 | ⏸ 보류(2026-10-25 사용자 결정: 운영 환경이 엮인 ④ 운영 표본과 ③ 의 운영 writer 정지·전환 증빙·운영 DDL 적용을 범위에서 뺀다). ③ 코드 몫 [#244](https://github.com/Kimgyuilli/PeekCart/pull/244): 4서비스 record_kind·root_record_id NOT NULL contract(DEFAULT 없음)와 DDL 전 가드, 위반 행은 history 없이 거부하고 자동 보정하지 않음, main `a1f831a` CI40132854886 성공. ②⑤ 는 D-078 재측정 세션 S4(2026-10-25, [#246](https://github.com/Kimgyuilli/PeekCart/pull/246))에서 실제 스택으로 재확인해 끝났다: ② `replay-drain-preflight.sh` 단독 실행과 배포 래퍼 경로 모두 exit 0, ⑤ 토픽 상태 가드가 토픽 30개(payment 6개 포함)의 파티션·계약 7키와 업무 group 27개 할당 일치. 남은 것은 ③ 운영 몫과 ④ 운영 표본이다. 이전 진행: writer #201·권한 예외 #211·signed gateway [#210](https://github.com/Kimgyuilli/PeekCart/pull/210)·수집 판정 [#189](https://github.com/Kimgyuilli/PeekCart/pull/189) 무료 범위 부분 병합. 최신 #189 main7915f26 CI39619377710 성공/6publish 성공(한 payment image job API 상태 지연, 내부 Complete job 성공). 실제 MySQL/Kafka adapter [#212](https://github.com/Kimgyuilli/PeekCart/pull/212) 무료 범위 병합(main e45d321 CI39636215009/6publish 성공); 제약 #202·Flyway #207 최신 병합 검증, 운영 표본/GKE/NOT NULL 실제적용 미완. replay 비개방 유지. GKE 확인(D-078 세션 2026-10-24, [#230](https://github.com/Kimgyuilli/PeekCart/pull/230)): ② 실제 스택 실행 **실패**(`replay-drain-preflight.sh` Kafka `localhost:9092` 고정, k8s 29092라 DLQ group lag 조회 전부 실패·exit 1) · ⑤ effective 토픽 30개 중 24개 선언값 일치, payment 토픽 6개 브로커 기본값. 두 결함은 D-106 ⑨⑩ 으로 추적. ① 은 [#210](https://github.com/Kimgyuilli/PeekCart/pull/210) 에서 로컬 signed stack 으로 실제 HTTP 관통을 확인해 끝났다(`docs/plans/done/D-065-signed-gateway.md` 완료 기록, 2026-10-24 정정). 남음: ③ NOT NULL contract 적용 · ④ 운영 표본(운영 환경 부재로 차단) · ②⑤ 수정 후 재확인(과금 세션, ⑤ 의 원인은 D-106 ⑩ [#237](https://github.com/Kimgyuilli/PeekCart/pull/237) 로 보완했고, 재확인은 파티션 수와 group 할당까지 보는 측정 전 토픽 상태 가드 [#239](https://github.com/Kimgyuilli/PeekCart/pull/239) 로 한다) |
| D-084 | Payment / Operations | **보호 승인 장기 미결의 증빙 기반 복구 경로가 없다** — PG 호출 전 중단 후 영구 404는 UNRESOLVED/APPROVAL_HELD를 유지한다. 기존 코드가 수동 FAILED로 닫은 보호 원장도 자동 복구하지 않는다. ADR-0035의 read-only 진단 후 PG·예약·환불 대사를 수행하고, 권한·증빙·감사 및 재고 1회 복구를 검증하는 운영 복구 절차/진입점이 필요하다. 시간 경과나 단순 운영자 확인만으로 해제하지 않는다 | D-060 후속 보강 [#176](https://github.com/Kimgyuilli/PeekCart/pull/176) · ADR-0035 Limits/Rollout diagnostics | ⏸ 보류(2026-10-25 사용자 결정: PG·운영이 엮인 항목은 이번 범위에서 뺀다. 재개 조건: 영구 404 의 PG 발급 무과금 증빙 원천·형식 확보). 이전 진행: 부분 병합 [#181](https://github.com/Kimgyuilli/PeekCart/pull/181) — 서버 직접 PG 확정 결과 복구 구현. 영구 404의 신뢰 가능한 PG 발급 증빙 원천·형식 미확보로 전체 미완 |
| D-109 | Product / Cache | **상품 조회 캐시가 같은 키 miss 를 묶지 않고, Redis 장애 때 내줄 값이 없다** — ADR-0046 D2~D6 구현: ① 같은 키 로딩을 Pod 안에서 묶기(`CacheConfig` 의 `RedisCacheManager.builder` 는 non-locking writer 라 `sync=true` 만으로는 묶이지 않음) ② `productStock` 만료 전 갱신(soft 값은 계획서, hard 5초 유지, 갱신이 상한에 거절돼도 hard 상한까지 기존 값) ③ Redis 장애 때 Pod 의 마지막 값으로 응답(정보 30분·목록 10분·재고 5초, 넘으면 묶인 DB 조회가 상한을 지남) ④ 관측 지표(묶인 로딩·장애 시 stale 응답·재고 갱신 거절)와 `docs/runbooks/redis-cache-fallback.md`·`docs/04-design-deep-dive.md` 갱신 ⑤ 검증: 로컬 Testcontainers(같은 키 동시 요청 N 개에 DB 조회 1회, 갱신 거절 시 503 0, Redis 중단 시 응답과 DB 조회 수 상한), GKE 는 D-108 과 같은 과금 세션에서 캐시 ON 고부하 503 비율(같은 워밍업 조건)과 Redis 중단 시 DB 조회 수 | D-078 재측정 S2(2026-10-25) "상한 4 증액 근거 있음"의 처분(사용자 결정: 상한 1 유지) · ADR-0046 · 관련: ADR-0026, ADR-0036, ADR-0037 | ◐ 부분 완료: 남은 것은 ⑤ 의 GKE 측정(캐시 ON 고부하 503 비율, Redis 중단 시 DB 조회 수). D-114 선행이 걸려 다음 과금 세션에서 잰다. ①~④ 와 ⑤ 로컬 검증은 [#262](https://github.com/Kimgyuilli/PeekCart/pull/262)(2026-11-13): 같은 키 miss 묶기(기다림 상한 3초), 재고 soft 3초 갱신, Redis 장애 시 Pod 사본 응답, 지표 3개, 런북·설계 문서 갱신. 계획에서 범위가 늘었다: 값의 나이를 DB 읽은 시각으로 재려고 Redis 값을 봉투로 싸고 키 접두사를 `cache:v2:` 로 바꿨다(롤링 배포 중 옛 Pod 의 직렬화 500 방지). 감싼 캐시의 S7 메트릭 바인딩을 다시 이었다 (`docs/plans/D-109.md`) |
| D-119 | Performance / Outbox | **부하 중 order outbox 발행이 이벤트당 트랜잭션 구조 때문에 굶는다** — D-110 로컬 probe 에서 poller 는 포화된 공유 자원을 64개 부하 스레드와 같은 몫으로 나눠 써 부하 중 발행비가 0.03(풀 10)~0.13(풀 64)이었다. 풀을 늘리면 획득 대기는 사라지지만 점유가 늘어 회복하지 않는다. 이벤트당 트랜잭션을 줄이는 C(저장 묶기)를 ADR 로 설계하고(ADR-0012·0020·0022 의 발행·커밋 실패 의미 재정의 포함), 그 입력으로 점유 증가가 MySQL 서버 안(커밋·잠금)인지 앱 CPU 인지 GKE MySQL 서버 지표로 확인한다(로컬은 Testcontainers 기본 `my.cnf` 라 미판정) | `docs/evidence/D-110/README.md` · ADR-0045 재검토 조건 1 · 선행: D-114 | ◐ 부분 완료: 남은 것은 GKE J4(부하 중 점유 증가가 MySQL 서버 안인지 앱 CPU 인지)와 GKE 에서의 효과. 다음 과금 세션(D-109 ⑤ 와 같은 세션)에서 잰다. C 설계·구현·로컬 검증은 [#269](https://github.com/Kimgyuilli/PeekCart/pull/269)(2026-11-19): ADR-0049(Accepted, ADR-0045 Partially Superseded) — ack 받은 이벤트를 사이클 끝에 한 문장으로 PUBLISHED 기록, 중복 범위 상한 1건에서 `batch-size`, `published_at` 은 기록 시각. 로컬 probe(사전 등록 판정 K1): 풀 10 따라잡기 비율(부하 중 발행/s ÷ 주문/s) 기준 코드 0.017 에서 0.986 (`docs/evidence/D-119/`). 범위 변화: 사용자 결정(2026-11-19)으로 GKE J4 를 다음 과금 세션으로 넘기고 구현과 로컬 판정을 더했다 (`docs/plans/D-119.md`) |
