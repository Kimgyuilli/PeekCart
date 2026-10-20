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
