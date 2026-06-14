# ADR-0014: 전환기 인증 검증 공유 모듈 — peekcart-common-auth (게이트웨이 이전)

- **Status**: Accepted
- **Decided**: 2026-06-14 (Proposed) → 2026-06-14 (Accepted)
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 4 (MSA 분리) — 구현 ① PR2

## Context

ADR-0011 이 Phase 4 모듈 토폴로지를 확정하며 §D2 에서 `auth`/`jwt`/`security` 를 **User 서비스 전속**으로 두었다. 그러나 이는 ADR-0013 의 **게이트웨이 검증 중앙화(구현 ③)** 가 이미 존재함을 전제한 배치였다. 구현 ① PR2(서비스 분리)에 착수하면서, **게이트웨이가 아직 없는 전환기**에는 그 전제가 성립하지 않음이 드러났다.

### C1. 현 인증 구현 (감사 — 실제 파일 인용)

- `JwtProvider implements TokenIssuer` (`global/jwt/JwtProvider.java:25`): **발급(`issue()`:47 → `signWith(key)`:80)과 검증(`parseToken()`:61 → `verifyWith(key)`:86)이 단일 클래스 + 단일 `SecretKey`(HS256 대칭키, :36)** 에 결합.
- `JwtFilter`(`global/jwt/JwtFilter.java`): 검증 필터. `JwtProvider` + `TokenBlacklistPort` 의존. `SecurityConfig.java:57` 가 `new JwtFilter(jwtProvider, tokenBlacklistPort)` 직접 생성(:70-71 필터 체인 등록).
- `TokenBlacklistPort`(`global/auth/TokenBlacklistPort.java`): impl = User `TokenBlacklistRepository`(Redis). 검증 경로(`JwtFilter`)와 발급 경로(`AuthService`) 양쪽에서 사용.
- `TokenIssuer`(`global/auth/TokenIssuer.java`, 발급 인터페이스): User `AuthService` 만 사용.
- `AdminProductController.java:25-26`: `@RequestMapping("/api/v1/admin/products")` + `@PreAuthorize("hasRole('ADMIN')")`. `SecurityConfig.java:37-49` 는 `/api/v1/products/**` 만 permitAll, 나머지 `anyRequest().authenticated()`(:62-64).

### C2. 인증 사용 서비스 (5개 전부 — 재감사 결과)

| 서비스 | 인증 표면 | 검증 필요 |
|---|---|---|
| User | `@CurrentUser`(UserController/AuthController) + 발급 | ✅ |
| Order | `@CurrentUser`(CartController/OrderController) | ✅ |
| Payment | `@CurrentUser`(PaymentController) | ✅ |
| Notification | `@CurrentUser`(NotificationController) | ✅ |
| Product | `@PreAuthorize("hasRole('ADMIN')")`(AdminProductController, `/api/v1/admin/products`) — public product API 만 permitAll | ✅ |

→ **전환기에는 auth-free 서비스가 없다.** 5개 모두 JWT 검증으로 SecurityContext 를 채워야 한다.

### C3. 문제

- ADR-0011 §D2(auth=User전속)는 게이트웨이 전제. 게이트웨이 이전 PR2 에선 5개 서비스가 각자 검증해야 하므로 §D2 와 충돌.
- 검증을 `common` 에 넣으면 god-module 화(entity/response/kafka/redis 와 auth 혼재). 게이트웨이 도입 후 축소도 어려움.

### C4. 선행 ADR 제약

- **ADR-0011**: §D1 모듈 토폴로지, §D2 auth=User전속, §D3 서비스↔서비스 직접 의존 금지, Alt A vs Alt C(모듈 폭증 경계).
- **ADR-0013**: 게이트웨이 RS256 검증 중앙화, 리소스 서비스 JWT 미재검증(§27), `X-User-Id`/`X-User-Role` 신뢰 헤더 주입·누락 시 거부(§49), access blacklist + family/session deny 확인·Redis 실패 시 fail-closed(§48), User 가 family/session deny 기록(§56). **본 ADR 의 endgame.**
- **ADR-0009**: `peekcart-common-observability` 집중 공용 모듈 선례.

## Decision

게이트웨이 이전 전환기 동안 **JWT 검증 전용 공유 모듈 `peekcart-common-auth` 를 도입**한다. JWT **발급**과 블랙리스트 **write** 는 User 전속으로 유지한다. 이 모듈은 ADR-0013 게이트웨이 도입(구현 ③) 시 검증 책임을 게이트웨이로 넘기고 **경량 identity(헤더→사용자) 모듈로 축소**되는 **전환기 구조물**이다.

> **중앙화의 의미 구분**: 본 모듈은 검증 *코드* 공유(라이브러리)일 뿐, 각 서비스가 자기 프로세스에서 `JwtFilter` 를 실행한다. **런타임 인증 중앙화는 게이트웨이(ADR-0013, 구현 ③)** 이며 본 모듈이 아니다.

### D1. 모듈 경계

| 소유 | 항목 |
|---|---|
| **`peekcart-common-auth`**(검증) | `LoginUser`/`CurrentUser`/`LoginUserArgumentResolver`/`TokenClaims`/`TokenParseException`, `JwtFilter`, JWT **verify**(`JwtTokenVerifier`), 보안 handler 2종(`JwtAccessDeniedHandler`/`JwtAuthenticationEntryPoint`), `WebMvcConfig`(resolver 등록), 재사용 SecurityFilterChain 기여(서비스는 자기 PUBLIC_URLS 선언), `TokenBlacklistLookupPort` + read-only Redis adapter |
| **User 전속**(발급/write) | `TokenIssuer`/JWT **sign**(`JwtTokenSigner`), `AuthController`/login/refresh, 블랙리스트 **write** owner(Redis impl) |

- **D1-b JwtProvider 분리**: `JwtProvider` 를 `JwtTokenSigner`(User, sign) ↔ `JwtTokenVerifier`(`common-auth`, verify)로 분리. 대칭키는 양쪽이 동일 시크릿 사용(전환기 한계, D2 참조).
- **D1-c Blacklist/Deny Redis Contract**(공유 인프라 경유 결합의 명문화):
  - **key namespace**: access blacklist 전용 prefix(예: `auth:blacklist:`), future `family/session deny`(예: `auth:deny:family:`)와 namespace 분리.
  - **value/식별자**: 토큰 **원문 금지**, jti 또는 토큰 hash 사용.
  - **TTL**: 토큰 잔여 만료와 정합(만료 후 자동 소멸).
  - **miss vs 조회 실패 구분**: miss = 통과, Redis 조회 **실패 = fail-closed**(ADR-0013 §48 정합).
  - **소유자**: User = write, `common-auth`/게이트웨이 = read.

### D2. 전환기 결합 수용 + 게이트웨이 exit

- **(a) 대칭키 공유(부채)**: HS256 시크릿을 5개 서비스에 배포(env). 서비스 수만큼 시크릿 노출면 증가 — ADR-0013 **RS256 전환**으로 해소 예정(검증자는 공개키만 보유).
- **(b) 블랙리스트 정책(결정)**: 5개 서비스 전부 **공유 Redis blacklist read + 조회 실패 시 fail-closed**(ADR-0013 §45-56 endgame 일치). skip 미채택 — 로그아웃/차단 토큰이 통과하는 보안 부채를 회피.
- **(c) 게이트웨이 exit(제거/잔류 분리)**: 구현 ③ 도입 시
  - **제거 대상**: `JwtFilter` / `JwtTokenVerifier` / blacklist lookup — 게이트웨이가 검증 + blacklist + family/session deny 를 인수(ADR-0013 §27,48).
  - **잔류·이관 대상**: `CurrentUser`/`LoginUser`/`LoginUserArgumentResolver` 의 **헤더(`X-User-Id`/`X-User-Role`) 기반 사용자 주입 계층**(ADR-0013 §49 신뢰 헤더, 누락 시 거부). → `common-auth` 는 검증 로직만 제거되고 경량 identity 모듈로 축소.

### D3. 의존 규칙

- **5개 서비스 전부**(User/Order/Payment/Notification + Product[admin API]) `:peekcart-common-auth` 의존. 전환기엔 auth-free 서비스 없음.
- `common-auth` 는 `:common`·`:peekcart-common-observability` 의존 가능. 블랙리스트 read 는 **공유 Redis 인프라 경유**이므로 ADR-0011 §D3 서비스↔서비스 직접 의존 금지 불변식을 위반하지 않는다(User impl 에 컴파일 의존하지 않음).
- `assertNoServiceProjectDeps` allowlist 에 `:peekcart-common-auth` 추가. **모듈 수 7→8**(common + observability + auth + 5서비스).

## Alternatives Considered

### Alternative A: 전용 `peekcart-common-auth` 모듈 (검증 전용) — **채택**
- **장점**: 응집도(auth 를 god-common 에서 분리), 명시 의존(향후 auth-free 서비스 추가 시 정직히 제외 가능), ADR-0009 관측성 모듈 선례와 일관. 게이트웨이 이후 모듈 단위 축소 용이.
- **단점**: 전환기엔 5개 모두 의존(분리 이득이 "Product 제외" 같은 형태로는 안 나타남). 모듈 1개 추가.
- **채택 사유**: 응집도 + exit 용이성. ADR-0011 Alt C(모듈 폭증)와 달리 observability 선례처럼 "집중 공용 모듈 1개" 추가에 그침.

### Alternative B: 검증을 `common` 에 주입
- **장점**: 새 모듈 없음.
- **단점**: `common` god-module 화(entity/response/kafka/redis + auth 혼재). auth 가 영구적으로 common 에 묶여 게이트웨이 이후 축소·제거가 어려움.
- **기각 사유**: 응집도 훼손 + exit 비용 증가.

### Alternative C: 서비스별 검증 스택 복제
- **장점**: 모듈 추가 없음, 서비스 독립성 최대.
- **단점**: 5개 서비스에 JwtFilter/verify/resolver/handler 중복. 수정 시 5곳 동기화.
- **기각 사유**: 중복 코드·유지보수 부담 과다.

### Alternative D: 게이트웨이(구현 ③) 선행
- **장점**: 전환기 검증 코드 자체가 불필요(서비스는 처음부터 신뢰 헤더만).
- **단점**: 라우팅 대상 서비스가 아직 분리 전 → 게이트웨이 선행은 닭-달걀, 구현 순서 재설계 비용.
- **기각 사유**: PR2(서비스 분리)와 동시 진행 불가. 단 "버릴 코드를 짓는다"는 본질적 우려는 D2 exit 경로로 완화.

## Consequences

### 긍정적 영향
- 게이트웨이 이전 전환기에 5개 서비스가 일관된 검증 코드를 공유, 중복 없이 분리 진행 가능.
- auth 경계가 `common` 과 분리되어 게이트웨이 도입 시 모듈 단위로 축소/제거 가능(D2-c exit).
- 블랙리스트 정책이 fail-closed 로 ADR-0013 endgame 과 미리 정합.

### 부정적 영향 / 트레이드오프
- **전환기 구조물**: 구현 ③에서 검증 로직을 뜯어내야 함(완전 폐기는 아님 — 헤더 변환 잔류).
- **대칭키 공유**: HS256 시크릿이 5개 서비스에 분산(노출면↑) — ADR-0013 RS256 으로만 근본 해소.
- **블랙리스트 공유 결합**: 공유 Redis 경유 숨은 결합. D1-c Redis Contract 로 명문화하되, 인프라 차원 결합은 남음.

### 후속 결정에 미치는 영향
- **ADR-0011**: §D1 토폴로지에 `peekcart-common-auth` 추가, §D2 auth 중 **검증 소유** 변경, §D3 서비스 허용 의존에 `:peekcart-common-auth` 추가 → ADR-0011 Status `Partially Superseded by ADR-0014`(범위 명시). 발급/AuthController/refresh/User 저장소 소유는 유효.
- **구현 ① PR2**: `peekcart-common-auth` 실제 생성, JwtProvider sign/verify 분리, 5개 서비스 검증 배선. `common-auth` 모듈은 첫 auth 서비스 peel(PR2a)에서 생성·이후 재사용.
- **구현 ③(ADR-0013)**: 게이트웨이 검증 인수 → 본 모듈 축소(D2-c).

## References
- ADR-0011(멀티모듈 토폴로지/D2 auth 소유/D3 의존규칙), ADR-0013(게이트웨이 RS256·신뢰헤더·blacklist fail-closed — §27,48,49,56), ADR-0009(관측성 집중 공용 모듈 선례), ADR-0001(4-Layered)
- 코드: `global/jwt/JwtProvider.java`, `global/jwt/JwtFilter.java`, `global/auth/TokenBlacklistPort.java`, `product/presentation/AdminProductController.java`, `global/config/SecurityConfig.java`
- 계획: `docs/plans/task-adr0014-transitional-auth-module.md`, `docs/plans/task-impl1-gradle-multimodule.md`(PR2)
