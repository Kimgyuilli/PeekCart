# D-065 constraints audit

## 2026-10-21 — 계획 작성

- 상태: plan-only. 별도 Codex 리뷰 미호출. 독립 검토 통과를 주장하지 않는다.
- 기준 main a98ae7d; 선행 PR201 b288e3a는 별도 검토/CI 중이며 본 단계의 테스트 결과가 아니다.
- 확인: 기존 INSERT IGNORE, 네 DB backfill, OutboxEvent discriminator, repository 집계·purge, raw INSERT fixture, 최소 권한 GRANT, ADR-0020/0021 및 선행 ADR-0043.
- 중단 조건: old writer 부재를 SQL로 증명 불가, 모호한 legacy 자동 보정 금지, MySQL DML rollback과 다중 DDL partial state 구별.
- 실행하지 않음: MySQL/Gradle, migration 작성·실행, 운영 데이터/환경 접근. 계획 형식 및 문서 lint는 별도 실제 결과로 기록한다.

- 실제 형식 확인: task ID validation 및 plan grade L 확인, 새 문서 whitespace 검사 통과. writing-lint는 commit/PR 전용이므로 계획 본문 검사로 주장하지 않는다.
- consistency precheck: warnings(exit 1). 현재 main 기준 tree에는 선행 PR201의 ADR-0043 파일이 없어 MISS가 발생했다. 선행 PR을 명시한 계획 참조이며 파일을 복제하거나 기존 ADR을 고치지 않았다. 실제 선행 main 통합 후 재검증한다.
