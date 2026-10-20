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
