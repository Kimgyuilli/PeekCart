# ADR-0013: Phase 4 Gateway 보안 — RS256 + Spring Cloud Gateway + Reuse Detection

- **Status**: Accepted
- **Decided**: 2026-06-14 (Proposed) → 2026-06-14 (Accepted)
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 4 (MSA 분리) — 설계 단계 A4 (마지막 설계 ADR)

## Context

Phase 4 설계의 마지막 ADR. Spring Cloud Gateway 도입 + JWT 보안 강화(보안 묶음 L-001/002/003/019)를 한 결정으로 묶는다. 현재는 모놀리스 단일 앱에서 **HS256 대칭키** JWT 를 `JwtFilter` 가 매 요청 검증한다. MSA 로 5개 서비스가 분리되면 대칭키 공유는 키 유출 표면을 N배로 키운다.

### C1. 현 인증 구현 (감사 — 실제 인용)

- **JWT 서명 = HS256 대칭키**: `JwtProvider.java:40` `Keys.hmacShaKeyFor(secret...)`, `secret` = `application.yml:53` `${JWT_SECRET:...}`(기본 fallback 하드코딩)
- **검증 위치**: 모놀리스 `JwtFilter`(`global.jwt`) 가 매 요청 검증(`SecurityConfig:63-70` PUBLIC_URLS permitAll + `addFilterBefore`). 서명 파싱 후 `tokenBlacklistPort.isBlacklisted(token)` 확인(`JwtFilter:37-39`)
- **Refresh rotation**: `AuthService.refresh`(L99) — `rotateToken`(grace-period 10초 `tokenBlacklistPort.addGracePeriod` 로 동시요청 이중발급 방지, 기존 row **삭제**) + `refreshViaGracePeriod`. **`family_id`/reuse(탈취) 감지 없음**. `RefreshToken` 엔티티 = id/userId/token/expiresAt(`RefreshToken.java`)
- **Gateway**: 현재 없음. `04-design-deep-dive §10-2`(L403-415) 에 "Phase 4 JWT 검증은 Gateway, 내부 서비스 미재검증, user_id/role 헤더 전달, NetworkPolicy 차단" 이 이미 설계 — 본 ADR 이 구체화

### C2. 닫아야 할 갭

- 대칭키 공유(L-001), 개인키 환경변수(L-002), reuse 감지 부재 + 삭제 기반 rotation 으로 재제시 감지 불가(L-003), 인증 실패 관측성 부재(L-019)

## Decision

### D1. RS256 비대칭키 전환 (L-001)

- HS256 대칭키 → **RS256 비대칭키**. **User(인증) 서버만 개인키로 서명**, **Gateway 가 공개키로 1차 검증**, **리소스 서비스는 JWT 미재검증**(헤더 신뢰 — `04 §10-2`·ADR-0011 정합). 서비스 재검증은 기각(Alt 비교)
- **공개키 배포**: User 서비스 JWKS endpoint(`/.well-known/jwks.json`) — Gateway 가 `kid` 로 키 선택, cache TTL 적용
- **키 안전 조건**: 토큰 헤더 `kid` 필수, 허용 알고리즘 **allow-list(RS256만)** — 전환 중 HS256 은 bounded fallback 기간만, unknown `kid`/예상외 `alg` 거부, JWKS cache refresh 실패 시 마지막 정상 키 유지 + 경보
- **마이그레이션**: HS256→RS256 dual-validation 기간, access token 짧은 TTL 로 자연 소멸, **active/previous 키 overlap > access token max TTL**, 이후 이전 키 제거

### D2. 시크릿 저장소 (L-002)

개인키 환경변수 부적합. **3안 비교 후 채택** (GKE/ADR-0004 기준):

| 안 | 개인키 앱 메모리 로드 | 비고 |
|---|---|---|
| **GCP Cloud KMS 비대칭 서명** | ❌ (non-exportable, KMS 가 서명) | 서명 API latency·availability 의존 |
| GCP Secret Manager(PEM 저장) | ⭕ (앱이 PEM 로드) | 단순, 회전 시 재배포/재로드 |
| Vault transit/secret engine | 케이스별 | 추가 인프라 |

**채택**: GCP Secret Manager(PEM 저장). **주입**: CSI driver/파일 마운트(**환경변수 금지**). **회전**: active/previous 키 병행(overlap > access TTL) 후 TTL 초과 시 이전 키 제거. 로컬 개발은 테스트 키쌍. ADR-0007(자격증명 환경별) 정합.
**후속(별도)**: Cloud KMS 비대칭 서명(개인키 non-exportable) 격상은 구현 ③ 의 서명 latency 측정 후 별도 ADR 개정으로 판단 — 본 ADR 의 채택 상태는 Secret Manager PEM 로 확정.

### D3. Spring Cloud Gateway (L-019 일부 + 구현 ③ 선행)

- **라우팅**: 5개 서비스(User/Product/Order/Payment/Notification) path 기반 라우팅
- **Gateway JWT 검증 순서**: ① 서명/만료 검증(공개키) → ② **access token blacklist + family/session deny 확인**(Redis) → ③ 신뢰 헤더(`X-User-Id`/`X-User-Role`) 주입. **② Redis 조회 실패 시 fail-closed**(인증 요청 401/503 + alert) — 보안 우선(통과 시 로그아웃/탈취 토큰 승격). `04 §10-6` SPOF 트레이드오프와 연결
- **헤더 신뢰 모델**: 외부 유입 `X-User-*` 헤더는 Gateway 에서 **항상 제거 후 재주입**, 내부 서비스 direct ingress 거부(Gateway pod/NS/serviceAccount 외 — K8s NetworkPolicy, `04 §10-2`), 신뢰 헤더 누락 시 내부 서비스는 401/거부
- **Rate Limit(route-class별)**: 로그인/refresh(인증 전) = IP+계정 식별자, 인증 API(인증 후) = userId, 공개 상품 조회 = IP/route. Redis `RequestRateLimiter`(replenish/burst). **Redis 장애 시 fail-closed**(보안 우선) — 가용성 영향은 트레이드오프로 수용. 429 metric/log owner = Gateway

### D4. Refresh Token Reuse Detection (L-003)

- **이력 모델 전환**: 현 삭제 기반 rotation 으로는 삭제된 토큰 재제시를 감지 불가. → `refresh_tokens` 에 `family_id`, `token_hash`, `status`(ACTIVE/ROTATED/REVOKED), `rotated_at`, `grace_until`, `replaced_by_token_id` 추가, **삭제 대신 상태 전이**
- **구분**: grace(정상 동시요청) = `grace_until` 내 1회성 재시도 허용 / reuse(탈취) = grace 초과 + ROTATED/REVOKED 토큰 재제시 또는 이미 revoked family 재제시 → **family 전체 무효화**
- **탈취 containment (D3↔D4 연결)**: access token 에 `family_id`(또는 `session_id`) 클레임 포함. reuse 감지로 family revoke 시 User 가 **family/session deny 를 Redis(Gateway blacklist source)에 기록** → Gateway 가 D3 ② 단계에서 확인해 **이미 발급된 access token 도 즉시 차단**. (deny 미도입 시 access token 짧은 TTL 까지의 bounded risk — §Consequences)

### D5. 인증 실패 관측성 (L-019)

- 인증 실패(서명오류/만료)·인가 실패(403)·reuse 감지·429(Rate Limit) 메트릭(counter + 사유 태그)·로그
- **ADR-0009 surface 표에 신규 행 S9(auth_failure) 추가** — SSOT = Gateway(`RequestRateLimiter`/인증 필터 메트릭) + User(reuse/logout 카운터), Phase 4 owner = Gateway/User, 이동·복제 금지(메트릭 이름 1개소), 검증 수단 = Gateway 메트릭 통합 테스트. ADR-0009 §Decision 표에 행 추가 동반

## Alternatives Considered

- **서명 알고리즘**: RS256(채택) vs HS256 유지(대칭키 공유 위험) vs ES256(대안 — 키 짧음·동등 보안, RS256 이 생태계 호환성 우위로 채택)
- **검증 위치**: Gateway 1차 검증(채택, `04 §10-2`) vs 서비스별 재검증(중복·NetworkPolicy 신뢰경계와 중복 → 기각)
- **공개키 배포**: JWKS endpoint(채택, 회전 유연) vs 정적 공개키 마운트(회전 시 재배포)
- **시크릿**: Secret Manager PEM(채택 기본) vs Cloud KMS 서명(격상 조건부) vs Vault(인프라 부담)

## Consequences

### 긍정적 영향
- 대칭키 공유 제거 → 키 유출 표면↓. Gateway 중앙 인증 + 서비스 미재검증으로 일관
- reuse 감지 + family deny → 탈취 토큰 containment (refresh + 이미 발급된 access token 양쪽)
- 구현 ③(Gateway)의 SSOT

### 부정적 영향 / 트레이드오프
- **Gateway SPOF** — 인증 경로가 Gateway에 집중. HA(다중 인스턴스) 필요
- **헤더 신뢰 모델 리스크** — NetworkPolicy/헤더 제거가 깨지면 spoofing 가능. 신뢰 경계 강제가 핵심
- **키 회전 운영** — JWKS/KMS 회전 절차, dual-validation 기간 관리
- Rate Limit fail-closed → Redis 장애 시 가용성 영향(보안 우선 선택)

### 후속 결정에 미치는 영향
- **구현 ③**: Gateway 모듈, RS256 키 생성·JWKS, family_id/status 마이그레이션, Redis RateLimiter, NetworkPolicy. ADR-0009 S9 surface 코드/검증
- mTLS·외부 IdP·Redis 블랙리스트 SPOF 해소는 비대상(Phase 5+ 또는 별도)

## References
- ADR-0009(관측성 SSOT — S9 추가), ADR-0010(User 인증 소유), ADR-0011(auth/jwt/security User 전속·Gateway A4)
- `docs/04-design-deep-dive.md §10-2`(Gateway 인증)·§9-2(JWT refresh)·§10-6(Redis SPOF), `docs/03-requirements.md §7-2`(Refresh Rotation), `docs/05-data-design.md`(refresh_tokens)
- 코드: `JwtProvider.java:38-40`, `JwtFilter.java:37-39`, `SecurityConfig.java:63-70`, `AuthService.java:99-130`, `RefreshToken.java`, `application.yml:51-55`
