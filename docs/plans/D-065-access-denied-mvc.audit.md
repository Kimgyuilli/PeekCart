# D-065 MVC 권한 거부 감사

## 2026-10-21 — actual HTTP 반례와 최소 계획

- PR210 firsthead4268af5, Actions39587253206: images6 SUCCESS/admission4 FAILURE. 네 baseline 모두 ADMIN exactdisabled200 뒤 USER expected403/actual500이다. 각 결과 complete=false이며 남은 음성·DB after 대조는 미실행이다. 각 cleanup6containers/1network/1volume 및 secret scan 성공을 별도 확인했다.
- 원본 `/private/tmp/pr210-signed39587253206`와 payment job rawlog를 보존했다. payment services.log415부터 generic GlobalExceptionHandler가 AccessDeniedException을 SYS-001로 소모하는 stack을 확인했다. 나머지 세 서비스도 같은 실패다.
- root는 별도 최소 product fix와 실제 MVC+Security 회귀를 승인했다. 별도 Codex 리뷰 미호출. 현재 plan/test 작성 단계이며 최초 regression-only Actions 결과는 아직 없다.
