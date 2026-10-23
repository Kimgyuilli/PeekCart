---
grade: M
---
# D-078 product barrier Actions 재현

## 1. 명제와 코드 전제

focused PASS나후속성공으로 원 barrier 실패원인을 해결했다고 쓰면 미완이다. 원 seed의 full product 실행, 실제 환경/순서/원 XML/log를 먼저 보존하고 실패하면 기존 진단을 읽는다. 30초 barrier·낙관충돌 최소1·재고 정합 기준은 바꾸지 않는다.

| 코드 전제 | 확인 |
|---|---|
| base | main209 c9e12d695e21c348a82da78c6577eac7b2dd4d89에서 own tree 생성 |
| 테스트 | product-service/src/test/java/com/peekcart/product/infrastructure/StockReservationLockBoundaryIntegrationTest.java SHA256083784f244e6bdb7c7348a7be420d7f61c6be6aa3928fd590e51ea698c4ad19c, 독립메모와 동일 |
| 기존 진단 | before/after repository save, barrier identity/waiters/broken/generation, monotonic phaseHistory, 5초 DB wait/TRX/thread snapshot 및 제한된 query/socket 검증이 이미 있다. 재구현하지 않는다. |
| 실행 | product-service/build.gradle이 testSeed를 받아 ClassOrderer.Random에 전달하고 scheduling/listener 기본off다. main CI는 Temurin17, SPRING_PROFILES_ACTIVE=test, :product-service:build를 실행한다. |
| 재사용 | 기존 payment-dlq-reproduction.yml의 별도 Actions/원로그 tee/always artifact/새Testcontainers만 cleanup 형태를 좁게 재사용한다. shared ci.yml·제품·기존테스트는 수정하지 않는다. |

CI 실행/증거 표면 때문에 M. 새 정책·의존성·아키텍처 결정이 없어 새 ADR은 없다. Root 소유 메모 docs/plans/evidence/D-078-product-barrier-review/README.md를 읽었으며 원실패1Timeout+4Broken과 후속 PASS/의도된 음성을 별개로 유지한다. 원5waiting만으로 동시 도착/같은 generation을 주장하지 않는다.

## 2. 작업 항목

- [x] P1. 별도 .github/workflows/product-barrier-reproduction.yml을 추가한다. 이 workflow만 PR path trigger, workflow_dispatch 지원, contents:read다. 기존 CI/gate/publish는 바꾸지 않는다.
- [x] P2. 원seed1792523372921로 full :product-service:build를 한 번 실행한다. Temurin17/test profile, sourceHEAD/대상file hash/java-version/환경/실행명령을 기록한다. 먼저 기존 --info/runner log/XML에서 실제 class/method 순서를 확인하며 테스트 본문/진단은 그대로 유지한다. 순서 자료가 부족할 때만 작은 init 기록을 후속으로 검토한다. focused 실행/로컬Gradle/Docker/추가 자동retry는 없다.
- [x] P3. 원 Gradle log/XML/HTML과 신규 Testcontainers cleanup 증거를 always artifact로 보존한다. 실패 rc는 pipefail로 workflow에 전파한다. 이번job 시작 전에 없던 Testcontainers만 정리하며 운영 명령/외부PG/GKE는 없다.
- [x] P4. 첫 Actions의 exact checkout/JDK/profile/실제순서/원결과를 기록한다. 재현 시 기존 5초진단/phaseHistory부터 읽고 모호할 때만 catch 시각+barrier state 좁은 후속을 제안한다. PASS는 한 조건의 관측이고 원인해소/부하검증이 아니다.

## 3. 검증과 후속 분리

로컬은 YAML 구조·고정 source hash·명령/실패전파의 경량 확인만 한다. 실제 product 실행은 첫 Actions에서 한다. 원상태 유지와 관측실패/빈 결과를 성공으로 취급하지 않는지 확인한다. 별도 Codex 리뷰 미호출.

D-078⑤ jitter/Kafka retry·DLQ 부하와⑥ 실제 scheduler pool4 stress는 각각 별도 reviewable PR로 계획/실행한다. 이 barrier 테스트는 proxied consumer 직접호출이며 Kafka 재시도/DLQ 체인 검증이 아니다. PR199의 cache collector 변경은 이 작업에 포함하지 않는다. MySQL2000m 천장/노드경합/GKE적용/Billing 및 원원인 미확정은 그대로 남긴다. TASKS/PHASE/master는 Root 소유다.

Root가 M계획과 최소성 조건을 승인했다. 첫실행은 별도 init/listener 없이 기존 --info/XML/원runner로그만 사용한다. 완전한 실제 실행순서를 확보하지 못하면 그 한계를 명시하고 후속기록 범위를 좁힌다.

## 재개 시 비교 기준

최종PR base는 main216 797fc30d1661353aaf3d0d878c0e34f3d0347007이다. 기존3WIP 파일을 보존해 fast-forward했고 Java hash083784…는 동일하다. 대상 barrier testcase 누락/skip을 성공으로 취급하지 않는 작은 stdlib XML 확인을 Actions 안에 추가한다. 새 진단/참여자억제 음성은 재구현하지 않는다.

## 첫 실행과 승인된 좁은 후속

PR218 ce2c964 원seed Actions39614791169의 Gradle은46XMLsuites/236testcases/0failures·errors, barrier4/0으로통과했다. job실패는후단XML guard가실제DisplayName을놓친것이며원barrier 재현이아니다. actualcheckout41e0f7e4cf1e1f8816501fc598cc9544447f78e8/JDK17.0.20.1/원XML·log·명령·cleanup을evidence에보존한다.236은XMLtestcase수이며고유Java method수로해석하지않는다.

실제XML/classname 기반좁은guard를교정했다. --info는20class의출력header만보여전체진입순서가부족했다. Root 승인후다음별도실행에작은Gradle beforeTest START1행listener를추가한다. 테스트본문과진단은불변이며추가기록이타이밍을바꿀수있으므로첫무계측PASS와분리한다. START는Gradle class/test식별자의시작이벤트이며worker의동시barrier도착/같은generation을자동증명하지않는다.

## 최종 실행 결과와 판정 범위

b4c374a 전용39615964190/일반39615963575 SUCCESS. 전용checkout370f04df83727cfd2d988e29caa70417bb3dda31, source083784…/Temurin17.0.20.1/test/원seed 동일,46XMLsuites236testcases/0failure·error·skip,barrier4/0(동시0.115s),XMLguard/cleanup6성공이다. START236unique의46class별수는XMLcase class별수와일치하고 barrier메서드는58–61/동시59다. 원자료/선택필드/해시는second evidence로보존한다.

이것은Gradle beforeTest 진입순서다. fastPASS로그에는worker arrival/identity/generation/phaseHistory 출력이없어같은generation의동시도착을직접복원하지못한다. 기존완료·unclassified0·낙관충돌최소1·재고assertions의PASS만확인한다. 첫무계측0.076s/두번째0.115s로listener영향/성능인과를정하지않는다. 원1Timeout4Broken의원인은미확정이며추가Java계측/실험을정당화하는새실패는없다. 이번좁은재현/증거수집작업만완료이고D078전체/부하·GKE정책해결을뜻하지않는다. 결과추기는docs-only이며실행source를새docsSHA로바꿔부르지않는다.
