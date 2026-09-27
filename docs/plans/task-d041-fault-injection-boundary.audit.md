# task-d041-fault-injection-boundary audit

## 2026-09-28 — 계획 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 코드 확인: D-033 run 36075940872 대조군 잡 로그로 461초를 대조군별로 분해. 고정 대기 3개가 405초(88%).
  ② 가 A 첫 줄 `create_product` 에서 죽어 주장한 불변식에 닿지 않음을 확인
- 등급 L 확정(사용자). 범위: 기존 대조군은 검출력 결함만 고치고 6종 유지(사용자)

## 2026-09-28 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 구현이 계획과 달라진 곳: P3 출력 구조화 대신 `Timeout.stage` 직접 대조(계약 표면 축소), ③ 에 원인 관측 추가(§2-4)
- 구현 중 발견: ① 의 ShedLock DELETE 해제가 poller 를 GC 전까지 멈춰 ③ 이 공허하게 통과할 수 있었다. `javap`·스레드 덤프로 확인, UPDATE 해제로 수정
- 검증: e2e 대조군 전량 ok(2회차), 시나리오 4종 ok, V3~V6 대조군 FAIL(기대대로), `--self-test` 14종, `./gradlew test` 1253 테스트 0 실패, ci-release-gate·ci-e2e-parallel·e2e-network-contract lint 통과

## 2026-09-28 — /ship
- PR: [#151](https://github.com/Kimgyuilli/PeekCart/pull/151)
- precheck: preflight ok · consistency ok · review health ok · 문체 lint(커밋 4·본문·제목) 통과
- 갱신: TASKS D-041 ✅, PHASE5 엔트리, 계획서 PR 링크
- 미충족: V7 CI 대조군 소요 대조
