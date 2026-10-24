# D-078 scheduler capacity audit

- main7915f26 own tree/branch debt/d078-scheduler-capacity. Root가 실제 product ConfigData/SchedulingConfig/Boot scheduler와 테스트 probe의3/4blocker/pool1 유한 검사만 승인했다. 실제7잡 payload/운영부하 적정성은 제외하며 미측정으로 남긴다.
- 기존 spring pool4/조건부 @EnableScheduling/StockSchedulerWiringIntegrationTest와 Boot3.5.12 실제 jar API를 확인했다. 수동 executor나 새 의존성/설정층/CI workflow는 추가하지 않는다. PR218/retry-load/source product 정책은 변경하지 않는다. 별도 Codex 리뷰 미호출.

## 구현 및 Actions 제출 준비

- 새 SchedulerPoolCapacityTest만 추가했다. ConfigData의product-service 이름/pool값, 실제Boot taskScheduler/core size, 등록된probe의정확target/method를검사한다.3/4blocker와pool1상태는start latch/미완료Future/release확인으로통제하고, due task와@Scheduled probe의진행/정체/회복을둘다확인한다. finally release와모든blocker Future완료를검사하며watchdog도유한하다. 테스트용probe1개만등록하고실7잡은측정하지않는다.
- product/common production Java/설정/기존test/CI workflow변경0. Testcontainers/DB/Kafka/Thread.sleep/새의존성없음. 실제Boot3.5.12 ContextConsumer.accept가checked Throwable을허용함을jar javap로확인했다. 로컬 Gradle/compile/full suite는미실행이며Actions가정식3case검증이다.
- integration-test-container-lint/scheduler-lock-contract-lint/보존/whitespace PASS. 잘못된파일명 scheduler-wiring-lint 시도는파일없음exit127이었고실제scheduler-lock-contract-lint로정정해PASS했다. hpx_ship_preflight blocked3(미실행항목),consistency0warnings. 사용자 Actions-first/Root draft-pending허용에따라미완checkbox유지. 별도 Codex 리뷰 미호출.

## 최초 실행 실패와 등록 증거 교정

- PR220 https://github.com/Kimgyuilli/PeekCart/pull/220 ,2a3b2fe,CI39622466849/product119299244681/checkouta9fccda. 원XML3tests3fail/line75의Task.OutcomeTrackingRunnable 직접type가정이원인이다. configuration/core/빈/등록수선행assertion은통과했으나capacity marker0으로blocker검사는미실행이다. 원자료/해시는own evidence에보존했다.
- Root 승인후registered Runnable.run으로정확probe.tick호출을현재검사threadcounter로확인한다. 다른target/등록없음 음성과별도이후자동tick을확인해manual호출이나경쟁timer로오탐하지않는다. 기존점유/start/release/watchdog/Future/pool1기준은변경하지않았다. 새Java는Actions검증대기이며로컬Gradle/compile/full suite는실행하지않았다. capacity PASS/완료주장없음. 별도 Codex 리뷰 미호출.
