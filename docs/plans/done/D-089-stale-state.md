---
grade: M
---
# D-089 stale 판정 — main 이 expected 를 지난 뒤의 publication-state

## 1. 명제

main 이 expected SHA 를 지나 앞으로 간 뒤 관측한 `publication-state` 가, 여섯 서비스의 자기 publish 와
SHA manifest·config 가 모두 확인됐는데도 실패로 남으면 미완이다. 반대로 자기 publish 실패, 조회 오류,
expected 도 현재 main 도 아닌 latest, 관측 시작 전에 main 이 이미 바뀐 상태의 expected latest 중 하나라도
성공으로 접으면 미완이다. 부모 계획 `D-089.md` 의 남은 항목(PR215 경량 리뷰 P2)만 다룬다.

등급 M 근거: 모듈 코드 변경 없음(관측 스크립트·self-test·runbook·ADR Update Log), 바뀌는 계약 표면은
관측기의 집계 상태와 종료 코드 하나, 되돌림은 이 작업의 파일 안에서 끝난다.

코드 검증 (2026-10-24, `main` 6052c2f):

| 전제 | 확인 결과 |
|---|---|
| 현재 stale 판정 | `scripts/ci-publication-state.py` `observe()` 가 관측 시작·끝 main 중 하나라도 expected 와 다르면 행 결과와 무관하게 `stale_or_concurrent_release`, exit 1 |
| 동시성 | `ci.yml` `concurrency: ci-${{ github.ref }}`, `cancel-in-progress: true`. 연속 push 는 앞 run 을 취소한다. `publication-state` 는 `always()` 라 취소된 run 에서도 돌 수 있다 |
| 관측 창 | run 40031226500: push 22:04:41, publish 시작 22:17:42, observer 19초. job timeout 10분. 관측 시작 때 main 이 expected 면 창 안에서 다음 run 의 publish 가 latest 를 바꿀 수 없다 |
| main 이동 방향 | branch protection 이 force push 를 막고 `enforce_admins` 가 켜져 있다. expected 와 다른 main 은 expected 이후 커밋이다 |
| 실제 발생 | PR215 병합(6f8cacc) 뒤 main push run 14개 중 13개 성공, 6253429 는 gate 에서 취소돼 observer 가 돌지 않았다. 아직 실관측 사례는 없다 |
| 고칠 대상 | self-test `test_current_main_and_observation_window` 가 관측 중 main 이동(`[SHA, b]`)을 stale 실패로 고정한다. latest 가 다음 SHA 로 넘어간 지연 관측도 같은 판정이다 |
| 계약 진술 | ADR-0030 Update Log(2026-10-21 D-089)와 runbook §상태 읽기가 stale 관측을 workflow 실패로 적는다 |

판정 원칙: main 이 지나간 뒤 latest 는 다음 release 의 observer 가 판정한다. 이 run 은 자기 승격이 완결됐는지와
latest 가 근거 있는 값인지만 본다. 근거는 둘이다. latest 가 현재 main SHA tag 의 manifest 이거나, latest 가
expected 이고 관측 시작 때 main 이 expected 였던 경우다. 후자는 이 run 의 승격이 다음 release 보다 앞섰음을
보장한다. 관측 전에 main 이 바뀐 상태에서 latest 가 expected 이면 그 순서를 증명할 수 없다(오래된 publisher
재실행에 의한 되돌림과 구분 불가).

## 2. 작업 항목

- [x] P1. 관측기: 두 main 조회가 성공하고 main_after 가 expected 와 다르면, `different_latest` 행에 한해 현재 main
  SHA tag manifest 를 한 번 더 읽어 latest 와 같으면 행을 `superseded` 로 표시한다. 추가 artifact 가 없고 여섯
  행 모두 push outcome 이 success 이며, 모든 행이 `superseded` 이거나 `aligned` 이면서 관측 시작 main 이 expected
  일 때만 집계 `superseded`, exit 0. 그 밖의 main 변경은 기존 `stale_or_concurrent_release`, exit 1
- [x] P2. self-test: 양성(관측 중 main 이동, latest 가 현재 main 으로 이동, 둘의 혼합)과 음성(제3 digest latest,
  현재 main tag 조회 실패, 자기 push 실패, 관측 전 이동과 expected latest, 조회 오류, 추가 artifact)을 고정한다.
  기존 `[SHA, b]` 기대값을 바꾸고, 잘못된 구현 4종을 mutant 로 추가한다
- [x] P3. runbook §상태 읽기·재실행 뒤 확인의 stale 서술 정정, ADR-0030 Update Log 1줄 append

## 3. 검증 방법

| 검증 | 실패 주입과 확인 |
|---|---|
| 연속 push 양성 | fixture main 을 `[SHA, NEW]`(전부 aligned), `[NEW, NEW]`(전부 latest 가 현재 main), `[SHA, NEW]`(혼합)로 두고 exit 0, `summary.json` status `superseded` |
| 실패 유지 | main 이동 상태에서 latest 를 제3 digest 로, 현재 main tag 를 누락으로, push outcome 을 failure 로, main 을 `[NEW, NEW]` 에 expected latest 로, read_error·추가 artifact 로 바꿔 각각 exit 1, status `stale_or_concurrent_release` |
| 잘못된 구현 | 현재 main 대조 생략, push outcome 무시, 관측 전 이동의 aligned 허용, superseded 를 실패로 반환하는 mutant 가 각 대상 테스트를 실패시키는지 self-test 무인자 실행으로 확인(기존 7종 포함 11종) |
| 기존 회귀 | 기존 test 전부, `ci-release-gate-lint.sh` 와 `--self-test`, actionlint |

## 미해결

- `publication-state` 는 main push 에서만 돈다. 새 판정의 실제 원격 관측은 병합 뒤 main run 이고, `superseded`
  실관측은 연속 push 가 있어야 생긴다(직렬 병합에서는 생기지 않는다)
- 관측 시작 전에 main 이 바뀌고 latest 가 아직 expected 이거나 중간 release 인 경우는 계속 stale 실패다.
  취소된 run 에서 `always()` observer 가 돌 때 생길 수 있다(이 레포 실관측 없음)
- `cancel-in-progress` 로 publish 가 중간에 취소되고 다음 push 가 publish 하지 못하면 latest 가 섞인 채 남고 이를
  알리는 observer 가 없다. 이 작업 범위 밖이며 root 에 보고한다
- 과거 unknown blob 원인 처분과 D-089 종결은 범위 밖(사용자 결정 대기)

## 완료 기록 (2026-10-24)

[PR234](https://github.com/Kimgyuilli/PeekCart/pull/234) 의 최종 head `090e828` CI(run40084496982)가 성공한 뒤 병합했다(`4ab907f`). main CI40086117418 에서 publication-state 는 `aligned (6/6 aligned)` 였다. `superseded` 실관측은 연속 push 가 있을 때 생긴다. §미해결의 과거 원인 처분과 D-089 종결은 사용자 결정으로 확정됐고 부모 계획 [D-089](D-089.md) 완료 기록에 있다.
