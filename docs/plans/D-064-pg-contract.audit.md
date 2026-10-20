# D-064 PG 계약 보정 감사

## 2026-10-21 계획·공식 계약 확인

- 상태: 별도 Codex 리뷰 미호출.
- 원본 기준: main `0e40b6a`, 전용 tree `/private/tmp/peakcart-worker-c-pg`, branch `debt/d064-pg-contract`.
- Toss 공식 webhook events / quick reference 7.2 / authorization / core reference를 직접 확인했다. 일반 결제 서명 부재·중첩 data·409 처리 중·orderId 6~64자 계약과 현재 코드를 대조했다.
- 사용자 권장안 승인: canonical merchant-order ID 생성·저장·응답 계약은 별도 후속 ADR(예약번호 0042)/PR로 분리한다. 현재 PR은 webhook와 승인 409 보정이다.
- 보안 설계: 16KiB raw stream 상한, event/schema allowlist, 알려진 UNRESOLVED 승인 행 잠금, 서버 유도 중복 키의 INSERT IGNORE 영향 행 수, 한 트랜잭션 로그+nudge. 한번의 힌트 후 주기 조회의 liveness를 유지한다.
- 경계: 실제 PG·키/secret 조회·실 결제/환불·GKE 없음. HTTP fixture를 실제 PG 증적으로 사용하지 않는다. D-064는 부분 완료다.

## 2026-10-21 로컬 검증

- 첫 Gradle 호출은 sandbox의 기존 Gradle wrapper cache lock 권한으로 시작하지 못했다. 승인된 상승 실행을 사용했다.
- 첫 compileTestJava는 새 JSON fixture의 문자열 escape 오류로 실패했다. 해당 테스트 입력을 수정했다.
- focused: `./gradlew :payment-service:test --tests '*WebhookServiceTest' --tests '*PaymentControllerTest' --tests '*TossPaymentClientTest' --no-daemon --no-parallel --max-workers=1` 통과(19초). 로그 `/private/tmp/d064-focused-tests.log`.
- 실제 MySQL 첫 실행: 10개 중 9개 통과. rollback 시험용 CREATE TRIGGER가 binary logging 환경의 SUPER 권한 부족으로 생성 단계에서 실패했다. 권한을 늘리지 않고 테스트 DB에 임시 CHECK(claimed_at IS NOT NULL)를 추가/삭제하는 주입으로 변경했다.
- 실제 MySQL 최종: `./gradlew :payment-service:test --tests '*WebhookAdmissionIntegrationTest' --no-daemon --no-parallel --max-workers=1` 10/10 통과(35초, XML 2.232초). 로그 `/private/tmp/d064-integration-tests.log`. 동시 8개 알림과 다른 status 재전송, 상태별 no-op, unknown key, SQL UPDATE 실패에 따른 INSERT rollback, 승인 409의 미결 및 조회 전용 확정을 확인했다.
- 테스트 종료 후 `docker ps --filter label=org.testcontainers=true` 결과 0개. 다른 컨테이너를 중지하지 않았다. 실행 슬롯을 주 작업자에게 반환했다.
- integration-test-container lint 및 self-test 56/56, migration-grant lint, diff check 통과. 전체 JVM/필수 CI와 최종 커밋 독립 검토는 대기 중이다.
- 새 파일까지 포함한 최종 payment-service 소스/테스트/설정 snapshot SHA256: `b11f43de343c3f87059a8193c717da5f81cdb1f2565d9471e380c94363182dd5`. focused 테스트 이후 production 코드 변경은 없고 rollback 주입만 최종 통합 테스트 전에 수정했다.

## 2026-10-21 /ship 실행

- 초안 PR: https://github.com/Kimgyuilli/PeekCart/pull/193 (branch `debt/d064-pg-contract`).
- main `be535c5`를 merge commit `aad5a2b`로 반영했다. main 변경은 harness이며 위에서 검증한 payment-service 파일 변경은 없다.
- 코드 `02233ca`, 테스트 `5174e78`, 설정 `38ce748`, ADR `d86d375`, 문서 `e16224f`로 분리했다. 각 명시적 staged category check와 최종 ship preflight, grade L, consistency precheck, commit/PR title/body writing lint, diff check를 통과했다.
- 완료 문서: 본 계획·감사, ADR-0041과 index, 기존 ADR-0023의 적용 범위 안내, 요구사항과 13-3 결제 테스트 시나리오를 갱신했다. 공유 TASKS·진행 문서·마스터 계획은 주 작업자가 별도로 관리하며 본 PR에서 변경하지 않았다.
- 별도 Codex 리뷰 미호출. 전체 필수 GitHub Actions와 최종 커밋 독립 검토는 대기 중이다. 실제 PG·외부 webhook 수신 미검증 및 후속 merchant-order ID 계약 때문에 D-064 전체는 부분 완료로 유지한다.
