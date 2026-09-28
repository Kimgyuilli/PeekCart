---
grade: S
---
# task-d048-e2e-stack-startup

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
- [ ] P1. 토픽 사전 생성을 kafka 컨테이너 안 **exec 1회**로 묶는다(생성 루프 + 전체 describe 1회로 대조).
      기존 두 가드(생성 성공 수 = 선언 수, 파티션 대조 실패 0)는 그대로 유지한다
- [ ] P2. 앱 4개 병렬 기동을 CI(ubuntu-latest)에서 실측한다. 창 초과·flake 없이 줄면 채택, 아니면
      순차 유지하고 실측값을 주석에 남긴다. `durations.tsv` 는 스크립트 밖 소비처가 없다(grep 확인)
- [ ] P3. 전후 `durations.tsv` 비교를 PR 본문에 기록

## 검증
- P1: 선언 목록에 존재하지 않는 파티션 수(예: 한 토픽을 미리 1파티션으로 생성)를 주입 → 대조 실패로
  exit 1 인지, 생성 루프가 한 건 실패하도록 주입 → "N종 중 M종만 생성" 으로 exit 1 인지 로컬 확인
- P2: CI 2회 이상 녹색 + app-up 구간 합계 감소를 `durations.tsv` 로 확인. 한 번이라도 창 초과면 불채택
