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
