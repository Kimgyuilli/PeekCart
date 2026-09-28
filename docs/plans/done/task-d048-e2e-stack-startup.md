---
grade: S
---
# task-d048-e2e-stack-startup

PR: [#156](https://github.com/Kimgyuilli/PeekCart/pull/156) (머지 2026-09-28, 머지 전 PR CI 성공 1회 — P2 판정 기준 2회 미충족)

## 명제
e2e 두 잡(scenarios · negative-control)이 스택 기동에 **토픽 사전 생성 65~68초 + 앱 순차 기동 약 88초**를
쓰고 있고, 그 시간이 결함 검출과 무관한 오버헤드로 남아 있으면 미완이다.

실측(main run 36356454290 · 36359840329 · 36395686775, `durations.tsv`): infra-up 26 · topic-precreate
65~68 · app-up 18~25 × 4 · readiness 2. 코드 확인 결과:
- 토픽 20종마다 `dc exec kafka-topics.sh` 를 create 1회 + describe 1회 → docker exec·JVM 기동 **40회**
- 앱 4개는 서로 `depends_on` 이 없다(infra 만 의존). 순차 기동 사유는 주석상 **CPU 경합**(과거 동시
  기동 시 order-service 가 창을 넘김)뿐이다
- 두 잡의 기동 중복은 범위 밖이다 — D-043 lint(`ci-e2e-parallel-lint.sh`)가 병렬 2잡을 계약으로
  강제하고, 합치면 PR 벽시계가 오히려 는다

## 작업 항목
- [x] P1. 토픽 사전 생성을 kafka 컨테이너 안 **exec 1회**로 묶는다(생성 루프 + 전체 describe 1회로 대조).
      기존 두 가드(생성 성공 수 = 선언 수, 파티션 대조 실패 0)는 그대로 유지한다.
      로컬 실측 exec 1회 ~0.4s · CLI JVM 1회 ~1.0s 라 남는 시간은 create JVM 20회다. 컨테이너 안
      병렬 생성(`xargs -P`)은 **채택하지 않았다** — 브로커가 기본 `-Xmx1G` 로 `mem_limit: 1g`
      컨테이너를 이미 채우고 있어, CLI JVM(각 `-Xmx256M`)을 겹쳐 띄우면 브로커 OOM 이 flake 로 보인다
- [x] P2. 앱 4개를 `dc up --wait` 1회로 동시 기동한다(로컬 scenarios · negative-control 통과).
      구간명은 `app-up:<svc>` 4개 → `app-up` 1개로 바뀐다. **채택 판정은 §검증의 CI 행이다** —
      CI 는 PR 에서만 돌아 PR 생성 전에는 판정할 수 없다(정정: 초안은 판정을 이 항목에 넣어
      `/ship` 사전 확인과 순환했다). 창 초과·flake 가 나면 순차로 되돌리고 실측값을 주석에 남긴다
- [x] P3. 전후 `durations.tsv` 비교를 PR 본문에 기록(로컬값. CI 값은 판정 후 PR 에 추가)

## 검증
- P1: 선언 목록에 존재하지 않는 파티션 수(예: 한 토픽을 미리 1파티션으로 생성)를 주입 → 대조 실패로
  exit 1 인지, 생성 루프가 한 건 실패하도록 주입 → "N종 중 M종만 생성" 으로 exit 1 인지 로컬 확인
- P2: PR 의 CI 2회 이상 녹색 + app-up 구간 합계 감소를 `durations.tsv`(artifact `e2e-evidence-*`)로 확인.
  한 번이라도 창 초과면 불채택. **이 행은 PR 생성 후 판정하며, 판정 전 머지하지 않는다**

## 실측 (로컬, Docker 8 CPU — CI 판정 전 참고값)
| 구간 | main 스크립트 | 이 브랜치 (scenarios) | 이 브랜치 (negative-control) |
|---|---|---|---|
| topic-precreate | 50 | 23 | 25 |
| app-up (합계) | 74 (19+18+18+19) | 51 | 38 |

결함 주입(P1 검증, 로컬 kafka): 파티션 불일치 → `대조 실패 1`·rc=1 / 파티션 0 생성 거부 →
`21종 중 20종만 생성`·rc=1 / describe 파서 고장 → `대조 실패 20`·rc=1. 셋 다 종전 가드 문구 그대로 실패한다.
