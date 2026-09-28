# task-d030-outbox-dlq-alert-split — audit

## 2026-09-28 — 계획 리뷰
- 상태: 해당 없음(등급 M)
- 뒤집힌 전제: TASKS D-030 의 "DLQ 적재량 메트릭 부재" 는 이미 해소됐다(`dlq.backlog` gauge + alert). "두 알림이 한 Slack 채널에 섞인다" 는 notification-service 에만 해당한다(3서비스는 no-op). 범위를 Outbox FAILED alert 신설로 재정의했다(사용자 선택 1안)

## 2026-09-28 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 검증: `./gradlew test` BUILD SUCCESSFUL — 1255 테스트 0 실패. Java 변경이 없어 49개 태스크 전부 up-to-date(캐시 결과)
- lint: observability-promql(본선 exit 0, self-test 19종 통과) · observability-ssot · kustomize-namespace · servicemonitor-selector 전부 exit 0
- 결함 주입: outbox 규칙 삭제 → `필수 alert rule 부재` exit 1, `status="failed"` 제거 → `alert 식이 계약과 다르다` exit 1. 계약 추가 전에는 새 규칙이 검사 없이 통과했다(lint exit 0) — 계약이 실제로 작동한다는 대조군
- 계획 외 변경: self-test 건수 문구 17→19 (`observability-promql-lint.sh`, `.github/workflows/ci.yml` 주석). 하드코딩 숫자라 같이 고쳐야 했다

## 2026-09-28 — /ship
- PR: https://github.com/Kimgyuilli/PeekCart/pull/155
- precheck: preflight ok · consistency ok(경고 0) · review_health ok · writing-lint 커밋 4·본문·제목 통과
- 갱신: TASKS D-030 ✅ + 범위 재정의 기록, PHASE5 작업 이력 엔트리. 부채 신규 등록 없음(이월 4건은 PR 본문 §미충족)
