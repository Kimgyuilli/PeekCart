# D-065 MVC 권한 거부 감사

## 2026-10-21 — actual HTTP 반례와 최소 계획

- PR210 firsthead4268af5, Actions39587253206: images6 SUCCESS/admission4 FAILURE. 네 baseline 모두 ADMIN exactdisabled200 뒤 USER expected403/actual500이다. 각 결과 complete=false이며 남은 음성·DB after 대조는 미실행이다. 각 cleanup6containers/1network/1volume 및 secret scan 성공을 별도 확인했다.
- 원본 `/private/tmp/pr210-signed39587253206`와 payment job rawlog를 보존했다. payment services.log415부터 generic GlobalExceptionHandler가 AccessDeniedException을 SYS-001로 소모하는 stack을 확인했다. 나머지 세 서비스도 같은 실패다.
- root는 별도 최소 product fix와 실제 MVC+Security 회귀를 승인했다. 별도 Codex 리뷰 미호출. 현재 plan/test 작성 단계이며 최초 regression-only Actions 결과는 아직 없다.

## 2026-10-21 — 원 handler 실제 회귀 실패

- 독립 PR211 regression-only head47457c366ba1d12519b18a356e638f4d02ea13bc의 Actions39588457231 platform119183132923은 완료 FAILURE다. 실제 XML3 tests/2 failures/0 skipped/0 errors/1.073초. authenticated expected403/actual500, anonymous expected401/actual500, unrelated500은 PASS다. test 컴파일/실행이 완료되어 예상 실패와 환경 실패를 구분했다.
- 원본 XML과 platform job log를 gzip 및 raw/gzip hash manifest로 보존했다. 전체 workflow의 다른 job 진행 상태를 이 platform 반례와 혼동하지 않는다.
- 이제 generic advice가 AccessDeniedException만 그대로 다시 던지도록 import와4줄 guard를 추가한다. 기존 JwtAuthenticationEntryPoint는401/USR-004, JwtAccessDeniedHandler는403/SYS-004다. 새 head의 동일 회귀/전체CI/실제 signed4체인은 아직 대기다.
