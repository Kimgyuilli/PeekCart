# D-065 contract migration audit

## 2026-10-25 03:55 — 계획 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 게이트: `hpx_codex_allowed` 1행 `blocked`, source `file`, origin `.cache/codex-off`. 등급 `hpx_plan_grade` L.
- 전제 확인: 최신 migration 번호(V14/V13/V15/V9), expand·backfill·ID 예약 네 벌의 sha256 동일, writer 의 root·kind 명시,
  NULL 전제 조회·테스트 위치, D-092 SQL 결합(runbook·통합 테스트·SQLite fixture), lab 과 Flyway lab 이 contract 이전
  상태만 가정하는 지점, PR 트리거 경로를 파일로 확인했다. Flyway 11.7.2 의 `BEFORE_MIGRATE` 호출 위치와 실패 행 기록
  조건, SQL callback 탐색·중복 이름 예외는 gradle 캐시 jar 를 디컴파일해 확인했다.
- 레인 경계: 레인 C 소유 `OutboxPollingService` 의 NULL 주석은 고치지 않고 §미해결에 남긴다.
