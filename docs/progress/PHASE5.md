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

## CI 그래프 직렬화 해소 + 컨테이너 수명 결정 ([#135](https://github.com/Kimgyuilli/PeakCart/pull/135), 2026-09-23)

CI 36분의 구조를 측정으로 분해하고, 그 결과를 ADR-0028 로 고정한 뒤 1단계를 구현했다.

측정이 먼저였다. 실측 [run 35731821466](https://github.com/Kimgyuilli/PeakCart/actions/runs/35731821466)
에서 `test(order) 1051s` · `gate 15s` · `images 173s` · `e2e 856s` 가 직렬로 이어져 36분 중
34.5분을 차지했다. 샤드 내부는 더 극단적이었다 — `:order-service:test` 975초 중 JUnit XML 이
보고하는 클래스 시간은 **131초뿐**이고 나머지 **844초(87%)** 가 컨테이너 부팅·Flyway·context
기동이다. JUnit 이 static initializer 를 testcase 시간에 넣지 않아 그동안 보이지 않던 구간이다.

**결정(ADR-0028)**: 컨테이너 수명을 per-class 에서 모듈 싱글톤으로 바꾼다. 싱글톤 전환 ·
`@Container` 금지 lint · 순서 셔플 검증을 한 묶음으로 간다. 대안 6종(JUnit 병렬 · Redpanda ·
withReuse · 러너 코어 재배분 · e2e PR 제외 · 모듈 분할)의 기각 사유를 함께 남겼다.
워크플로 그래프 변경은 YAML 한 줄이고 되돌림이 즉시라 ADR 보호 대상에서 제외했다.

**구현(D-031, 1단계)**: `images` 의 needs 를 `[lint]` 로 완화해 test 와 병렬화했다.

착수 후 범위가 늘었다. `publish` 가 `needs: images` 하나만 걸고 있어서 그동안 `images` 를 거쳐
**간접적으로만** 게이트되고 있었고, 그 경로를 끊으면 테스트가 실패한 main push 에서도 GHCR 에
`:latest` 가 올라간다. `publish: needs: [images, gate]` 로 직결하고, 그 연결이 다시 끊기지
않도록 `scripts/ci-release-gate-lint.sh` 를 신설했다. 도달성만 보지 않고 `gate` 의 실패 전파
스텝 존재까지 본다 — `gate` 는 `if: !cancelled()` 라 선행이 실패해도 초록으로 끝날 수 있어서다.
이 발견으로 등급이 S 에서 M 으로 올라갔다.

`images` 빌드를 buildx + `type=gha` 캐시로 전환했다. scope 를 서비스별로 나눈다(매트릭스 6개가
단일 scope 를 공유하면 서로의 캐시를 덮어쓴다). `load: true` 가 필수인데 `docker-container`
드라이버가 결과를 로컬 daemon 에 남기지 않아 뒤따르는 health smoke 와 `docker save` 가 이미지를
찾지 못하기 때문이고, 로컬에서 양방향으로 확인했다.

**실측 (머지 후)**:

| | 베이스라인 | 변경 후 | 차이 |
|---|---|---|---|
| PR run | 36분51초 | **21분51초** | -15분 (-40.7%) |
| main push run | 39분18초 | **21분20초** | -18분 (-45.7%) |

`images` 가 15:04:50 에 시작하고 `test(order)` 가 15:20:29 에 끝났다. **15분39초 앞서 시작**해
직렬 사슬이 실제로 끊겼다. 다만 목표였던 20분에는 1분51초 미달이다.

**릴리스 게이트가 실작동했다.** main push run 에서 `gate` 종료(15:49:38) 후 `publish` 6개가
15:49:42 에 시작했다. PR 에서는 `publish` 가 skip 되므로 이 확인은 머지 후에만 가능했다.
`ci-release-gate-lint` 도 CI 에서 `self-test OK (6/6)` 로 조작 입력 4종을 전부 탐지했다.

**임계경로가 이동했다.** `test(order) 1051s -> gate -> images -> e2e 856s` 였던 것이
`lint 51s -> images 206s -> e2e 949s` 가 됐다. 20분 미달의 원인은 **e2e 단독**이고
(949초 = 전체의 72%), 이것은 ADR-0028 §후속 ① 이 예측한 상태이며 D-033 의 표적이다.

**미충족**:

- ~~buildx `type=gha` 캐시가 순손실이다~~ → **D-034 에서 유지로 판정(2026-09-22).** 다음
  main push run [35751377973](https://github.com/Kimgyuilli/PeakCart/actions/runs/35751377973)
  에서 `CACHED` **30스텝**, `images` **86~130초**로 베이스라인(104~173초)보다도 빨랐다.
  첫 run 이 느렸던 원인은 GitHub Actions 캐시의 ref 격리였다 — PR 브랜치가 채운 캐시를
  main push 가 읽지 못해 콜드였고 `cache-to mode=max` 의 export 비용만 냈다
- 로컬 `--load` 재빌드가 6.25초에 CACHED 29스텝이었던 것은 BuildKit 로컬 캐시였고,
  `type=gha` 의 거동을 예측하지 못했다
- `publish` 가 `e2e` 를 기다리지 않는 것은 **기존 상태**이고 범위 밖으로 두었다. D-033 에서
  e2e 실행 정책을 정할 때 함께 본다

**후속**: D-032(싱글톤 본체, 844초가 표적) · D-033(e2e 음성 대조군 605초 정책 재판정).
D-034 는 같은 날 판정 완료.
Codex 리뷰는 계획·diff 양쪽 모두 `.cache/codex-off` 로 차단된 상태에서 진행했다(의도적 생략).

## Phase 5 기반 세팅 — 로드맵 축 제거 ([#134](https://github.com/Kimgyuilli/PeakCart/pull/134), 2026-09-22)

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

**미충족**: 없다. Phase 5 는 종결 조건이 없는 단계이므로 "남은 항목" 개념이 이 작업에 없다.

**다음**: D-030 (Slack 채널 분리 + DLQ 적재량 메트릭) — 5서비스 분리로 per-service
태그(ADR-0015)가 갖춰져 라우팅 기준이 이미 있다.
