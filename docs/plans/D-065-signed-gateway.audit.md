# D-065 signed gateway audit

## 2026-10-21 — 계획 작성

- 상태: plan-only. 별도 Codex 리뷰 미호출. 독립 검토 통과를 주장하지 않는다.
- 기준 main a98ae7d. 기존 saga의 gateway 제외/DUAL_ACCEPT와 실제 User/Gateway RSA 키 도메인·SIGNED_ONLY·ADMIN guard·disabled 무감사 반환을 소스로 확인했다.
- 설계: 별도 로컬 실제 JVM 체인, 실제 signup/login, 테스트 소유 DB의 ADMIN fixture, 네 서비스 kill-switch false 고정, DB/outbox 불변 양음 대조.
- 실행하지 않음: Docker/Gradle/실제 HTTP 스택, 운영 환경, 외부 PG. 계획 작성만으로 전체 경로를 검증했다고 기록하지 않는다.

- 실제 형식 확인: task ID validation 및 plan grade L 확인, 새 문서 whitespace 검사 통과. writing-lint는 commit/PR 전용이므로 계획 본문 검사로 주장하지 않는다.
- consistency precheck: warnings(exit 1). 현재 main 기준 tree에는 선행 PR201의 ADR-0043 파일이 없어 MISS가 발생했다. 선행 PR을 명시한 계획 참조이며 파일을 복제하거나 기존 ADR을 고치지 않았다. 실제 선행 main 통합 후 재검증한다.
