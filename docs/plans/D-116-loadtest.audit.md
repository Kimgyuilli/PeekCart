# D-116-loadtest audit

## 2026-10-26 19:38 — 계획 리뷰
- 상태: 해당 없음(등급 M)
- 게이트: blocked(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.). 등급과 무관하게 호출하지 않았다.
- 등급 M 근거: 테스트 fixture 경로가 바뀐다(판정 기준 2). 코드 모듈은 건드리지 않고, 지운 원자료는 base 커밋에서 되살릴 수 있다.
- 결정 해석: 1-3 의 "인용된 run" 은 10-25 `w6`, 10-26 `w4`, `invalid/contend-retry2` 사고 증적이다. 1-4 의 대체물 없는 원본 2개는 발췌를 만든 뒤에도 로컬에 둔다.

## 2026-10-26 20:06 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 검증(계획 §3, 대조군은 괄호):
  1. 복원: `pruned.tsv` 84행(삭제 62, 토큰 제거 22) 실패 0 (blob 한 글자 변조 → 실패 1).
  2. 집계 재현: 42블록 불일치 0, 지운 run 은 base blob 으로 재실행. 조사 때 따로 낸 집계 71행과 모두 같음 (TSV 한 행 삭제 → 불일치 1).
  3. 참조 0: 판정 대상 0건 (가짜 링크 → 2건 검출). 예외 안 166건은 정리 기록과 정리 전 인벤토리, 파일명만 같은 언급 23건은 10-25 촬영 계획표·요약 파일·다른 run 의 같은 이름 TSV.
  4. 토큰: 22파일 실패 0, `loadtest/reports` 의 JWT 모양 문자열 0 (metrics 값 변조 → 실패 1).
  5. fixture: 정상 통과, 새 fixture 를 치우면 실패(errors=3), reports 원본을 치우면 통과.
  6. 발췌: ERROR·WARN 67/67, INFO 251/251, k6 이벤트 823/823, 다시 만들어도 같음. D-112 오류 문구 33건이 발췌에도 33건.
  7. CI lint 잡 로컬 실행: Document preservation·DLQ contract·test matrix·Harness archive health 통과. policy lints 는 `replay-entrypoint-lint.sh` 만 로컬 실패(gitignore 된 `docs/learning/` 사본 2건, D-106·D-108 audit 와 같은 원인)이고 그 뒤 15개 명령은 따로 돌려 전부 통과.
  8. 크기: `loadtest/reports` 추적 바이트 39.14MB → 24.19MB, 830 → 777 파일.
- `./gradlew test --continue`: BUILD SUCCESSFUL, 54개 작업 전부 up-to-date. Java·리소스 입력이 바뀌지 않았고 실행 중 `loadtest/reports` 를 읽는 테스트는 없다(주석 1곳만).
- `docs/consistency-hints.sh`: 처음에 exit 1. 새 문서가 파일 없는 ADR 번호(결번 두 개와 다음 번호)를 접두사와 함께 적어서다. 표기를 바꾼 뒤 exit 0. 같은 함정을 상위 계획 P4 항목에 적었다.
- 계획과 달라진 것: 기록에 쓰려던 "서명키가 지워졌다" 는 확인하지 못해 뺐다. 토큰의 `kid` 는 `peekcart-dev-2026` 이고 30분 만료라는 것만 확인해 적었다. 측정 run 의 kafka-lag export 는 결정 범위 밖으로 보고 남겼다.

## 2026-10-26 20:32 — /ship
- PR: https://github.com/Kimgyuilli/PeekCart/pull/251 (base main `91cfa1f`)
- 커밋 6개(docs 3 · src 2 · chore 1). 분류 검사 통과, writing-lint(커밋 6건·본문·제목) 통과
- precheck: consistency ok(warnings 0), `hpx_review_health` ok, 보존 검사 `--base 91cfa1f` PASS, `harness-context.sh --check --base 91cfa1f` ok
- 갱신: TASKS D-116 행에 PR 링크(5개 PR 중 첫 번째라 진행 중 유지), PHASE5 엔트리. 상위 계획의 P1 상자는 머지 뒤 다음 세션이 채운다
