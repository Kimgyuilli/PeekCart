# task-adr0013-gateway-security — Phase 4 Gateway 보안 ADR-0013

> 작성: 2026-06-14
> 관련 Phase: Phase 4 (MSA 분리) — 설계 단계 A4 (마지막 설계 ADR)
> 로드맵: `docs/progress/phase4-design-roadmap.md §1 A4`
> 선행: ADR-0010(5서비스·User가 인증 소유), ADR-0011(auth/jwt/security는 User 전속·A4), ADR-0012(이벤트 계약)
> 후속: 구현 ③(Spring Cloud Gateway)
> 관련 ADR: 신규 = **ADR-0013** (Proposed → Accepted). ADR-0009(인증 실패 관측성 surface), `04-design-deep-dive §10-2`(Gateway 인증 책임) 구체화

## 1. 목표

Phase 4 설계의 **마지막 ADR**. Spring Cloud Gateway 도입 + JWT 보안 강화(보안 묶음 L-001/002/003/019)를 한 결정으로 묶는다. 현재는 모놀리스 단일 앱에서 **HS256 대칭키** JWT 를 각 요청마다 `JwtFilter` 가 검증한다. MSA 분리 시 5개 서비스가 각자 대칭키를 공유하면 키 유출 표면이 N배가 되므로, **비대칭키(RS256) + Gateway 1차 검증** 으로 전환한다.

세부 목표:

- **(D1) RS256 전환 (L-001)** — HS256 대칭키 → RS256 비대칭키. **User(인증) 서버만 개인키로 서명/refresh/logout 소유**, **Gateway 가 공개키로 1차 검증**, **리소스 서비스는 JWT 미재검증**(헤더 신뢰 — `04 §10-2`·ADR-0011 정합). 키 배포(JWKS endpoint vs 정적 공개키) + HS256→RS256 마이그레이션(`kid`·alg allow-list·키 overlap) 결정
- **(D2) 시크릿 저장소 (L-002)** — 개인키/시크릿을 환경변수(`${JWT_SECRET}`)에서 **관리형 시크릿 저장소**로. GCP/GKE(ADR-0004) 기준 GCP Secret Manager vs Vault 비교·채택. 키 회전(rotation) 정책 골격
- **(D3) Spring Cloud Gateway (구현 ③ 선행)** — 5개 서비스 라우팅, **Gateway JWT 1차 검증**(`04 §10-2` 구체화 — 검증된 user_id/role 을 신뢰 헤더로 전달, 내부 서비스 미재검증 + NetworkPolicy 차단), Rate Limiting(Redis 기반 `RequestRateLimiter`)
- **(D4) Refresh Token Reuse Detection (L-003)** — `refresh_tokens` 에 `family_id` 추가, rotation 시 family 승계. **재사용(탈취) 감지 시 family 전체 무효화**. 현 grace-period rotation(동시요청 이중발급 방지)과의 관계 정리
- **(D5) 인증 실패 관측성 (L-019)** — 인증/인가 실패(서명 불일치/만료/reuse 감지) 메트릭·로그. ADR-0009 관측성 surface 와 정합(신규 surface 추가 여부)

본 task 는 **문서만 변경**한다. 실제 Gateway 모듈/RS256 키 생성/family_id 마이그레이션/Rate Limit 코드는 구현 ③(별도 task).

## 2. 배경 / 제약

### 현 인증 구현 (감사 — 실제 인용)

- **JWT 서명 = HS256 대칭키**: `JwtProvider.java:40` `Keys.hmacShaKeyFor(secret...)`, `secret` = `application.yml:53` `${JWT_SECRET:...}`(환경변수/하드코딩 기본값)
- **검증 위치**: 모놀리스 `JwtFilter`(`global.jwt`) 가 매 요청 검증, `SecurityConfig:63-70` PUBLIC_URLS permitAll + `addFilterBefore(jwtFilter, ...)`
- **Refresh rotation**: `AuthService.refresh`(L99) — `rotateToken`(grace-period 10초로 동시요청 이중발급 방지, `tokenBlacklistPort.addGracePeriod`) + `refreshViaGracePeriod`. **`family_id`/reuse(탈취) 감지 없음** — `RefreshToken` 엔티티는 id/userId/token/expiresAt 만(`RefreshToken.java`)
- **Gateway**: 현재 없음(모놀리스). `04-design-deep-dive §10-2`(L403-414)에 "Phase 4 JWT 검증은 Gateway, 내부 서비스 미재검증, user_id/role 헤더 전달, NetworkPolicy 차단" 이 이미 설계됨 → 본 ADR 이 구체화
- **블랙리스트 SPOF**: `04 §10-6`(L485) Redis 단일 인스턴스 — 본 ADR 비대상(관측만 정합)

### 편입 보안 묶음

- **L-001** RS256 전환, **L-002** KMS/Vault 시크릿 저장소, **L-003** Reuse Detection(family_id), **L-019** 인증 실패 관측성

### ADR 관계

- ADR-0010(User 가 인증 소유)·ADR-0011(auth/jwt/security 는 User 전속, Gateway 인증은 A4) 구체화
- ADR-0009(관측성 SSOT) — 인증 실패 surface 를 신규로 추가할지 결정(surface owner = Gateway/User)
- `04-design-deep-dive §10-2` Layer 1 — Gateway 헤더 전달/NetworkPolicy 를 본 ADR 이 인용·확정

### 제약 / 비대상

- 코드 변경 0건. Gateway 모듈/키/마이그레이션/Rate Limit 구현은 구현 ③
- OAuth2/OIDC 외부 IdP(Keycloak 등) 도입 비대상 — 자체 User 서비스가 IdP 역할 유지
- mTLS 서비스 간 암호화 비대상(Phase 5+ 후보). 본 ADR 은 NetworkPolicy 차단까지(`04 §10-2`)
- Redis 블랙리스트 SPOF 해소(L-006 fallback 류) 비대상

## 3. 작업 항목

### Part A — 사전 감사

- [ ] **P1.** 현 JWT 구현 감사 — HS256 서명(`JwtProvider:40`)·검증 위치(`JwtFilter`/`SecurityConfig:63-70`)·토큰 발급(`TokenIssuer`)·블랙리스트(`TokenBlacklistPort`)를 실제 라인 인용으로 §Context 기록
- [ ] **P2.** Refresh rotation 현황 감사 — `AuthService.refresh`/`rotateToken`/`refreshViaGracePeriod`(L99-130) 의 grace-period 메커니즘 정확히 기술. `family_id`/reuse 감지 부재 + `RefreshToken` 엔티티 필드 인용
- [ ] **P3.** Gateway 기존 설계 감사 — `04-design-deep-dive §10-2`(L403-414) Gateway 인증 책임·헤더 전달·NetworkPolicy 를 인용. 본 ADR 이 확정/구체화할 범위 식별

### Part B — Decision

- [ ] **P4.** RS256 전환(D1) — 알고리즘 RS256(또는 ES256 비교), 키 쌍 소유(User 개인키 서명 / **Gateway 공개키 검증, 리소스 서비스 미재검증**), **공개키 배포 방식**(User 서비스 JWKS endpoint vs 정적 공개키 마운트) 비교·채택. **키 안전 조건**: 토큰 헤더 `kid` 필수, 허용 알고리즘 allow-list(RS256만 — 전환 중 HS256 은 bounded fallback 기간만), unknown `kid`/예상외 `alg` 거부, JWKS cache TTL 및 refresh 실패 시 동작, **active/previous 키 overlap > access token max TTL**. **마이그레이션**: HS256→RS256 dual-validation 기간 + access token 짧은 만료로 자연 소멸 + 이전 키 제거 시점
- [ ] **P5.** 시크릿 저장소(D2) — **3안 비교**: ① GCP Cloud KMS 비대칭 서명(개인키 non-exportable, 앱 밖 서명) ② GCP Secret Manager 에 PEM 저장·주입 ③ Vault transit/secret engine. **비교축**: 개인키가 앱 메모리에 로드되는가, 서명 latency/availability, 로컬 개발 대체 경로, 회전 절차. 채택 후 주입 방식(CSI driver/env)·키 회전 정책. ADR-0007(자격증명은 환경별) 정합
- [ ] **P6.** Gateway 라우팅·필터·Rate Limit(D3) —
  - 5개 서비스 라우팅 표
  - **Gateway JWT 검증 순서**: ① 서명/만료 검증(공개키) → ② **access token blacklist + family/session deny 확인**(`tokenBlacklistPort` 상당 — 미확인 시 로그아웃/탈취 토큰이 헤더로 승격됨) → ③ 신뢰 헤더(`X-User-Id`/`X-User-Role`) 주입. Redis 장애 시 동작은 `04 §10-6` SPOF 트레이드오프와 연결. **owner 분리**: User=logout/refresh/family revoke 상태 owner, Gateway=request-path enforcement owner
  - **탈취 containment 연결(P7↔P6)**: access token 에 `family_id`(또는 `session_id`) 클레임 포함 → reuse 감지로 family revoke 시 User 가 **family/session deny 를 Redis(Gateway blacklist source)에 기록** → Gateway 가 ② 단계에서 확인해 **이미 발급된 access token 도 즉시 차단**. (deny 미도입 시 access token 짧은 TTL 까지의 bounded risk·최대 노출 시간을 §Consequences 에 명시)
  - **헤더 신뢰 모델**: 외부 유입 `X-User-*` 항상 제거 후 재주입, 내부 서비스 direct ingress 거부(Gateway pod/NS/serviceAccount 외), 신뢰 헤더 누락 시 내부 서비스 처리 방침. NetworkPolicy(`04 §10-2`)
  - **Rate Limit(route-class별)**: 로그인/refresh(인증 전)=IP+계정 식별자, 인증 API(인증 후)=userId, 공개 상품 조회=IP/route. Redis `RequestRateLimiter` replenish/burst. **Redis 장애 시 fail-open/closed 결정**(보안 트레이드오프). 429 metric/log owner=Gateway
- [ ] **P7.** Reuse Detection(D4) — **이력 모델 전환**: 현 rotation 은 토큰 row 를 **삭제**(`AuthService:112-118`)하므로 삭제된 토큰 재제시를 DB 로 감지 불가. → `refresh_tokens` 에 `family_id`, `token_hash`, `status`(ACTIVE/ROTATED/REVOKED), `rotated_at`, `grace_until`, `replaced_by_token_id`(또는 동등 이력 모델) 추가하고 **삭제 대신 상태 전이**. rotation 시 family_id 승계, **ROTATED/REVOKED 토큰 재제시 또는 이미 revoked family 재제시 → family 전체 무효화**. **grace(정상 동시요청)↔reuse(탈취) 구분**: grace 는 `grace_until` 내 1회성 재시도 허용, reuse 는 grace 초과/2회 이상/revoked family. **reuse 감지 시 family 무효화에 더해 family/session deny 를 Redis 에 기록**(P6 ② 단계가 확인 → 이미 발급된 access token 차단). `05-data-design` refresh_tokens 반영(스키마는 구현 ③ DDL)
- [ ] **P8.** 인증 실패 관측성(D5, L-019) — 인증 실패(서명오류/만료)·인가 실패(403)·reuse 감지·429(Rate Limit) 메트릭(counter, 사유 태그)·로그. **ADR-0009 surface 표에 신규 행(예: S9 auth_failure) 추가 시 6컬럼 전부 채움**(SSOT 파일:라인, Phase 4 owner=Gateway/User, 이동·복제 금지 규칙, 검증 수단) → `docs/adr/0009-observability-contract-ssot.md` 갱신. 추가 안 하면 "신규 surface 없음, ADR-0013 이 Gateway/User metric 이름만 정의" 로 명시

### Part C — ADR-0013 작성

- [ ] **P9.** `docs/adr/0013-phase4-gateway-security.md` 신규 — `template.md`/ADR-0012 형식
  - Status Proposed / Decided 2026-06-14 / Phase 4
  - Context: 현 HS256·grace rotation·Gateway 미존재(P1~P3) + 보안 묶음
  - Decision: RS256(P4) + 시크릿 저장소(P5) + Gateway(P6) + Reuse Detection(P7) + 관측성(P8)
  - Alternatives: RS256 vs ES256, JWKS vs 정적 공개키, GCP Secret Manager vs Vault, Gateway 검증 vs 서비스별 재검증
  - Consequences: 긍정(키 유출 표면↓, 중앙 인증) / 부정(Gateway SPOF·키 회전 운영, 헤더 신뢰 모델 리스크) / 후속(구현 ③)
  - References: ADR-0009/0010/0011, `04 §10-2/§10-6`, `03 §7-2`, JWT 코드 인용
- [ ] **P10.** ADR Status `Proposed` → `Accepted`

### Part D — 인덱스/참조 동기화

- [ ] **P11.** `docs/adr/README.md` INDEX 행 추가
- [ ] **P12.** Layer 1 정합 — `04-design-deep-dive §10-2`(Gateway 인증 — RS256·Rate Limit·헤더 신뢰 모델 보강 + `see ADR-0013`), `§9-2`(JWT refresh — family_id reuse detection 반영), `03-requirements §7-2`(JWT Rotation → reuse detection 정정), `05-data-design`(refresh_tokens family_id), `02-architecture §5`(Gateway 보안 표기)
- [ ] **P13.** `bash docs/consistency-hints.sh` exit 0

### Part E — 문서 동기화

- [ ] **P14.** `docs/progress/phase4-design-roadmap.md §1 A4` 상태 갱신(설계 ADR A1~A4 완료) + `docs/TASKS.md` A4 행 ✅ + `docs/progress/PHASE4.md` A4 엔트리 + 보안 묶음(L-001/002/003/019) 처리 표기

## 4. 영향 파일

| 파일 | 변경 유형 | Part |
|------|-----------|------|
| `docs/adr/0013-phase4-gateway-security.md` | 신규 (Proposed → Accepted) | C |
| `docs/adr/0009-observability-contract-ssot.md` | 인증 실패 surface 신규 행 추가 시 6컬럼 (P8 결정에 따라) | C/D (P8) |
| `docs/adr/README.md` | INDEX 행 | D (P11) |
| `docs/04-design-deep-dive.md` | §10-2/§9-2 보강 + `see ADR-0013` | D (P12) |
| `docs/03-requirements.md` | §7-2 reuse detection 정정 | D (P12) |
| `docs/05-data-design.md` | refresh_tokens family_id | D (P12) |
| `docs/02-architecture.md` | §5 Gateway 보안 표기 | D (P12) |
| `docs/progress/phase4-design-roadmap.md` | A4 상태 갱신 | E (P14) |
| `docs/TASKS.md` | A4 행 ✅ | E (P14) |
| `docs/progress/PHASE4.md` | A4 엔트리 | E (P14) |

코드 변경: **0건** (Gateway/RS256/family_id 구현은 ③).

## 5. 검증 방법

### 자동
- `bash docs/consistency-hints.sh` exit 0
- `./gradlew test` 불필요 (코드 변경 0건)

### 수동 (ADR 본문 품질 — 모두 통과해야 Accepted)

**§Context:**
- [ ] **C1**: 현 HS256·grace rotation·Gateway 미존재가 실제 라인 인용 (P1~P3)
- [ ] **C2**: `family_id`/reuse 부재가 명시됨

**§Decision:**
- [ ] **D1**: RS256 전환 — 키 소유(서명/검증 분리)·공개키 배포·HS256→RS256 마이그레이션 경로 확정
- [ ] **D2**: 시크릿 저장소 — 관리형 저장소 채택 + 주입 방식 + 회전 골격, ADR-0007 정합
- [ ] **D3**: Gateway — 라우팅·검증 순서(서명/만료→blacklist→헤더 주입)·헤더 신뢰 모델·Rate Limit(route-class), `04 §10-2` 와 모순 없음. **검증 항목**: 외부 `X-User-*` 항상 제거 후 재주입 / 내부 서비스 direct ingress 거부(Gateway 외) / 신뢰 헤더 누락 시 처리 / Redis 장애 fail-open|closed
- [ ] **D4**: Reuse Detection — 이력 모델(status/token_hash/family_id/grace_until) 기반 family 무효화 + grace(정상)와 reuse(탈취) 구분 기준. 현 삭제 방식 대체 명시. **탈취 containment**: access token `family_id/session_id` 클레임 + family deny 기록 → Gateway 가 이미 발급된 access token 도 차단 (또는 짧은 TTL bounded risk 명시)
- [ ] **D5**: 인증 실패 관측성 — 메트릭/로그 + ADR-0009 surface 정합(신규 행이면 6컬럼, owner 명시)
- [ ] **D6**: ADR-0009/0010/0011·`04 §10-2` 와 모순 없음 (Gateway-only 검증, 서비스 미재검증)

**§Alternatives:**
- [ ] **A1**: RS256/ES256, JWKS/정적키, Secret Manager/Vault, Gateway/서비스 재검증이 동일 비교축, 채택/기각 대칭

**§Consequences:**
- [ ] **CQ1**: 구현 ③ 작업이 본 ADR 에서 1:1 도출 (Gateway 모듈·RS256 키·family_id 마이그레이션·Rate Limit)
- [ ] **CQ2**: Gateway SPOF·헤더 신뢰 모델 리스크·키 회전 운영 등 부정 영향 구체적

## 6. 완료 조건

- [ ] P1 ~ P14 전부 체크
- [ ] ADR-0013 파일 존재 + Status: Accepted
- [ ] `bash docs/consistency-hints.sh` exit 0
- [ ] §5 수동 체크리스트 (C1~C2, D1~D6, A1, CQ1~CQ2) 전부 통과
- [ ] 보안 묶음 L-001/002/003/019 가 D1~D5 로 1:1 매핑
- [ ] Gateway-only 검증 + 서비스 미재검증이 채택안(모순 없음), blacklist 확인 순서 명시
- [ ] Reuse Detection 이 이력 모델(삭제→상태전이) 위에서 성립 + 탈취 containment(family deny → 이미 발급된 access token 차단, 또는 bounded TTL risk) 연결
- [ ] RS256 키 안전 조건(kid·alg allow-list·키 overlap>access TTL) 명시
- [ ] P8 결정에 따라 ADR-0009 surface 갱신(추가 시 6컬럼) 또는 "신규 surface 없음" 명시
- [ ] Layer 1(04/03/05/02) 정합 + ADR-0013 참조
- [ ] PR 생성 + 머지

## 7. 트레이드오프 및 결정 근거

| 결정 | 채택 (계획 시점) | 기각 대안 | 근거 |
|------|------|-----------|------|
| 서명 알고리즘 | **RS256(비대칭)** | HS256 유지, ES256 | MSA 에서 대칭키 공유는 유출 표면 N배. 공개키 검증 분리가 정석. ES256 은 대안 비교만 |
| 인증 검증 위치 | **Gateway 1차 검증 + 헤더 전달** | 서비스별 재검증 | `04 §10-2` 기결정. 중복 검증 제거 + NetworkPolicy 로 신뢰 경계 |
| 시크릿 저장소 | **관리형 3안 비교**(Cloud KMS 비대칭 서명 / Secret Manager PEM / Vault) | 환경변수 유지 | 개인키 환경변수 부적합. KMS(non-exportable)와 Secret Manager(PEM 로드)는 보안 모델 상이 — 앱 메모리 로드 여부·서명 latency·로컬 개발 기준 채택 |
| Reuse 처리 | **family_id 전체 무효화** | 단건 무효화/grace만 | 탈취 시 단건 무효화는 공격자 토큰 잔존. family 무효화가 표준 |
| 산출물 범위 | 결정·계약만 (A4) | Gateway 코드까지 | 구현은 ③. 설계 ADR 로 분리 |

## 8. 후속 (Out-of-Scope)

- 구현 ③ — Spring Cloud Gateway 모듈, RS256 키 생성·JWKS, family_id 마이그레이션, Redis Rate Limiter, NetworkPolicy
- mTLS 서비스 간 암호화 — Phase 5+
- Redis 블랙리스트 SPOF 해소 — 별도
- 외부 IdP(OAuth2/OIDC) 전환 — 비대상
