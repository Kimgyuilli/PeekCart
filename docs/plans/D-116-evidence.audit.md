# D-116-evidence audit

## 2026-10-26 22:40 — 계획 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 등급 L 근거: 증적 364파일 이동(구조 변경)과 CI 게이트인 보존 검사기의 manifest 형식·판정 변경.
- 범위 변화: 상위 계획 P3 을 P3a(이 계획)와 P3b(`D-116-docs-move`)로 나눴다. 검사기 변경은 상위 계획에 없던 작업이고 근거는 계획 §2.1·§2.4-1 이다.
- 사용자 사전 승인(2026-10-26): CI 통과·리뷰 수렴 뒤 직접 머지, 새 판단은 보수적 기본값으로 진행하고 기록.

## 2026-10-26 23:25 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 검증(계획 §4, 대조군은 괄호):
  1. 셀프테스트: `docs-preservation-selftest.py` 62/62(새 사례 9), real-selftest, `plans-archive.sh --self-test` 통과. 구현을 하나씩 되돌린 검사기 사본에서 새 사례가 실패했다(이름만 보는 필터 → 같은 이름 사례 1건, resolve 의 routes 삭제 → 3건, routes 파일 검증 삭제 → 2건). 사본을 임시 폴더에서 돌릴 때 공통으로 나는 ERROR 6건은 형제 스크립트 부재로, 변이 없는 사본에서도 같다.
  2. 보존: `--base 7d4fa4d` PASS (D-075 route 를 뺀 manifest → `Destination ordered content differs`, D-089 README 한 줄 삭제 → 같은 실패).
  3. corpus 대조: 파싱 가능한 추적 md 에서 옛 증적 경로로 해석되는 링크 0건, 깨진 증적 링크 0건. 파싱 불가 문서 중 증적을 가리키는 것은 PHASE4 하나이고 그 링크는 D-104 안내 파일로 이어진다.
  4. 파일 대응: base 364파일이 모두 새 경로에 있다. 내용이 바뀐 것은 README 5개(바깥 링크 깊이, 도구 위치 절)와 D-078 도구 2개(`parents[3]`)뿐이다. 옛 자리에는 대응표와 D-104 안내 파일만 남았다.
  5. D-104: 옛 README 와 새 README 의 링크 정규형이 같다(53줄). 도구 위치 절은 그 뒤에 추기했다.
  6. 도구: D-078 `check-legacy.py`·`check-mutants.py` 가 새 위치에서 이동 전 기록과 같은 마지막 3줄(출력 sha256 포함)을 낸다. 렌더러 셀프테스트 통과.
  7. CI lint 잡 단계를 ci.yml 원문대로 로컬 실행: 6단계 전부 PASS. `harness-context.sh --check --base 7d4fa4d` ok, `plans-index.sh --check` 최신, consistency precheck ok.
  8. 앵커 7곳과 기존 manifest 이동·삭제 0건.
- `./gradlew test --continue`: BUILD SUCCESSFUL, 54개 작업 up-to-date(Java 입력 무변경).
- 계획과 달라진 것: README 없는 폴더가 계획의 5개 외에 `campaign-records-review` 도 있어 6개에 README 를 더했다(명제 그대로). D-075·D-078·D-104 README 에 옛 명령 경로를 설명하는 "도구 위치" 절을 추기했다(D-075·D-078 은 manifest literal).
- 실행 메모: 이동 도구가 D-104 안내 파일을 쓰다 폴더 부재로 한 번 멈춰, 이동분만 되돌리고 다시 돌렸다. 커밋하지 않은 검사기 변경은 그 전에 커밋해 두었다.

## 2026-10-26 23:45 — /ship
- PR: https://github.com/Kimgyuilli/PeekCart/pull/253 (base main `7d4fa4d`)
- 커밋 13개(docs 11 · chore 2). D-087 폴더 이동 커밋은 142파일 순수 rename 이라 100파일 기준으로 나누지 않았다. 분류 검사 통과, writing-lint(커밋 13건·본문·제목) 통과
- precheck: consistency ok, `hpx_review_health` ok, 보존 검사 `--base 7d4fa4d` PASS, `harness-context.sh --check --base 7d4fa4d` ok
- 갱신: TASKS D-116 행에 PR 링크(진행 중 유지), PHASE5 엔트리. 상위 계획의 P3a 칸은 머지 뒤 다음 단계가 채운다
