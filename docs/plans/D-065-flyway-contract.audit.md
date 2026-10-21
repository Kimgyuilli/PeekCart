# D-065 Flyway contract audit

## 2026-10-21 — 계획

- 별도 Codex 리뷰 미호출. root가 실제 Flyway 후속과 Actions 우선 검증을 승인했다.
- 새 자기 worktree는 main f4ee03b, PR202 코드 의존 없음. 기존 raw SQL/classifier 증거를 actual Flyway 이력 증거로 전용하지 않는다.
- 실제 Boot3.5.12 BOM에서 Flyway11.7.2 확인. 네 서비스 원본 migration14/13/15/9, backfill11/9/9/7을 직접 읽었다.
- 전용 tag/task/JVM에서만 자기 SharedContainers MySQL을 restart한다. 일반 test 기존 목록/randomization은 보존한다. 별도 child JVM helper를 사용해 새 객체와 새 JVM을 구분한다.
- 현재 계획 단계이며 새 Gradle/Docker 실제 실행 없음. 제품 migration/공유 TASKS/progress/master 수정 없음. PR205 수정 요청 우선 복귀.

## 2026-10-21 — 구현과 Actions 검증 준비

- 전용 tag/task, 새 JVM helper, 네 서비스 각각 fresh/upgrade/guard/checksum/partial-DDL와 실제 자기 MySQL restart 경계를 구현했다. 예상 child JVM76회(서비스당19), migrate48/validate28, 의도된 실패28회다. 이는 아직 실행 결과가 아니다. 각 child30초, restart30초, JDBC connect2초/socket3초/query5초, test10분/workflow15분으로 제한한다.
- 원본 migration SHA256와 후보 fixture SHA256를 분리하고 before/after history·schema·ledger·outbox·allocator 및 각 child JSON/log를 artifact로 보존한다. 실제 Flyway는 최소8권한 계정으로만 호출한다. repair/clean/baseline 실행 기능은 없다.
- 기존 정상 payment334개는 이전 test-profile baseline54 XML의 classname/displayname multiset으로 보존했다. parameterized displayname 두 쌍은 중복 횟수까지 비교한다. 일반 task에서 새lab만 제외하고 randomization은 보존한다. 별도 task는 정확1개lab,4서비스,76child 및 성공·실패 분포가 빠지면 증거검사가 실패한다.
- 로컬 artifact-verifier13 controls와 container singleton lint/diffcheck/consistency는 PASS. 이 controls는 synthetic JSON/XML 검증이며 actual Flyway/SQL 증거가 아니다. 로컬 Gradle/Docker 미실행이다.
- ship preflight는 미완 P2–P7 때문에 blocked를 반환했다. 사용자 Actions 우선 및 root의 draft PR 생성 지시에 따라 미실행 항목을 체크하지 않은 채 draft로 Actions를 시작한다. 완료판정이나 gate 우회가 아니며 최종 head의 일반CI·전용workflow와 독립검토가 남는다. 공유TASKS/progress/master는 수정하지 않는다.

- Draft 전 main bdc370a80e0bfa6adefb6a436a5f608f878d1b69를 정상 통합했다. f4 기반 de28f28 작성 patch와 bdc 기반 cbba917 patch가 byte동일(SHA2566a7515aef8bbce518df35fe32815a4b329466959d9bb2cf55ce19b0514e191b2)이다. 문서보존gate(basebdc), commit writinglint도 PASS.
