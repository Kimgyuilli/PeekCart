# D-064 merchant order ID 감사

## 2026-10-21 계획

- 상태: 별도 Codex 리뷰 미호출.
- 기준 main `be535c5`, tree `/private/tmp/peakcart-worker-c-merchant`, branch `debt/d064-merchant-order-id`.
- 사용자의 “권장하는 방향으로 진행” 답변에 따라 신규 생성·저장·응답·동일 confirm 계약을 구현한다. webhook/409 PR #193과 분리하고 ADR-0042를 사용한다.
- Payment 생성·DTO·native approval fence·dispatch·query·recovery 및 migration을 직접 확인했다. 기존 NULL 식별자를 재작성하지 않는 방식과 실제 PG 없이 검증할 범위를 주 작업자에게 보고했다.
- 구 바이너리와 신규 checkout의 혼합 배포는 additive schema만으로 안전하지 않다는 제약을 발견했다. 접수 중지와 writer/checkout 전환 순서를 ADR에 명시하며 실제 배포는 수행하지 않는다.
- 아직 구현·테스트하지 않았다. heavy slot을 예약하지 않았다.

## 2026-10-21 구현·검증 진행

- 주 작업자가 설계를 승인했고 legacy NULL은 길이와 무관하게 새 prepare를 거절하도록 경계를 명확히 했다. 기존 유효 숫자 callback과 이미 접수된 원장 조회만 유지한다.
- ADR 선행 커밋 `1c0c770`, 계획 `4bc2fb2`.
- heavy slot을 승인받아 집중 6개 클래스 Gradle 실행을 시작했다(`--no-daemon --no-parallel --max-workers=1`). 최초 compileTestJava는 PaymentControllerTest의 추가 PaymentDetailDto fixture 인자 누락으로 실패했다. 인자를 보완하고 동일 테스트를 재실행했다.

- 집중 6개 클래스 58개 통과(14초), 로그 `/private/tmp/d064-merchant-focused.log`.
- 실제 MySQL 36개 통과(33초): MerchantOrderIdentityIntegrationTest 6, ApprovalLedgerIntegrationTest 8, ApprovalFenceRecoveryIntegrationTest 4, ApprovalRecoveryIntegrationTest 18. 로그 `/private/tmp/d064-merchant-integration.log`.
- 인바운드 재확인으로 기존 dispatcher 통합 fixture가 생성된 임의 ID 대신 상수를 쓰는 점을 찾아 저장 ID를 사용하도록 수정했다. ApprovalDispatchIntegrationTest 8개 통과(30초), 로그 `/private/tmp/d064-merchant-dispatch.log`.
- 해당 실행은 모두 `./gradlew :payment-service:test --tests '<위 클래스 이름>' --no-daemon --no-parallel --max-workers=1`로 필요한 클래스를 선택했다. 테스트 소유 컨테이너 종료 후 `docker ps --filter label=org.testcontainers=true` 0개 확인. heavy slot 반환.
- 전체 새 파일 포함 소스·테스트 snapshot SHA256: `a3ad40e945734c4684d16eebfe362d9ae4e7053f2734299a39a8b7e769efd7aa`. 웹훅 PR #193 통합 전 로컬 검증이다.

## 2026-10-21 선행 PR 통합 검증

- 주 작업자의 지시에 따라 PR #193의 고정 head `6886c19ce634a57b27c0f3aadc355dc019dfb774`를 merge commit `0f33db7`로 반영했다. 선행 브랜치는 수정하지 않았다. ADR index의 두 추가 행 충돌은 0041/0042를 모두 유지해 해결했다.
- 실제 merge commit이 여러 기존 분류를 포함하므로 기본 mixed hook이 거절했다. 이미 분류별 커밋된 선행 작업의 merge에 문서화된 `HPX_ALLOW_MIXED=1`을 사용했다. 새 구현 커밋은 분류별로 유지했다.
- WebhookAdmissionIntegrationTest가 새 서비스 인자·executor constructor 및 저장된 PG ID를 사용하도록 조정했다. 중간 IN_PROGRESS HTTP fixture도 실제 identity 필드를 포함하도록 보완했다.
- `./gradlew :payment-service:test --tests '*WebhookAdmissionIntegrationTest' --tests '*PaymentControllerTest' --tests '*MerchantOrderIdentityIntegrationTest' --no-daemon --no-parallel --max-workers=1` 통과(34초): controller 28, webhook 실제 MySQL 10, merchant 실제 MySQL 6. 로그 `/private/tmp/d064-merchant-merged.log`, 세션 76658 종료. 테스트 컨테이너 0개 확인 후 heavy slot 반환.
- 최종 migration-grant/integration-container lint, consistency precheck, PR 제목·본문 writing lint 및 diff check 통과. 전체 필수 CI와 독립 검토는 대기다.

## 2026-10-21 /ship 실행

- 초안 PR: https://github.com/Kimgyuilli/PeekCart/pull/196 (branch `debt/d064-merchant-order-id`, target main).
- 선행 PR #193의 head `6886c19`에 의존한다. merchant delta는 이 head와 비교하며 #193 merge 이후 실제 main 반영·최종 검토·전체 CI 확인이 필요하다.
- 최종 검증된 소스/테스트는 `7c31e6d`의 내용이다. 이후 커밋은 계획·감사 문서뿐이다. 테스트 보완 전 코드는 `87ed489`, 테스트는 `b000065`, 계약 문서는 `8e6bd93`으로 분리했다.
- ship preflight, grade L, consistency precheck, 명시적 staged category, commit/PR title/body writing lint 및 diff check 통과. 별도 Codex 리뷰 미호출. 전체 필수 CI와 세션 독립 검토는 대기다.
- 완료 문서는 ADR-0042/index 및 03/05/06의 승인받은 영역과 본 계획·감사에 반영했다. TASKS·진행 문서·마스터 계획은 주 작업자가 소유한다. 실제 PG·외부 webhook 수신·운영 checkout 전환은 미검증이며 D-064는 부분 완료다.

## 2026-10-21 독립 검토 P1: E2E 소비자 누락

- 독립 검토자가 #196 `651044a`에서 저장소 E2E 승인 3곳이 prepare/pgOrderId 없이 새 결제를 접수하는 오류를 확인했다. PG stub 성공 응답에는 totalAmount가 없고 fence 조회에는 identity가 없어 신규 검증에서 종결할 수 없었다. 기존 로컬 JVM 검증을 전체 E2E 통과로 확대 해석하지 않는다.
- 범위를 P7로 확장했다. A/E의 모든 실제 승인 호출은 소유자 prepare 응답을 거쳐 동일 PG ID를 전달하며, stub 호출 원장의 ID/금액까지 대조한다. stub은 최초 confirm의 identity를 저장하고 재요청 불일치·타입/길이 오류를 거절한다. 승인 없는 기존 합성 환불 script 의미는 유지한다.
- prepare helper의 양음 대조 10개와 PG stub의 기존 chunked 9개 및 확장 HTTP 계약 32개를 통과했다. 기존 CI stub.py --self-test 진입점에서도 확장 검증을 호출한다. 실제 E2E 및 수정 head 독립 재검토는 아직 대기다.

## 2026-10-21 E2E P1 보완의 실제 검증

- 첫 실제 시도 `d064-2af347c-ae`는 Payment V14가 적용됐지만 runner 기대 목록이 V13까지여서 readiness에서 실패했다. 실패 로그와 cleanup 증적을 보존했다. 기대 목록을 수정했고 네 서비스 모두 실제 SQL 파일 버전 목록을 readiness와 같은 검증 함수에 넣어 누락·추가·대체를 거절하는 16개 대조를 추가했다.
- 중간 `d064-f70b82d-ae`는 새 runner/이전 image tag의 혼합을 피하려고 E 진행 중 의도적으로 중지했다. A가 통과했어도 이 실행은 최종 증거로 사용하지 않는다. 두 시도 모두 자기 Compose 프로젝트만 정리했으며 기존 momens 3개는 유지했다.
- `bash scripts/saga-e2e-smoke.sh --self-test`: 59개 통과. `python3 scripts/e2e/pg-stub/stub.py --self-test`: 기존 chunked 9개와 HTTP identity 계약 32개 통과. 처음 sandbox loopback bind 제한으로 실패한 HTTP self-test는 승인된 로컬 실행으로 재검증했다. Python compile, shell syntax, migration/container lint, consistency, commit writing 및 diff 검사도 통과했다.
- 실제 검증 소스: `bb944c39cfa09a56ea4730914d1565c3af95e9b4`. 4개 이미지는 이 commit의 정상 Dockerfile로 빌드했고 tag는 `d064-merchant-bb944c3`이다. 기존 앱 소스/build 입력은 그대로여서 cache를 사용했다. metadata를 위조하거나 image gate를 변경하지 않았다.
- image ID: order `sha256:7b9552f78e1b235c906214ba8d777da044eaf75bfb27137865267f2d50be38d2`, product `sha256:8de4c11a0dce95eda722a1307f916ceb61ff57e279fda0dd77cb6ff331403130`, payment `sha256:4c7321867526989057e6a56b471323141102850d240da4f3d0335ba2e9ee4a54`, notification `sha256:b4e6e88eab6532fbba13bf00cf35e58f5943474052ba69f4c1bc62da334720de`.
- 실행: `E2E_RUN_ID=d064-bb944c3-ae E2E_COMMIT_SHA=bb944c39cfa09a56ea4730914d1565c3af95e9b4 PEEKCART_IMAGE_TAG=d064-merchant-bb944c3 bash scripts/saga-e2e-smoke.sh --scenarios a,e`. 세션 67747 exit 0. 로그 `/private/tmp/d064-e2e-ae-matched.log`, 증적 `.cache/e2e/d064-bb944c3-ae/`.
- readiness 통과, A 27.1초, E 298.7초. host durations는 A 29초/E 301초(프로세스 준비·감시 포함)이며 E가 기존 300초 예산에 근접했다. 시간 정책은 바꾸지 않았다. A는 CANCELLED/FAILED 및 재고 복원을 확인했다. E는 실제 sweeper와 Payment JVM 재시작 동안 HELD를 보존하고, 승인 POST 1회·재시작 뒤 GET·실패 확정 후 재고 반환을 확인했다. 별도 성공 주문은 APPROVED/SUCCEEDED 및 CONFIRMED 예약에 도달했다. 세 승인 모두 prepare에서 받은 canonical ID와 stub 원장을 대조했다.
- E2E 진단 중 읽기 전용 SQL의 잘못된 attempt_count 컬럼명 조회는 실패했고 실제 schema의 generation/claimed_at로 바로잡았다. fixture 데이터는 수정하지 않았다. cleanup 뒤 실행 컨테이너는 기존 momens 3개뿐이었고 heavy slot을 반환했다.
- 이후 변경은 본 계획·감사 문서뿐이다. 로컬 A/E 결과를 B/C/D/F·음성 대조 또는 실제 PG 검증으로 확대하지 않는다. 최종 pushed head의 필수 전체 CI와 독립 재검토는 대기다.

## 2026-10-21 actual main 통합 및 product CI 실패 조사

- #193을 승인받아 head `184c19c`에서 정상 merge했다. 실제 main merge는 `a7ffa11f24be2bd281d8952ac5fc5d351fb6119c`, main CI는 39483390397이다. merchant branch에 이를 정상 merge한 head는 `aa33234`다. 기존 merchant 전체 authored diff `6886c19..5030e31`과 `a7ffa11..aa33234`는 byte-identical이며 SHA256 `8d20a619c5e4cf44a9fdd44e3ca0fd23021c4c1ba97661cf20cba5f2bb4cd647`이다.
- #196 head `5030e31`의 CI 39480677850은 product 234개 중 StockReservationLockBoundaryIntegrationTest의 동시 예약 1개가 실패했고 gate가 실패를 전파했다. E2E 시나리오·음성 대조는 통과했지만 전체 CI 통과로 주장하지 않는다.
- 원본 log `/private/tmp/d064-merchant-ci39480677850-failed.log`, artifact `/private/tmp/pr196-product-failure-artifact`를 보존했다. XML의 실패는 optimisticConflicts=0이며 실행 30.088초, seed `1792523372921`이다. 기존 test의 barrier는 30초다. 각 worker Throwable을 버리고 optimistic count를 먼저 단언했기 때문에 실제 원인은 artifact에 없다. 시간 일치만으로 barrier 원인을 확정하지 않는다.
- merchant authored 변경은 product code/test/config를 건드리지 않았고 공유 ErrorCode에는 PAY-014/015만 추가했다. 기존 테스트 자체의 문제인지 환경 경합인지는 추가 증거가 필요하다.
- 승인받은 진단 보완은 repository 실제 save 전후·barrier 도달/통과와 transaction 상태 및 unexpected Throwable 전체 stack을 보존하고, 원인 단언을 먼저 수행하며 finally에서 worker 종료를 기다린다. barrier/optimistic/stock 기준은 바꾸지 않는다. 원 seed full product suite는 아래와 같이 재현됐으며 최종 CI는 아직 대기다.

- `./gradlew :product-service:test -PtestSeed=1792523372921 --no-daemon --no-parallel --max-workers=1`은 1분44초 뒤 같은 테스트가 실패했다(세션 68702, `/private/tmp/d064-product-seed-diagnostic.log`). 이번에는 order 1004의 TimeoutException 1개와 다른 네 worker의 BrokenBarrierException을 보존했다. 다섯 worker의 마지막 phase는 barrier 대기이며 txActive=true였으나, 마지막 map은 동시 도착 증거가 아니다. 첫 timeout 뒤 나머지가 늦게 도착한 경우도 구분해야 한다.
- 최초 CI XML SHA256 `8d2854e9570e4f8ed9ba7c08ef0eeb117e6491e330a04c3d472a584965f96b20`, 첫 로컬 재현 XML은 `/private/tmp/d064-product-diagnostic-first.xml` SHA256 `a186813d57c631105d8e2e135c732d2d9948275ade5783bb446a23ea707c15f3`로 별도 보존했다. 테스트 컨테이너 종료 후 기존 momens 3개만 확인하고 heavy slot을 반환했다.
- root가 승인한 다음 관찰은 원래 동기화 위치와 30초 제한을 유지하며 elapsed history·barrier identity/대기수/generation, timeout 전 MySQL lock wait/transaction 및 ThreadMXBean monitor owner/full stack을 수집한다. DB 또는 Java 잠금 원인은 아직 미확정이며 관찰 실행은 대기다.

- 같은 관찰 patch로 targeted 실행(세션 63881, `/private/tmp/d064-product-observation.log`)은 33초, 테스트 1개 0.593초로 통과했다. 이어 원 seed full product(세션 46439, `/private/tmp/d064-product-observation-full.log`)는 1분9초, 234개/실패 0/skip 0으로 통과했다. 해당 클래스는 2개 0.198초였다. 각각 XML을 `/private/tmp/d064-product-observation-targeted.xml`, `/private/tmp/d064-product-observation-full.xml`로 보존했다.
- 두 실행 모두 5초 전에 완료되어 MySQL/ThreadMXBean snapshot 분기는 실행되지 않았다. 이 통과를 최초 CI/로컬 timeout 원인 해결로 해석하지 않는다. 단독과 전체 suite에서 관찰 patch의 컴파일·정상경로를 확인했으며, timeout 당시 SQL/Java 잠금 원인은 미확정이다. 근거 없이 barrier 위치·제한·재고/낙관락 기준을 바꾸지 않았다. 종료 후 테스트 컨테이너 0개, 기존 momens 3개만 확인하고 heavy slot을 반환했다.

- root 승인으로 명시적 진단 음성 대조를 수행했다. worker 1004만 실제 consumer 진입 전에 `NEGATIVE_CONTROL withheld barrier participant` 예외를 내도록 임시 주입했고 나머지 4개는 실제 경로와 원래 30초 barrier를 유지했다. targeted 동일 명령은 세션 34166, 1분3초 exit 1, test 30.529초로 예상대로 실패했다(`/private/tmp/d064-product-diagnostic-negative.log`).
- 5초 분기가 실제 실행되어 SQL locks=[] 및 RUNNING transaction 4개, 같은 barrier identity 1390486136에 도달한 4개 worker의 TIMED_WAITING/CyclicBarrier stack과 불참 worker를 기록했다. 원인 출력에는 주입 예외와 TimeoutException/BrokenBarrierException이 포함됐다. XML `/private/tmp/d064-product-diagnostic-negative.xml`, 전체 snapshot `/private/tmp/d064-product-diagnostic-negative-snapshot.txt` 보존. 이는 진단 경로 검증이며 최초 실패의 원인 증거가 아니다.
- 주입 코드를 제거하고 원본 Java 파일 SHA256 `b429efbaabb10a30b06c87c59d84df9b0b2f28b1e35633e0e154551dfc87da38` 일치를 확인했다. 이는 앞서 234개 통과한 파일과 동일하다. executor finally 종료 및 테스트 컨테이너 정리를 확인했고 기존 momens 3개를 유지한 채 heavy slot을 반환했다.
- root는 제한된 진단을 최종 테스트에 유지하고 최초 실패·미확정 원인·이후 통과를 구분하도록 승인했다. product 원인 분석 잔여는 D-078 관련 후속이며 merchant 제품 결함 해결로 표시하지 않는다. 새로운 head의 전체 CI와 독립 검토는 대기다.

- 최종 product 진단 소스는 `c27602b` test commit에 분리했다. merchant 기존 코드·E2E와 main 통합은 `aa33234`이며, 진단 소스는 위 234개 통과 및 음성 대조 후 원복 hash와 같다. ship preflight, grade L, consistency, migration/container lint, PR writing 및 diff 검사를 통과했다. PR #196에 push하여 최종 전체 CI 및 순차 독립 검토를 요청한다. merge는 승인되지 않았으며 draft를 유지한다.
