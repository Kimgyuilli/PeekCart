# D-060 followup 실행 기록

- 기준: origin/main cb8056d (PR #175). 별도 Codex 리뷰 미호출. 이전 세션 검증은 이번 PR 근거로 사용하지 않는다.

## 코드 검증 및 실행
- 신규/legacy 구분은 기존 dispatch_deadline으로 구현. 신규 접수 always non-null, V12 기존 행 NULL을 확인했다. 기존 migration 수정 없음, 새 토픽/UUID 없음, replay DENY 유지.
- 수동 인수 뒤 신규 보호 승인은 UNRESOLVED+감사 필드만 유지, 이후 PG 조회 SUCCEEDED로 종결. legacy NULL deadline은 MANUALLY_RESOLVED FAILED 유지. 기존 terminal 데이터 자동복구는 없으며 ADR-0035에 진단 SQL과 별도 대사 필요를 명시했다.
- 첫 실행 payment 전체258건 중 신규 QueryService repository mock 누락1건 실패. 수정 후 최신 scoped 실행에서 Payment24건 모두 통과: 원장8, API slice9, query3, 신규 recovery4. 로그 `/private/tmp/d060-followup-focused-final.log`.
- Recovery4건은 실제 DB/HTTP stub에서 PG POST 성공 후 finalize 생략→GET 확정, CLAIMED 후 미호출→HTTP404와 stale generation 무시, manual 이후 CANCELLED+UNRESOLVED 조회/PG 성공 수렴, legacy 수동 종결을 검증한다. 실제 프로세스 kill은 이 JVM 테스트가 아니라 별도 E2E 담당 검증 범위다.
- saga matrix 34행 구조 및 전체 self-test, integration-test-container-lint, git diff --check 통과. 신규 JVM5행은 independent REQUIRED_IDS와 bracket DisplayName 함께 추가했다.
- 전체 저장소 테스트와 새 runtime E2E는 메인 통합 이후 별도 수행하며 아직 완료 주장하지 않는다.

- 최신 scoped Gradle **BUILD SUCCESSFUL 1m41s**, Payment24 + Product4 = 28건 통과. Product는 MySQL data_lock_waits >0을 확인한 뒤 winner commit하여 두 승자 순서를 검증했고 terminal 역전/복구1회/취소 선도착도 통과. 무거운 슬롯 반환. P1/P2만 완료, P3/P4는 메인 alert/E2E/전체 통합 결과까지 대기한다.

## 최신 main 통합 JVM 검증 (2026-10-17)
cb8056d 기반 통합 worktree에서 `./gradlew test :build :order-service:bootJar :product-service:bootJar :payment-service:bootJar :notification-service:bootJar --no-daemon --console=plain` 성공(8m22s, 67 tasks executed). JUnit 1,292건 실패·오류·skip 0, 아키텍처 가드 5개 및 JVM 매트릭스 34행 통과. 로그 `/private/tmp/peakcart-orchestration/ship-gradle-test.log`. 최신 E2E는 이 기록 시점 진행 중이며 이전 기준 증적을 사용하지 않는다.

최종 JVM 검증 뒤 ApprovalProperties와 application.yml의 승인 상한 주석만 실제 계약에 맞게 정정했다. 런타임 값/코드 변경이 없어 전체 테스트를 반복하지 않았다. 환불 정책은 변경하지 않았다.

## 최신 runtime E2E 최초 실행 실패
run `d060-followup-final-20261017`에서 a~d 통과, e는 실제 sweeper 대조군·보호 유지·Payment 재시작·PG 조회·FAILED 수렴·재고 복구까지 통과했으나 마지막 정상 승인 202의 Location 헤더 검사가 실패했다. #175 Controller는 accepted body만 반환하므로 이번 E2E의 조회 URL 응답 계약과 구현이 맞지 않았다. 전체 실행 exit1이며 통과로 세지 않는다. 실패 증거는 `/private/tmp/peakcart-e2e-followup/.cache/e2e/d060-followup-final-20261017`, 로그는 `/private/tmp/peakcart-e2e-runtime-final/full-e2e.log`. 헤더를 구현하고 slice 회귀 및 전체 JVM/E2E를 다시 확인한다.

Location 회귀: matcher static import 누락으로 최초 컴파일 실패 후 정정. Controller 수정 전 9개 slice 중 202 세 상태 모두 Location 단언 실패(나머지6통과), `/private/tmp/d060-location-red-test.log`. 조회 URI 헤더 추가 뒤 동일9개 통과(9s), `/private/tmp/d060-location-green.log`.

Location 수정 후 전체 test/:build/bootJar 재실행 성공(1m26s, 12 executed/55 up-to-date), JUnit1,292 실패·오류·skip0 재확인. 이전전체실행에서 변경없는 모듈결과 재사용. 로그 `/private/tmp/peakcart-orchestration/ship-gradle-test-location-final.log`.

## 최신 main 기준 최종 검증 (2026-10-17)
cb8056d 기반 전체 test/:build/4서비스 bootJar 성공(8m22s, 67 tasks executed). Location 보강 후 전체 재실행도 성공(1m26s, 12 executed/55 up-to-date), JUnit 1,292건 실패·오류·skip 0. 아키텍처 가드 5개, JVM 및 E2E 매트릭스 각 34행 통과. 최종 runtime E2E a~e와 Payment 실제 재시작, PG 조회·보호 유지·실패 해제·정상 승인 확정 성공. 증거 `.cache/e2e/d060-followup-location-final-20261017`. 관련 lint 및 self-test 통과. 최초 E2E Location 실패는 audit에 보존하고 최종 실행과 구분한다. 별도 Codex 리뷰 미호출.

## /ship 실행 결과
PR: https://github.com/Kimgyuilli/PeekCart/pull/176
preflight/consistency precheck/writing lint 통과. TASKS 완료 4행을 TASKS-done으로 이동, D-060 기존 #175 완료 행에 후속 링크 추가, PHASE5와 계획서 갱신. 운영 미해결은 D-084/ADR-0035로 추적. 별도 Codex 리뷰 미호출. 머지 미실행.
