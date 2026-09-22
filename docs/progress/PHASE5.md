# Phase 5 진행 보고서 — 수요 기반

> Phase 5 작업 이력, 주요 결정 사항, 이슈 기록
> 작업 상태 추적은 `docs/TASKS.md §개발 부채 / 작업`

---

## Phase 5 는 무엇이 다른가

Phase 1~4 는 **착수 전에 순서표가 있었다.** 무엇을 언제 할지 로드맵
(`docs/07-roadmap-portfolio.md §16`)에 미리 적어 두고 그 순서를 소진하는 방식이었고,
순서가 비면 단계가 닫혔다.

Phase 5 에는 그 순서표가 없다. **필요하다고 판단한 시점에 항목을 추가하고 그때 착수한다.**

그래서 세 가지가 바뀐다:

1. **Exit Criteria 가 없다.** 소진할 목록이 없으므로 단계가 종결되지 않는다.
   `docs/TASKS.md` 의 열린 표가 곧 현황이다
2. **추적 축이 하나다.** D- 번호 단일 표. "부채"와 "신규 작업"을 나누지 않는다 —
   수요 기반에서는 둘 다 *지금 필요하다고 판단한 작업* 이라는 같은 성격이다
3. **동기를 기록하지 않으면 복원되지 않는다.** 로드맵이 근거를 대신 붙들어 주던 것이
   사라졌으므로, 항목을 추가할 때 **왜 필요한지**를 `요약` 에, **어디서 발견했는지**를
   `묶음` 에 적는다. 이것이 Phase 5 에서 유일하게 늘어난 규율이다

## 이월 항목 (Phase 4 → 5)

| ID | 영역 | 상태 |
|---|---|---|
| D-027 | Harness / Cost | 🔄 ② 완료([#128](https://github.com/Kimgyuilli/PeakCart/pull/128)) · ①③ 재개 조건 미충족 대기 |
| D-030 | Observability / Ops | 🔲 Outbox `FAILED` ↔ DLQ Slack 채널 분리 + DLQ 적재량 메트릭 (L-004 승격, [#133](https://github.com/Kimgyuilli/PeakCart/pull/133)) |

---

## 작업 이력

> 엔트리 형식은 PHASE4.md 와 동일: `## <제목> ([PR](...), YYYY-MM-DD)`

## Phase 5 기반 세팅 — 로드맵 축 제거 (2026-09-22)

Phase 4 가 종결([#133](https://github.com/Kimgyuilli/PeakCart/pull/133))되면서 사전 로드맵이
소진됐다. Phase 5 는 순서표를 다시 만들지 않기로 했으므로, **문서가 순서표를 전제하던
자리들을 먼저 걷어냈다.** 코드 변경 0.

무엇을 했나:

- `docs/TASKS.md` — `## 현재 단계` 를 Phase 5 로 교체. 그 자리를 차지하던 Phase 4
  설계(A1~A4.5)·구현(①~⑥) 표는 **아래 `## Phase 4 — MSA 분리` 섹션으로 합쳤다**
  (두 곳에 나뉘어 있던 Phase 4 기술이 한 곳이 됐다)
- 부채표 제목을 `개발 부채 / 작업 (Tech Debt & Backlog)` 로. **표를 나누지 않는 것이
  결정이다** — 수요 기반에서 "부채 해소"와 "신규 작업"은 같은 성격이고, 표가 둘이면
  항목 추가 때마다 분류부터 해야 한다. 다음 번호는 D-031
- `docs/progress/PHASE5.md` 신설 — `harness-context.sh` 가 `현재 단계` 줄에서 Phase
  번호를 추론해 `PHASE{N}.md` 를 읽으므로, 이 파일이 없으면 다이제스트가 파서 고장으로 떨어진다
- `docs/07-roadmap-portfolio.md §16` — Phase 5 항목을 추가하되 **작업 목록도 Exit
  Criteria 도 적지 않았다.** 적으면 그게 로드맵이 되기 때문이다. 현황은 TASKS.md 를 가리킨다

**검증**: `scripts/harness-context.sh --check` = `ok` (단계가 Phase 5 로 읽히고 PHASE5.md 를
찾는다) · `scripts/plans-index.sh --check` exit 0.

**부수 정리**: `task-phase4-closure` 계획서가 PR 링크 부재로 `보류` 였다. #133 이 근거임을
확인해 계획서에 링크를 적고 `done/` 으로 아카이브했다(인덱스 51행).

**다음**: D-030 (Slack 채널 분리 + DLQ 적재량 메트릭) — 5서비스 분리로 per-service
태그(ADR-0015)가 갖춰져 라우팅 기준이 이미 있다.
