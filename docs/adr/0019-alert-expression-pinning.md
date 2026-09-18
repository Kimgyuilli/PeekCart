# ADR-0019: alert 식 정본 고정 — PromQL 정적 lint 를 라벨 invariant 에서 식 동일성으로 격상 (ADR-0015 부분 무효화)

- **Status**: Accepted
- **Date**: 2026-08-26
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 4 (구현 ④-d-1)

## Context

ADR-0015 는 `observability-promql-lint` 의 검증 범위를 **PromQL syntax + 라벨 invariant** 로 규정하고, Consequences 에 그 한계를 명시했다.

> PromQL 정적 lint 는 syntax + 라벨 invariant 만 검증 — Grafana `__expr__`(threshold/reduce) 의미 평가·실제 series 존재·발화 임계 동작은 정적 검증 대상 외

구현 ④-d-1 에서 saga/DLQ backlog alert 2종을 추가하면서 이 범위가 **불충분함이 드러났다**. diff 리뷰가 실증한 우회 경로:

1. **`0 * <식>`** — `application` matcher 도 `by (application)` grouping 도 그대로라 라벨 invariant 를 전부 통과한다. 그런데 Grafana 의 조건식 `$A > 0` 은 **영원히 참이 되지 않는다.** 검사는 초록불이고 alert 는 죽어 있다.
2. **prometheus data entry 삭제** — uid 만 남기면 라벨 검사 루프가 아예 실행되지 않고, 필수 UID 존재 검사는 통과한다.
3. **부정 matcher**(`application!="..."`) — 허용 집합 검사는 통과하면서 특정 서비스를 감시에서 제외한다.
4. **메트릭 substring 매칭** — `잘못된식 + 0 * 계약메트릭{정상라벨}` 처럼 계약 메트릭을 곁들여 검사를 통과시키고 실제로는 다른 것을 잰다.

이들의 공통점은 **"라벨이 맞으면 통과"** 라는 검사 축이 alert 의 *발화 가능성* 과 무관하다는 것이다. 라벨 invariant 는 "누구를 보는가" 를 지키지만 "실제로 보는가" 는 지키지 못한다.

추가로, ④-d-1 이 신규 2종만 방어했을 때 **기존 4종(`high-error-rate`·`slow-response`·`target-down`·`scrape-absent`)에 같은 우회가 그대로 남는** 비대칭이 발생했다. 약한 쪽이 뚫리면 강한 쪽을 지킨 의미가 없다.

## Decision

**`observability-promql-lint` 는 모든 필수 alert 의 prometheus 식을 정본 문자열과 정확히 일치시킨다.**

- lint 내부에 `ALERT_EXPR_CONTRACTS`(uid → 순서 있는 식 목록)를 정본으로 둔다. 공백만 정규화하고 그 외는 문자 단위로 비교한다.
- `scrape-absent` 5종은 기존 ground truth(`EXPECTED_SERVICES`)에서 생성해 서비스 집합과의 커플링을 유지한다.
- 라벨/메트릭 invariant 검사는 **삭제하지 않고 유지**한다 — 위반 시 "무엇이 다른가" 를 짚어주는 진단 품질을 담당한다.
- **alert 식을 바꾸려면 정본도 함께 바꿔야 한다.** 이 마찰은 의도된 것이다 — 식 변경은 계약 변경이다.

## Alternatives Considered

### Alternative A: PromQL AST 기반 의미 검사
- **장점**: 문자열 형태와 무관하게 의미를 검증. `0 *` 뿐 아니라 아직 모르는 무력화 패턴도 원리적으로 차단.
- **단점**: PromQL 파서 의존성 추가(promtool 은 syntax 검사만 제공, AST 를 노출하지 않는다). "무력화" 를 의미론적으로 정의하는 것 자체가 어렵다 — 어떤 식이 "발화 가능한가" 는 threshold·데이터 분포와 얽힌다.
- **기각 사유**: 비용 대비 효용이 맞지 않는다. 저장소가 소유한 alert 는 10종이고 전부 우리가 작성했다. 그 10개 식을 고정하는 것이 파서를 들이는 것보다 단순하고, 남는 위험(정본을 고칠 때 함께 무력화)은 코드 리뷰가 볼 수 있는 형태로 드러난다.

### Alternative B: 신규 alert 2종만 식 고정, 기존 4종은 라벨 invariant 유지
- **장점**: 변경 범위 최소. 기존 alert 를 건드리지 않는다.
- **단점**: **계약 강도가 비대칭이 된다.** 공격자(혹은 실수)는 약한 쪽을 지나간다 — `high-error-rate` 에 `0 *` 를 붙이면 5xx 감시가 통째로 죽는데 lint 는 통과한다.
- **기각 사유**: 부분 방어는 방어가 아니다. 실제로 ④-d-1 이 이 상태로 한 라운드를 돌았고 리뷰가 즉시 지적했다.

### Alternative C: 금지 패턴 blacklist (`0 *`, `* 0` 등을 정규식으로 거부)
- **장점**: 정본 관리 부담 없음. 기존 alert 를 그대로 두고 검사만 추가.
- **단점**: whack-a-mole. `0 *` 를 막으면 `< 0`, `and vector(0)`, `unless` 등 무한히 많은 변형이 남는다. 막은 패턴 목록이 곧 "우리가 생각해 본 것" 의 목록이 된다.
- **기각 사유**: 열거로는 닫히지 않는 문제다. allowlist(정본 고정)가 blacklist 보다 구조적으로 강하다.

## Consequences

### 긍정적 영향
- alert 무력화가 **구조적으로 차단**된다. 식이 다르면 그 이유와 무관하게 실패한다.
- 10종 전부에 균일하게 적용돼 계약 강도의 비대칭이 사라진다.
- self-test 10종이 조작 입력에서 실제로 실패함을 확인한다 — lint 자체가 vacuous-green 으로 썩는 것을 막는다(기존 lint 규약 승계).

### 부정적 영향 / 트레이드오프
- **alert 를 정당하게 수정할 때마다 lint 가 막는다.** 정본 문자열을 함께 고쳐야 통과한다. 의도된 마찰이지만 개발 흐름에 비용이다.
- **의미가 같은데 형태가 다른 식을 거부한다** — 공백 외 정규화를 하지 않으므로 라벨 순서·따옴표 스타일 변경도 위반이 된다. 일관성을 강제하는 부수 효과가 있으나 유연성은 없다.
- **여전히 문자열 비교이지 의미 분석이 아니다.** 정본 자체를 무력화된 식으로 고치면 lint 는 통과한다 — 그 지점의 방어는 코드 리뷰다. 이 한계는 ADR-0015 의 "정적 검증 대상 외" 서술을 좁혔을 뿐 없애지 못했다.
- alert 가 늘어날수록 정본 목록이 길어진다. `scrape-absent` 처럼 규칙적인 것은 ground truth 에서 생성해 완화한다.

### 후속 결정에 미치는 영향
- **ADR-0015 의 "정적 lint 는 syntax + 라벨 invariant 만" 서술을 부분 무효화**한다. lint 범위는 이제 **식 동일성**까지다. 다만 ADR-0015 의 나머지(per-service SSOT 위치, ground truth 정의, alert 4종의 라벨 규약)는 그대로 유효하다.
- **발화 동작 검증은 여전히 대상 외다.** Grafana `__expr__` 의미 평가·실제 series 존재는 Grafana/Prometheus 기동 + fixture 가 필요하며 본 결정 범위 밖이다(구현 ④-d-1 §2.4-B 에 미검증으로 명시).
- 신규 alert 를 추가할 때는 `ALERT_EXPR_CONTRACTS` 등록이 필수다 — 등록하지 않으면 required UID 검사에서 걸린다.

## References
- ADR-0015 (관측성 per-service 계약 — 본 ADR 이 lint 범위 서술을 Partially Supersede)
- ADR-0009 (관측성 계약 SSOT)
- 계획서: `docs/plans/done/task-impl4-d1-saga-metrics.md` · 리뷰 이력 `.audit.md` (diff 3R #1)
- `scripts/observability-promql-lint.sh` · `k8s/monitoring/shared/grafana-alerts.yml`
