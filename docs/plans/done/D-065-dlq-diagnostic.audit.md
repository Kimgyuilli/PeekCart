# D-065 DLQ 실패 진단 audit

## 2026-10-21 — 계획과 실패 경계 확인

- 작업 tree /private/tmp/peakcart-worker-c-dlq-diagnostic, branch debt/d065-dlq-listener-diagnostic, base main4a4a36a.
- 별도 Codex 리뷰 미호출. 원본 CI run39572731873/head57af/checkoutac426be/seed1792572889718을 확인했다.
- E의 DB목록[] 해석을 exact source로 재대조했다. head와 checkout source SHA f365af... 모두 line193 DLT records, DB195. E와 root가 이전 해석을 철회했다. timestamp equality 미도달, 해당 마지막 poll의 원장 상태 미측정으로 기록한다.
- 계획 단계에 Docker/Gradle은 실행하지 않았다. root가 reviewer의 cleanup delta 재현에 heavy를 배정 중이며 다음 실행 전에 슬롯을 요청한다.

## 2026-10-21 — 첫 baseline과 환경 차이 정정

- exact4a4/f365 소스 무변경, session15192, seed1792572889718 전체 payment54 suites/334tests PASS, 3m48s. Dlq 첫 timestamp2.953s/trace1.807s/route2.027s. XML 전체와 stdout203845bytes는 /private/tmp/d065-dlq-baseline-1792572889718/에 보존했다. 전체 Docker inventory 전후 byte동일이다.
- CI와 local 각54 XML timestamp/filename 순서를 직접 비교한 결과 동일했다. Dlq 직전 ApprovalFenceRecoveryIntegrationTest이며 앞선 suite sequence도 동일하다.
- **환경 정정**: 첫 local 명령에 SPRING_PROFILES_ACTIVE가 없었고 실제 로그는 profile local이었다. CI workflow와 raw 실행 로그는 test를 지정한다. 따라서 첫 PASS는 CI동등 재현이 아니며 원본 실패 해결 증거가 아니다. test profile에는 logback appender가 없어 banner295bytes와 local203845bytes 차이를 설명할 수 있다. 이전 loggingcontext 가설은 이 환경차이보다 우선하지 않는다.
- 테스트 관측 Java/XML/patch는 /private/tmp/d065-dlq-observation-patch에 보존하고 원본 Java SHA256f365af380d7fbcfb90c4be33ffb52a8d8ece19ebe5b9b8e91d40062f8ebc2eef 복원 후 CI profile baseline을 시작했다(session14441). root heavy승인, SPRING_PROFILES_ACTIVE=test +cleanTest/test+원seed+기존한도. CI :build 중 assemble은 제외한다. 결과는 아직 기재하지 않는다.

## 2026-10-21 — CI profile baseline

- session14441 exit0, 1m29s, 54 suites334tests PASS. timestamp첫2.534s/trace1.769s/route1.769s. DLQ stdout295bytes가 원CI와 동일하며54 XML의 timestamp/filename 순서도 실제 동일하다. 원실패는 재현되지 않았다.
- SPRING_PROFILES_ACTIVE=test를 명시했고 Java원본 f365로 실행했다. CI Temurin17.0.20-1/x64 대비 로컬17.0.11+9/macOS, CI :build 대비 :test 범위 차이는 남는다.
- 전체 XML/log/순서 /private/tmp/d065-dlq-baseline-test-1792572889718 보존. Docker전체inventory 전후byte동일, 소유fixtures 모두정리. heavy반환 후 승인된 test-only 관측패치를 다시 적용했다.

## 2026-10-21 — 관측·음성·retention 증거와 최종 범위

- 관측Java dc00bbfa/XML4e9e3f, session17766 full334PASS/1m35s. 원20s,send10s,assignment30s와정확header/ledger조건 유지. observerdrop session53209 단일expectedfail21.084s; 실제Admin 약61ms, 원topicstart1=end1/DLTstart0,end1/verifiercommitted1. loopback무응답 session80130 단일expectedfail22.403s; Admin약1.020s후ExecutionException/loopbackclosed, 원AssertionError[]보존. outerexception만 출력해내부cause유형은직접기록하지않았으며 그한계를유지한다. 두임시변이제거/정상dc00bbfa복원.
- retention probe session32296 exit0/52s,1test36.336s. Spring없이 자기Kafka3.8.1기본정책으로old/recent같은offset0ack후old만retention삭제/recent정확값읽기를검증. 실제config/시계열/명시적삭제로그/source보존. internalinitialdelay는Admin미반환으로실측값이라고쓰지않는다. 공식3.8.1소스기본30s는가설설명이며 containerstart→삭제32.94s관측과구분한다.
- reviewer_general의읽기전용독립memo는13경계/allpaymenttest가PR201전후불변이고원장allocator가독립verifierqueue직접경로가아님을확인했다. @Primarytesthandler가ledgerfactory에도선택될수있다는기존wiring한계는별도원인증거가아니다. 원CIcause미확정유지.
- root승인으로최종fixture timestamp만실행시한번샘플링한now-60s로바꾸고정확값단언/원20s유지. 최종Java SHA256eb40e48d3c6133add9adec035b879acd2a50fa91d31e99dbdb05be3bb568dc80. 이전full334검증dc00과의차이는이표현식/설명주석뿐이다. 사용자Actions우선지시를따라추가로컬full은미실행, 최종정상CI+별도원seedLinux17.0.20:build대기.
- 별도workflow는고정seed/manual입력없음/pull_request paths와contentsread/timeout15/alwaysartifact. 실행전전체IDsnapshot은성공시atomicrename후인정하고cleanup은신규Testcontainerslabel+immutableID만선택한다. hermeticownership/manifest없는earlyfailure2대조PASS, 실제Actionscleanup은CI결과대기. 기존CIrandomization불변.
- 프로토타입retention회귀는30초기본cleanup과별도브로커생명주기를요하므로이번PR자동job에반복추가하지않았다. 실행가능원문/설정/삭제로그를보존하며최종기존DLQtest는재발행시각과구별되는정확값을검증한다. arithmetic를복제하는별도unit은추가하지않았다.
- integration-test-container-lint/consistency/shippreflight/diffcheck PASS. 모든ownedfixtures정리/전체Dockerinventory전후동일. heavy반환. Root소유TASKS/master/PHASE는수정하지않으며D065완료로표기하지않는다.

## 2026-10-21 — ship과 최신 main 통합

- Draft PR: https://github.com/Kimgyuilli/PeekCart/pull/205 . 별도 Codex 리뷰 미호출, 독립 최종 head 리뷰 대기.
- main f4ee03b97b0bec0c9be7cd3077911fb67b578b74를 정상 merge했다. 이전4a4 기반 authored patch와 새base 대비patch가 byte동일하며 SHA2567d64f032f90f1bca40a1cf7e2525989c19af578a510cd96bb5e9692b90875bb4였다. 추가main변경은문서보존workflow/도구로Java검증source와무관하다.
- test/chore/docs로명시경로분할커밋했고writinglint/shippreflight/consistency/새docs-preservation-ci(basef4ee)PASS. 처음일반제목은형식lint에서거부되어test(kafka)형식으로고친뒤통과했다.
- 원문로그는repo의*.log ignore규칙에걸리지않도록내용그대로.txt로보존했고SHA256manifest를갱신했다. 원형raw전체증거는/private/tmp각실행경로에도남아있다.
- 최종Java eb40e48d, root소유TASKS/master/PHASE/ADR완료상태변경없음. user지시에따라최종full검증은Actions로넘겼다. PR일반CI와별도original-seed job이해당최종head에서통과해야하며, 현재미충족이다. D065전체완료로표기하지않는다.

## 2026-10-21 — PR200 main 통합

- 독립 검토 comment6313081275는 exact2b24에 차단 의견이 없었다. 해당 head의 원seed Actions39579295824와 일반CI39579295780 payment는 각각334tests PASS로 확인되었고, 일반전체 결과와 병합은 별도 조건이다. Root 승인으로 draft를 ready로 전환했다.
- main bdc370a80e0bfa6adefb6a436a5f608f878d1b69를 정상 merge했다. 이전f4→2b24 authored patch와 새base→mergehead2c4a908 비교가 byte동일하며 SHA2565fa385e5ebd06847104d117fab9ce05b0fd0c8c8d2f3c899a08ec25d3aa7f988이다. 추가main은 D075 order 전용 probe/문서이며 payment 진단·workflow는 불변이다.
- 최종 Java eb40e48d 및 보존 manifest12개 hash가 모두 일치했다. 새base 문서보존gate/diffcheck PASS. 이 audit 추가 외 authored 동작 변경 없음. 로컬 full 재실행은 하지 않았으며 새 최종 head의 일반CI/원seed Actions와 통합 delta 검토를 기다린다. 병합은 아직 승인되지 않았다.


## 2026-10-21 — DLQ fixture·진단 변경 병합 후 확인

[PR205](https://github.com/Kimgyuilli/PeekCart/pull/205) 최종 head `b8cde5d722391a3d42366db15018c17dd9d84230`의 [독립 통합 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/205#issuecomment-6313390929)는 기존 코드·음성 대조 증거의 보존과 고정 seed Actions의 실제 54 suites/334 PASS 및 cleanup을 확인했다. 원20초 단언과 최대5초 best-effort 진단 경계는 유지했다.

[일반 CI39581901199](https://github.com/Kimgyuilli/PeekCart/actions/runs/39581901199)와 [고정 seed CI39581901183](https://github.com/Kimgyuilli/PeekCart/actions/runs/39581901183)가 성공한 뒤 정상 병합 SHA는 `d506f7eef5d29e9894342a6d47b93b89a34266b2`다. [main CI39584187702](https://github.com/Kimgyuilli/PeekCart/actions/runs/39584187702)는 E2E·gate와 여섯 publish 모두 성공했다.

오래된 timestamp fixture의 retention 삭제는 독립 실험으로 확인했지만 PR194의 과거 CI 실패 원인은 당시 broker 증거가 없어 미확정이다. 이 변경은 D-065의 운영 표본·실제 GKE·NOT NULL 적용·replay 개방이나 D-078의 별도 실패 분석을 완료하지 않는다. 운영 배포와 실제 replay는 수행하지 않았다.
