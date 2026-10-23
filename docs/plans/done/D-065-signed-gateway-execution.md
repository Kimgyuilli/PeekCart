---
grade: L
---
# D-065 signed gateway 실행 계획

## 1. 명제와 기준

실제 User login/JWKS, Gateway 외부 JWT 검증·내부 RSA 발행, 리소스 SIGNED_ONLY, ADMIN 권한 가드 중 하나라도 mock/평문 양성 요청으로 대체하면 미완이다. ADMIN의 응답이 단순200이거나 replay disabled 사유와 DB 불변을 대조하지 못하면 미완이다. USER403을 body400/잘못된경로404/요청제한429로 대체해도 미완이다.

2026-10-21 계획 단계. 자기 tree `/private/tmp/peakcart-worker-c-signed`, branch `debt/d065-signed-gateway`, 기준 actual main bdc370a80e0bfa6adefb6a436a5f608f878d1b69다. PR202의 `D-065-signed-gateway.md` 선행 계획을 재검토하며 동일 파일을 두 PR에서 수정하지 않기 위해 실행 계획을 분리했다. PR202 Python lab 코드 의존은 없다. PR207 Flyway 최초 Actions/리뷰 실패와 PR205 통합·병합 요청을 우선 처리한다.

사용자 지시에 따라 실제 이미지 빌드/Compose/HTTP 검증은 Actions가 우선이다. 로컬은 소스·정적·hermetic 검사만 한다. 운영 키·토큰·DB·GKE·replay 개방·실제 PG 호출은 금지 범위다. root 소유 TASKS/progress/master는 수정하지 않는다.

## 2. 다시 확인한 코드 전제

| 원문 | 사실과 검증 반영 |
|---|---|
| `docker-compose.e2e.yml` | 기존 saga는 User/Gateway 없이4서비스 DUAL_ACCEPT다. 이 성공을 signed 경로 증거로 재사용하지 않고 기존 saga 의미를 보존한다. |
| `gateway/application.yml` | 서비스별 admin route는 숫자ID만 `/actuator/deadletter/{id}`로 rewrite한다. User JWKS URI와 서비스 upstream은 환경 연결정보로 override 가능하다. management는8081별도다. |
| `GatewayJwtVerifier` | User JWKS RS256, exp 필수 및 서명·만료 검증. 외부 신원 family_id를 Gateway가 내부 fid로 전달한다. |
| `InternalTokenIssuer/Properties` | Gateway private key는 파일 위치로 주입, 별도 active kid,30초ttl/family필수. User 서명 키와 혼용하지 않는다. |
| `User AuthController` | 실제 signup/login 및 BCrypt/refresh family 경로. 테스트 DB에서 한 계정 role만ADMIN으로 바꾼 뒤 login한다. 공개 관리자 생성 API를 추가하지 않는다. |
| `DeadLetterEndpoint` | replay body는 flat action/actor/reason이다. 비어있는actor는 ADMIN 검증 전에 반환되므로 유효actor로 권한을 검사한다. USER는 AccessDeniedException403이다. |
| `DeadLetterReplayService` | root 잠금 뒤 replay.enabled=false이면 exactdisabled 사유로 반환하며 lastReplayBy/outbox를 바꾸지 않는다. 실제발행/감사영속증거로 확대하지 않는다. |
| `ci-image-identity.sh`와 Dockerfile | 현재 정본은 archive SHA256와 actualimage ID record/load 비교다. 기존 Dockerfile에는 source label이 없으므로 label검사를 이미 수행했다고 쓰지 않는다. 같은Actions checkout의 SHA/생성metadata와 archive·loadID를 함께 기록하고 수동재라벨로 대체하지 않는다. |

등급 L: User/Gateway/공유auth/4리소스에 걸친 실제 체인이다. 제품 계약은 바꾸지 않으며 ADR0017/0022의 기존 계약 검증이므로 신규 ADR은 현재 필요하지 않다. 제품 결함/보안정책 변경이 필요하면 근거를 root에 먼저 전달한다. 문자열 route/환경변수/JWK kid, 자기fixture 정리의 실패경계와 증거누락 falsegreen을 검토한다.

## 3. 구성과 파일 범위

별도 `docker-compose.signed-gateway.yml`, `scripts/signed-gateway/` runner/fixture/selftest/evidence, 전용 `.github/workflows/signed-gateway.yml`, 이 plan/audit 및 좁은 테스트 문서가 범위다. 기존 ci.yml 및 saga runner는 수정하지 않는다. build image identity helper는 기존 record/load 명령을 재사용한다.

Actions의 build matrix6개(User/Gateway/4리소스)는 같은 checkout Dockerfile과 서비스별캐시로 이미지를 만들고 archive+ID+checksum을 보존한다. 실행 matrix4개는 각 User/Gateway/해당리소스3JVM만 기동해 한runner의6JVM 메모리 경합을 피한다. MySQL/Redis/Kafka/HTTP runner는 자기 Compose project의 internal:true 네트워크에만 붙이며 host port/외부egress를 열지 않는다. PG/Slack 외부 연결은 필요 없고 payment baseURL은 도달불가 자기fixture sentinel로 명시한다. 자율scheduler/listener는 이번 닫힌HTTP 계약과 무관하므로 기존 off 설정으로만 격리한다.

User/Gateway/공격자용 RSA 키를 매실행 별도로 생성한다. User·Gatewayprivate key는 각각 자기issuer에만 파일mount하며 리소스는 Gatewaypublic key만 가진다. Userpublic은 actualJWKS로 제공하고 Gateway에 staticmirror를 넣지 않는다. runner는 실제login access token을 메모리에만 보관하며 privatekey/token/password는 artifact/log에 넣지 않는다. 키값이 없는 kid/공개keyfingerprint/sourceSHA/imageID/요청유형/HTTPstatus/fixturebefore-after만 증거로 남긴다.

매트릭스마다 실제signup USER2명을 만든 뒤 자기UserDB 한계정만ADMIN으로 fixture승격하고 실제login한다. 리소스별 knownroot fixture와 outbox 전체before-image를 보존한다. baseline DB를 seed하는 것도 이미적용된 실제Flyway history와 파일명대조 뒤에만 한다. root_record_id를 직접 명시하며 production allocator/oldwriter rollout 증거로 확대하지 않는다.

## 4. 작업 항목과 검증

- [x] P1. 기존계획과actualmain의User/JWKS/Gateway/리소스경계·이미지identity정본을 읽고 실행scope를 분리한다.
- [x] P2. 독립Compose/키생성/3JVM matrix/실제login·knownroot fixture와 boundedstartup·HTTP·cleanup을 구현한다.
- [x] P3. ADMIN signed양성은4서비스 각각id/rootId일치, accepted=false, exactdisabledrejection,attemptId없음과전체ledger/outbox불변으로 확인한다. USER정상JWT에 X-User-RoleADMIN 위조를 동반해도403이어야 한다.
- [x] P4. 미인증/malformed/다른키서명/만료외부토큰/직접서비스평문ADMIN/route외부노출/잘못된내부공개키 음성을 실제HTTP로 검증한다. 각각정상경로복구와DB불변을 함께 확인한다.
- [x] P5. 자기fixture의준비실패·요청실패·artifact누락·foreignprojectcollision 정리 대조를 작성한다. source/image/4서비스/case누락과공개artifact의privatekey/JWT누출이green이되지않게guard한다.
- [x] P6. Actions실제build/4matrix/음성/정리결과와일반CI·독립검토로최종head를확인한다. docs06변경은root의좁은소유범위를받아실제증거만추가한다.

만료토큰은 claims를변조한서명실패로대체하지않는다. 자기User issuer의짧은TTL fixture단계를별도로사용해실제login발급→exp경과→Gateway401을확인하고정상TTL과정상login을복구한다. 검증기의만료/skew/서명정책은변경하지않는다. 정상token이나issuerprivate key를runner에제공해직접양성내부token을만드는방법은사용하지않는다.

내부공개키음성은리소스의공개키mount만외부공격자public key로바꾼뒤동일실제ADMINlogin요청이리소스401로거부되는지확인한다. 올바른키복구후동일signedchain양성이돌아와야하며JWKS/외부인증실패와혼동하지않는다. rate-limit429는권한성공·거부증거로세지않고일반설정의창안에서계정/요청수를계획한다. 각한도와이미지소유권을명시하고실행상한을늘려상태단언을우회하지않는다.

## 5. 남는 범위

replay.enabled는항상false다. lastReplayBy영속,실제replay claim/publish/drain,운영표본P22/GKE/ACL/oldwriter중지증거는이검증으로완료되지않는다. D084복구전체체인과실제DB/Kafka collector는기반fixture를재사용할수있는별도계획/PR로진행한다. PR207 Flyway/PR202offlineconstraints도각증거범위를유지한다. D065전체는부분완료다.

## 6. 구현 중 단순화와 검증 구분

2026-10-21 사용자 최소복잡성 지시를 반영해 생성 YAML 세 파일과 직접 작성한 readiness polling을 제거했다. Compose의 SPRING_APPLICATION_JSON은 공개 key 위치와 설정만 전달하고, 재생성은 native `up --force-recreate --no-deps --wait --wait-timeout 300`을 사용한다. 별도 shell wrapper나 공통 실행 framework는 추가하지 않는다. 초기420초/재생성300초 한도와 오류 보존, 생성 전 nonce 소유권 기록, 자기 프로젝트만 정리하는 경계는 유지한다.

검증은 독립 사례 목록을 가진 작은 evidence 검사기로 16 HTTP 사례/5단계 전체 ledger·outbox 불변/source와 image ID/migration 원문/cleanup을 대조한다. 로컬 hermetic 검사는 실제 HTTP·MySQL 성공을 의미하지 않는다. 실제 build와 네 리소스 체인, 정상 CI 및 독립 검토가 끝나기 전 P2–P6는 미완으로 유지한다.

## 7. actual USER500 반례와 명시적 선행

최초 실제 Actions39587253206에서 네 ADMIN disabled200 뒤 USER500이 관측됐다. root 설계검토 후 제품 수정은 독립 PR211에서 common MVC advice의 AccessDeniedException rethrow만 다룬다. 본 PR은 PR211 exactd882fecf830e261e78fb6b890aca27b73911cc86을 정상 merge해 의존하며 fixture 자체의 제품 변경은 없다. 검토 시 이 선행 head 대비 signed delta와 main 대비 통합을 구분한다. 선행 실제 main 병합 뒤 다시 정렬·검토·CI하며 선행보다 먼저 병합하지 않는다.

원 handler 회귀는 PR211 Actions39588457231 platform에서 USER403/anonymous401이 모두500으로 실패했고 unrelated500은 통과했다. 원본을 보존했으며 수정 head/실제4체인 성공은 새 Actions 전까지 미검증이다. direct plaintext 음성에서 malformed internal token을 제거해 SIGNED_ONLY 거부 의미를 분리했고 실제 baseline 요청 인자 캡처를 포함한 hermetic12 methods가 통과했다. 기대 HTTP 상태나 한도를 낮추지 않는다.

## 8. 실제 검증 결과

head53af54e의 전용 Actions39589065938에서 이미지6개와 리소스4개가 성공했다. 각16 HTTP 사례와5개 DB불변 snapshot, 실제 짧은TTL 만료/잘못된내부키 거부 후 복구, cleanup6containers/1network/1volume와 safe artifact scan을 raw로 확인했다. 원문bundle과개별hash를 보존했다. P5의 실패·foreign collision은12 hermetic methods의 증거이고 actual 종료 정리는 네 Actions fixture에서 별도로 확인했다. 두 근거를 혼동하지 않는다. P6의 최신 main/전체CI/독립검토/직렬승인은 아직 남아 있다.

## 2026-10-21 — 실제 main 병합 후 확인

최종 head `0be5aa32ec9159cdbc86f351475443fa090aec7f`의 [독립 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/210#issuecomment-6315032985)는 새 전용 Actions39592715075의 실제64HTTP/20DB snapshot/112files와 네 fixture 정리를 확인했다. 일반 CI39592715276도 성공했다. [PR210](https://github.com/Kimgyuilli/PeekCart/pull/210)을 보호 우회 없이 직렬 병합한 실제 main은 `7894dcc9cf0de3dd966f62777d1b463a1d9313dc`다. [main CI39595168219](https://github.com/Kimgyuilli/PeekCart/actions/runs/39595168219)의 테스트·E2E·gate와 여섯 publish 모두 SUCCESS를 Root가 직접 조회했다.

이 기록은 무료 signed gateway 경로 검증의 완료다. 운영 replay는 계속 false이며 D-065 전체는 부분 완료다. 실제 운영 표본·GKE·NOT NULL 적용, 별도 수집기/제약/Flyway PR의 최신 병합 검증을 대신하지 않는다. 이미지 archive의 독립 로컬 재다운로드 검증은 수행하지 않았고 Actions checksum/loadID 검사와 구분한다.
