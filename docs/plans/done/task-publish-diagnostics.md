---
grade: S
---
# Publish 실패 출력 보존

## 명제

main publish 실패 시 Docker의 stdout/stderr가 사라지거나 실패 후 이미지 승격이 진행되면 미완이다.

## 작업 항목

- [x] P1. docker push 출력을 tee로 CI 로그와 runner 임시 파일에 보존한다.
- [x] P2. pipefail을 유지하고 실패 코드 전파·후속 승격 차단을 로컬 probe로 확인한다.
- [x] P3. 같은 SHA CI와 독립 검증 및 병합 후 main publish 결과를 확인한다.

## 검증

stdout·stderr를 내고 exit 17인 가상 push를 동일 pipefail/tee 구조로 실행해 exit 17, 로그 두 줄 보존, 후속 승격 미실행을 확인했다. ci-release-gate-lint 및 self-test 24/24 통과. 기존 unknown blob의 근본 원인은 미확정이며 이 변경으로 해결되었다고 주장하지 않는다. 재시도나 gate 완화는 없다.

## 완료 증거

[PR179](https://github.com/Kimgyuilli/PeekCart/pull/179)의 head392901c/base9275556와 실제 checkout f2c01383b482bcdedf0eb48b969abb4333b82df9에 대해 CI38979426567 및 독립 검증 PASS. root 병합 SHA3e1ba64c715d22b1f5a2d2635a97da17eb4ebb28의 main CI38981041166은 23개 job, gate, 6개 publish 모두 성공했다. 독립 검증자가 공개 GHCR 6서비스의 해당 SHA/latest manifest digest와 config 일치를 확인했다. 전체 digest 및 증거는 상위 orchestration audit의 복구 main 검증 절과 PR 본문에 보존했다. 별도 codex exec 리뷰는 호출하지 않았다.
