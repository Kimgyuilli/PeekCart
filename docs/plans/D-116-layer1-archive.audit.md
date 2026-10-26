# D-116-layer1-archive audit

## 2026-10-27 01:40 — 계획 리뷰
- 상태: 해당 없음(등급 M). 게이트도 blocked(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 등급 M 근거: 문서만 옮기고 되돌림이 이 PR 의 파일과 manifest 안에서 끝난다. 계약 표면 무변화.
- 사용자 사전 승인(2026-10-26): CI 통과·리뷰 수렴 뒤 직접 머지, 새 판단은 보수적 기본값으로 진행하고 기록.

## 2026-10-27 01:40 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 검증(계획 §3, 대조군은 괄호):
  1. 보존: `--base fbb839c` PASS (아카이브 한 줄 삭제 → `Destination ordered content differs`, Layer 1 에 남은 줄 한 글자 변경 → 같은 실패).
  2. 구조: 바뀐 6개 문서 펜스 짝 정상, 새 연속 빈 줄 0(03 의 2곳은 base 에 원래 있다), 포인터 9개와 아카이브·색인의 링크 대상 실재.
  3. 범위: 블록 14개의 첫·끝 줄을 시험 실행 출력으로 대조했다. 처음 정의에서 §9-4·§10-5 가 여는 펜스에서 끊겨 "다음 절 앞" 끝 지정으로 고쳤고, 04 §9-3 의 도입 시점 두 줄을 diff 머리글에서 찾아 더했다. 옮긴 제목을 가리키는 인바운드 링크는 없다(보존 검사 통과).
  4. CI lint 잡 단계 원문 실행 6단계 PASS, `harness-context.sh --check --base fbb839c` ok, `plans-index.sh --check` 최신, consistency precheck ok, 앵커·기존 manifest 이동/삭제 0건.
- `./gradlew test --continue`: BUILD SUCCESSFUL(Java 입력 무변경).
