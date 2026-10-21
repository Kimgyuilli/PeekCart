---
grade: M
---
# D-065 MVC 권한 거부 응답 보존

## 1. 명제와 근거

실제 MVC와 Security filter를 통과한 USER 권한 거부가 403이 아니거나 익명 거부가 401이 아니면 미완이다. 다른 서버 오류를 함께 403으로 바꾸어도 미완이다.

PR210 첫 Actions39587253206에서 네 리소스 모두 실제 User login/JWKS→Gateway 서명→ADMIN disabled200을 통과한 뒤 USER가500으로 실패했다. `GlobalExceptionHandler.handleException(Exception)`이 `DeadLetterEndpoint.replay`의 AccessDeniedException을 잡아 SYS-001로 변환했다. Security의 ExceptionTranslationFilter까지 예외가 전달되지 않았다. source43a1745 기준 공통 handler/security handler와 원본 서비스 로그를 대조했다. 실제 운영 권한 정책이나 replay 개방 문제로 해석하지 않는다.

## 2. 범위와 결정

별도 branch `fix/d065-access-denied-mvc`/자기 worktree에서 common handler와 common-auth의 작은 MockMvc 회귀만 수정한다. common은 이미 security-core를 의존하며 common-auth는 Security/Web/test를 갖추므로 의존성·새 framework·설정 계층은 추가하지 않는다.

catch-all에서 AccessDeniedException만 그대로 rethrow하여 기존 ExceptionTranslationFilter 및 JwtAccessDeniedHandler(403/SYS-004), JwtAuthenticationEntryPoint(401/USR-004)가 판단하게 한다. 일괄403 매핑은 anonymous401을 잃을 수 있어 선택하지 않는다. 다른 business/validation/500 매핑은 건드리지 않는다. 기존 보안 계약 구현의 결함 수정이며 신규 ADR 결정은 없다. 등급 M: 모든 MVC 서비스의 공통 handler지만 변경은 특정 예외 전달뿐이다.

## 3. 작업과 검증

- [x] P1. actual4서비스 실패와 공통 handler/Security 경로를 대조하고 좁은 수정 계획을 정한다.
- [ ] P2. actual GlobalExceptionHandler, ExceptionTranslationFilter, 프로젝트 두 Security handler를 쓰는 standalone MVC 회귀로 USER403/anonymous401/unrelated500을 검증한다. 원 handler에서 회귀 실패를 Actions로 먼저 보존한다.
- [ ] P3. AccessDeniedException만 rethrow한 뒤 동일 회귀와 전체CI를 통과한다. 기존 오류 메시지나 요청 내용이 추가로 노출되지 않아야 한다.
- [ ] P4. 독립 검토와 PR210 위 stack의 네 실제 signed HTTP 경로를 검증하고 증거 범위를 기록한다.

로컬 Gradle/Docker는 실행하지 않는다. 별도 workflow 없이 기존 CI의 platform/common-auth shard를 사용한다. 최초 회귀-only draft의 예상 red도 실제 결과 전 PASS/재현으로 적지 않는다. 이후 제품 fix head의 새 Actions를 요구한다. PR210은 이 fix를 명시적으로 의존한 별도 검증 PR로 유지하며 병합은 root 직렬 승인 뒤에만 한다. 공유 TASKS/progress/master는 수정하지 않는다.
