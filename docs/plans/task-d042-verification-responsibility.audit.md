## 2026-09-28 — 계획 리뷰
- 상태: 해당 없음(등급 M)
- 범위 변화: TASKS 행의 "적용 여부 판정" 중 적용을 제외(사용자 결정). 등급 L → M
- 뒤집힌 전제: "HTTP 생산자·소비자 호환성" — 앱 서비스 간 HTTP 호출이 없다. gateway 라우팅·Toss 로 좁혀짐

## 2026-09-28 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 검증: `./gradlew test` BUILD SUCCESSFUL (1253 테스트, 실패 0) · `scripts/*-lint.sh` 22종 통과 (`writing-lint.sh` 는 커밋·PR 대상이라 `/ship` 에서)
- 결함 주입 23건: 잡힘 20 · JVM 공백 3 (M12·M13·M14). 주입 후 `docs/` 밖 변경 0
