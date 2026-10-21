# D-065 MVC 권한 거부 감사

## 2026-10-21 — actual HTTP 반례와 최소 계획

- PR210 firsthead4268af5, Actions39587253206: images6 SUCCESS/admission4 FAILURE. 네 baseline 모두 ADMIN exactdisabled200 뒤 USER expected403/actual500이다. 각 결과 complete=false이며 남은 음성·DB after 대조는 미실행이다. 각 cleanup6containers/1network/1volume 및 secret scan 성공을 별도 확인했다.
- 원본 `/private/tmp/pr210-signed39587253206`와 payment job rawlog를 보존했다. payment services.log415부터 generic GlobalExceptionHandler가 AccessDeniedException을 SYS-001로 소모하는 stack을 확인했다. 나머지 세 서비스도 같은 실패다.
- root는 별도 최소 product fix와 실제 MVC+Security 회귀를 승인했다. 별도 Codex 리뷰 미호출. 현재 plan/test 작성 단계이며 최초 regression-only Actions 결과는 아직 없다.

## 2026-10-21 — 원 handler 실제 회귀 실패

- 독립 PR211 regression-only head47457c366ba1d12519b18a356e638f4d02ea13bc의 Actions39588457231 platform119183132923은 완료 FAILURE다. 실제 XML3 tests/2 failures/0 skipped/0 errors/1.073초. authenticated expected403/actual500, anonymous expected401/actual500, unrelated500은 PASS다. test 컴파일/실행이 완료되어 예상 실패와 환경 실패를 구분했다.
- 원본 XML과 platform job log를 gzip 및 raw/gzip hash manifest로 보존했다. 전체 workflow의 다른 job 진행 상태를 이 platform 반례와 혼동하지 않는다.
- 이제 generic advice가 AccessDeniedException만 그대로 다시 던지도록 import와4줄 guard를 추가한다. 기존 JwtAuthenticationEntryPoint는401/USR-004, JwtAccessDeniedHandler는403/SYS-004다. 새 head의 동일 회귀/전체CI/실제 signed4체인은 아직 대기다.

## 2026-10-21 — 최소 수정의 실제 통과와 main206 정렬

- fix head d882fecf830e261e78fb6b890aca27b73911cc86의 Actions39588911211 platform119184872320 SUCCESS. 동일 MVC XML3 tests/0 failures/0 errors/0 skipped/1.265초이며 USER403/anonymous401/unrelated500을 각각 통과했다. common-auth 전체60 tests도 실패0이다. 고친 XML과 raw/gzip hash를 보존했다. 실제4서비스 경로는 PR210 새 실행39589065938에서 진행 중이며 아직 성공으로 기록하지 않는다.
- root 병합 순서에 따라 actual main206 3bb01bbc3190a9aa38a7b4e6faec7bb767117808을 정상 merge했다. 이전43a→d882와새3bb→mergehead의 전체 작성patch가 byte동일(cmp 및 SHA256bb4569b26c867acb71d2bfb01572c49dd16008d125a0cfd01de3efc3bb659779)이다. 이 감사/증거 추기 외 제품5줄과 회귀3개는 불변이다. 최신base의 일반CI/독립검토/직렬승인은 남는다.
