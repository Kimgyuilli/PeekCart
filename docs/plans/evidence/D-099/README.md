# D-099 완료 archive 건강 검사 근거

기준 main은 `4a4a36a12a3d914d6bc0379ded94bc0123c4d370`, 실행일은 2026-10-21이다. 제품/운영 명령·Gradle/Docker 실행은 없다. 실제 shell entrypoint를 disposable git fixture에 복사해 실행하며 원본 root tree는 수정하지 않는다.

## 두 계약

```bash
bash scripts/harness-context.sh --check
bash scripts/harness-context.sh --check --base <비교-commit>
```

standalone은 현재 읽기·필수 구획 형식, 완료 archive heading/table/최소 D행, 두 task 문서의 D-ID 유일성, 완료 archive 마지막 status cell의 완료 표기를 확인한다. 숨겨진 과거 기대 수를 추정하지 않는다. 따라서 heading/ID/status가 유효한 일부 행만 남아도 standalone만으로 과거 소실을 알 수 없다. 성공 문구에 base 비교 미실행을 명시한다.

명시 base는 git source를 실제로 읽고 기존 두 문서의 ID 손실과 기존 완료 archive ID의 감소를 별도로 검사한다. 새 ID로 개수를 맞춰도 기존 ID가 사라지면 실패한다. TASKS에서 done으로의 완료 이동과 새 부채 등록은 허용하고, 기존 archive 행을 active로 되돌리는 감소는 실패한다. plan/audit의 done 폴더 이동 때문에 root plan 개수가 줄어드는 것은 task ID 손실이 아니다.

이 검사는 본문·footer·순서·링크의 보존 증명(D-098)이나 부채 전체 완료 판정이 아니다. 기존 digest의 상태 구분 및 과거 내용의 의미/정확성 검토도 대신하지 않는다. base 생략은 의도된 standalone이며, 빈 base/알 수 없는 인자는 exit2, 건강/기준 source 조회 실패는 exit3이다. base 실패를 현재 HEAD로 대체하거나 실패를 무시하지 않는다.

## 실행과 반례

```bash
python3 -B scripts/harness-context-selftest.py
python3 -B docs/plans/evidence/D-099/check-mutants.py
bash -n scripts/harness-context.sh
bash scripts/ci-release-gate-lint.sh
bash scripts/ci-e2e-parallel-lint.sh
bash scripts/ci-test-matrix-lint.sh
```

[selftest-results.txt](selftest-results.txt)는 33개 method PASS다. 빈/누락/중복(들여쓰기 포함)/형식/status 오류, 겉보기 정상 감소·같은 수의 ID 교체, 잘못된/비어 있는 base, base 문서 누락/UTF-8 오류/git 조회 실패를 포함한다. 명시한 이전 base 대신 현재 HEAD를 쓰는 경우도 구분한다. 정상 신규 ID·완료 이동·plan archive·마지막 pipe가 없는 정상 status cell은 통과한다. 역사 객체가 없는 저장소에서 역사 대조가 skip되지 않고 실제 실패하는 경우도 포함한다.

[mutant-results.txt](mutant-results.txt)는 항상 성공/항상 거부, 빈 archive/중복 허용, base 무시/HEAD 대체, 실패한 revision/source 조회 무시, 완료 행 active 복귀 허용의 9개 잘못된 구현을 실제 fixture가 거부한 기록이다. 원본은 수정하지 않는다.

[current-health.txt](current-health.txt)와 [current-base.txt](current-base.txt)는 현행 own tree 정상 결과다. [workflow-check.txt](workflow-check.txt)는 기존 workflow 내용 전체와 비교해 새 named step 하나만 추가했음을 확인한 기록이다. 이 구조 확인은 원격 CI 실행 성공을 뜻하지 않는다. CI는 PR의 `pull_request.base.sha` 또는 main push의 `before`를 명시 전달하고 shallow일 때만 history를 fetch한다. fetch 실패도 전파한다. 앞쪽 D-098/C 전용 step 영역은 수정하지 않았다.

## 실제 역사 원문

[history.json](history.json)의 git source SHA와 bytes/text 줄/D행 개수를 구분한다.

| source | bytes | 전체 text 줄 | 첫 column의 D행 |
|---|---:|---:|---:|
| d5f1a2ac의 parent | 67553 | 59 | 44 |
| D-053 완료 반영 d5f1a2ac | 875 | 2 | 1 |
| 복구 bf678c0c | 68427 | 60 | 45 |

이 수치는 당시 TASKS-done 원문이며 현행 완료/캠페인 개수와 혼용하지 않는다. fixture는 정확한 git bytes를 읽어 사용한다. 정상 44행 원문을 base로 두고 기존 main4a4의 옛 checker에 실제 덮어쓰기 875B를 넣으면 exit0으로 잘못 통과한다. 수정 checker는 standalone과 base 비교 모두 거부하고, 실제 복구 원문 45행은 통과한다. 과거 d5f 시점에 지금의 checker를 실제 운영 실행했다는 주장이 아니다. D-052 최초 분리 사건으로 바꾸지 않는다.

`manifest.json`은 검사 source·출력의 동일성을 기록하며 검증 의미의 독립 oracle은 아니다. 별도 Codex 리뷰 미호출. 정확 head 독립 검토와 전체 원격 CI는 별도로 기다린다.

## 독립 검토 후 추가 검증

위 33/9 결과와 `manifest.json`은 초기 head `c9330783e6a25e00a318fc07952872b9e855eca9`에 귀속한다. 독립 검토 후 추가 빈 final status cell과 shallow 조회 오류의 삼킴을 수정했다. optional final border는 하나만 제거하며 escaped pipe는 cell 구분자로 취급하지 않는다. 본문 code pipe가 있어도 마지막 status 판정은 유지한다.

정확 `.github/workflows/ci.yml`의 `Harness archive health` run body를 PyYAML로 추출하여 임시 mock git/bash/python3에서 실행한다. 정상 shallow/full, query23/fetch23, 예상 외 출력(빈 값 포함), 빈 base, health 실패의 전파를 대조하며 실제 fetch는 실행하지 않는다. 기존 CI lint job이 설치하는 PyYAML을 사용한다.

[review-fix-minimal.txt](review-fix-minimal.txt)는 원래 두 결함의 최소 2 method PASS다. 수정 전체 40 method와 15 wrong-implementation control(10 CLI/5 exact-workflow)은 Actions에서 실행할 대상으로, 이 로컬 기록은 전체 통과 증거가 아니다. `review-fix-manifest.json`은 수정 source와 최소 출력의 hash이며 독립 oracle이나 전체 CI 성공 판정은 아니다.
