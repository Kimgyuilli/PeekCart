# task-d035-user-container-singleton — audit

## 2026-09-26 — 계획 리뷰
- 상태: 해당 없음(등급 M)
- 등급: M (변경은 user-service 에 한정되나 CI 공용 lint 스크립트의 검사 대상이 늘어남)
- 코드 검증(§2): 수행. 공유 인프라 기존재 · 전환 대상 4클래스 · 자율 writer 0 · 공유 상태 · lint 대상
- 범위 변화: D-035 ③(writer opt-in 전수 조사) 결과 0건 → build.gradle 기본 off 만. ④(Kafka 토픽 누적)는 user 가 Kafka 미사용이라 후속 PR 로 이월

## 2026-09-26 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 검증: `./gradlew test` 전량 BUILD SUCCESSFUL (8모듈 1253 테스트 0 실패, 263초) · integration-test-container-lint 0건 · --self-test 7/7
- 계획 대비 변경: P3 에 lint self-test 픽스처 수정 편입(대상 확대로 ITC-001 오검출) · 셔플 검출력 뮤테이션을 "자기 행만 cleanup" 방식으로 정정(계획서 정정 이력)
