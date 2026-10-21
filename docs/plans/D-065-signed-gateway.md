---
grade: L
---
# D-065 signed gateway — 닫힌 replay 진입점의 실제 인증 체인

## 1. 명제

평문 X-User 헤더나 mock 인증으로만 ADMIN 도달을 확인했거나, USER/위조 토큰이 replay 경로에 접근하거나, kill-switch를 열어야 성공하는 테스트라면 닫힌 진입점의 signed gateway 검증은 미완이다.

2026-10-21 현재 **plan-only**. 기준 main `a98ae7d51e3f266a0d9a89996f4a2dd2483af4eb`. 기존 saga A–F와 별도로 실제 User 발급/JWKS→Gateway 검증·내부 서명→네 리소스 서비스 SIGNED_ONLY→ROLE_ADMIN 가드→닫힌 replay 응답을 HTTP로 확인한다. replay를 발행하지 않으며 D-065 전체 완료 증거로 확대하지 않는다.

## 2. 배경과 코드 확인

| 확인 대상 | 실제 계약 | 계획에 반영 |
|---|---|---|
| `docker-compose.e2e.yml`, `scripts/e2e/saga_e2e.py` | gateway 제외, DUAL_ACCEPT 및 평문 X-User 호출 | 기존 saga 성공을 signed 경로 증거로 사용하지 않음. 기존 시나리오 의미 보존 |
| `gateway/.../GatewayAuthenticationFilter`, `InternalTokenIssuer` | 외부 JWT 검증 후 trusted header 제거, Gateway RSA 내부 토큰 발급 | 실제 JVM과 HTTP 사용; 테스트용 인증 bypass/controller 금지 |
| `gateway/.../application.yml` | 네 서비스별 admin summary/numeric ID route와 rewrite, upstream override | 네 실제 서비스까지 각각 연결. 임의 actuator/traversal 노출 음성 대조 |
| `user-service/.../AuthController`, `JwtTokenSigner`, `JwkController` | signup/login과 RS256 token/JWKS 제공 | 실제 User 발급과 JWKS를 사용; Gateway와 다른 키 도메인 유지 |
| `peekcart-common-auth/.../InternalTokenProperties` | SIGNED_ONLY 기본, 내부 공개키 필수, fid 필수 | 구성에 SIGNED_ONLY를 명시하고 평문 헤더만으로 보호 endpoint 호출 시 거부 확인 |
| `DeadLetterEndpoint.transition/replay` | flat action/actor/reason, replay는 ROLE_ADMIN 검사 | 유효 actor 포함하여 role 가드까지 도달. 잘못된 body 오류를 USER 거부로 오인하지 않음 |
| `DeadLetterReplayService.replay` | root 조회/잠금 후 disabled 사유 반환; 닫힌 경우 감사 기록도 안 씀 | ADMIN은 정확한 disabled 사유/accepted=false, DB·outbox 불변을 확인. lastReplayBy 저장을 기대하지 않음 |

등급 L: User/Gateway/공유 auth/네 리소스 서비스와 테스트 Compose 경계를 함께 검증한다. 운영 계약 변경은 목표가 아니며 새로운 운영 환경은 만들지 않는다. ADR-0017 내부 서명과 ADR-0022 닫힌 진입점의 기존 결정을 검증하므로 별도 ADR은 현재 불필요하다. 키 소유권·권한·route·kill-switch 계약을 바꿔야 할 결함을 발견하면 구현 전 ADR 판단을 다시 한다.

PLAN-BLINDSPOTS: 모듈 이동은 없음. B1b의 경로·환경변수 문자열과 B4 실제 메커니즘을 점검한다. B12 관점에서 닫힌 응답은 replay claim/drain/동시성 fence의 증거가 아니다. 실제 ROLE_ADMIN 진입과 발행 가능성 검증을 구분한다.

### 로컬 구성과 안전 경계

- 별도 Compose/runner에서 실제 User, Gateway, order/product/payment/notification JVM과 필요한 MySQL/Redis/Kafka를 자기 project로 실행한다. 기존 saga fixture는 그대로 유지한다. build source SHA, Dockerfile label, image ID 일치 게이트를 재사용하며 수동 재라벨로 대체하지 않는다.
- 로컬 실행마다 User용/Gateway용 RSA 키를 별도로 생성한다. private key는 임시 파일을 issuer 컨테이너에만 mount하고 키·토큰·암호를 로그/아티팩트에 남기지 않는다. 리소스 서비스는 Gateway 공개키만 가진다. production/local-keys의 기존 비밀을 읽지 않는다.
- 실제 signup으로 USER 두 명을 만든다. 한 명의 role만 **테스트 소유 User DB fixture**에서 ADMIN으로 설정한 뒤 실제 login으로 ADMIN 토큰을 발급받는다. 관리자 생성 API/운영 role 변경은 추가하지 않는다. 일반 USER도 실제 login 경로를 거친다.
- 네 리소스 서비스에 SIGNED_ONLY와 replay.enabled=false를 명시한다. 식별 가능한 독립 root fixture를 각 DB에 넣고 before-image/outbox 수를 기록한다. Gateway 요청에 직접 내부 토큰을 주입해 양성 경로를 우회하지 않는다.
- 전체 assertion과 정리까지 유한 timeout을 두고 자기 project만 종료한다. 다른 세 momens 컨테이너와 다른 worker 컨테이너는 건드리지 않는다. CI 연결은 소유자와 조정한다.

## 3. 작업 항목

- [x] P1. 현 E2E의 DUAL_ACCEPT 범위, 서명 키 도메인, route, endpoint와 닫힌 replay 동작을 소스로 확인한다.
- [ ] P2. 기존 saga와 독립적인 로컬 signed-stack 구성과 실제 로그인 fixture를 준비한다. source/image identity와 안전한 cleanup을 보존한다.
- [ ] P3. 네 서비스 ADMIN 닫힌 replay와 USER/미인증/위조 헤더 거부를 실제 HTTP·DB 대조로 구현한다.
- [ ] P4. 잘못된 외부 서명·만료·route, 직접 서비스 평문 호출 등 음성 대조를 추가한다. 실패가 올바른 경계에서 발생했는지 상태와 DB로 확인한다.
- [ ] P5. 로컬 전체 체인 실행, 각 음성 대조의 실패 주입, 관련 lint 및 전체 CI 결과를 보존한다. CI에서 실제 실행되도록 연결하되 비용/시간 변화와 모듈 검사 의미를 유지한다.
- [ ] P6. docs06의 실제 검증 범위와 D-065 남은 운영 전제를 기록한다. D084 복구 흐름은 공유 fixture를 재사용할 수 있는 별도 계획/PR로 제안한다.

## 4. 검증 방법

| 조건 | 실제 요청과 반증 대조 | 결과 및 불변식 |
|---|---|---|
| ADMIN 양성 | 실제 로그인→Gateway `/api/v1/admin/deadletter/{service}/{id}`, 유효 flat replay body | 네 서비스 각각 `accepted=false`와 정확한 disabled 사유. 인증/route 성공을 일반 200 하나로 판단하지 않음. root/outbox/audit before-image 동일 |
| USER 권한 | 동일 body/ID에 실제 USER JWT; 위조 X-User-Role ADMIN도 동반 | 403, DB/outbox 불변. 인위적 404/입력 오류가 아니라 ADMIN 가드의 거부 |
| 미인증/위조 | bearer 없음, malformed/다른 키 서명, 만료 외부 토큰 | 보호 route 401, 원장 불변. 외부 토큰 검증 실패와 유효 USER 403을 구분 |
| 내부 신뢰 | 직접 서비스 보호 endpoint에 평문 X-User ADMIN만 전송 | 401, 상태 불변. 실제 chain 양성에는 직접 내부 토큰 사용 금지 |
| 경로 제한 | 숫자 외 ID, traversal 인코딩, 임의 actuator 경로 | route 밖 호출이 보호 리소스를 노출하지 않음; response/body와 모든 fixture 불변 대조 |
| 키 분리 | 잘못된 Gateway 공개키를 주입한 음성 구성 후 정상 구성 복구 | 외부 로그인 성공만으로 내부 검증을 건너뛸 수 없음. 기대한 내부 거부 후 정상 체인 복구 |
| cleanup/증거 | 준비/요청 실패를 주입하고 종료 | fixture project만 정리; 로그에 개인 키/토큰 없음; source/image SHA 기록 유지 |

rate limiter가 권한 거부를 429로 가리지 않도록 계정/요청 순서와 정상 설정의 창을 고려한다. 시간을 늘리거나 인증 가드를 약화시켜 통과시키지 않는다. 단위 서명 테스트는 실제 User/JWKS/Gateway 체인을 대체하지 않는다. heavy 슬롯을 root에게 받은 후 Docker/Gradle을 실행하며 계획 단계에는 실행하지 않는다.

## 5. 완료 조건과 미해결

네 서비스의 실제 signed 경로와 권한 양음 대조, DB 불변, cleanup·source 증거 및 전체 CI가 있어야 이 로컬 범위를 닫는다. `lastReplayBy` 영속 및 실제 replay claim/publish/drain은 kill-switch false 범위를 넘으므로 완료로 세지 않는다. GKE 적용/ACL, 운영 timestamp 표본 P22, production 키·토큰·데이터, PG 호출, 운영 replay 개방은 제외한다. constraint/backfill은 별도 D-065-constraints 계획이다. D084 결제 복구는 별도 PR로 검토하며 여기서는 수행하지 않는다. D-065 전체는 PARTIAL을 유지하고 TASKS/PHASE/master는 root가 갱신한다.
