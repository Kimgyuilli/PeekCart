---
grade: M
---
# CI 임계경로 단축 — 문서 전용 skip, images 의 lint 비대기, e2e 3러너

## 1. 명제

PR CI 의 전체 시간을 정하는 경로 `lint → images → e2e(scenarios) → gate` 에서 lint 대기와
시나리오 러너의 직렬 구간을 떼어낸다. 문서만 바꾼 PR 은 JVM·이미지·E2E 를 돌지 않는다.
gate 는 어느 경우에도 검증 없이 초록이 되지 않는다 (see ADR-0044).

등급 M 근거: 모듈 코드 변경 없음(CI·스크립트·문서), 계약 표면은 CI gate 계약 하나(ADR-0030 D1
부분 대체), 되돌림은 이 작업의 파일 안에서 끝난다.

이 계획서는 `/plan` 없이 구현한 뒤 `/ship` 을 위해 사후에 작성했다. 아래 전제는 구현 전에
실제 실행 기록과 코드로 확인한 것이다.

코드 검증 (2026-10-24, 현재 `main` 1cc1efc):

| 전제 | 확인 결과 |
|---|---|
| 임계경로가 lint→images→e2e(scenarios) 다 | 맞음 — run 40007646179: lint 4.4m, images 시작 +4.5m, e2e(scenarios) 12.1m, test 최장 5.0m |
| images 가 lint 결과를 입력으로 쓴다 | 아님 — 906bf62 는 gate 대기를 lint 로 완화했을 뿐이다. CRG-002 가 `needs(images) == ["lint"]` 를 고정할 뿐 근거는 없다 |
| scenarios 러너의 최장 구간 | 시나리오 e 4.5분(payment 재시작), 스택 기동 약 2.5분 |
| 시나리오를 나눠 돌릴 수 있다 | 있음 — `saga-e2e-smoke.sh --scenarios`, `validate_order` 는 `[e]`·`[a,b,c,d,f]` 를 허용(f 는 꼬리) |
| 필수 체크 | `gate` 하나 — job 이름 변경이 브랜치 보호에 영향 없음 |
| 테스트·E2E 가 docs/ 를 입력으로 읽는다 | `docs/plans/fixtures/saga-contract-matrix.tsv` 하나(saga-contract-matrix-lint). Java·gradle·compose·e2e 스크립트에는 없음 |
| `d092-grafana-routing` 이 아직 필요하다 | 아님 — 조건이 PR 217 전용이고 PR 217 은 2026-10-21 병합 |

## 2. 작업 항목

- [x] P1. `changes` job 과 `scripts/ci-docs-only.sh`(self-test 포함). PR merge 커밋 `HEAD^1..HEAD` 를 `--no-renames` 로 비교, push 는 항상 `code=true`
- [x] P2. test·guards·images 는 `changes` 만 기다리고 `code == 'true'` 일 때만 실행. images 의 lint 대기 제거
- [x] P3. e2e 를 scenarios(a,b,c,d,f)·fence(e)·negative-control 3러너로 분할
- [x] P4. E2E 증적 대조를 gate 로 이동, `--e2e-evidence` 가 쉼표로 여러 디렉터리를 받아 합집합 대조(디렉터리 간 중복도 실패)
- [x] P5. gate 실패 전파 조건에 `changes` 판정 반영 — 문서 전용이면 나머지 4개가 정확히 skipped, 출력 누락은 코드 변경으로 취급
- [x] P6. `ci-release-gate-lint.sh`(CRG-002·003·007)·`ci-e2e-parallel-lint.sh` 갱신과 변이 추가
- [x] P7. `d092-grafana-routing` 삭제
- [x] P8. ADR-0044 작성, ADR-0030 Partially Superseded 표시, `06-testing-strategy.md` 정정

## 3. 검증 방법

| 검증 | 결과 |
|---|---|
| `ci-docs-only.sh --self-test` 11종 | 통과. 첫 실행에서 마지막 줄 무개행 입력을 버리는 결함을 잡아 수정 |
| `ci-release-gate-lint.sh` / `--self-test` | 통과. 변이 85종, 결과·판정 출력·취소 조합 24,576가지 전수 대조 |
| `ci-e2e-parallel-lint.sh` / `--self-test` | 통과. 변이 13종(시나리오 누락·중복·fence 증적 제외 포함) |
| `saga-contract-matrix-lint.sh --self-test` | 통과. 다중 디렉터리 통과·누락·디렉터리 간 중복·없는 디렉터리 4종 추가 |
| run 40007646179 실제 E2E 증적을 scenarios(a,b,c,d,f)/fence(e) 로 나눠 gate 스텝 재현 | 35행 대조 통과. fence 증적 부재 시 `ls` 실패로 중단 |
| ci.yml 소비 lint·self-test(wiring·health·build-profile·publication-state·codex-render·harness·test-matrix·image-contract 등) | 전부 통과 |
| actionlint 1.7.7 | 신규 지적은 SC2012(info) 1건(gate 의 `ls`). 기존 3건 그대로 |
| `harness-context.sh --check`, `docs-preservation-ci.py` | 통과 |

## 미해결

- e2e 분할은 이 PR 의 CI 에서 처음 실제 스택으로 돈다. fence 단독, e 없이 a·b·c·d·f 가 통과하는지는 그 실행이 확인한다
- 문서 전용 경로(4개 job skipped, gate 초록)는 문서만 바꾼 다음 PR 에서 처음 확인된다
- 단축폭(약 19분 → 10~11분)은 추정이다. 병합 후 main·PR 실행으로 실측한다

## 완료 기록 (2026-10-24)

§미해결 세 항목을 병합 뒤 실행으로 확인했다. e2e 분할은 #231 PR CI 두 번에서 실제 스택으로 돌았고 gate 의 합집합 증적 대조(35행)를 통과했다. 문서 전용 경로는 [PR233](https://github.com/Kimgyuilli/PeekCart/pull/233) CI(run40039880106)에서 test·guards·images·e2e skipped, gate 성공으로 처음 확인됐다.

단축폭은 push 에서 gate 까지 약 13분이다. main `2c9aa36` 12분 58초, `6052c2f` 12분 49초, PR234 12분 57초, PR235 14분 2초와 main 반영 뒤 재실행 13분 0초였다. 이전 약 19분에서 줄었지만 추정한 10~11분에는 못 미쳤다.

이미지 다운로드가 1분을 넘긴 8회는 모두 10-23 21:07 에서 10-24 00:10 UTC 사이 run 에 있었다. 10-24 10:05 이후 PR run 세 개의 9회는 5~28초였고, 분할 전 main run 네 개의 8회는 7~39초였다. 3러너 분할 구조보다 시간대 변동으로 판단한다. 10~11분 미달의 남은 원인은 e2e fence 러너다. PR234 run 에서 시나리오 e 단계가 스택 기동을 포함해 8분 9초였고 다운로드는 5~8초였다. 추가 단축은 시나리오 e 를 줄이는 별도 작업이며 부채로 등록하지 않는다(사용자 결정).
