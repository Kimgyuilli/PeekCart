# task-impl3-spring-cloud-gateway — 구현 ③ Spring Cloud Gateway (RS256 + Reuse Detection + S9)

> 선행 ADR-0013(Accepted). 보안 묶음 L-001/002/003/019 편입. Phase 4 구현 로드맵 ③.
> PR 분할: PR1 RS256/JWKS(dual-validation) → PR2 Refresh Reuse Detection → PR3 Gateway 모듈 + header-trust 전환(+ ADR-0014 D2-c servlet 검증 exit) → PR4 관측성 S9 + HS512 잔재 제거.
> **PR3d 재정의(ADR-0017 Accepted, 경로 A)**: PR3d 는 "평문 header-trust 굳히기 + verifier 삭제"에서 **"Gateway 서명 내부 토큰(`X-Internal-Auth`)으로 격상"** 으로 대체됐다. 아래 §PR3 실행 분할 PR3d 행과 **P14 클래스 처분표(loop2 #3)** 는 `docs/plans/done/task-impl3-pr3d-internal-token.md`(P1~P8)가 정본이다.
> 각 PR 은 자체 `/work` + `/ship` 을 거치며, 해당 PR 착수 시 세부 계획을 보강한다(impl② PR3 선례).

## 1. 목표

- **RS256 비대칭키 전환**(D1): User 만 개인키 서명, Gateway 가 공개키 1차 검증, 리소스 서비스 미재검증(헤더 신뢰). JWKS 공개키 배포.
- **Refresh Token Reuse Detection**(D4): 삭제 기반 rotation → `family_id`/`status` 상태전이. reuse(탈취) 감지 시 family 전체 무효화 + Redis family/session deny(access token 즉시 차단).
- **Spring Cloud Gateway**(D3): 5서비스 path 라우팅, JWT 검증 3단계(서명→blacklist/deny fail-closed→신뢰 헤더 주입), Redis RateLimiter(route-class별).
- **관측성 S9**(D5): 인증실패/reuse/429 메트릭(counter+사유 태그), ADR-0009 S9 행 추가.
- **성공 기준**: 8+1(gateway) 모듈 그린, gateway 통해서만 인증 통과(리소스 서비스 direct ingress 거부 + NodePort/LB 환원), 공개 경로는 무헤더 통과, reuse 재제시→family revoke 확증, RS256 왕복·위조 alg(HS256/HS384/none) 거부.

## 2. 배경 / 제약

### 현재 코드 (grep 검증 완료, 2026-07-04)

- **서명/검증 = HMAC 대칭키**(당시 "HS256" 으로 기록했으나 512bit secret 이라 **실제 alg 는 HS512** — PR1 구현 중 확인, loop2 #8): `JwtTokenSigner`(user-service `global.jwt`) 가 `Keys.hmacShaKeyFor` 로 서명, `JwtTokenVerifier`(common-auth) 가 동일 대칭키로 검증. 양쪽 단일 `JwtAuthProperties`(`app.jwt.secret/accessTokenExpiry/refreshTokenExpiry`) 바인딩(ADR-0014 D1-b).
- **검증 위치 = 매 서비스 in-process**: `JwtFilter`(common-auth) 가 요청마다 서명검증 + `TokenBlacklistLookupPort.isBlacklisted(token)` 확인. `JwtSecurityConfigurer.apply(http, publicUrls)` 를 5서비스 SecurityConfig(User/Product/Order/Payment/Notification) 가 전부 위임 소비. **Gateway 없음.**
- **blacklist**: 읽기 = common-auth `RedisTokenBlacklistLookupAdapter`(모든 서비스). 쓰기 owner = user-service `AuthService`/`TokenBlacklistRepository`(`TokenBlacklistPort`), `addGracePeriod` 로 rotation 동시요청 이중발급 방지.
- **RefreshToken 엔티티** = id/userId/token/expiresAt(`user.domain.model.RefreshToken`). `AuthService.refresh` = **삭제 기반** rotation(grace 10초). **family_id/status/reuse 감지 없음**(ADR-0013 C1).
- **X-User-* 헤더 신뢰 코드 없음**(현 grep hit 은 Kafka trace 헤더로 무관). **spring-cloud-gateway 의존 없음.**
- **k8s**: `k8s/base/{infra,services}` + `overlays/{gke,minikube}`. 현 canonical 서비스 이미지 5개.

### B1 — 역의존 스윕 (header-trust 전환이 대체하는 인증 검증 seam)

> PR3 에서 "리소스 서비스가 JWT 를 재검증한다"(현재) → "Gateway 만 검증, 서비스는 X-User-* 헤더 신뢰"(목표)로 바꾼다. seam 을 밖에서 참조하는 인바운드 간선과 처분:

| 인바운드 간선 (참조처) | 현재 결합 | 처분 (PR3) |
|---|---|---|
| `JwtSecurityConfigurer.apply()` — User/Product/Order/Payment/Notification 5 SecurityConfig | crypto-verify 정책 위임 | **디커플**: common-auth 에 `HeaderTrustSecurityConfigurer` 신설, 5서비스가 그것으로 전환 |
| `JwtFilter`(crypto verify + blacklist) — common-auth, 5서비스 필터체인 | 서명검증 + blacklist 재확인 | **대체**: `HeaderAuthenticationFilter`(X-User-Id/Role 신뢰, 서명검증 없음, 헤더 누락 시 401). 리소스 서비스 blacklist 재검증 제거(Gateway 소유) |
| `TokenBlacklistLookupPort`/`RedisTokenBlacklistLookupAdapter` — common-auth, 5서비스 | 서비스별 blacklist 읽기 | **확장(PR2)→이동(PR3)**: PR2 에서 family deny 읽기 확장(전환기 enforcement, P9) 후, blacklist/deny 확인 = Gateway 소유로 이관. 리소스 서비스는 미확인(헤더 신뢰). read adapter 는 Gateway 전용화 |
| `UserSecurityIntegrationTest`·`NotificationSecurityIntegrationTest` (통합) | Bearer 토큰으로 인증 프록시 | **재작성**: X-User-* 헤더 인증으로. header-trust 필터 검증 |
| `AuthControllerTest`/`UserControllerTest`/`ProductControllerTest`/`AdminProductControllerTest`/`OrderControllerTest`/`CartControllerTest`/`PaymentControllerTest`/`NotificationControllerTest` (슬라이스) | Bearer/mock 인증 | **재작성(B1b 프록시)**: 컴파일러가 못 잡음. 슬라이스가 인증을 X-User-* 로 세우도록 |
| `JwtFilterTest`(common-auth) | crypto 필터 단위 | **처분**: RS256 검증은 Gateway 로 이관 → Gateway 검증 필터 테스트로 이동/재작성 |
| `AuthService`/`TokenBlacklistPort`/`TokenBlacklistRepository`(user) | blacklist **쓰기** owner | **유지 + 확장**(PR2): family/session deny 쓰기 추가. 쓰기 owner 는 User 유지 |

- **string-level(B1b)**: 리소스 서비스의 `/api/v1/{domain}` permitAll·actuator 노출은 Gateway 라우팅 뒤에서도 유지(내부 direct ingress 는 NetworkPolicy 로 차단). Gateway route path 리터럴이 서비스 URL prefix 와 정합하는지 스윕.

### B2 — ADR 타깃 ≠ 현재 코드 (없는 것은 "만든다"로 승격)

- **Gateway 모듈**: 없음 → PR3 신설(P11). **JWKS endpoint**: 없음 → PR1 신설(P4). **family_id/status 스키마**: 없음 → PR2 마이그레이션(P6). **NetworkPolicy**: 없음 → PR3 신설(P17). **RS256 키 로딩**: 없음 → PR1 신설(P1). 모두 명시 작업 항목으로 승격됨.

### B6 — 새 gateway 모듈이 `:common` 스캔으로 떠안는 횡단 빈

- Gateway 는 WebFlux(reactive) 기반 → `:common`/`:peekcart-common-auth`(servlet MVC·`spring-boot-starter-web`·`JwtFilter`(OncePerRequestFilter)) 를 그대로 의존하면 **servlet↔reactive 충돌**. Gateway 는 `:common` 의 payload/response DTO·에러코드만 선택 의존하고, MVC/servlet 필터·`JwtSecurityConfigurer` 는 **의존 금지**. 검증 컴포넌트는 Gateway 전용(reactive) 로 별도 구현. build.gradle 의존 최소화 명시(P11).

### 트레이드오프

- **Gateway SPOF**: 인증 경로 집중 → HA 다중 인스턴스(HPA) 필수(P17). Redis deny/RateLimiter **fail-closed**(보안 우선, 가용성 영향 수용, ADR-0013 D3). **blast radius**: Redis 장애 시 deny 조회와 RateLimiter 가 동시 fail-closed → 인증 경로뿐 아니라 **공개 경로도 503**(P13 에 운영 문서화).
- **헤더 신뢰 리스크**: NetworkPolicy/헤더 strip 이 깨지면 spoofing. 외부 유입 X-User-* **항상 제거 후 재주입**(공개 경로 포함) + 내부 direct ingress 거부 + **기존 NodePort/LB 노출 환원**이 핵심(P12/P17). 전환 도중이 가장 취약 → 롤아웃 순서 고정(P18).
- **키 회전 운영**: dual-validation 기간(active/previous overlap > access TTL) 관리. PR4 에서 **HS512** fallback 제거(P22).
- **계약 이중 구현(servlet↔reactive)**: `:common` 이 servlet 을 `api` 로 전이 노출해 Gateway 가 재사용 불가(보강 e) → alg/kid/claims/Redis-key 계약이 두 곳에 존재. **conformance 테스트로만 동등성 보장**(P19), PR3 P14 에서 servlet 측이 삭제되면 자연 해소.
- **replay/deny bounded risk**: family deny 미기록 access token 은 짧은 TTL 까지 bounded(ADR-0013 D4 Consequences).

## 3. 작업 항목

### PR1 — RS256 비대칭키 전환 + JWKS (D1/D2, dual-validation)

- [ ] **P1.** RS256 키 로딩: `JwtKeyProperties`(`app.jwt.rs256.*` — active `kid`·개인키 PEM 경로·공개키 목록{kid→PEM}). **파일 마운트**(환경변수 금지, ADR-0007/D2). GKE = Secret Manager CSI 마운트(k8s 배선은 PR3 P17 과 함께). **테스트 키쌍 물리 소유(B5)**: user-service(서명)·common-auth(검증)·PR3 gateway(JWKS 검증) 다중 소비 → 단일 소유 위치 = **`:common` testFixtures 리소스**(모든 모듈이 `:common` 의존). 각 모듈은 복제 대신 testFixtures 로 참조(공개키는 gateway JWKS stub 도 동일 소스 사용).
- [ ] **P2.** `JwtTokenSigner` RS256 서명: `signWith(privateKey, RS256)` + JWT 헤더 `kid` 세팅. 발급은 즉시 RS256 단일 전환.
- [ ] **P3.** `JwtTokenVerifier` dual-validation: 토큰 `kid` 로 공개키 선택 → RS256 검증. **alg allow-list(RS256만)** — `kid` 부재/unknown, alg=none/HS256/HS384 위조 거부. 전환기 **HS512** fallback(레거시 alg 정확 한정·bounded·기본 off) — PR4 제거. *(구현 결과 정정: 레거시 토큰의 실제 alg 는 HS512 — `JwtTokenVerifier:90-95`)*
- [ ] **P4.** User JWKS endpoint `/.well-known/jwks.json`: 공개키(kid/kty/n/e) 노출. presentation 계층 + permitAll(공개 URL).
- [ ] **P5.** PR1 테스트 + latency 측정: RS256 서명↔검증 왕복, kid 선택, alg allow-list 거부(HS256/HS384 위조·unknown kid·none alg), JWKS 응답 스키마, dual-validation(**HS512** fallback on/off). **+ RS256 서명 latency p50/p95 측정**(ADR-0013 D2 후속 조건 — 테스트/로컬 키 기준) → Cloud KMS 비대칭 서명 격상 재검토 근거로 기록(격상은 별도 ADR 후보, 측정만·전환 미결정).

### PR2 — Refresh Token Reuse Detection (D4)

> PR2 착수 보강 (2026-07-06, code-verified): 마이그레이션 번호·평문 token 처분·Redis grace 경로 처분·TokenIssuer 시그니처 seam 을 현재 코드 대조로 확정.

- [ ] **P6.** `refresh_tokens` 마이그레이션(user-service **`V2__`** — 현재 `V1__init_user.sql` 단일): `family_id`·`token_hash`·`status`(ACTIVE/ROTATED/REVOKED)·`rotated_at`·`grace_until`·`replaced_by_token_id` 추가. 삭제 기반 → 상태전이. **`token_hash CHAR(64) NOT NULL` + `UNIQUE KEY uk_refresh_tokens_token_hash(token_hash)`**(드롭하는 `uk_refresh_tokens_token` 의 대체 unique — 중복 시 findByTokenHash 모호성·reuse 오판 차단). 해시 알고리즘 = **common-auth `TokenHasher.sha256Hex` 재사용**(blacklist 신키와 동일 유틸, 키스킴 드리프트 차단). 조회 인덱스(`family_id`). **평문 `token` 컬럼 + `uk_refresh_tokens_token` 드롭**(token_hash 대체·전량 무효화라 backfill 불요). `fk_refresh_tokens_user` 는 도메인 내 FK 로 **유지**. **기존 데이터 처분(명시)**: 그린필드(보존 prod 이력 없음, impl② 선례)라 **기존 `refresh_tokens` row 전량 무효화(재로그인 요구)** 채택 — V2 에서 기존 row **전량 삭제** 후 컬럼 재구성. 기존 `token`(평문 UUID)을 해시해 `token_hash`/`family_id=신규`/`status=ACTIVE` 로 backfill 하는 경로는 *기술적으로 가능하나* 보존 요구가 없어 채택하지 않고 전량 만료로 단순화. access token 은 짧은 TTL 로 자연 소멸.
- [ ] **P7.** `RefreshToken` 엔티티 + repository 확장: 상태전이 rotation, family 단위 조회/무효화, token_hash 조회(평문 미저장). 현 `RefreshTokenRepository`(findByToken/deleteByToken/deleteByUserId/save) → 상태전이 모델로 재정의(`findByTokenHash`·`revokeFamily`·`revokeAllByUserId` 등). **grace 원자 소비 메서드 `consumeGraceOnce(tokenHash, now)`**: 조건부 UPDATE(`status='ROTATED' AND grace_until > now` 인 한 행만 consume 마킹, affected rows=1 만 성공) — 현행 Redis `GETDEL` 원자성(TokenBlacklistRepository)과 동등 보장을 DB 로 이전. 동시 refresh 2건이 둘 다 grace 유효를 읽는 이중 발급 차단. **grace 성공 후 상태 불변식**: 평문 미저장이라 첫 rotation 의 replacement token 을 반환할 수 없음 → 새 token 발급 시 **같은 트랜잭션에서 `replaced_by_token_id` 의 기존 replacement 를 ROTATED 처리** → **family 내 ACTIVE 정확히 1개** 유지(요구사항 "재발급 시 기존 토큰 즉시 무효화" 정합). **force-rotation 비순환 계약**: supersede 되는 replacement 는 **ROTATED-without-grace**(`grace_until` 미부여 또는 `≤ now` — `consumeGraceOnce` 조건 `grace_until > now` 에 다시 걸리는 상태전이 순환 금지), `replaced_by_token_id` 는 새 token id 로 **단방향** 설정(자기참조/순환 금지) → 재제시 즉시 거부(reuse 판정). **supersede 된 replacement 의 access token 처분**: family deny 는 새 토큰까지 차단하므로 정상 grace 경로에 사용 불가 → **access TTL 까지 bounded overlap 으로 수용**(ADR-0013 D4 Consequences 의 bounded risk 와 동일 계열, 즉시 무효화는 미채택 — jti 단위 blacklist 는 과설계).
- [ ] **P8.** `AuthService.refresh` 재작성: **grace**(정상 동시요청 = `grace_until` 내 1회성 허용 — **P7 `consumeGraceOnce` 원자 소비로만 통과**, 조회-후-판단 금지) vs **reuse**(grace 초과 + ROTATED/REVOKED 재제시, 또는 이미 revoked family 재제시) → **family 전체 무효화**. **Redis grace 경로 처분(제거)**: 현행 grace 는 Redis 기반(`TokenBlacklistPort.addGracePeriod`/`consumeGracePeriod` + `AuthService.refreshViaGracePeriod`) — DB `grace_until` 상태전이로 대체하고 port 메서드 2종·Redis 구현·`refreshViaGracePeriod` 를 제거(blacklist `addToBlacklist` 는 유지). **콜사이트 처분**: `login`/`logout` 의 `deleteByUserId` → family REVOKED 상태전이로 전환(row 삭제는 retention 소관이 아닌 user-service 정책 — 삭제 대신 상태 보존이 reuse 감지 증거 유지에 필수).
- [ ] **P9.** family/session deny → Redis 기록(Gateway blacklist source): reuse 감지 시 family deny 키 write(User 소유). **deny 키 계약(ADR-0014 D1-c 준거, PR3 Gateway 가 같은 계약을 읽음)**: 키 = `auth:deny:family:<familyId>`(blacklist 신키 `auth:blacklist:<sha256hex>` 와 동일 네임스페이스 계열, familyId 는 claim 노출 식별자라 해시 불요·토큰 원문 저장 금지), TTL ≥ access token 최대 잔여 TTL(= `accessTokenExpiry`, family 내 마지막 발급 기준 상한), value = 감지 시각(디버깅용, 판정은 키 존재로), write owner = User·read owner = **전환기 common-auth(PR2)** → Gateway(PR3 이관)·miss = 통과·조회 실패 = fail-closed. **전환기 enforcement(PR2, ADR-0013 D4 "즉시 차단" 충족)**: common-auth `TokenBlacklistLookupPort`/`RedisTokenBlacklistLookupAdapter` 를 family deny 확인으로 확장(예: `isBlacklistedOrFamilyDenied(token, familyId)`), `JwtFilter` 가 파싱한 `family_id` claim 을 전달 — PR3 에서 read 경로 전체가 Gateway 로 이관되는 B1 표 동일 행이라 버려지는 작업 아님. 현행 blacklist 조회는 이미 fail-closed(어댑터 확인) — posture 변화 아님. **family_id 부재 계약(전환기 레거시 토큰)**: PR2 배포 전 발급 유효 access token(HS512 fallback 포함)은 family_id claim 없음 — claim 부재는 Redis "조회 실패"(fail-closed)가 아님 → **absent/null/blank 면 blacklist 만 검사, family deny 는 miss 취급**(NPE·`auth:deny:family:null` 오조회·레거시 전면 401 금지). PR2 이후 신규 발급 토큰은 family_id **필수**. access token 에 `family_id` claim 추가 — **`TokenIssuer.issue(userId, role)` 시그니처 확장 seam**: family_id 는 AuthService 가 생성(login/signup=신규)·승계(refresh=기존 family) 하므로 issue 파라미터로 전달. `TokenClaims` record 필드 추가 → common-auth `JwtTokenVerifier.parseToken` 매핑 + 생성자 사용처 컴파일 sweep(record 라 컴파일러가 잡음).
- [ ] **P10.** PR2 테스트: rotation 상태전이, grace 1회 허용/2회 차단, **동일 ROTATED 토큰 병렬 2요청 중 정확히 1건만 성공(consumeGraceOnce 원자성, Testcontainers MySQL)**, reuse 감지→family revoke→Redis deny write, **deny 어댑터 키 계약 검증(prefix `auth:deny:family:`·TTL·원문 미포함)**, **전환기 enforcement: family deny hit→401·miss→통과·Redis 조회 실패→fail-closed(`JwtFilter`+adapter)**, **family_id 부재 레거시 토큰 회귀(RS/HS fallback 각각): NPE 없이 blacklist miss→통과·hit→거부(family deny 미조회)**, **grace 성공 후 family 내 ACTIVE 정확히 1개 + supersede 된 replacement 재제시 즉시 거부(ROTATED-without-grace — consumeGraceOnce 재성공 순환 없음 확증)**, family_id claim 왕복, revoked family 재제시 거부, **common-auth 회귀: `JwtTokenVerifierTest`(family_id claim 매핑)·`JwtFilterTest`(`TokenClaims` 생성자 회귀 + family deny 3분기)**. **+ 마이그레이션 회귀**: Flyway 마이그레이션 적용 후 기존 row 전량 무효화 확인 + `uk_refresh_tokens_token_hash` unique index 존재 검증 + 기존(만료된) refresh token 재제시 시 `AuthService.refresh` 가 안전 거부(NPE/오통과 없음).

### PR3 — Spring Cloud Gateway 모듈 + header-trust 전환 (D3)

> **PR3 착수 보강 (2026-07-23, code-verified + Codex 리뷰 13건 전량 반영)**: PR1/PR2 머지 이후 현재 코드 대조로 아래를 확정. §2 B1 표는 PR3 착수 전 sweep(2026-07-04) 기준이라 PR2 산출물 반영 보정.
>
> **(a) PR2 가 이미 심은 것 (Gateway 가 재사용/이관할 표면)**: `TokenBlacklistLookupPort.isBlacklistedOrFamilyDenied(token, familyId)` + `RedisTokenBlacklistLookupAdapter`(servlet) + `JwtFilter` family deny enforcement + `TokenClaims.familyId` + `RsaPublicKeyRegistry`·`JwtKeyProperties`·`PemKeyLoader`·`JwtTokenVerifier`(RS256·kid) 전부 **존재**. → **P12 Gateway 검증기는 이 read 계약·키 선택 로직을 reactive 로 재구현**(B6: servlet `OncePerRequestFilter`/`RedisTemplate` adapter 직접 재사용 불가). 확정 사항:
>   - **공개키 소스 = User JWKS 정본**(`/.well-known/jwks.json`, ADR-0013 D1:27-29). `RsaPublicKeyRegistry` 로컬 미러 선택지 **폐기**(정본 이원화 금지). 개인키 PEM 은 **user-service Pod 에만** 마운트하고 **Gateway 에는 절대 마운트하지 않는다**(D1/D2 — Gateway 는 검증만).
>   - **전환기 알고리즘 = RS256 + 조건부 HS512**(HS256 아님). 실제 `JwtTokenVerifier.java:90-95` 가 레거시를 **HS512 정확 한정**하고 HS256/HS384 를 거부 → 계획 전반의 "HS256 fallback" 표기를 **HS512** 로 정정(허용면 과확장·잔존 토큰 오거부 동시 방지).
>   - **키 계약 그대로 재사용**(새 스킴 금지): `auth:blacklist:<sha256hex>`·`auth:deny:family:<familyId>`. **레거시 `bl:<raw-token>` dual-read 유지**(`RedisTokenBlacklistLookupAdapter:14-19,43-49`) — access token 최대 TTL 경과를 증명한 명시적 제거 게이트 전까지 존속.
>
> **(b) `LoginUser.accessToken()` seam 파손 → 계약 확정**: `@CurrentUser LoginUser` resolver 가 `authentication.getDetails()`(= `JwtFilter` 가 넣는 raw access token)를 노출. **유일 소비자 = `AuthController.logout:53-56` → `authService.logout(accessToken)`(sha256 blacklist, `AuthService:87-98`)**. header-trust 하에선 리소스 서비스가 raw 토큰 미보유 → logout blacklist 불가. **확정 처분**: Gateway 가 `X-User-Family-Id` 도 주입, `LoginUser(userId, role, familyId)` 로 계약 고정(**nullable accessToken 대안 폐기**), `logout(userId, familyId)` = family deny write + `revokeAllByUserId` (`JwtTokenVerifier` 의존 제거). ADR-0013 D4 family containment(`0013:52-56`) 정합. **`X-Access-Token`/`X-Token-Hash` forward 는 비채택** — 리소스 서비스가 JWT 를 보지 않는다는 D1(`0013:27`) 원칙을 약화.
>
> **(c) 슬라이스 테스트 rewrite 실범위 (P15 정정)**: 8개 컨트롤러 슬라이스는 **Bearer 토큰을 쓰지 않음** — `@WithMockLoginUser`(common testFixtures `WithMockLoginUserSecurityContextFactory`)로 SecurityContext 를 직접 세팅(`TestSecurityConfig` permitAll, 필터 무관). header-trust 는 **필터 메커니즘만 교체**하므로 슬라이스 무영향. **재작성 실범위 = SecurityConfig 통합테스트 2개**(`UserSecurityIntegrationTest`·`NotificationSecurityIntegrationTest`) **+ `JwtFilterTest`**(Gateway reactive 검증 필터로 이관) **+ `WithMockLoginUserSecurityContextFactory`**(principal 에 familyId 추가분 소폭 조정).
>
> **(d) canonical 고정 목록 = 3 스크립트 (P16/P17 정정)**: `scripts/image-contract-lint.sh:44`·`scripts/promote-images.sh:29`(`CANONICAL_SERVICES` 5) **+ `scripts/servicemonitor-selector-lint.sh:93`**(`CANONICAL` 5 집합 **동등 비교** — 6번째 monitor 가 곧 lint 실패). Gateway 는 *도메인* 서비스가 아니라 **인프라 게이트웨이** → 도메인 canonical 5 정본(ADR-0010 §5)을 보존하고 **별도 인프라 목록으로 분리**, 매트릭스/lint 총계는 6.
>
> **(e) `:common` 이 servlet 을 `api` 로 전이 노출 (B6 가 물리적으로 성립)**: `common/build.gradle:12-14` 가 `spring-boot-starter-web`·`data-jpa`·`spring-kafka` 를 **`api`** 로 선언 → gateway 가 `project(':common')` 을 의존하면 WebFlux 런타임에 MVC/servlet + JPA/Kafka 가 그대로 유입(`PLAN-BLINDSPOTS.md:49` 경고와 동일). **확정 처분: gateway 는 `:common` 미의존, 응답 DTO 는 gateway-local.** 순수 DTO 공유 모듈 신설은 ADR 절차가 필요하므로 PR3 범위 밖(필요 시 별도 ADR).
>
> **(f) 기존 서비스 외부 노출이 전환기 상태로 잔존**: minikube **NodePort ×5**(`overlays/minikube/patches/*-service.yml`), GKE **Internal LoadBalancer ×5**(`overlays/gke/patches/*-service.yml`) — 각 patch 주석이 *"gateway ③ 도입 시 단일 ingress 로 통합 예정 — 전환기"* 로 이미 예고. NetworkPolicy 만 얹으면 **외부 노출 잔존**. 동시에 Prometheus 가 각 ServiceMonitor 로 `/actuator/prometheus` 를 **직접 scrape**(`base/services/*/servicemonitor.yml`) → Gateway-only 정책이면 **scrape 차단**. **확정 처분: ClusterIP 환원 + NetworkPolicy 에 monitoring scrape 예외**(P17).
>
> **(g) ADR-0014 D2-c exit 이 PR3 소관 (PR4 아님)**: `docs/adr/0014-transitional-auth-module.md:68-70` 이 **PR3 에서** `JwtFilter`·`JwtTokenVerifier`·blacklist lookup 제거를 요구. 기존 P21/P22 가 common-auth verifier 의 PR4 존속을 전제해 **ADR 과 충돌** → 삭제는 PR3(P14)로 당기고, **PR4 범위는 Gateway 의 HMAC fallback + 잔재 sweep 으로 축소**.

#### PR3 실행 분할 (PR3a~d) — loop2 #2 반영

> 단일 PR/단일 이미지로는 P18 롤아웃의 ④(header-trust 배포)와 ⑤(Authorization 중단·verifier 삭제)를 **구분 배포할 수 없고 역순 롤백도 불가**. 작업 항목(P11~P19)은 그대로 두고 **실행 단계를 분리**한다. 각 단계는 고유 이미지 태그를 갖고, 이전 단계로 되돌릴 수 있어야 한다.

| 단계 | 내용 | 주 항목 | 진입 조건 | 롤백 |
|---|---|---|---|---|
| **PR3a** | Gateway shadow 배포 — 라우팅·검증기·RateLimiter, **Authorization + 재주입 헤더 병행 전달**. 서비스는 아직 `JwtFilter`(구) 유지 | P11·P12·P13·P16 | Redis/JWKS warm-up 완료, Gateway 최초 JWKS 적재 성공(usable key ≥1), readiness=true | Gateway 트래픽 0 (서비스 직접 경로 살아있음) |
| **PR3b** | 트래픽을 Gateway 로 전환 (canary → 100%) | P17 부분(gateway svc/HPA/SA/ServiceMonitor) | PR3a 안정, 보호/공개/spoof canary 통과, 오류율 임계 이하 | 트래픽을 기존 직접 경로로 복귀 |
| **PR3c** | 서비스 직접 노출 제거(ClusterIP)+NetworkPolicy, **header-trust 호환 이미지 rollout**(구·신 Pod 혼재 허용 — 헤더/Bearer 양쪽 수용) | P14 전환분·P17 나머지 | PR3b 100% 전환 유지, 혼재 구간 refresh/logout 정상 | 이전 이미지 태그로 rollout undo, NodePort/LB patch 복구 |
| **PR3d** | **[재정의 — ADR-0017/경로 A]** 평문 header-trust → **Gateway 서명 내부 토큰(`X-Internal-Auth`)** 격상 + Authorization 전달 중단 + 서비스 측 사용자 토큰 verifier·blacklist 삭제(ADR-0014 D2-c exit, verifier 기계는 내부 토큰 검증기로 **용도 변경**). 정본: `task-impl3-pr3d-internal-token.md`(P1~P8) | P1~P8(구 P14 삭제분·P18 ⑤ 대체) | family-less 토큰 소멸 증명(#1 게이트), dual-accept 종료(혼재 Pod 0) | ⚠️ **Gateway 가 평문/Authorization 전달을 먼저 복구**해야 서비스 롤백 가능 — 역순 강제 |

- **rollout 공통**: `maxUnavailable=0`, 단계별 고유 이미지 태그, 각 단계 readiness·오류율 임계·역순 rollback 명령과 증적을 P18/P19 완료 조건으로 고정.

#### 공개 경로 SSOT (method+path) — loop2 #5 반영

> Gateway allowlist ↔ 5서비스 SecurityConfig permitAll ↔ 기대 응답의 **삼자 정본**. 현재 서비스 matcher 는 method 무관 문자열이라(`ProductSecurityConfig:31-36` 이 `/api/v1/products/**` 전체 permitAll) **HttpMethod matcher 로 좁힌다**.

| method + path | Gateway 외부 | 서비스 permitAll | 기대 |
|---|---|---|---|
| `POST /api/v1/auth/signup`·`/login`·`/refresh` | 공개 | 유지 | 무헤더 200 |
| `GET /api/v1/products`·`/api/v1/products/**` | 공개 | **GET 으로 한정**(현재 method 무관 → 정정) | 무헤더 200 |
| `POST /api/v1/products/**`(관리) · `/api/v1/admin/products/**` | 인증 | 보호 | 무헤더 401 / role 부족 403 |
| `POST /api/v1/payments/webhook`(Toss) | 공개 | 유지 | 무헤더 200, **Gateway 경유 확증** |
| `/.well-known/jwks.json` | **미노출** | User 내부 permitAll | **Gateway 외부 404** / User 내부 200 (P19 분리 검증) |
| `/swagger-ui/**`·`/api-docs/**` | **미노출** | 내부 유지 | Gateway 외부 404 |
| `/actuator/**` | 미노출 | S4 소유(ActuatorSecurityConfig) | Prometheus scrape 만 허용(P17 예외) |

- [ ] **P11.** `gateway` 모듈 신설: `settings.gradle` include, `build.gradle`(`spring-cloud-starter-gateway`(webflux)·`data-redis-reactive`). **`:common` 의존 금지(보강 e)** — 응답 DTO/에러코드는 gateway-local 정의. **라우트 정본 표**(placeholder 금지, 실 controller prefix 대조): `/api/v1/auth/**`·`/api/v1/users/**`→user, `/api/v1/products/**`·`/api/v1/admin/products/**`→product, `/api/v1/cart/**`(**단수**)·`/api/v1/orders/**`→order, `/api/v1/payments/**`→payment, `/api/v1/notifications/**`→notification. **`/.well-known/jwks.json` 은 `/api/v1` 밖**(`JwkController:29`) → Gateway 내부 fetch 전용, 외부 라우트 **미노출**(외부 공개 불요). Swagger/api-docs 는 서비스 간 경로 충돌 소지 → **외부 미노출**로 처분.
- [ ] **P12.** Gateway JWT 검증 필터(reactive): ① 서명/만료(**User JWKS 공개키**, kid) → ② blacklist + family deny(Redis, **조회 실패 fail-closed**) → ③ 외부 유입 `X-User-*` **strip 후** `X-User-Id`/`X-User-Role`/`X-User-Family-Id` 주입. **공개 경로는 JWT 미요구**(보강/P14 와 연결): allowlist 경로도 외부 `X-User-*` 는 **항상 strip** 하되 토큰 없으면 그대로 통과. **PR2 read 계약 reactive 재구현(보강 a)**: `isBlacklistedOrFamilyDenied` 시맨틱·키 계약 동일, `bl:` dual-read 유지. **알고리즘 = RS256 + 조건부 HS512**(HS256/HS384/none 상시 거부). **JWKS client 운영(D1:28-29)**: cache TTL, unknown `kid` 도착 시 refresh, refresh 실패 시 last-known-good 유지 + alert.
  - **family-less 토큰 처분 = 시한부(loop2 #1)**: `familyId` absent/null/blank 수용은 **PR3a~c 전환기 한정**. P14 가 principal·`logout(userId, familyId)` 를 familyId 고정으로 잡는데 수용을 영구화하면 `auth:deny:family:null` 오기록(writer `TokenBlacklistRepository:47-49` 가 키에 직결) 또는 혼재 구간 401 중 하나가 터진다. **PR3d 진입 게이트 = "마지막 family-less 토큰 발급 시각 + access token 최대 TTL 경과" 증명** → 게이트 통과 시 수용 경로 제거하고 **`LoginUser.familyId` non-null 불변식** 확정. 전환기에 family-less 토큰은 신·레거시 blacklist 만 조회(family deny 는 miss, fail-closed 대상 아님).
  - **응답 행렬 확정(loop2 #7 — 503↔LKG 분기점)**: known kid + 유효 LKG → JWKS 장애 중에도 **정상 처리 + alert** / unknown kid 이고 refresh 성공 후에도 미존재 → **401** / unknown kid 이고 refresh **실패** → **503** / cold start 에 usable key 0 → **readiness=false**(트래픽 미수신, 503 아님). Redis lookup 실패 → **503**, deny·서명오류·만료 → **401**, role 부족 → **403**, rate limit 초과 → **429**. 이 행렬을 P19 parameterized test 로 고정.
- [ ] **P13.** Redis RateLimiter(route-class별): 로그인/refresh(인증 전)=IP(+계정은 후속), 인증 API(인증 후)=**검증된** userId, 공개 조회=IP/route. **Redis 장애 fail-closed → 503**(P12 응답 계약). 429 metric/log owner=Gateway. **blast radius 명시(운영 문서)**: 공개 경로는 deny 조회 대상이 아니지만 **RateLimiter Redis 장애로는 503** → Redis 장애 시 인증·공개 경로가 동시 차단됨을 운영 문서에 기록.
  - **구현 정정(PR3a, GW-2 c2:1/c3:1/c3:2/c3:3)**: ① SCG 기본 `RedisRateLimiter` 는 Redis 오류를 `allowed=true` 로 삼켜 **fail-OPEN** → `FailClosedRedisRateLimiter`(고정 윈도우 INCR, 오류 전파) 자체 구현으로 대체. ② 인증 필터가 라우트 필터보다 **먼저** 실행돼야(order<1) RateLimiter 가 위조 `X-User-Id` 를 키로 쓰지 않는다 → 검증된 userId 를 exchange attribute 로 전달. ③ `/api/v1/auth/**` 를 통째로 pre-auth 키로 묶으면 인증 API 인 logout 이 섞임 → signup/login/refresh 전용 라우트 분리.
  - **후속(계정 차원 제한)**: 계획의 "IP+계정" 중 **계정 성분은 미구현**. login/signup 이 email 을 **JSON 본문**으로 받아 gateway 가 읽으려면 body-caching decorator 가 필요하다(프록시 경로 개입). PR3a 는 조작 가능한 `?email` 쿼리 성분을 **제거하고 IP 단독**으로 좁혔다(이전 구현은 쿼리만 바꿔 버킷 회피가 가능해 실효가 없었음). 계정 단위 credential-stuffing 방어는 body-caching 도입과 함께 **별도 항목으로 후속** 처리한다.
- [ ] **P14.** 리소스 서비스 header-trust 전환(**PR3c**) + servlet 검증 삭제(**PR3d**): common-auth `HeaderAuthenticationFilter` + `HeaderTrustSecurityConfigurer`, 5서비스 SecurityConfig 를 `JwtSecurityConfigurer`→header-trust 로 전환. **`LoginUser` 계약(보강 b)**: `LoginUser(userId, role, familyId)`, `logout(userId, familyId)`=family deny write + `revokeAllByUserId`, resolver 의 `getDetails()`→accessToken 매핑 제거.
  - **헤더 3-state 계약 확정(loop2 #4)**: ① **세 헤더 모두 없음 = anonymous 로 체인 계속**(즉시 401 금지 — 공개 경로가 깨진다. 보호 경로는 `anyRequest().authenticated()` 가 401 로 막는 것이 확인됨 `JwtSecurityConfigurer:41-44`) / ② 세 헤더가 **각각 정확히 하나씩 존재 + 검증 통과 = 인증** / ③ **그 외(부분 존재·blank·중복 헤더·형식 오류·미허용 role) = 401**. 검증 규칙: `X-User-Id`=양의 정수, `X-User-Role`=명시 enum(USER/ADMIN), `X-User-Family-Id`=non-blank(PR3d 이후 필수). **파싱 예외가 500 이나 anonymous fallback 으로 새지 않게** 한다.
  - **ADR-0014 D2-c exit — 클래스별 move/delete/retain 표(loop2 #3, PR3d 실행)**: 일괄 삭제하면 User 서명/JWKS 가 깨진다(`JwtTokenSigner` 가 `JwtAuthProperties`/`JwtKeyProperties`/`PemKeyLoader` 의존, `JwkController:3` 이 `RsaPublicKeyRegistry` 의존).
  - **⚠️ [PR3d 재정의로 대체 — ADR-0017]** 아래 표는 "평문 header-trust 굳히기" 전제로 작성됐다. 경로 A 채택으로 PR3d 는 서명 내부 토큰 격상이 되어, `RsaPublicKeyRegistry`/`PemKeyLoader`/`JwtKeyProperties`(공개키)는 **삭제가 아니라 내부 토큰 검증기로 용도 변경**되고 삭제 대상은 사용자 토큰 검증 필터·서비스 측 blacklist lookup 으로 좁혀진다. **정본 처분표 = `task-impl3-pr3d-internal-token.md` §3 P6.** 아래 표는 이력으로 보존(참조 금지).

| 대상 | 처분 |
|---|---|
| `JwtFilter`·`JwtTokenVerifier`·`JwtSecurityConfigurer`·`TokenBlacklistLookupPort`·`RedisTokenBlacklistLookupAdapter` | **삭제** |
| `JwtFilterTest`·`JwtTokenVerifierTest`·`RedisTokenBlacklistLookupAdapterTest` | **삭제 또는 Gateway 테스트 벡터로 이관**(#6 golden vector 원천) |
| `JwtKeyProperties`·`PemKeyLoader`·`RsaPublicKeyRegistry`·`JwtAuthProperties` 의 서명/TTL 부분 | **user-service 로 이동**(서명·JWKS 소유자) |
| `TokenClaims`·`TokenParseException` | 검증 전용 → **삭제**(Gateway 는 자체 타입) |
| `TokenHasher` | User 로 이동 **또는** common-auth 잔존 시 "identity-only" 문구 정정 |
| `CommonAuthConfig` 의 JWT 설정 바인딩 | 이동 대상 따라 정리 |
| `LoginUser`·`CurrentUser`·`LoginUserArgumentResolver`·`WebMvcConfig`·`HeaderAuthenticationFilter` | **retain**(servlet identity 변환) |

  - **의존 제거 범위(과잉 삭제 방지)**: User 의 JJWT(서명)·Redis(blacklist/deny **write**) **유지**, Product 의 Redis 캐시(`product-service/build.gradle:29-33`) **유지**. 그 외 서비스에서 *검증 목적으로만* 존재하는 JJWT/Redis 를 제거 — 서비스별 표로 확인.
- [ ] **P15.** 인증 테스트 재작성(**보강 c 로 범위 확정**): SecurityConfig **통합**테스트 2개(`UserSecurityIntegrationTest`·`NotificationSecurityIntegrationTest`) Bearer→X-User-* + `JwtFilterTest`→Gateway reactive 검증 필터 테스트 이관 + `WithMockLoginUserSecurityContextFactory` familyId 반영. 8개 컨트롤러 슬라이스는 SecurityContext 직접 세팅이라 **원칙 무영향**.
- [ ] **P16.** Gateway Dockerfile(단일 `Dockerfile` ARG SERVICE·멀티모듈 COPY·base digest 고정) + CI 이미지 매트릭스 6 + **canonical 목록 갱신(보강 d)**: `scripts/image-contract-lint.sh`·`scripts/promote-images.sh` 에 **도메인 5 + 인프라 gateway 1 분리 정본** 도입(ADR-0010 §5 "5서비스" 의미 보존), image/publish 매트릭스·lint 총계 6/6.
- [ ] **P17.** k8s gateway: base/overlays deployment/svc/cm/secret + **HPA**(SPOF→HA) + Gateway 전용 ServiceAccount + **Gateway ServiceMonitor**(PR4 S9 수집 선결) + `scripts/servicemonitor-selector-lint.sh` 를 도메인 5+인프라 1 분리 정본으로 갱신(보강 d). **외부 노출 단일화(보강 f)**: 5서비스 overlay 의 **NodePort/Internal LoadBalancer patch 제거 → ClusterIP 환원**, 외부 노출은 Gateway 하나. **NetworkPolicy**: 업무 API 는 Gateway pod selector/SA 만 허용 + **monitoring namespace/Prometheus pod 의 `/actuator/prometheus` scrape 예외**. **RS256 키 CSI 마운트는 user-service Pod 전용 — Gateway 미마운트 명시**(보강 a).
- [ ] **P18.** **무중단 롤아웃**(신규, 실행 단위는 위 **PR3a~d** 표): 구서비스는 Bearer 만 읽고(`JwtFilter:31-49`) Gateway 는 Authorization 을 strip 하므로 순서 없이는 **Gateway 선배포=전면 401**, **서비스 선전환=NodePort 경유 `X-User-*` 위조**. 단계 ①Gateway 배포(strip + **Authorization 병행 전달**) → ②트래픽 전환 → ③ClusterIP+NetworkPolicy → ④header-trust rollout(혼재 허용) → ⑤Authorization 중단 + verifier 삭제. **단계별로 실제 값을 적는다(loop2 #2 — "명시할 것"으로 남기지 말 것)**:
  - **진입 조건**: PR3a=Redis/JWKS warm-up + 최초 JWKS 적재 usable key ≥1 + readiness / PR3b=보호·공개·spoof canary 통과 + 오류율 임계 이하 / PR3c=100% 전환 유지 + 혼재 구간 refresh·logout 정상 / PR3d=**family-less 토큰 소멸 증명**(P12 게이트) + 혼재 Pod 0 + rollback window 경과
  - **rollout 파라미터**: `maxUnavailable=0`, 단계별 고유 이미지 태그(단일 태그로 ④/⑤ 구분 불가)
  - **rollback**: 각 단계 역순 명령 + 증적. ⑤ 이후는 **Gateway 의 Authorization 전달 복구가 서비스 롤백보다 선행**해야 함(역순 강제) — 이 제약을 runbook 에 명시


- [ ] **P19.** PR3 테스트: **라우팅**(전 controller prefix 양성 + 오라우팅 음성, `cart` 단수·`admin/products`·JWKS 외부 미노출 포함), **JWT 검증 + 응답 행렬 parameterized**(P12 행렬 전항목: known kid+LKG 정상+alert / unknown kid refresh 성공 후 미존재 401 / unknown kid refresh 실패 503 / cold start usable key 0 → readiness=false / Redis 실패 503 / deny·서명오류·만료 401 / role 부족 403 / 초과 429), RS256 왕복·**fallback off 시 HS512 거부/on 시 허용**·HS256/HS384/none 상시 거부, **family-less 토큰 회귀**(RS256·레거시 HMAC 각각 blacklist hit/miss + Redis 오류 + **보호 API·logout·혼재 Pod** 경로), 헤더 strip+inject(외부 spoof 제거 확증), **공개 경로 무헤더 양성**(SSOT 표 기준: signup/login/refresh·상품 **GET**·webhook) + 동일 서비스 보호 경로 무헤더 **401** + **`POST /api/v1/products/**`·admin API 의 401/403**, **JWKS 기대값 분리**(Gateway 외부 route **404** ↔ User 내부 endpoint **200** — P11 미노출과 정합), **header-trust 음성 매트릭스**(`X-User-Id` 단독·Role 누락·비숫자 ID·임의 Role·blank familyId·중복 헤더 → **401**, 500/anonymous fallback 아님), rate limit 429, **Redis 중단 fault-injection**(deny+RateLimiter 동시 fail-closed→503 + alert), **`WebApplicationType.REACTIVE` 부팅 + servlet/MVC 클래스패스 부재 검증**(보강 e).
  - **conformance = golden vector 방식(loop2 #6)**: 최종 head 에서는 비교 대상(`JwtTokenVerifier`)이 삭제되므로 "기존 verifier 와 동등" 을 최종 CI 에서 실행할 수 없다. → **PR3a 단계에서 differential test**(기존 verifier ↔ Gateway verifier: alg/kid/claims/Redis-key)를 실행하고 **결과 벡터를 독립 test fixture 로 동결**. PR3d 이후 최종 CI 는 **golden vector 에 대한 Gateway 단독 conformance** 만 실행. **k8s 음성·양성**: NodePort/서비스별 LB **부재**, Prometheus target up, non-gateway pod → 업무 API **차단**, gateway podSelector ingress → **성공**(SA 미도입 — vanilla NP 는 podSelector 로 판정), payment webhook 의 Gateway 경유. **GKE 필수 exit 실행화(보강)**: enforcement 확인→배포→probe(gateway/non-gateway/monitoring)→artifact 보존→cleanup 을 한 절차의 **보안 smoke 스크립트/수동 승인 job** 으로 만들고 증적 위치 지정. **실행 불가 시 렌더 성공으로 대체 금지 — PR3 미완료 처리.**

### PR4 — 관측성 S9 + HS512 잔재 제거 (D5)

> **범위 축소(보강 g)**: common-auth `JwtFilter`/`JwtTokenVerifier`/blacklist lookup **삭제는 PR3 P14 소관**(ADR-0014 D2-c 가 PR3 exit 로 규정). PR4 는 **Gateway 의 HMAC fallback 종료 + 잔재 sweep** 만 담당한다.

- [x] **P20.** S9 auth_failure 메트릭: 인증실패(서명오류/만료)·인가실패(403)·reuse 감지·429 counter(+사유 태그). SSOT = Gateway(인증필터/RateLimiter) + User(reuse/logout). 이름 1개소·이동/복제 금지.
- [x] **P21.** S9 관측성 구현(ADR **본문 수정 금지** — ADR-0009:58 에 S9 행 **이미 존재**, README:8-15 immutable): (a) S9 owner(gateway/user-service) **존재·정합 검증** + ADR-0015:42 per-service lint 정합 + Layer1(`02`/`04`) **현재 상태 동기화**. (b) S9 계약 자체를 바꾸는 경우에만 신규 ADR/Status 절차로 분리. dashboard `$application` 변수 + alert per-service + `observability lint` 갱신(도메인 5+인프라 1 = 6). **B11 sweep**: 식별자 치환은 `application=\?"..."` **및** `service=\?"..."` 형제 라벨 양쪽 + JSON/embedded-YAML escaped/unescaped 양쪽 sweep, 브랜드 문자열(`peekcart-*` uid/tag) 제외.
- [x] **P22.** **HS512** fallback 제거(overlap 만료 후, dual-validation 종료): **Gateway 검증기 RS256 단일화**(common-auth verifier 는 P14 에서 이미 삭제됨) + `JwtAuthProperties.secret`·HMAC 잔재 sweep. **레거시 `bl:<raw-token>` dual-read 제거**도 여기서 — access token 최대 TTL 경과 증명을 게이트로(보강 a). **ADR-0013 사실 정정(loop2 #8)**: `0013:10,14,29,30` 이 레거시를 "HS256" 으로 기술하나 실제 서명은 **HS512**(512bit secret) — *결정 변경이 아닌 사실 오류*라 README 규칙대로 **`## Update Log` 추가 + `fix(adr):` 커밋**으로 정정(Why 와 구현 사실 분리, 새 ADR 불요). `:65` 의 알고리즘 대안 비교(HS256 vs RS256 vs ES256)는 결정 근거라 **원문 유지**.
- [x] **P23.** PR4 테스트: 메트릭 counter 통합테스트(사유 태그별), observability lint **negative**(총계 6 불일치·**Gateway ServiceMonitor 누락**·selector 불일치·escaped-quote 잔존 false-green 차단), HS512 제거 후 부팅/검증 회귀(Gateway).

### PR3b 세부 — gateway k8s 배포 표면 (P17 분해)

> **id 배치 주의**: `hpx_plan_lint` 가 stable id 의 **파일 등장 순서**를 P1..Pn 연속으로 강제한다(`.claude/scripts/lib/sync.sh:38-45`). 기존 P20~P23(PR4)의 번호를 바꾸면 PHASE4/audit 의 인용이 깨지므로, PR3b 세부는 문서 말미에 **P24~P30** 으로 잇는다. 실행 순서는 PR3b → PR3c → PR3d → PR4 그대로다.
>
> **범위 정본 = §PR3 실행 분할표**(PR3b = gateway 매니페스트 + 외부 노출 + canary, PR3c = 5서비스 ClusterIP 환원 + NetworkPolicy + header-trust). `docs/TASKS.md:43` 의 "PR3b(k8s/NetworkPolicy·노출 단일화)" 축약 서술은 분할표에 맞춰 정정한다 — **5서비스 직접 경로가 살아 있어야 canary 롤백이 성립**하므로 ClusterIP 환원을 PR3b 로 당기면 롤백 경로가 사라진다.

**PR3b 진입 시 확정한 결정 (코드 grep 검증)**

- **(가) gateway ServiceMonitor·관측성 lint 6 은 PR4 이연** — `scripts/servicemonitor-selector-lint.sh:95` 의 `CANONICAL` 은 도메인 5 **정확 일치**(extra 도 위반)이고, ADR-0015 §Decision S5 가 그 집합을, S6.d 가 "expected-service set = SM 이 매칭하는 Service name 집합" 을 계약으로 고정했다. gateway SM 을 지금 넣으면 selector-lint(D5-V5) + scrape-absent equality 5 rule + S6.a/b regex 5-set 이 **동시에** 깨져 ADR-0015 계약 변경(신규 ADR)을 동반한다. → PR3b 는 SM 을 만들지 않고 **ADR 무변경**으로 끝내며, SM·alert·lint 6 확장은 PR4(P21)에서 ADR 과 함께 일괄한다. PR3b 는 selector-lint 가 그대로 5 로 그린임을 **확증**한다(gateway Service 는 SM 없는 Service 로 남고, 이는 mysql/redis/kafka Service 와 동일한 무해 케이스).
- **(나) Service 는 8080 단일 포트 — 관리 포트 8081 미노출**: gke overlay 가 gateway Service 를 `type: LoadBalancer` 로 patch 하는데, Service 에 8081 을 함께 선언하면 **LB 가 관리 포트까지 노출**해 PR3a 가 포트 분리로 막은 `/actuator/prometheus` 인터넷 노출이 k8s 층에서 되살아난다. 컨테이너는 8081 을 열되(probe·후속 scrape) Service 는 8080 만 게시한다. scrape 용 `gateway-metrics` ClusterIP Service 는 SM 과 함께 PR4 에서 신설.
  - **PR4 로 넘기는 계약(리뷰 #7 → 2차 #6 으로 확정)**: `gateway-metrics` 는 `app: gateway` **만으로 선택되면 안 된다** — `servicemonitor-selector-lint.sh` 가 SM 이 매칭한 **모든** Service 에 endpoint port 존재를 요구하므로, 공용 label 만 쓰면 SM 이 public gateway Service(8080, 8081 없음)까지 매칭해 실패한다. → PR4(P21) 인수조건으로 **고정**: `gateway-metrics` Service labels = `{app: gateway, monitoring-role: metrics}`, ServiceMonitor `selector.matchLabels` = **동일한 두 키(논리곱)**, canonical 6 확장과 SM `metadata.name` 도 그때 함께 확정. **Secret 부재 계약(결정 라)은 PR4 에서도 유지** — PR4 가 뒤집는 것은 SM 기대값(5→6) 하나뿐이다(2차 리뷰 #3).
- **(다) probe 는 8081** — `gateway/src/main/resources/application.yml` 의 `management.server.port: 8081` 때문에 `/actuator/health/{liveness,readiness}` 는 8081 에만 있다(도메인 5서비스는 8080). readiness 는 JWKS usable key ≥1 을 반영하므로(`JwksReadinessConfig`) cold start 시 트래픽 미수신이 k8s 층에서 그대로 성립한다.
- **(라) Secret 매니페스트 미생성**: 현재 gateway 가 소비하는 비밀이 없다 — HS512 fallback 은 base 에서 off 이고 `JWT_SECRET` 은 빈 기본값, RS256 **개인키는 user-service Pod 전용**(보강 a, gateway 미마운트). P17 의 "secret" 항목은 **의도적 미이행**(빈 Secret 은 speculative). 전환 배포에서 fallback 을 켜야 할 때만 Secret 을 신설한다.
- **(마) gateway HPA 는 order-service 단일 HPA 원칙의 명시적 예외**: `k8s/overlays/gke/hpa.yml` 이 "5서비스 균일 HPA 기각" 을 못박았으나, gateway 는 **전 트래픽 단일 진입점(SPOF)** 이라 §2 트레이드오프가 HA 를 요구한다. 도메인 서비스 확장 정책이 아니라 인프라 컴포넌트 가용성 결정으로 분리 기록.

- [ ] **P24.** gateway base 매니페스트 — `k8s/base/services/gateway/{deployment.yml(Deployment+Service), configmap.yml}` + `k8s/base/kustomization.yml` 등록(2 리소스). 디렉터리명은 **`gateway`**(`-service` 접미사 없음) — `scripts/image-contract-lint.sh:47` 의 `INFRA_SERVICES=(gateway)` 가 `k8s/base/services/${svc}/deployment.yml` 로 직결. image = `ghcr.io/kimgyuilli/peekcart-gateway:latest`(D-015 3-way 정본). 컨테이너 포트 8080(+8081), probe 3종은 **8081**(결정 다), `replicas: 1`(base), resources 는 도메인 서비스 patch 와 같은 축.
  - **`envFrom.configMapRef.name: gateway-config` 필수(리뷰 #1, P1)**: 도메인 서비스 선례(`k8s/base/services/user-service/deployment.yml:33-37`)와 달리 이 배선을 빠뜨리면 `SPRING_PROFILES_ACTIVE=k8s` 가 주입되지 않아 **application-k8s.yml 이 통째로 비활성**되고 Redis 가 `localhost` 기본값으로 붙는다. 렌더도 부팅 테스트도 못 잡는 false-green → P30 에서 **참조 존재를 구조적으로 assert**.
  - **식별자 계약 고정(리뷰 #6)**: Deployment/Service `metadata.name: gateway`, 컨테이너 `name: gateway`, ConfigMap `metadata.name: gateway-config`. strategic merge 는 gvk+`metadata.name`, 컨테이너는 `name` 이 merge key라 하나라도 어긋나면 patch 가 조용히 무시되거나 **두 번째 컨테이너가 생긴다**.
  - **`strategy.rollingUpdate.maxUnavailable: 0`**(+`maxSurge: 1`) 명시 — §6 롤아웃 공통 조건을 Deployment 필드로 실체화(리뷰 #8).
- [ ] **P25.** gateway ConfigMap + k8s 프로파일 — ConfigMap 은 도메인 서비스와 동형으로 `SPRING_PROFILES_ACTIVE: k8s` 만 담고, **k8s 연결 정보는 `gateway/src/main/resources/application-k8s.yml` 이 단독 소유**(ADR-0007: 연결 정보=프로파일, 라우트 predicate·rate limit·alg allow-list=base).
  - **소유 범위(리뷰 #2, P1)**: k8s yml 이 Redis 뿐 아니라 **업스트림 URI 5종·JWKS URI 도 명시 선언**한다 — `spring.data.redis.{host,port}` + 라우트가 참조하는 업스트림 키 5종 + `app.gateway.jwt.jwks-uri`. base 기본값은 **로컬 실행 편의**로 남기되, "k8s 에서 실제로 쓰이는 값" 의 소유자는 프로파일로 일원화한다(base 기본값이 k8s 정본을 겸하던 애매함 제거).
  - **placeholder 키를 정규 계층형으로 교정(2차 리뷰 #1)**: 현재 base 는 `uri: ${USER_SERVICE_URI:http://user-service:8080}` — 프로파일이 이 값을 소유하려면 `USER_SERVICE_URI:` 라는 **환경변수 표기법을 프로퍼티 이름으로 고착**시켜야 한다(동작은 하지만 비정규). → base 의 placeholder 이름만 `${app.gateway.upstream.user-uri:http://user-service:8080}` 류로 바꾸고(5종 + `jwks-uri` 는 이미 정규 키) `application-k8s.yml` 이 그 키를 채운다. 환경변수 override 는 `APP_GATEWAY_UPSTREAM_USER_URI` 로 그대로 가능(SystemEnvironmentPropertySource 완화 매핑). **라우트 목록 구조는 base 에 그대로 둔다** — 프로파일로 옮기면 라우트 전체가 복제된다. 기존 테스트가 `*_SERVICE_URI` 를 참조하지 않음은 확인함(`gateway/src/test` 는 `app.gateway.jwt.jwks-uri` 만 사용).
- [ ] **P26.** overlay patch — minikube: `patches/gateway-service.yml`(NodePort **30080** — minikube kustomization 주석의 "외부 노출 30080" 을 gateway 가 승계, 기존 30081~30085 와 무충돌) + `patches/gateway-deployment.yml`(`imagePullPolicy: Never`). gke: `patches/gateway-service.yml`(Internal LB annotation, 5서비스와 동일) + `patches/gateway-deployment.yml`(resources 상향) + `images[]` **6번째 entry**(`ghcr.io/kimgyuilli/peekcart-gateway` → `.../peekcart/gateway`) + `hpa.yml` 에 gateway HPA 추가(**minReplicas: 2**, maxReplicas 4, CPU 60%, `scaleTargetRef.name: gateway`, 결정 마). 양 overlay `kustomization.yml` patches 목록 등록. 모든 patch 의 `metadata.name`·컨테이너 `name` 은 P24 식별자 계약과 동일(리뷰 #6).
- [ ] **P27.** CI 계약 — (a) `IMAGE_CONTRACT_TRANSITION` 제거 → **full 6/6**: `.github/workflows/ci.yml:55-59` 의 env 와 전환기 주석 삭제. 제거 후 `scripts/image-contract-lint.sh` 가 `checked_manifests == 6` 로 "full" 을 출력해야 한다(5/6 이면 실패 — PR3a 가 남긴 꼬리의 종결 지점). (b) **`scripts/gateway-exposure-lint.sh` 신설 + 같은 policy step 에 등록(리뷰 #4, P1)** — 아래 음성 조건은 `kubectl kustomize` 가 성공으로 통과시키므로 자동 검출이 없다. 렌더 산출 YAML 을 파싱해 위반 시 **non-zero exit**:
  - **데이터 경로 전체를 고정(2차 리뷰 #2, P1)** — `ports[].port == {8080}` 만 보면 **`port: 8080, targetPort: 8081` 이 통과해 관리 엔드포인트가 LB 8080 으로 공개**된다. 따라서: Service `ports` 정확히 1개 + `port == 8080` **and `targetPort == 8080`**.
  - **개수·selector 정합 — 이름이 아니라 실제 매칭으로 판정(3차 리뷰 #1, P1)**: `metadata.name` 만 세면 **다른 이름의 Service 가 gateway Pod 를 선택**하거나 **다른 Deployment 가 `app: gateway` Pod 를 추가**하는 우회가 남는다. → 렌더 산출 전체에서 ① **gateway Pod 를 selector 로 선택하는 Service 가 정본 하나뿐** ② **`app: gateway` Pod 를 생성하는 workload 가 정본 Deployment 하나뿐** ③ Deployment `selector.matchLabels` ↔ Pod template labels ↔ Service `selector` 3자 일치 ④ Deployment `containers` **정확히 1개**이고 `name == gateway`(P24 merge key 사고 탐지) ⑤ ConfigMap 1개. PR4 에서 `gateway-metrics` 를 **명시 allow-list** 로만 추가한다.
  - **호스트 네트워크 우회 차단(3차 리뷰 #1)**: `hostNetwork == false` + 모든 컨테이너의 `hostPort` **부재** — hostPort 8081 은 Service 의 port/targetPort 검사를 통째로 우회해 관리 포트를 노출한다.
  - **probe 계약**: startup/readiness/liveness 3종의 포트 == **8081** + 경로(`/actuator/health/{liveness,readiness}`) — 결정 (다)를 매니페스트에 고정.
  - **비밀 미주입은 이름이 아니라 참조로, PodSpec 전체를 대상으로(2차 #3 → 3차 #2 로 확장, P1)**: 이름 prefix 추측은 임의 이름 Secret 을 놓치고, **`containers` 만 순회하면 `initContainers` 를 통한 주입을 놓친다**(initContainer 가 Secret 을 읽어 emptyDir 로 복사 가능하고, native sidecar 는 `restartPolicy: Always` 로 `initContainers` 에 위치해 "컨테이너 1개" 검사에도 안 걸린다). → **PodSpec 전체**에서 `containers` + `initContainers` 의 `env[].valueFrom.secretKeyRef` · `envFrom[].secretRef` 와 `volumes[].secret` · `volumes[].projected.sources[].secret` 이 **전무**함을 검사. gateway 는 initContainer 가 필요 없으므로 **`initContainers` 0개를 계약으로 고정**(가장 단순한 차단). k8s API 를 쓰지 않으므로 `automountServiceAccountToken: false` 도 함께 고정.
  - **ServiceMonitor 집합은 검사하지 않는다** — `servicemonitor-selector-lint.sh` 의 canonical 정확일치가 이미 소유한 책임이라 중복 검사는 PR4 에서 **두 곳을 동시에 고쳐야 하는 부채**만 만든다. (2차 리뷰 #3)
  - gateway Deployment 의 `envFrom[].configMapRef.name == gateway-config` **존재** + 해당 ConfigMap 의 `SPRING_PROFILES_ACTIVE == k8s`(1차 리뷰 #1 false-green 차단)
  - gateway Deployment 의 `strategy.rollingUpdate.maxUnavailable == 0`
  - minikube 렌더: gateway Service `type == NodePort && nodePort == 30080` / gke 렌더: `type == LoadBalancer` + Internal annotation + `images[]` rewrite 적용 결과가 `.../peekcart/gateway`
  - **음성 자기검증(조작 입력 정본 — §5·§6 은 이 목록을 복제하지 말고 여기를 참조)**: `targetPort=8081` · Service selector 불일치 · 컨테이너 2개 · **다른 이름의 두 번째 Service 가 gateway Pod 선택** · **다른 이름 Deployment 가 `app: gateway` Pod 생성** · `hostPort: 8081` · `initContainers` 의 `secretKeyRef` 주입 · `projected` Secret volume · `configMapRef` 제거 — **9종** 전부에서 스크립트가 non-zero 여야 한다(vacuous-green 차단, `image-contract-lint` 두 모드 검증 선례).
  - **검증 소유권 분계(3차 리뷰 #4)**: **Secret 소비 참조 = `gateway-exposure-lint`** / **ServiceMonitor 집합 = `servicemonitor-selector-lint`**. 두 스크립트가 같은 대상을 겹쳐 검사하지 않는다.
- [ ] **P28.** 롤아웃 runbook(§7 신설) — canary → 100% 전환 절차를 **실행 가능한 명령**으로.
  - **rollback 은 `kubectl delete -k` 금지(리뷰 #3, P1)**: gateway 전용 kustomization 이 없어 overlay 전체를 대상으로 실행되면 **5서비스 + MySQL/Redis/Kafka(PVC 포함)** 까지 삭제된다 — 롤백이 전면 장애가 된다. 정본 순서는 ① 클라이언트 진입점을 기존 서비스별 NodePort/LB 로 **먼저** 복귀 → ② 필요 시 `kubectl -n peekcart rollout undo deployment/gateway` → ③ 완전 철거가 필요하면 `kubectl -n peekcart delete deployment/gateway service/gateway configmap/gateway-config`(+gke `hpa/gateway`)를 **이름 단위로** 명시. runbook 에 "overlay 전체 delete 금지" 를 경고로 박는다.
  - **canary 파라미터(리뷰 #8)**: 트래픽 분할은 클라이언트 진입점 전환(요청 비율)로 정의하고 — 진입점 cohort·승격 임계(5xx 비율·p95)·중단 임계·관찰 시간을 수치로 적는다. 단계별 이미지는 **`latest` 금지 — SHA 태그 또는 digest 고정**(`kustomize edit set image ...@sha256:...`, `scripts/promote-images.sh` 가 digest 산출)해야 canary/rollback 버전이 재현된다.
  - **rollback 을 재현 가능하게(2차 리뷰 #5, P1)**: `rollout undo` 만으로는 대상 revision 이 암묵적이고, undo 뒤 **canary digest 가 그대로인 kustomization 을 다시 apply 하면 즉시 실패 버전으로 복귀**한다. runbook 순서를 ① 배포 **전** known-good digest + `kubectl rollout history` revision 번호 **기록** → ② 진입점 복귀 → ③ `rollout undo --to-revision=<기록값>` 또는 `kubectl -n peekcart set image deployment/gateway gateway=<known-good>@sha256:...` → ④ `rollout status` + **기존 5서비스 진입점 정상성 확인** → ⑤ 로컬 kustomization 도 known-good digest 로 되돌린 뒤에만 재apply — 로 고정한다.
  - **②의 제어면과 barrier 를 명시(3차 리뷰 #3, P1)**: "진입점 복귀" 를 추상어로 남기지 않는다 — 환경별 실제 수단(minikube = 클라이언트가 치는 NodePort 번호, gke = 사용할 LB 주소)과 cohort 값을 runbook 에 적고, **② 직후 barrier** 를 둔다(기존 5서비스 진입점 도달성 확인 + gateway 잔존 트래픽 0 또는 허용 임계 이하). 전파 전에 gateway 를 undo/철거하면 잔존 트래픽이 실패한다. **기록 위치도 고정**: known-good digest·revision·전환 시각을 타임스탬프 붙은 증적 파일로 남긴다(장애 중 기억에 의존 금지). **완전 철거 시 HPA 를 Deployment 보다 먼저 삭제**하고(HPA 가 replica 를 되살리는 것 방지), 이미지 rollback 중에는 HPA 유지·일시정지 중 어느 쪽인지 명시.
  - 배포(`kubectl apply -k k8s/overlays/<env>`) → readiness 확인 → 보호/공개/spoof 3종 probe → 전환 → 오류율 확인 → 역순 rollback. PR3c 진입 게이트("100% 전환 유지·혼재 구간 refresh/logout 정상")의 판정 기준을 수치로 적는다.
- [ ] **P29.** gke README 갱신 — 외부 진입점이 서비스별 5개 LB 에서 **gateway 1개** 로 바뀜(5서비스 LB 는 PR3c 까지 잔존하는 전환기 표면임을 명시). 기존 per-service 노출 안내에 전환기 표식.
- [ ] **P30.** PR3b 검증(§5 PR3b 항 참조) — 렌더 양성/음성 + lint 3종 + gateway 이미지 smoke 재확인. **canary 실증적은 PR3c GKE 세션에 합류**(아래 결정 참조).
  - **canary 증적 처분(정직성 게이트)**: 본 repo 에는 앱 레벨 compose e2e 가 없고(`docker-compose.yml` = 인프라 3종만) 상시 클러스터도 없다. 따라서 PR3b 는 **렌더+lint+이미지 smoke** 로 코드 산출물을 닫되, **"canary 통과" 를 렌더 성공으로 대체 기록하지 않는다**. 실 클러스터 probe(보호/공개/spoof·오류율)는 PR3c 의 **GKE 보안 smoke 세션에서 NetworkPolicy 음성·양성과 함께 1회 수행**하고, 그때까지 PR3b 는 "매니페스트 완료 / 전환 증적 미확보" 로 §6 에 남긴다(§5 PR3 의 "렌더-only 대체 금지" 규칙 유지).

### PR3c 세부 — 5서비스 ClusterIP 환원 + NetworkPolicy + header-trust 전환 (P14 전환분·P17 나머지 분해)

> **id 배치**: `hpx_plan_lint` 가 stable id 의 **파일 등장 순서**를 P1..Pn 연속으로 강제한다(`.claude/scripts/lib/sync.sh:38-45`). PR3b 세부가 P24~P30 을 소비했으므로 PR3c 세부는 **P31~P38** 로 잇는다. 실행 순서는 PR3b → **PR3c** → PR3d → PR4 그대로.
>
> **범위 정본 = §PR3 실행 분할표의 PR3c 행**: (1) 5서비스 직접 노출 제거(ClusterIP 환원) + NetworkPolicy 로 gateway 경유 강제, (2) header-trust **호환 이미지 rollout**(구·신 Pod 혼재 허용), (3) PR3b 가 미확보로 남긴 canary 증적을 **GKE 보안 smoke 세션에서 1회 수행**. **servlet verifier(`JwtFilter`/`JwtTokenVerifier`/blacklist lookup) 삭제 = ADR-0014 D2-c exit 는 PR3d** — PR3c 는 header-trust configurer 를 **추가·전환**하되 구 컴포넌트는 **잔존**시킨다(rollback 안전창).

**PR3c 진입 시 확정한 결정 (2026-07-24, 코드 grep 검증)**

- **(¬a) 각 Pod 는 단일 모드 — 혼재는 fleet 수준**: gateway 가 `Authorization` 을 **strip 하지 않고 전달**함이 확인됨(`GatewayAuthenticationFilter:37` "PR3a 전환기: Authorization 을 제거하지 않고 그대로 전달"). 따라서 구 이미지 Pod = Bearer 검증(`JwtFilter:38-46`), 신 이미지 Pod = `X-User-*` 신뢰 — **gateway 가 양쪽을 다 실어 보내므로 각 Pod 는 자기 것만 읽으면 된다**(Pod 별 dual-accept 불필요, "헤더/Bearer 양쪽 수용" 은 fleet 해석). rollback = 구 이미지 재배포로 성립(gateway Authorization 전달이 PR3d 까지 살아 있어야 하는 이유). **Authorization 전달 중단은 PR3d** 이므로 PR3c 에서 gateway 를 건드리지 않는다.
- **(¬b) 안전 순서 = NetworkPolicy/ClusterIP 를 header-trust rollout 보다 먼저**: 서비스를 header-trust(`X-User-*` 맹신)로 먼저 전환하고 NodePort/LB 가 살아 있으면 **직접 경로로 위조 `X-User-*` 주입 = 전면 spoof**. 반대로 NetworkPolicy/ClusterIP 를 먼저 적용하면 그 시점 Pod 는 아직 구 이미지(Bearer 검증)라 `X-User-*` 를 무시 → 위조 무효. 그러므로 PR3c 매니페스트(P34/P35)를 **먼저 apply → 확인 → 이후 header-trust 이미지 rollout(P32)**. 이 순서를 P36 runbook 에 barrier 로 고정. (§PR3 P18 롤아웃 ③→④ 순서의 실체화)
- **(¬c) header-trust 전환 = configurer 스왑, 구 컴포넌트 잔존**: 5서비스 SecurityConfig 는 전부 `jwtSecurityConfigurer.apply(http, ActuatorSecurityConfig.mergedPublicUrls(BUSINESS_PUBLIC_URLS))` 단일 위임(`UserSecurityConfig:44`·Product/Order/Payment/Notification 동형). → `HeaderTrustSecurityConfigurer.apply(...)` 로 **호출부만 교체**, `BUSINESS_PUBLIC_URLS`·`ActuatorSecurityConfig` 병합·`@EnableMethodSecurity`(Product/Order/Payment 의 `@PreAuthorize`) **불변**. `JwtSecurityConfigurer`/`JwtFilter`/`JwtTokenVerifier`/`RedisTokenBlacklistLookupAdapter` 는 **삭제하지 않는다**(PR3d D2-c exit gate 까지 잔존 — 삭제를 앞당기면 rollback 이미지가 아닌 *현 소스* 의 회귀 위험 없이도 ADR-0014 D2-c 의 "family-less 소멸 증명" 선결을 건너뛰게 됨).
- **(¬d) `LoginUser` 계약 확장 = `(userId, accessToken)` → `(userId, role, familyId)`**: `.accessToken()` 유일 실사용처 = `AuthController.logout:56 → authService.logout(loginUser.accessToken())`(`AuthService.logout:87-98` = parseToken → blacklist + `revokeAllByUserId`). header-trust 하에선 리소스 서비스가 raw 토큰 미보유 → **`logout(userId, familyId)` = family deny write + `revokeAllByUserId`**(`jwtTokenVerifier` 의존 제거). resolver(`LoginUserArgumentResolver:44-45`)의 `getDetails()`→accessToken 매핑을 principal 확장으로 대체. **family-less(전환기 레거시) 토큰 = familyId null → family deny 생략, `revokeAllByUserId` 만**(access token 즉시 무효화 불가·TTL bounded, 보강 b 수용 리스크). **nullable accessToken 대안 폐기** 확정. 그 외 7개 `@CurrentUser LoginUser` 소비자(User/Order/Payment/Notification 컨트롤러)는 `userId()` 만 사용 → record 확장에 무영향(컴파일러 sweep 으로 확인).
- **(¬e) 테스트 재작성 실범위 = 통합 2개 + factory (보강 c 재확인)**: Bearer 사용 테스트 = `UserSecurityIntegrationTest`·`NotificationSecurityIntegrationTest`(+`JwtFilterTest`). 8 컨트롤러 슬라이스는 `@WithMockLoginUser`(`WithMockLoginUserSecurityContextFactory:20-30` = SecurityContext 직접 세팅, 필터 무관)라 **원칙 무영향** — factory 의 `principal=userId`/`details=accessToken` 을 principal 확장(role/familyId)으로 소폭 조정만. `JwtFilterTest` 는 PR3d(verifier 삭제)까지 **잔존**(PR3c 에서 이관/삭제하지 않음 — 구 컴포넌트가 살아 있으므로 그 테스트도 유효).
- **(¬f) overlay service patch 10개가 ClusterIP 환원 대상**: `overlays/{minikube,gke}/patches/<svc>-service.yml` × 5씩 = 10개가 NodePort(minikube 30081~30085)/Internal LB(gke) 로 patch. base Service 는 이미 ClusterIP(default) → **patch 파일 삭제 + 각 `kustomization.yml` patches 목록에서 제거**하면 자동 환원. **gateway patch(30080/LB)는 잔존**(단일 외부 진입점). 각 patch 주석이 이미 *"gateway ③ 도입 시 단일 ingress 로 통합 예정 — 전환기"* 로 예고(`overlays/gke/patches/user-service-service.yml:1`).
- **(¬g) NetworkPolicy 부재 → 신설(B2)**: 현재 `NetworkPolicy` 리소스 0(`kustomize-namespace-lint.sh:89` 의 허용 kind 목록·gke README 예고에만 존재). scrape 계약과 충돌 주의 — Prometheus 가 각 SM(`base/services/*/servicemonitor.yml`, `release: kube-prometheus-stack`·namespace `peekcart`·namespaceSelector)로 `/actuator/prometheus`(8080) 를 **직접 scrape**. gateway-only ingress 정책이 이 scrape 를 끊으면 관측성 붕괴 → **monitoring namespace/Prometheus pod 예외를 policy 에 명시**(보강 f).
- **(¬h) GKE 실 클러스터 이번 세션 확보(사용자 확인)**: PR3b 가 "매니페스트 완료/증적 미확보" 로 남긴 canary probe(보호/공개/spoof·오류율)를 PR3c 의 NetworkPolicy 음성·양성과 **함께 1회 실 클러스터 수행**. GKE 는 NetworkPolicy enforcement 에 **Dataplane V2 또는 `--enable-network-policy`(Calico)** 필요 — 클러스터 생성 시 활성 필수(미활성이면 policy 가 조용히 무시돼 음성 테스트가 false-green). 증적은 `docs/progress/evidence/pr3c-gke-smoke-<YYYYMMDD-HHMM>.md`.

- [ ] **P31.** common-auth header-trust 인증 필터 신설 — `HeaderAuthenticationFilter`(reactive 아님, servlet `OncePerRequestFilter` — 리소스 서비스는 MVC) + `HeaderTrustSecurityConfigurer`. **헤더 3-state 계약(§PR3 P14, loop2 #4)**: ① 세 헤더 전부 없음 = **anonymous 로 체인 계속**(즉시 401 금지 — 공개 경로 보존, 보호 경로는 `anyRequest().authenticated()` 가 401) / ② `X-User-Id`(양의 정수)·`X-User-Role`(enum USER/ADMIN) 각 정확히 1개 + (PR3d 이후 `X-User-Family-Id` non-blank) = **인증** / ③ 부분 존재·blank·중복 헤더·형식 오류·미허용 role = **401**(파싱 예외가 500·anonymous 로 새지 않음). `X-User-Family-Id` 는 PR3c 전환기엔 **선택**(family-less 레거시 수용). `LoginUser(userId, role, familyId)` 확장 + `LoginUserArgumentResolver`(principal→userId, authorities→role, 신규 attribute→familyId) 갱신 + `:common` testFixtures `WithMockLoginUserSecurityContextFactory`(role/familyId 세팅) + `WithMockLoginUser`(role/familyId 속성) 조정. **`JwtSecurityConfigurer`/`JwtFilter` 등 구 컴포넌트는 잔존**(결정 ¬c).
  - **configurer 동등성 계약(review #6, B6)**: `HeaderTrustSecurityConfigurer.apply()` 는 `JwtFilter`→`HeaderAuthenticationFilter` **필터만 교체**하고 나머지 공통 정책은 `JwtSecurityConfigurer:38-51` 을 **전부 보존**한다 — `csrf.disable()`·`sessionManagement(STATELESS)`·`authorizeHttpRequests(publicUrls permitAll + anyRequest authenticated)`·`exceptionHandling(authenticationEntryPoint=JwtAuthenticationEntryPoint, accessDeniedHandler=JwtAccessDeniedHandler)`·`addFilterAfter(MdcFilter, <auth filter>)` 순서. 이 5개 중 하나라도 누락되면 401/403 응답 계약·MDC traceId·stateless 세션이 조용히 달라진다(configurer 스왑이 "필터만" 이 아님을 못박음).
- [ ] **P32.** 5서비스 header-trust 전환 + logout 계약(결정 ¬c/¬d): User/Product/Order/Payment/Notification `*SecurityConfig.filterChain` 의 `jwtSecurityConfigurer.apply(...)` → `headerTrustSecurityConfigurer.apply(...)` 교체(`BUSINESS_PUBLIC_URLS`·`ActuatorSecurityConfig.mergedPublicUrls`·`@EnableMethodSecurity` 불변). `AuthController.logout` = `@CurrentUser LoginUser` 에서 `userId()`/`familyId()` 추출 → `authService.logout(userId, familyId)`. `AuthService.logout(long userId, String familyId)` 재작성 = family deny write(non-null 시) + `revokeAllByUserId`(`jwtTokenVerifier.parseToken` 제거, `TokenBlacklistPort.denyFamily` 재사용). **공개 경로 SSOT(§PR3 표) 불변** — PR3c 는 필터 메커니즘만 교체하므로 permitAll 집합은 그대로.
- [ ] **P33.** 인증 테스트 재작성(결정 ¬e): `UserSecurityIntegrationTest`·`NotificationSecurityIntegrationTest` 를 Bearer→`X-User-*` 헤더 인증으로 재작성(3-state 음성 매트릭스: 헤더 없음 anonymous 통과 / 부분·blank·중복·비숫자 ID·임의 role→401 / 정상→인증). **configurer 동등성 회귀(review #6)**: 통합테스트에 401(무인증 보호경로)·**403(role 부족 — `@PreAuthorize` 경로)**·MDC traceId 응답 헤더 존재를 assert(JwtSecurityConfigurer 시절과 동일). 5서비스 context-load 스모크로 configurer 스왑 후 부팅 회귀 확인. `WithMockLoginUserSecurityContextFactory` role/familyId 반영으로 8 컨트롤러 슬라이스 그린 유지. `AuthServiceTest`/`AuthControllerTest` 의 logout 경로를 새 시그니처로. `JwtFilterTest` **잔존**(PR3d 삭제).
- [ ] **P34.** 5서비스 ClusterIP 환원 + `maxUnavailable:0`(결정 ¬f, review #2): `overlays/minikube/patches/{user,product,order,payment,notification}-service-service.yml`(NodePort) + `overlays/gke/patches/{동일}-service.yml`(Internal LB) **10개 삭제** + 양 `kustomization.yml` patches 목록에서 제거. **gateway service patch(minikube 30080·gke LB)는 유지**. base Service 가 ClusterIP default 로 자동 환원됨을 렌더로 확증. **5서비스 base Deployment 에 `strategy.rollingUpdate.maxUnavailable:0`(+`maxSurge:1`) 명시** — §8 무중단 rollout 을 주석이 아닌 매니페스트 필드로 실체화(gateway 는 PR3b 에서 이미 보유).
- [ ] **P35.** NetworkPolicy 신설 — **ingress-only, podSelector 기반**(결정 ¬g, B2; review #3/#4/#5 반영): vanilla NetworkPolicy 는 **peer 를 ServiceAccount 로 선택할 수 없다**(podSelector/namespaceSelector 만) → SA 기반 허용 폐기. **selector 정본(코드 grep 확정)**: 대상 = `podSelector.matchLabels {app.kubernetes.io/component: backend}`(5서비스 pod template 이 모두 보유, **gateway 는 `component: gateway` 라 자동 제외** — 신규 라벨 불요·Deployment 무수정). source ingress = ① gateway pod `{app: gateway}` 로부터 8080 + ② monitoring namespace 의 Prometheus pod 로부터 8080(`/actuator/prometheus` scrape 예외 — `namespaceSelector` monitoring NS + Prometheus pod label). **`policyTypes: [Ingress]` 단독** — egress 격리하지 않으므로 DNS/DB/Redis/inter-service outbound 는 정책 무관(egress 규칙·DNS 허용 삭제; egress 격리는 별도 하드닝 항목, PR3c 범위 밖). 배치 = **base `k8s/base/networkpolicy.yml`**(환경 무관 보안, minikube/gke 공통) + `kustomization.yml` 등록(`kustomize-namespace-lint.sh:89` 가 `NetworkPolicy` kind 를 이미 허용 → namespace 정합만). **enforcement 는 CNI 의존** — minikube 는 미보장(P37 GKE Dataplane V2 에서 실증). **Gateway 전용 ServiceAccount 는 PR3c 에서 미도입** — vanilla NP 가 SA 를 안 쓰므로 보안 기여 0. §PR3 P17 의 "Gateway SA" 는 workload identity(GKE WI) 용도라 **별도 항목으로 이연**(NetworkPolicy 와 무관함을 명시).
  - **실행 가능 lint(review #7) — `scripts/networkpolicy-contract-lint.sh` 신설**: 렌더 성공만으로는 "0개 workload 를 선택하는 정책"·"gateway peer 누락"·"monitoring 예외 누락" 이 전부 통과한다(vacuous-green). 렌더 산출을 파싱해 assert: ① 정책의 `podSelector` 가 렌더된 backend Deployment pod(5개) 를 **실제로 선택**(0개면 실패) ② gateway `{app: gateway}` ingress peer **존재** ③ monitoring namespace scrape 예외 peer **존재** ④ `policyTypes == [Ingress]`(egress 규칙 부재) ⑤ 대상에 gateway pod(`component: gateway`) **불포함**. **self-test(조작 입력 non-zero)**: podSelector 라벨 오타(0 선택)·gateway peer 제거·monitoring peer 제거·egress 규칙 추가·backend 라벨을 gateway 로 오설정 → **5종 전부 non-zero**(`gateway-exposure-lint` self-test 선례). CI policy step 등록.
- [ ] **P36.** PR3c 무중단 rollout runbook(§8 신설, 결정 ¬a/¬b): **안전 순서 강제** — ① NetworkPolicy + ClusterIP 환원 apply(구 이미지 Pod = Bearer 검증이라 위조 `X-User-*` 무효) → ② **barrier**: 직접 경로(NodePort/LB) 도달 불가 확인 + Prometheus target up 유지 확인 → ③ header-trust 이미지 rollout(`maxUnavailable=0`, digest 고정 태그, 혼재 구간 gateway 가 Authorization+헤더 병행 공급) → ④ 혼재 구간 refresh/logout 정상 확인 → ⑤ 100% 신 이미지. **역순 rollback**: 신 이미지 문제 시 known-good 이미지로 `rollout undo`(gateway Authorization 전달이 살아 있어 구 Bearer Pod 복귀 즉시 인증 회복) → 필요 시 NetworkPolicy/ClusterIP 되돌리기(NodePort/LB patch 복구). **`kubectl delete -k` 금지**(PR3b runbook 과 동일 — overlay 전체 삭제 위험). digest 기록·revision·전환 시각을 증적 파일에.
- [ ] **P37.** GKE 보안 smoke 스크립트 + 실 클러스터 1회 수행(결정 ¬h, §PR3 P19 "GKE 필수 exit 실행화"): `scripts/gke-security-smoke.sh`(또는 수동 승인 job) = **enforcement 확인**(Dataplane V2/network-policy 활성 검증 — 미활성 시 즉시 fail, false-green 차단) → **배포**(digest 고정) → **probe**: [양성] gateway 경유 공개 경로 무헤더 200·보호 경로 401·spoof `X-User-*` 제거 확증·payment webhook gateway 경유 / [음성] non-gateway pod → 업무 API **차단**·직접 NodePort/LB 부재 / [scrape] Prometheus target up·`/actuator/prometheus` monitoring 예외 통과 / [canary] PR3b 미확보분(보호·공개·spoof·오류율 임계) → **증적 보존**(`docs/progress/evidence/pr3c-gke-smoke-<ts>.md`) → **cleanup**(`loadtest/cleanup.sh` 계열, orphan PD/IP 확인). **렌더 성공을 실행 증적으로 대체 금지 — 미실행 시 PR3c 미완료**.
- [ ] **P38.** PR3c 검증(§5 PR3c 항): `./gradlew build test`(9모듈) 그린 — header-trust 3-state 음성 매트릭스·**configurer 동등성 회귀(401/403/MDC, review #6)**·logout 새 계약·**family-less logout TTL 계약(review #8: access 유효/refresh 차단)**·8 슬라이스 factory 회귀. 렌더 양성/음성 — ClusterIP 환원(5서비스 Service `type` 부재=ClusterIP·gateway 만 NodePort/LB)·NetworkPolicy `policyTypes:[Ingress]`+`component:backend` 5선택+gateway peer+monitoring 예외. CI policy lint 전체(namespace·image-contract full 6/6·gateway-exposure·**networkpolicy-contract+self-test**·servicemonitor **5 유지**·observability 2)가 NetworkPolicy/ClusterIP 추가 후에도 그린(**PLAN-BLINDSPOTS 자동화후보 "k8s 리소스 추가 시 lint 전부 실행"** 준수). **GKE smoke 증적(P37) 확보 = PR3c 완료 필수 게이트**.

## 4. 영향 파일

- **신규 모듈**: `gateway/**`(build.gradle·routing config·검증 필터·RateLimiter·Dockerfile), `settings.gradle`.
- **PR1**: `user-service/global/jwt/JwtTokenSigner.java`, `peekcart-common-auth/global/jwt/JwtTokenVerifier.java`·`JwtAuthProperties.java`(→`JwtKeyProperties` 신규), User JWKS controller, **테스트 키쌍 = `:common` testFixtures 리소스**(단일 소유, B5).
- **PR2**: `user-service` `refresh_tokens` 마이그레이션(V2)·`RefreshToken.java`·`RefreshTokenRepository(Impl/Jpa)`·`AuthService.java`·`TokenBlacklistPort`/deny 확장·`TokenClaims`(family_id)·common-auth `JwtTokenVerifier`(claim 매핑)·`JwtFilter`/`TokenBlacklistLookupPort`/`RedisTokenBlacklistLookupAdapter`(전환기 family deny enforcement)·`JwtTokenSigner`/`TokenIssuer`(issue 시그니처).
- **PR3b**(실행 분할 — P24~P30):
  - *신설*: `k8s/base/services/gateway/{deployment.yml,configmap.yml}`(Deployment+Service+ConfigMap), `gateway/src/main/resources/application-k8s.yml`, `k8s/overlays/minikube/patches/gateway-{deployment,service}.yml`, `k8s/overlays/gke/patches/gateway-{deployment,service}.yml`.
  - *신설(계약 lint)*: `scripts/gateway-exposure-lint.sh`(P27b — 렌더 음성 조건 실행화).
  - *수정*: `k8s/base/kustomization.yml`(+2 리소스)·`k8s/overlays/{minikube,gke}/kustomization.yml`(patches 등록, gke `images[]` 6번째)·`k8s/overlays/gke/hpa.yml`(gateway HPA 추가)·`.github/workflows/ci.yml`(`IMAGE_CONTRACT_TRANSITION` 제거 + `gateway-exposure-lint` 등록)·`k8s/overlays/gke/README.md`·본 계획서 §7 runbook.
  - *부분 수정*: `gateway/src/main/resources/application.yml` — **구조는 유지**(라우트 목록·rate limit·alg allow-list 는 동작 규약이라 base 소유)하되 ① 업스트림 placeholder 이름을 정규 계층형 키로 교정(`${USER_SERVICE_URI:..}` → `${app.gateway.upstream.user-uri:..}` 5종, 2차 리뷰 #1), ② `:176` 부근 주석 정정 — 현재 "k8s Service 는 8080 만 노출하고 ServiceMonitor 가 8081 을 scrape 한다(P17/PR3b)" 는 SM 이 PR4 로 이연된 계획과 모순 → "PR3b 는 probe 전용, PR4 의 `gateway-metrics` Service + ServiceMonitor 가 8081 을 scrape" 로 수정(2차 리뷰 #7).
  - *미포함(의도)*: Secret(소비 비밀 0, 결정 라)·ServiceMonitor(ADR-0015 계약, 결정 가 → PR4)·ServiceAccount(vanilla NetworkPolicy 는 `podSelector` 로 peer 선택 → SA 는 정책에 기여 0, **PR3c 에서도 미도입** — workload identity 용도면 별도 항목 이연, review #3/#4)·NetworkPolicy/5서비스 ClusterIP 환원(→ PR3c).
- **PR3c**(실행 분할 — P31~P38):
  - *신설*: `peekcart-common-auth/global/security/HeaderAuthenticationFilter.java`·`HeaderTrustSecurityConfigurer.java`, `k8s/base/networkpolicy.yml`(**ingress-only**·`podSelector{component:backend}`·gateway peer + monitoring scrape 예외 — SA·egress 없음), `scripts/networkpolicy-contract-lint.sh`(review #7, self-test 포함), `scripts/gke-security-smoke.sh`(P37 — 실 클러스터 보안 probe·canary 증적), `docs/progress/evidence/pr3c-gke-smoke-<ts>.md`(증적).
  - *수정*: 5서비스 `*SecurityConfig.java`(configurer 스왑)·`LoginUser.java`(→`(userId, role, familyId)`)·`LoginUserArgumentResolver.java`·`AuthController.logout`·`AuthService.logout`(family deny + `revokeAllByUserId`)·`:common` testFixtures `WithMockLoginUser`/`WithMockLoginUserSecurityContextFactory`·`UserSecurityIntegrationTest`·`NotificationSecurityIntegrationTest`·`AuthServiceTest`/`AuthControllerTest`(logout)·5서비스 Deployment(`strategy.rollingUpdate.maxUnavailable:0` 명시, review #2)·`k8s/base/kustomization.yml`(+networkpolicy)·`k8s/overlays/{minikube,gke}/kustomization.yml`(service patch 5개씩 제거)·`.github/workflows/ci.yml`(networkpolicy-contract-lint 등록)·`k8s/overlays/gke/README.md`(전환기 표면 → gateway 단일)·본 계획서 §8 PR3c runbook.
  - *미포함(의도)*: Gateway ServiceAccount(vanilla NP 가 SA peer 미지원 → 보안 기여 0, workload identity 는 별도 이연 — review #3/#4)·egress NetworkPolicy(ingress-only, review #5).
  - *삭제*: `k8s/overlays/{minikube,gke}/patches/{user,product,order,payment,notification}-service-service.yml`(**10개** — NodePort/LB, ClusterIP 환원).
  - *미포함(의도, PR3d 소관)*: `JwtFilter`/`JwtTokenVerifier`/`JwtSecurityConfigurer`/`TokenBlacklistLookupPort`/`RedisTokenBlacklistLookupAdapter`/`JwtFilterTest` **삭제**(ADR-0014 D2-c exit) + gateway `Authorization` 전달 중단(PR3d) — PR3c 는 구 컴포넌트 **잔존**(rollback 안전창).
- **PR3(전체)**:
  - *신설*: `gateway/**`(routing 정본·reactive 검증 필터·RateLimiter·gateway-local DTO·Dockerfile), `peekcart-common-auth/global/security/`(HeaderAuthenticationFilter·HeaderTrustSecurityConfigurer), `k8s/base/services/gateway/**`(deployment/svc/cm/HPA)·NetworkPolicy. **Gateway ServiceMonitor 는 PR4 신설**(PR3c 동안 canonical SM 정확히 5 유지 — ADR-0015 S5/S6.d·P38, review #10). Secret·SA 는 PR3b/PR3c "미포함(의도)" 결정 참조.
  - *삭제(ADR-0014 D2-c exit)*: common-auth `JwtFilter`·`JwtTokenVerifier`·`JwtSecurityConfigurer`·`TokenBlacklistLookupPort`·`RedisTokenBlacklistLookupAdapter`(+`JwtFilterTest`) 및 common-auth/5서비스의 검증용 JJWT·Redis 의존.
  - *수정*: 5서비스 `*SecurityConfig.java`·`LoginUser`/`LoginUserArgumentResolver`/`AuthController.logout`/`AuthService.logout`·`WithMockLoginUserSecurityContextFactory`·통합테스트 2개·`settings.gradle`·CI images 매트릭스·`scripts/image-contract-lint.sh`·`scripts/promote-images.sh`·`scripts/servicemonitor-selector-lint.sh`·**overlay service patch 10개 제거**(minikube NodePort 5·gke LB 5).
- **PR4**: Gateway/User 메트릭 컴포넌트·grafana dashboard/alert·observability lint·`02`/`04` Layer1 동기화. **ADR 본문은 수정 안 함**(S9 는 ADR-0009:58 기존재) — 존재/owner 정합만 검증, 계약 변경 시에만 신규 ADR.

## 5. 검증 방법

- **PR1**: `./gradlew :user-service:test :peekcart-common-auth:test` — RS256 왕복·alg 거부·JWKS 스키마 그린. HS256 위조 토큰 401.
- **PR2**: `./gradlew :peekcart-common-auth:test :user-service:test` — grace 1회/2회 차단·병렬 1건만 성공, reuse→family revoke→Redis deny write 확증(Testcontainers Redis), common-auth `TokenClaims`/parseToken family_id 회귀.
- **PR3**: `./gradlew build test`(9모듈) — gateway 라우팅(전 prefix 양성 + 오라우팅 음성)·응답 행렬(401/403/429/503+readiness)·fail-closed·헤더 strip/inject·**header-trust 음성 매트릭스(부분·형식오류 401)**·공개 경로 SSOT 표 기준 무헤더 양성 ↔ 보호 경로 401/403·JWKS 404(외부)↔200(내부)·rate limit 429·Redis fault-injection·**conformance golden vector**(PR3a differential → 동결 → PR3d 이후 Gateway 단독)·REACTIVE 부팅+servlet 부재. **단계별 canary**(PR3a~d 진입 조건) 증적. **k8s 음성·양성**(NodePort/LB 부재·Prometheus target up·non-gateway 차단·**gateway podSelector ingress 성공**·webhook Gateway 경유) — minikube CNI 제약 시 **GKE 보안 smoke 스크립트 필수 exit**(enforcement 확인→배포→probe→증적→cleanup, 렌더-only 불충분·미실행 시 PR3 미완료).
- **PR3b**(P30):
  - ① 렌더 — `for env in minikube gke; do kubectl kustomize "k8s/overlays/$env"; done`(brace expansion 은 한 명령에 인자 2개를 넘겨 실패한다). **양성**: gateway Deployment/Service/ConfigMap 각 1, gke `images[]` rewrite 가 gateway 에도 적용(`.../peekcart/gateway`), HPA 2건(order-service·gateway), minikube gateway Service `type=NodePort nodePort=30080`.
  - ② **음성은 산문이 아니라 실행 가능한 assertion** — `scripts/gateway-exposure-lint.sh` 가 렌더 산출을 파싱해 위반 시 non-zero. **검사 조건과 조작 입력 목록의 정본은 P27(b)** — 여기에 재복제하지 않는다(3차 리뷰 #4: 복제본이 어긋나 구현자가 다른 합격 기준을 따를 위험). 소유권 분계도 P27(b) 를 따른다(Secret 소비 참조=본 lint / ServiceMonitor 집합=`servicemonitor-selector-lint`).
  - ③ CI policy step **lint 전체 재현**(4종 — `ci.yml:62-65`): `image-contract-lint.sh`(env 없이 **full 6/6**, "manifest-checked: 6/6, full" 확인) · `servicemonitor-selector-lint.sh`(**canonical 5 유지 그린** — gateway Service 추가가 SM↔Service 매칭을 깨지 않음을 확증) · `observability-ssot-lint.sh` · `observability-promql-lint.sh`. 여기에 `kustomize-namespace-lint.sh` + 신규 `gateway-exposure-lint.sh`.
  - ④ 이미지 — `docker build --build-arg SERVICE=gateway -t gateway:ci .`(CI 와 동일 형식) + `bash scripts/docker-health-smoke.sh gateway:ci`(PR3a 계약 회귀 — smoke 가 쓰는 관리 포트 8081 이 매니페스트 probe 포트와 동일함을 확인).
  - ⑤ `./gradlew :gateway:test` 그린 + **k8s 프로파일 전용 설정 테스트 신설(2차 리뷰 #4, P1)** — `:gateway:test` 는 기본적으로 `application-k8s.yml` 을 **로드하지 않으므로**(CI 는 오히려 `SPRING_PROFILES_ACTIVE=test`) 새 키가 오타여도 base 기본값으로 그린이 된다. → `k8s` 프로파일을 명시 활성화한 테스트에서 **업스트림 5키·JWKS·Redis 가 프로파일 property source 에 실제 존재**함을 assert(값만 비교하면 base 기본값과 같아 무의미 — **property 존재/origin 까지 확인**)하고, 각 `RouteDefinition.uri` 가 그 값으로 해석됐는지 확인.
  - **canary 실증적은 미포함** — PR3c GKE 세션(P30 정직성 게이트).
- **PR3c**(P38):
  - ① `./gradlew build test`(9모듈) 그린 — **header-trust 3-state 음성 매트릭스**(헤더 없음 anonymous 통과 / 부분·blank·중복·비숫자 ID·임의 role→401 / 정상→인증, 500·anonymous fallback 아님)·**configurer 동등성 회귀**(401·403·MDC traceId 보존, review #6)·`logout(userId, familyId)` 새 계약(family deny + `revokeAllByUserId`, family-less=deny 생략)·**family-less logout TTL 계약**(access 유효/refresh 차단, review #8)·8 컨트롤러 슬라이스 factory 회귀·`UserSecurityIntegrationTest`/`NotificationSecurityIntegrationTest` 헤더 인증 재작성.
  - ② 렌더 양성/음성 — `kubectl kustomize k8s/overlays/{minikube,gke}`(각각): 5서비스 Service 에 NodePort/LB patch **부재**(ClusterIP default), gateway 만 외부 노출 유지, `NetworkPolicy` 리소스 존재 + `policyTypes:[Ingress]` + `podSelector{component:backend}` 가 backend 5개를 선택(gateway 제외) + gateway ingress peer + **monitoring scrape 예외 peer 존재**, 5서비스 Deployment `maxUnavailable:0`.
  - ③ **CI policy lint 전부 재현**(PLAN-BLINDSPOTS 자동화후보 준수 — NetworkPolicy/ClusterIP 추가가 일부 lint 를 깨지 않음 확증): `kustomize-namespace-lint`(NetworkPolicy namespace 정합) · `image-contract-lint`(full 6/6 유지) · `gateway-exposure-lint`(+self-test) · **`networkpolicy-contract-lint`(+self-test 5종, review #7)** · `servicemonitor-selector-lint`(**5 유지** — gateway SM 미신설, review #10) · `observability-ssot`/`observability-promql`.
  - ④ **GKE 보안 smoke 실 클러스터 1회 수행**(P37, 결정 ¬h) — enforcement(Dataplane V2/network-policy) 활성 검증 → 배포(digest 고정) → probe(양성: 공개 200·보호 401·spoof strip·webhook gateway 경유 / 음성: non-gateway pod 차단·직접 NodePort/LB 부재 / scrape: Prometheus target up) → PR3b canary 증적(보호·공개·spoof·오류율 임계) → 증적 파일 보존 → cleanup. **미실행 시 PR3c 미완료 — 렌더 성공으로 대체 금지**.
- **PR4**: 메트릭 counter 통합테스트(사유 태그)·observability lint negative(총계 6·Gateway ServiceMonitor 누락)·HS512 제거 회귀. 전 모듈 그린.
- **가드**: `assertNoServiceProjectDeps`(gateway↔서비스 직접 의존 금지), **gateway↔`:common` 의존 금지 가드**(보강 e), B1b string-level sweep(route path↔서비스 prefix).

## 6. 완료 조건

- [ ] RS256 서명↔JWKS 검증 왕복, HS256/none/unknown-kid 거부. (PR1)
- [ ] reuse 재제시 → family 전체 무효화 + Redis family deny → 이미 발급된 access token 즉시 차단 확증(PR2 = 전환기 common-auth enforcement, PR3 = Gateway 이관). (PR2/PR3)
- [ ] Gateway 통해서만 인증 통과, 리소스 서비스 direct ingress 거부(헤더 신뢰·NetworkPolicy·**NodePort/LB 환원**). 외부 유입 X-User-* spoof 제거. 공개 경로는 무헤더로도 통과. (PR3)
- [ ] Rate limit route-class별 429 + fail-closed(401/429/503 응답 계약 분리). (PR3)
- [ ] **ADR-0014 D2-c exit**: move/delete/retain 표대로 servlet 검증 컴포넌트 삭제 + 키/서명 클래스 User 이관 + 검증 전용 JJWT·Redis 의존 제거(User 서명·Product 캐시는 유지). (PR3d)
- [ ] **PR3b**: gateway k8s 배포 표면 완성(base 2 리소스 + overlay patch 4 + gke `images[]` 6 + HPA) 후 **`image-contract-lint` full 6/6**(전환기 flag 제거) · `servicemonitor-selector-lint` 5 유지 · **`gateway-exposure-lint` 그린 + P27(b) 조작 입력 9종 전부에서 실패**(조건·목록의 정본은 P27(b), 여기서 재열거하지 않음). runbook 의 rollback 이 **이름 단위 삭제**(overlay 전체 delete 금지)이고 canary 이미지가 **digest 고정**. **전환 증적은 PR3c GKE 세션까지 미확보로 명시** — 렌더 성공을 canary 통과로 기록 금지. (PR3b)
- [ ] **PR3c**: 5서비스 ClusterIP 환원(overlay service patch 10개 제거) + NetworkPolicy(**ingress-only·podSelector `component:backend`**·gateway peer + monitoring scrape 예외, SA 미도입) + **header-trust 전환**(5 SecurityConfig configurer 스왑 — 공통 정책 전부 보존·`LoginUser(userId,role,familyId)`·`logout(userId,familyId)`, 구 servlet 컴포넌트 잔존) + 안전 순서 rollout(NetworkPolicy/ClusterIP 선행→header-trust rollout, 역순 rollback) + `networkpolicy-contract-lint` self-test. CI policy lint 전부 그린 유지. (PR3c)
- [ ] **family-less logout 계약 게이트(review #8)**: header-trust 전환 후 logout 은 raw access token 미보유 → family deny + `revokeAllByUserId` 로 재정의. **family 有 토큰**: family deny 로 즉시 차단 확증. **family-less(전환기 레거시) 토큰**: 기존 access token 은 **최대 잔여 access TTL 까지 유효**(즉시 무효화 상실 = 수용 리스크), 단 **refresh 는 차단**(`revokeAllByUserId`). 이 계약을 테스트로 고정하고 완료조건에 TTL 상한·위험 수용 명시(즉시 무효화가 필요하면 PR3d family-non-null 이후로). (PR3c)
- [ ] **무중단 롤아웃 PR3a~d 완주**(단계별 이미지 태그·진입 조건·`maxUnavailable=0`·역순 rollback runbook, 혼재 구간 인증 무중단). (PR3)
- [ ] **family-less 토큰 소멸 증명 후** 수용 경로 제거 + `LoginUser.familyId` non-null 불변식. (PR3d)
- [ ] header-trust 3-state 계약(anonymous 통과 / 완전 인증 / 그 외 401) — 부분·형식오류가 500·anonymous 로 새지 않음. (PR3)
- [ ] **GKE 보안 smoke 증적** 확보(NetworkPolicy 양성·음성·scrape). 미실행 시 PR3 미완료. (PR3)
- [x] S9 auth_failure 메트릭 + ADR-0009 S9 행 + observability lint 6/6(도메인 5+인프라 1). (PR4 — 자식 계획 `task-impl3-pr4-auth-observability.md` P1~P13. **canonical 5→6 은 ADR-0015 계약 변경이라 ADR-0024 신설**)
- [x] HS512 fallback + 레거시 `bl:` dual-read 제거 후 **10모듈** 그린(모듈 수는 PR3d-a 의 `internal-token-contract` 신설로 9→10), 인증 회귀 0. (PR4 — 전제 정정: 발급측이 이미 RS256 단독이고 `bl:` writer 가 0 이라 "TTL 경과 증명 게이트"는 불필요했다)
- [ ] 보안 묶음 L-001/002/003/019 종결, ADR-0013 구현 완료.

---

## 7. 롤아웃 runbook — PR3b (canary → 100% 전환)

> 실행 단위는 §PR3 실행 분할표의 **PR3b**. 이 단계에서 5서비스 직접 경로(NodePort/Internal LB)는 **살아 있다** —
> 그것이 canary 의 롤백 경로다. 직접 경로 제거·NetworkPolicy 는 PR3c.
>
> ⚠️ **본 runbook 은 아직 실행되지 않았다.** PR3b 는 매니페스트·lint 까지 코드로 닫고, 실제 클러스터 probe 는
> PR3c 의 GKE 보안 smoke 세션에서 NetworkPolicy 음성·양성과 함께 1회 수행한다. 렌더 성공을 canary 통과로
> 기록하지 않는다(§5 PR3 "렌더-only 대체 금지").

### 7-1. 배포 전 기록 (rollback 재현성의 전제)

장애 중에 기억에 의존하지 않도록 **배포 전에** 아래를 증적 파일로 남긴다 —
`docs/progress/evidence/pr3b-rollout-<YYYYMMDD-HHMM>.md`:

| 항목 | 취득 명령 |
|---|---|
| known-good gateway digest | `scripts/promote-images.sh --dry-run` 출력의 `@sha256:...` |
| 현재 Deployment revision | `kubectl -n peekcart rollout history deployment/gateway` |
| 전환 전 진입점 상태 | `kubectl -n peekcart get svc -o wide` (5서비스 NodePort/LB 주소) |
| 전환 시각 | `date -u +%FT%TZ` |

이미지는 **`latest` 금지 — digest 고정**. `latest` 로 배포하면 canary 와 rollback 대상이 같은 태그를 가리켜
"어느 버전으로 되돌리는가" 가 성립하지 않는다.

```bash
cd k8s/overlays/gke
kustomize edit set image \
  ghcr.io/kimgyuilli/peekcart-gateway=asia-northeast3-docker.pkg.dev/<PROJECT>/peekcart/gateway@sha256:<digest>
# 편집 결과는 커밋하지 않는다 — operator 로컬 상태(overlay README 규약)
```

### 7-2. 배포 + readiness

```bash
kubectl apply -k k8s/overlays/<env>
kubectl -n peekcart rollout status deployment/gateway --timeout=5m
kubectl -n peekcart get pods -l app=gateway   # READY 1/1 (gke: HPA minReplicas 2)
```

readiness=true 는 **JWKS usable key ≥ 1** 을 뜻한다(`JwksReadinessConfig`). cold start 로 공개키를 못 받은
인스턴스는 트래픽을 받지 않는다 — readiness 가 안 오르면 User JWKS 도달성부터 확인한다.

### 7-3. 전환 전 probe 3종 (gateway 주소 기준)

`GW` = minikube `http://$(minikube ip):30080` / gke = Internal LB 주소.

| # | 목적 | 명령 | 기대 |
|---|---|---|---|
| 1 | 공개 경로 무헤더 통과 | `curl -s -o /dev/null -w '%{http_code}' $GW/api/v1/products` | `200` |
| 2 | 보호 경로 무토큰 거부 | `curl -s -o /dev/null -w '%{http_code}' $GW/api/v1/orders` | `401` |
| 3 | **spoof 제거** | `curl -s -H 'X-User-Id: 999' -H 'X-User-Role: ADMIN' $GW/api/v1/orders -o /dev/null -w '%{http_code}'` | `401` (외부 헤더는 항상 strip) |
| 4 | 관리 포트 미노출 | `curl -s -o /dev/null -w '%{http_code}' $GW/actuator/prometheus` | `404` (라우트 없음) |

4번이 200 이면 즉시 중단 — Service 에 8081 이 섞인 것이다(`gateway-exposure-lint` 가 CI 에서 막지만
운영 클러스터의 수동 patch 는 못 막는다).

### 7-4. canary → 100%

트래픽 분할은 **클라이언트 진입점 전환 비율**로 정의한다(Ingress 가중치 라우팅은 PR3c 이후 도입 시 재작성).

| 단계 | cohort | 관찰 시간 | 승격 조건 | 중단 조건 |
|---|---|---|---|---|
| c1 | 내부 검증 클라이언트만 | 15분 | 5xx < 0.5%, p95 < 500ms, 401 급증 없음 | 5xx ≥ 1% 또는 p95 ≥ 1s |
| c2 | 트래픽 50% | 30분 | 동상 | 동상 |
| c3 | 100% | 60분 | 동상 + refresh/logout 정상 | 동상 |

c3 를 60분 유지하면 **PR3c 진입 조건("100% 전환 유지")** 충족으로 본다. 증적은 7-1 파일에 이어 적는다.

### 7-5. rollback (역순 — 순서를 지키지 않으면 잔존 트래픽이 실패한다)

> ❌ **`kubectl delete -k k8s/overlays/<env>` 금지.** gateway 전용 kustomization 이 없어 overlay 전체가
> 대상이 된다 — 5서비스와 MySQL/Redis/Kafka(PVC 포함)까지 삭제되어 롤백이 전면 장애가 된다.

```bash
# ① 진입점 복귀: 클라이언트를 기존 서비스별 직접 경로로 되돌린다
#    minikube = NodePort 30081~30085 / gke = 서비스별 Internal LB 주소 (7-1 에 기록해 둔 값)

# ② barrier — 복귀가 전파됐는지 확인한 뒤에만 다음 단계로
kubectl -n peekcart get svc user-service product-service order-service payment-service notification-service
for p in 30081 30082 30083 30084 30085; do
  curl -s -o /dev/null -w "$p=%{http_code}\n" "http://$(minikube ip):$p/actuator/health"
done
#    gateway 잔존 트래픽이 0(또는 허용 임계 이하)인지 확인 — 아니면 여기서 대기

# ③ 이미지 되돌리기 (둘 중 하나, 7-1 기록값 사용)
kubectl -n peekcart rollout undo deployment/gateway --to-revision=<기록한 revision>
# 또는
kubectl -n peekcart set image deployment/gateway gateway=<known-good>@sha256:<digest>

# ④ 확인
kubectl -n peekcart rollout status deployment/gateway --timeout=5m

# ⑤ 로컬 kustomization 도 known-good digest 로 되돌린 뒤에만 재apply
#    (canary digest 가 남은 채 apply 하면 ③을 무효화하고 즉시 실패 버전으로 복귀한다)
cd k8s/overlays/gke && kustomize edit set image \
  ghcr.io/kimgyuilli/peekcart-gateway=<known-good>@sha256:<digest>
```

**완전 철거 시**: HPA 를 Deployment 보다 **먼저** 삭제한다(HPA 가 replica 를 되살린다).
이미지 rollback(③) 중에는 HPA 를 그대로 둔다 — HPA 는 replica 수만 조정하고 이미지 revision 은 건드리지 않는다.

```bash
kubectl -n peekcart delete hpa/gateway              # gke only, Deployment 보다 먼저
kubectl -n peekcart delete deployment/gateway service/gateway configmap/gateway-config
```

---

## 8. 롤아웃 runbook — PR3c (ClusterIP 환원 + NetworkPolicy + header-trust)

> 실행 단위는 §PR3 실행 분할표의 **PR3c**. **전제**: PR3b 의 gateway 100% 전환이 유지되고 있다.
> **핵심 안전 불변식**: NetworkPolicy/ClusterIP 를 header-trust 이미지 rollout **보다 먼저** 적용한다(결정 ¬b) —
> 순서가 뒤집히면 NodePort/LB 직접 경로로 위조 `X-User-*` 를 주입하는 전면 spoof 창이 열린다.
> gateway 의 `Authorization` 전달은 PR3c 내내 **살아 있다**(PR3d 에서 중단) — 이것이 구 이미지 rollback 의 안전창이다.

### 8-1. 배포 전 기록 (rollback 재현성)

`docs/progress/evidence/pr3c-gke-smoke-<YYYYMMDD-HHMM>.md` 에:

| 항목 | 취득 명령 |
|---|---|
| known-good(header-trust 이전) 이미지 digest | `scripts/promote-images.sh --dry-run` 의 5서비스 `@sha256:...` |
| 현재 각 서비스 Deployment revision | `kubectl -n peekcart rollout history deployment/<svc>` |
| 전환 전 진입점 상태 | `kubectl -n peekcart get svc -o wide` (5서비스 NodePort/LB + gateway) |
| NetworkPolicy enforcement 활성 | `gcloud container clusters describe <c> --format='value(networkPolicy.enabled)'` 또는 Dataplane V2 확인 |

### 8-2. ① 정책·Service 만 적용 — 이미지는 건드리지 않는다 (review #2)

> ⚠️ `kubectl apply -k k8s/overlays/<env>` 통짜 적용은 **5서비스 Deployment 이미지도 선언 상태로 되돌린다** — base 가
> `:latest` 를 가리키므로 apply 가 header-trust rollout 을 barrier 보다 **먼저** 트리거할 수 있다. 정책 도입 단계는
> **이미지 revision 을 고정한 채** Service/NetworkPolicy 변경만 적용한다.

```bash
set -euo pipefail
cd k8s/overlays/<env>
# (a) 5서비스 이미지를 known-good digest 로 고정(apply 가 이미지를 흔들지 않도록)
for svc in user-service product-service order-service payment-service notification-service; do
  kustomize edit set image ghcr.io/kimgyuilli/peekcart-$svc=<known-good-$svc>@sha256:<digest>
done
# (b) apply 전 revision 을 기록(8-1 파일값과 동일해야 함)
declare -A REV_BEFORE
for svc in user-service product-service order-service payment-service notification-service; do
  REV_BEFORE[$svc]=$(kubectl -n peekcart get deploy $svc -o jsonpath='{.metadata.annotations.deployment\.kubernetes\.io/revision}')
done
kubectl apply -k .
# (c) revision hard assert — 하나라도 올라가면 이미지가 흔들린 것 → 즉시 중단
for svc in "${!REV_BEFORE[@]}"; do
  after=$(kubectl -n peekcart get deploy $svc -o jsonpath='{.metadata.annotations.deployment\.kubernetes\.io/revision}')
  [ "$after" = "${REV_BEFORE[$svc]}" ] || { echo "FATAL: $svc revision ${REV_BEFORE[$svc]}->$after (이미지 흔들림)"; exit 1; }
done
kubectl -n peekcart get svc            # 5서비스 type=ClusterIP, gateway 만 NodePort/LB
kubectl -n peekcart get networkpolicy  # 정책 존재
```

`maxUnavailable:0` 은 5서비스 Deployment **매니페스트 필드**로 박는다(주석 아님, review #2) — rollout 이 항상 무중단이도록.

### 8-3. ② barrier — header-trust rollout 전에 **enforcement 를 증명**한다 (review #1)

> enforcement·직접경로 차단 검증을 rollout **이후**(P37)로 미루면, 정책이 실제로 막지 못하는 상태에서 신 Pod 가
> 헤더를 신뢰하기 시작해 spoof 창이 열린다. 아래는 **rollout 전** 실행하고 **각 항목이 기대와 불일치하면 `exit 1`**
> 한다(산문 아님) — 하나라도 실패하면 rollout 금지. `scripts/gke-security-smoke.sh --barrier`(P37)가 이 블록을 소유.

```bash
set -euo pipefail
# (1) enforcement 활성 hard-fail — Dataplane V2 또는 networkPolicy.enabled 중 하나면 통과(단일 계약, review #4)
DP=$(gcloud container clusters describe <c> --format='value(networkPolicy.enabled)')          # True/False
NM=$(gcloud container clusters describe <c> --format='value(networkConfig.datapathProvider)') # ADVANCED_DATAPATH=Dataplane V2
[ "$DP" = "True" ] || [ "$NM" = "ADVANCED_DATAPATH" ] || { echo "FATAL: NP enforcement OFF (DP=$DP datapath=$NM)"; exit 1; }
# (2) non-gateway Pod → 5서비스 8080 차단(정책 양성): 도달하면 exit 1
code=$(kubectl -n peekcart run np-probe --image=curlimages/curl --restart=Never --rm -i --quiet -- \
  sh -c 'curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://order-service:8080/actuator/health || echo 000')
[ "$code" = "000" ] || { echo "FATAL: non-gateway 가 order-service 도달(code=$code) — 정책 미차단"; exit 1; }
# (3) gateway 경유 공개 경로 200 아니면 exit 1
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 $GW/api/v1/products)
[ "$code" = "200" ] || { echo "FATAL: gateway 공개 경로 $code (기대 200)"; exit 1; }
# (4) Prometheus target up — monitoring 예외 동작 검증(도달 실패면 exit 1)
UP=$(curl -s "$PROM/api/v1/query?query=up{namespace=\"peekcart\"}" | jq '[.data.result[].value[1]|tonumber]|add // 0')
[ "$UP" -ge 5 ] || { echo "FATAL: peekcart scrape target up=$UP (<5) — monitoring 예외 미동작"; exit 1; }
# (5) 직접 경로 도달 불가 — 도달 성공(2xx/3xx)이면 exit 1
#     minikube = NodePort 30081~30085 / gke = 8-1 에 기록한 5서비스 LB 주소
for ep in "$(minikube ip):30081" ... ; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://$ep/actuator/health" || echo 000)
  case "$code" in 000|4*|5*) : ;; *) echo "FATAL: 직접 경로 $ep 도달(code=$code)"; exit 1 ;; esac
done
echo "barrier PASS"
```

(1)~(5) 전부 `exit 1` 없이 통과해야 header-trust 이미지를 rollout 한다. 이 barrier 가 결정 ¬b(안전 순서)의 실체다.

### 8-4. ③ header-trust 이미지 rollout (혼재 허용, digest 고정)

```bash
# 각 서비스를 header-trust 이미지 digest 로 (latest 금지). maxUnavailable=0 로 무중단.
kubectl -n peekcart set image deployment/<svc> <svc>=<header-trust>@sha256:<digest>
kubectl -n peekcart rollout status deployment/<svc> --timeout=5m
```

혼재 구간(구 Bearer Pod + 신 header-trust Pod 공존) 동안 gateway 는 `Authorization` + `X-User-*` 를 **둘 다**
공급하므로 어느 Pod 로 라우팅돼도 인증이 성립한다. **혼재 검증은 "한 번 성공" 으로 부족(review #9)** — 요청이 구·신
Pod **양쪽에** 도달했음을 증명해야 한다:

```bash
# rollout 중간(구·신 revision 공존)에 refresh/logout 을 반복 호출하고, 응답 헤더로 serving revision 을 식별.
# (서비스가 X-Served-By 류 헤더가 없으면, 구/신 이미지에 임시 revision 헤더를 심거나
#  gateway access log 의 upstream pod 를 대조 — 어느 쪽이든 두 revision 응답을 증적에 남긴다)
for i in $(seq 1 40); do
  curl -s -D - -o /dev/null $GW/api/v1/auth/refresh -X POST -d "$REFRESH_JSON" | grep -i 'x-served-by\|http/'
done | sort | uniq -c   # 구/신 revision 응답이 둘 다 관측돼야 혼재 검증 성립
```

두 revision 응답 + refresh/logout 정상(logout 새 계약 = family deny + `revokeAllByUserId`)을 8-1 증적 파일에 기록한다.

### 8-5. ④ GKE 보안 smoke (P37 증적)

```bash
bash scripts/gke-security-smoke.sh <env>    # enforcement 확인 → probe(양성/음성/scrape) →
                                            # canary(보호·공개·spoof·오류율) → 증적 → cleanup
```

렌더 성공을 실행 증적으로 대체하지 않는다. 스크립트가 enforcement 미활성을 감지하면 즉시 실패한다(음성 테스트
false-green 차단).

### 8-6. rollback (역순 — NetworkPolicy 는 나중, 이미지 먼저)

> ❌ `kubectl delete -k` 금지(overlay 전체 삭제 위험, PR3b 와 동일).

```bash
# ① 이미지 되돌리기 — gateway 가 Authorization 을 전달하므로 구 Bearer Pod 로 즉시 인증 회복
kubectl -n peekcart set image deployment/<svc> <svc>=<known-good>@sha256:<digest>
kubectl -n peekcart rollout status deployment/<svc> --timeout=5m
# ② 필요 시에만 NetworkPolicy/ClusterIP 되돌리기 — service patch 복구 후 재apply
#    (구 이미지가 Bearer 검증이므로 NetworkPolicy 를 급히 걷어낼 이유는 낮다)
```

이미지 rollback 이 NetworkPolicy 제거보다 먼저다 — 정책을 먼저 걷으면 아직 header-trust 인 Pod 가 직접
경로에 노출된다.

---

## 종결

머지 PR: [#108](https://github.com/Kimgyuilli/PeakCart/pull/108) — 구현 ③ PR4 — 인증 관측성 S9 (구현 ③ 마지막 PR)

계획서 아카이브 판정을 위해 PR 링크를 명시한다. 작업은 위 PR 로 종결됐고 본문의
체크박스 상태는 당시 갱신되지 않은 것이라 완료 여부의 근거가 아니다.
