# D-098 경량 검증 기록

구현 비교 base: `4a4a36a12a3d914d6bc0379ded94bc0123c4d370`. 실제 source fixture는 고정 `a7ffa11f24be2bd281d8952ac5fc5d351fb6119c`다. 2026-10-21 실행이며 source와 root tree는 변경하지 않았다. 구현자 확인이고 독립 검토 결과가 아니다.

```bash
python3 -B scripts/docs-preservation-selftest.py
python3 -B scripts/docs-preservation-real-selftest.py
bash scripts/plans-archive.sh --self-test
python3 -B scripts/docs-preservation-ci.py --base origin/main
python3 -B scripts/docs-preservation-check.py --base origin/main --root /Users/kimgyuill/dev/projects/PeakCart
bash -n scripts/plans-archive.sh
bash scripts/ci-release-gate-lint.sh
bash scripts/ci-e2e-parallel-lint.sh
bash scripts/ci-test-matrix-lint.sh
```

- `hermetic.txt`: 52 test method PASS(일부 method에 복수 정상/음성 subtest 포함). 실제 checker/selected archive/CI wrapper를 임시 git fixture에서 실행한다.
- `real-source.txt`: 실제 architecture 344줄을 147/197로 분리 및 D-097 plan/audit archive 정상 PASS. 문서 소속 교환·반복 빈 줄 1개 삭제·audit 줄 역전은 각각 거부한다.
- `archive.txt`: 기존 후보 판정 6개 자기검증 PASS. 후보 신호를 전체 완료 증명으로 확장하지 않는다.
- `local-ci.txt`: 현행 own tree와 위 비교 base의 CI wrapper PASS.
- `root-readonly.txt`: root 기록 작업 tree와 같은 base 비교 PASS. root-context.json의 전후 HEAD/두 task 문서 hash가 동일한 구간에서 읽기 전용 확인했다. root가 WIP checker로 발견한 5행 순서 오류를 직접 보정한 뒤 확인했다. 보정 전 WIP는 D085,D105,D097,D090,D094 순서를 D085,D090,D094,D097,D105와 비교해 실패했다. 이 과거 실패는 감사 설명이며 이 파일의 새 실행으로 재현했다고 주장하지 않는다.
- 구조 lint: release gate / E2E parallel / test matrix 모두 PASS. 별도 보존 CI step과 fetch-depth 변경 외 CI job/matrix는 그대로다.

`manifest.json`의 SHA256은 source/출력 동일성을 확인하며 보존 의미의 독립 oracle은 아니다. 실제 archive와 Gradle/Docker는 실행하지 않았다. 전체 CI 및 독립 head review는 PR에서 별도로 확인한다.


초기 PR head0775c9f의 출력은 git 이력에 남아 있다. 현재 출력은 독립 검토의 세 P2와 final-LF 없는 정상 추기를 포함해 수정한 checker에서 실행했다. 초기 footer 이동/unquoted HTML/query-fragment collision falsegreen이 발견됐으며, 수정 head의 독립 review는 별도로 기다린다. 현재 source hash와 아래 출력의 의미를 과거47개 결과와 혼동하지 않는다.
