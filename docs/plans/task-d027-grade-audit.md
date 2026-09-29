---
grade: M
---
# D-027 ③ — 등급 사후 검증

## 1. 명제

`grade:` 가 적힌 계획서 각각에 대해 실제 PR diff 로 `/plan` §1 의 세 기준을 다시 적용한 판정이
한 표로 남아 있지 않으면 미완이다. 또 그 표에서 판정 기준을 고칠지, 사후 검산을 자동화할지
결론이 나 있지 않으면 미완이다.

등급 M 근거: 하네스 문서만 건드린다(앱 모듈 무관). 결론에 따라 `plan.md` §1 판정 기준이라는
공유 규칙이 바뀔 수 있다. 되돌림은 그 파일 안에서 끝난다.

코드 검증 (2026-09-29, 현재 `main`):

| 전제 | 확인 결과 |
|---|---|
| 등급 판정은 `hpx_plan_grade` 가 frontmatter 에서 읽는다 | 맞음 — `.claude/scripts/shared-logic.sh`, `/work`·`/ship` 이 호출. 도입은 2026-09-18 이전 |
| `grade:` 계획서는 25개 | 맞음 — S 8 · M 11 · L 6. 전부 09-18 이후 작성이라 소급 부착 표본은 없다 |
| 계획서 안의 첫 PR 링크가 그 계획서의 PR 이다 | **틀림** — d029(#119→실제 #131)·d026-d002a(#124→#129)·phase5-baseline(#133→#134)·d035 3건(#144→#145~147) 은 다른 PR 을 먼저 인용한다. 매칭은 PR head 브랜치명으로 했다 |
| 사후 검산 도구가 이미 있다 | 없음 |
| diff 리뷰 결과(M 에서 P0 = 등급 오판 신호, `/work` §7)를 비교 재료로 쓸 수 있다 | **못 쓴다** — `.cache/codex-off`(2026-09-22)로 25개 중 실제 리뷰를 받은 것은 codex-review-render · d026-d002a 2개뿐이다 |

범위 변화: 줄었다. 리뷰 결과를 재료로 쓸 수 없으므로 "등급이 리뷰 강도를 맞게 배분했나" 는 판정
대상이 아니다. 남는 질문은 "세 기준을 실제 diff 에 다시 적용하면 같은 등급이 나오나" 하나다.

정정 이력: `/plan` 1차 보고에서 "#144 = 계획서 3개, #133 = 2개가 PR 을 공유한다" 고 했으나 틀렸다.
계획서의 첫 링크를 PR 로 오인한 것이다. 실제 공유는 #150(D-045·D-046) 1건뿐이다.

## 2. 작업 항목

- [x] P1. 계획서 25개를 PR 에 매칭하고, PR 마다 `docs/` 를 뺀 변경 경로와 라인 수를 뽑는다
- [x] P2. PR 마다 세 기준(모듈 2개 이상 · 계약 표면 변경 · 작업 밖 되돌림)을 다시 적용해 판정한다
- [x] P3. 결론 — 판정 기준 수정 여부, 사후 검산 자동화 여부

## 3. 검증 방법

분석 작업이라 주입할 코드가 없다. 대신 표가 거짓 일치를 내지 않는지를 확인한다.

| # | 확인 | 결과 |
|---|---|---|
| V1 | 표의 행 수 = `grep -rl '^grade:' docs/plans` 수 | 25 = 25 |
| V2 | PR 매칭이 계획서 링크가 아니라 head 브랜치에서 왔다 — 링크를 그대로 믿었다면 틀렸을 6건이 표에서 바로잡혀 있다 | 위 코드 검증 표 3행 |
| V3 | 일치 판정이 라인 수에 끌려가지 않았다 — 라인 수로 가르면 뒤집히는 쌍을 표에서 찾아 판정이 유지되는지 본다 | d035-product(M, 617줄) vs d029(L, 115줄) · d037(M, 0줄) 판정 유지 |
| V4 | `grade:` 값이 git 이력에서 바뀐 적이 있는지 `git log -p` 로 확인 | 값 변경 0건. 재판정은 audit 에만 2건 기록 |

## 4. 결과

비문서 = `docs/` 를 뺀 변경 라인(추가+삭제). 기준 ①모듈 2개 이상 ②계약 표면 ③작업 밖 되돌림.

| 계획서 | 등급 | PR | 비문서 | 건드린 곳 | ① | ② | ③ | 사후 판정 |
|---|---|---|---|---|---|---|---|---|
| d028-topic-describe-setup-race | S | #126 | 79 | order-service 테스트 1 · plans-archive.sh 4줄 | | | | S 일치 |
| phase4-closure | S | #133 | 0 | 문서 | | | | S 일치 |
| phase5-baseline | S | #134 | 0 | 문서 | | | | S 일치 |
| d040-local-test-scope | S | #148 | 8 | harness-context.sh | | | | S 일치 |
| d046-cache-timeout-bound | S | #150 | 14 | product-service 테스트 1 (PR 은 D-045 와 공유) | | | | S 일치 |
| d048-e2e-stack-startup | S | #156 | 47 | saga-e2e-smoke.sh (CI 가 부르는 인터페이스 무변화) | | | | S 일치 |
| d039-gradle-setup | S | #157 | 0 | 문서 | | | | S 일치 |
| d027-rationale-split | S | #158 | 68 | .claude/commands (근거 문장 이동, 규칙 무변화) | | | | S 일치 |
| d031-ci-graph-parallelization | M | #135 | 283 | ci.yml · lint | | ○ CI 잡 그래프 | | M 일치 (S→M 재판정 기록) |
| d033-e2e-parallel | M | #140 | 118 | ci.yml · lint | | ○ CI 잡 그래프 | | M 일치 |
| d037-branch-protection | M | #141 | 0 | GitHub 보호 설정(저장소 밖) | | ○ 필수 체크 | | M 일치 |
| d035-user-container-singleton | M | #144 | 116 | user-service 테스트 · 공유 lint | | ○ lint ALLOWED | | M 일치 |
| d035-notification-container-singleton | M | #145 | 250 | notification-service 테스트 · 공유 lint | | ○ | | M 일치 |
| d035-payment-container-singleton | M | #146 | 400 | payment-service 테스트 · 공유 lint | | ○ | | M 일치 |
| d035-product-container-singleton | M | #147 | 617 | product-service 테스트 · 공유 lint | | ○ | | M 일치 |
| d044-test-seed-input | M | #149 | 82 | 서비스 5개 build.gradle (`-PtestSeed` 는 기존 키, 의미 유지) | ○ | | | M 일치 |
| d042-verification-responsibility | M | #153 | 0 | ADR-0032 | | ○ ADR | | M 일치 |
| d047-jvm-gap-coverage | M | #154 | 42 | payment · notification 테스트 | ○ | | | M 일치 |
| d030-outbox-dlq-alert-split | M | #155 | 85 | grafana-alerts · ci.yml · promql lint | | ○ alert (ADR-0009 SSOT) | | M 일치 |
| codex-review-render | L | #128 | 735 | .claude/commands · schemas · scripts | ○ | ○ output schema | | L 일치 (M→L 재판정 기록) |
| d026-d002a-read-ceiling-session | L | #129 | 761 | .claude · settings.json · .githooks · k8s · loadtest | ○ | ○ 훅·설정 | | L 일치 |
| d029-mysql-cpu-base-promotion | L | #131 | 115 | k8s/base · overlays · loadtest | ○ | ○ base 매니페스트 | | L 일치 |
| d032-integration-test-container-singleton | L | #138 | 1228 | common · order-service · ci.yml · CLAUDE.md | ○ | ○ 공유 모듈·규약 | ○ | L 일치 |
| d041-fault-injection-boundary | L | #151 | 265 | scripts/e2e · ADR | | ○ 주입 경계 ADR | ○ 대조군 전반 | L 일치 |
| d043-ci-release-gate | L | #142 | 524 | ci.yml · 게시 스크립트 · 보호 설정 | | ○ 게시·필수 체크 | ○ 저장소 설정 | L 일치 |

**25개 전부 일치한다.** 재판정은 2건이고 둘 다 상향이다. 둘 다 `/plan` §2 코드 검증이나 구현 중에
범위가 늘어난 것이 계기였다. "범위가 바뀌면 등급을 다시 판정한다" 는 규칙이 설계대로 작동했다.
하향 재판정이나 사후 과대 판정은 없다.

### 결론 (P3)

**판정 기준은 고치지 않는다. 사후 검산도 자동화하지 않는다.** ③ 은 이 결과로 닫는다.

- 기준 수정 불요: 불일치 0건. 고칠 대상이 되는 사례가 없다
- 자동화 불요: 자동 검산이 쓸 수 있는 신호는 크기뿐인데, 크기는 등급과 맞지 않는다. M 이 0~617줄이고
  L 이 115~1228줄이라 둘이 겹친다. 이는 기준이 크기가 아니라 파급을 보도록 설계된 결과다.
  크기 임계값 검사기는 d035-product(M 617줄)·d037(M 0줄) 같은 올바른 판정을 오탐한다.
  파급(①②③)은 계약 표면을 알아야 판정할 수 있어 스크립트로 옮길 수 없다

관찰 두 가지는 기록만 하고 조치하지 않는다.

- 기준 ① 의 "모듈" 은 앱 모듈을 전제로 한 말이다. 25개 중 11개 PR 은 CI·k8s·스크립트·하네스만
  건드렸다. 그래도 판정이 흔들리지 않은 것은 이런 작업에서는 ② 계약 표면이 결정 변수로 작동했기
  때문이다. 오판 사례가 생기면 그때 정의를 보강한다
- 등급 근거를 계획서에 적은 것은 3개(d037·d043·d047)뿐이다. `/plan` §1 은 근거를 대화에서만
  말하게 한다. 이번 사후 검증은 근거 없이 diff 에서 판정을 재구성할 수 있었으므로 규칙은 추가하지 않는다

재검토 조건: Codex 리뷰가 다시 켜진 뒤 M 등급 diff 리뷰에서 P0 가 나오면 등급 오판 신호다.
이 신호는 `/work` §7 에 이미 있으므로 새 장치는 두지 않는다.

한계: 판정자가 등급을 매긴 쪽과 같은 에이전트다. 같은 출처가 다시 판정하므로 "전부 일치" 는 불일치를
찾지 못했다는 뜻이다. 불일치가 없다는 증명은 아니다.
