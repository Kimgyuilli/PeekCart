# task-adr0014-transitional-auth-module — 전환기 인증 검증 공유 모듈 ADR-0014

> 구현 ① PR2(서비스 분리) 착수 중 발견된 전환기 인증 문제를 ADR 로 먼저 확정한다.
> 결정: **`peekcart-common-auth` 모듈(검증 전용, 게이트웨이 이전 전환기)** 도입.
> 관계: ADR-0011(모듈 토폴로지/auth 소유) **부분 무효화**, ADR-0013(게이트웨이 RS256) endgame 연결.

## 1. 목표

게이트웨이(구현 ③, ADR-0013) 이전 단계에서 **5개 서비스 전부**(User/Order/Payment/Notification + Product[admin API], public product API 만 permitAll)가 JWT **검증**을 필요로 하는 현실을 반영해, JWT 검증 primitives 를 **전용 공유 모듈 `peekcart-common-auth`** 로 분리하는 결정을 ADR-0014 로 명문화한다. JWT **발급**·블랙리스트 **저장소**는 User 전속으로 유지하고, 이 모듈이 게이트웨이 도입 시 축소/제거되는 **전환기 구조물**임을 명시한다.

성공 기준: ADR-0014 가 (D1) 모듈 경계·검증/발급 분리, (D2) 전환기 결합(대칭키·블랙리스트) 수용·exit 경로, (D3) ADR-0011/0013 과의 관계를 SSOT 로 확정하고, ADR-0011 Status 를 Partially Superseded 로 갱신.

## 2. 배경 / 제약

### 현 인증 구현 (감사 — 실제 인용)
- `JwtProvider implements TokenIssuer` — **발급(`issue`/`signWith(key)`)과 검증(`parseToken`/`verifyWith(key)`)이 한 클래스 + 단일 `SecretKey`(HS256 대칭키)** 에 결합 (`global/jwt/JwtProvider.java`)
- `TokenIssuer`(발급 인터페이스): User `AuthService` 만 사용 (`global/auth/TokenIssuer.java`)
- `JwtFilter`(검증 필터) → `JwtProvider` + `TokenBlacklistPort` 의존. `SecurityConfig` 가 `new JwtFilter(...)` 직접 생성 (`global/jwt/JwtFilter.java`, `global/config/SecurityConfig.java`)
- `TokenBlacklistPort`(인터페이스): impl = User `TokenBlacklistRepository`(Redis). 검증 경로(JwtFilter)+발급 경로(AuthService) 양쪽 사용
- 인증 사용 서비스: **5개 전부** — User/Order/Payment/Notification(`@CurrentUser LoginUser`) + **Product**(`AdminProductController @PreAuthorize("hasRole('ADMIN')")`, `/api/v1/admin/products`). public product API 만 permitAll, admin API 는 검증 필요. (게이트웨이 이전 전환기에선 auth-free 서비스 없음)
- auth 패키지(현 위치, PR1 미이동): `global/auth`(CurrentUser, LoginUser, LoginUserArgumentResolver, TokenBlacklistPort, TokenClaims, TokenIssuer, TokenParseException), `global/jwt`(JwtFilter, JwtProvider), `global/security`(JwtAccessDeniedHandler, JwtAuthenticationEntryPoint), `config.WebMvcConfig`(resolver 등록), `config.SecurityConfig`

### 문제
- ADR-0011 §D2 는 auth 전체를 **User 전속**으로 뒀으나, 이는 **게이트웨이 검증 중앙화(ADR-0013, 구현 ③)를 전제**한 것. 게이트웨이 이전인 PR2 에선 **5개 서비스 전부** 각자 검증해야 하므로 D2 와 충돌.
- 검증을 `common` 에 넣으면 god-module 화(entity/response/kafka/redis 등과 auth 가 한 모듈에 혼재). 전용 모듈이 응집도·명시 의존 측면에서 우수.

### 제약 / 비대상
- **런타임 중앙화 아님**: 본 모듈은 검증 *코드* 공유(라이브러리)일 뿐, 각 서비스가 자기 프로세스에서 JwtFilter 실행. 진짜 인증 중앙화 = 게이트웨이(ADR-0013, 구현 ③).
- RS256 전환·게이트웨이 검증·Reuse Detection 은 ADR-0013 범위(비대상). 본 ADR 은 그 이전 전환기 한정.
- 실제 코드 이동/모듈 생성은 구현 ① PR2(별도 task). 본 ADR 은 결정만.

### ADR 관계
- **ADR-0011**: §D1 모듈 토폴로지에 `peekcart-common-auth` 추가, §D2 "auth=User전속" 중 **검증 범위 무효화** → ADR-0011 Status `Partially Superseded by ADR-0014`(무효화 범위 명시). Alt A vs Alt C(모듈 폭증) 논점은 observability 선례처럼 "집중 공용 모듈 1개 추가"로 정당화.
- **ADR-0013**: 게이트웨이 RS256 endgame. 본 모듈의 검증 책임은 ③에서 게이트웨이로 이관 → 모듈은 claims DTO 수준으로 축소/제거.
- **ADR-0009**: `peekcart-common-observability` 집중 공용 모듈 선례 인용.

## 3. 작업 항목

### Part A — 사전 감사 (ADR Context 근거)
- [ ] **P1.** 현 auth 코드의 검증/발급/블랙리스트 결합 관계를 ADR Context 에 실제 파일·라인 인용으로 확정 (JwtProvider sign/verify 결합, JwtFilter→TokenBlacklistPort, 서비스별 @CurrentUser 사용 매트릭스).

### Part B — Decision (ADR 본문 핵심)
- [ ] **P2.** **D1 모듈 경계**: `peekcart-common-auth`(java-library) 가 소유할 검증 primitives 확정 — LoginUser/CurrentUser/LoginUserArgumentResolver/TokenClaims/TokenParseException, JwtFilter, JWT **verify**(JwtProvider 분리), 보안 handler 2종, WebMvcConfig(resolver 등록), 재사용 SecurityFilterChain 기여(서비스는 자기 PUBLIC_URLS 선언). **User 잔류**: TokenIssuer/JWT **sign**, AuthController/refresh, 블랙리스트 저장소(Redis impl).
- [ ] **P3.** **D1-b JwtProvider 분리 + 블랙리스트 경계(닫힌 결정)**: sign/verify 를 `JwtTokenSigner`(User) ↔ `JwtTokenVerifier`(auth 모듈)로 분리. 블랙리스트는 **`common-auth` 가 read-only Redis adapter(`TokenBlacklistLookupPort` + 공유 Redis read 구현) 소유** → 5개 서비스가 서비스↔서비스 의존 없이(공유 Redis 인프라 경유) JwtFilter 에서 조회. **write owner = User 전속**(로그아웃/차단 시 기록). ADR-0011 §D3 서비스↔서비스 금지 불변식 유지(공유 인프라는 위반 아님).
- [ ] **P4.** **D1-c Blacklist/Deny Redis Contract(명문화)**: 공유 Redis 데이터 계약을 ADR Decision 에 고정 — access blacklist **key namespace**, **value/토큰 식별자**(원문 금지·hash 권장), **TTL 기준**(토큰 만료와 정합), **miss vs 조회 실패 구분**(miss=통과, 실패=fail-closed), **소유자**(User write, common-auth/게이트웨이 read), **future `family/session deny` 와의 namespace 분리·호환**(ADR-0013 §48,56 정합). 이 계약이 "공유 인프라 경유 숨은 결합"을 명시적 문서화.
- [ ] **P5.** **D2 전환기 결합 수용·exit(제거/잔류 분리)**: (a) **대칭키 공유** — HS256 시크릿을 5개 서비스에 배포(env), ADR-0013 RS256 으로 해소 예정으로 명시. (b) **블랙리스트 정책 = 채택 결정**: 5개 서비스 전부 **공유 Redis blacklist read + 조회 실패 시 fail-closed**(ADR-0013 §45-50,52-56 endgame 일치). skip 미채택. (c) **게이트웨이 exit 분리**: ③ 도입 시 **제거 대상 = `JwtFilter`/`JwtTokenVerifier`/blacklist lookup**(게이트웨이가 검증+blacklist+family/session deny 인수, ADR-0013 §27,48), **잔류·이관 대상 = `CurrentUser`/`LoginUser`/`LoginUserArgumentResolver` 의 헤더(`X-User-Id`/`X-User-Role`) 기반 사용자 주입 계층**(ADR-0013 §49 신뢰 헤더). → `common-auth` 를 경량 identity/header-auth 모듈로 축소(검증 로직만 제거, 헤더 변환 잔류).
- [ ] **P6.** **D3 의존 규칙**: **5개 서비스 전부**(User/Order/Payment/Notification + Product[admin API]) `:peekcart-common-auth` 의존(전환기엔 auth-free 서비스 없음). 모듈은 `:common`·`:peekcart-common-observability` 의존 가능, 서비스↔서비스 금지 불변식(ADR-0011 §D3) 유지. `assertNoServiceProjectDeps` allowlist 에 `:peekcart-common-auth` 추가. **모듈 수 7→8**(common+observability+auth+5서비스). `common-auth` 모듈은 첫 auth 서비스 peel(PR2a)에서 생성·이후 재사용.

### Part C — ADR-0014 작성
- [ ] **P7.** `docs/adr/0014-transitional-auth-module.md` 작성(template 복사, Status: Proposed→Accepted). Context/Decision(D1~D3)/Alternatives(A 전용모듈[채택]·B common주입·C 서비스복제·D 게이트웨이선행)/Consequences(전환기 구조물·대칭키/블랙리스트 임시·③ exit)/References.

### Part D — 인덱스/참조 동기화
- [ ] **P8.** `docs/adr/README.md` INDEX 행 추가(0014). **ADR-0011 Status → `Partially Superseded by ADR-0014`** + 무효화 범위 정확히 명시: **§D1 모듈 레이아웃 확장(+`peekcart-common-auth`), §D2 auth/jwt/security 중 *검증 소유*만 변경(발급/AuthController/refresh/User 저장소 소유는 유효), §D3 서비스 허용 의존에 `:peekcart-common-auth` 추가**. ADR-0011 본문 Status 줄 갱신(immutable 예외: Status 변경 허용).

### Part E — 문서 동기화 + 계획 반영
- [ ] **P9.** 문서 동기화(구체화):
  - **`task-impl1-gradle-multimodule.md`**: "7모듈/`:common`+`:peekcart-common-observability`만" 문구를 **8모듈(+`peekcart-common-auth`)** 로 정정 — 성공 기준(§1), P7(모듈명/의존), **P11("별도 모듈 신설 금지 — 7모듈 불변" → "마이그레이션 전용 모듈 신설 금지"로 범위 축소)**, P12(`assertNoServiceProjectDeps` allowlist), **§5 자동 검증("PR2: 7모듈" → 8모듈)**, 완료 조건, 영향 파일까지 모두. PR2 인증 메모를 ADR-0014 결정(검증→auth 모듈, 발급/블랙리스트 write→User, read 공유 Redis)으로 정합.
  - **`02-architecture.md §4-4`**: 모듈 목록에 `peekcart-common-auth` 추가(What) + 필요 시 §12 포인터.
  - **`docs/TASKS.md`·`PHASE4.md`**: "설계 ADR A1~A4 전부 완료/마지막 설계 ADR" 문구를 훼손 없이 보정 — ADR-0014 를 **"PR2 중 발견된 전환기 인증 보정 ADR(A4.5)"** 로 추적 추가(예: "초기 설계 ADR A1~A4 완료, PR2 보정 ADR-0014 추가").

## 4. 영향 파일

- 신규: `docs/adr/0014-transitional-auth-module.md`
- 수정: `docs/adr/README.md`(INDEX + ADR-0011 Status), `docs/adr/0011-phase4-multimodule-structure.md`(Status 줄 + 무효화 범위 D1/D2/D3 주석), `docs/plans/task-impl1-gradle-multimodule.md`(7→8모듈 정정: §1·P7·P12·완료조건·영향파일 + PR2 인증 메모 정합), `docs/02-architecture.md`(§4-4 모듈 목록 +auth), `docs/TASKS.md`·`docs/progress/PHASE4.md`("A1~A4 완료/마지막 설계 ADR" 문구 보정 + ADR-0014 A4.5 추적)
- 비대상(구현 ① PR2): 실제 모듈 생성·코드 이동·JwtProvider 분리

## 5. 검증 방법

### 자동
- `hpx_plan_lint` / 마크다운 링크·ADR 번호 정합
- ADR README INDEX 와 본문 Status 일관성(0011 Partially Superseded 양쪽 반영)

### 수동 (ADR 본문 품질 — 모두 통과해야 Accepted)
- [ ] D1 모듈 경계가 "검증 전용"으로 명확하고, 발급/블랙리스트 write owner User 잔류가 근거와 함께 기술됐는가
- [ ] D1-b 블랙리스트 런타임 배선이 닫힘 — `common-auth` read-only Redis adapter + User write owner, 서비스↔서비스 의존 없이 성립함이 명시됐는가
- [ ] D1-c Blacklist/Deny Redis Contract(key namespace·hash·TTL·miss vs 실패·소유자·family/session deny namespace)가 Decision 에 명문화됐는가
- [ ] D2 게이트웨이 exit 가 "제거(JwtFilter/Verifier/blacklist lookup) vs 잔류·이관(CurrentUser/resolver 헤더 변환)"으로 분리 기술되고 ADR-0013 §27,49 와 정합인가
- [ ] D2 블랙리스트 정책이 **결정**(5개 서비스 공유 Redis read + fail-closed)으로 박혔는가(열린 선택지 아님), 대칭키 공유가 부채로 기록되고 ADR-0013 exit 경로가 명시됐는가
- [ ] "런타임 중앙화 아님(라이브러리 공유, 게이트웨이가 런타임 중앙화)" 구분이 명시됐는가
- [ ] Alternatives 4종(전용모듈/common주입/서비스복제/게이트웨이선행) 트레이드오프가 비교됐는가
- [ ] ADR-0011 부분 무효화 범위가 **§D1 확장+§D2 검증소유+§D3 allowlist** 로 정확히 기술되고, 발급/User 저장소 소유 유효가 못박혔는가
- [ ] ADR-0011/0013/0009 와 충돌 없이 인용됐는가

## 6. 완료 조건

- [ ] `0014-transitional-auth-module.md` Accepted, Decision D1~D3 + Alternatives + Consequences + exit 경로 완비
- [ ] README INDEX 0014 추가, ADR-0011 Partially Superseded(범위 명시) 양쪽 반영
- [ ] 블랙리스트 정책(공유 Redis read + fail-closed, write owner User)이 Decision 으로 박힘
- [ ] 상위 PR2 계획서(7→8모듈)·02-architecture·TASKS·PHASE4 정합
- [ ] 수동 검증 7항목 통과

## 7. 트레이드오프 및 결정 근거

- **전용 모듈(채택) vs common 주입**: 응집도(auth 를 god-common 에서 분리) + 명시 의존(향후 auth-free 서비스 추가 시 정직히 제외 가능) + observability 선례. 전환기엔 5개 모두 의존하지만, common 주입은 auth 를 영구적으로 common 에 묶어 게이트웨이 이후 축소를 어렵게 함.
- **전환기 구조물 수용**: ③에서 뜯어낼 코드를 짓는 비용 < 게이트웨이 선행(닭-달걀, 시퀀스 재설계) 리스크. 단 대칭키/블랙리스트 임시 결합을 ADR 에 명시적 부채로 기록.
- **Alt D(게이트웨이 선행) 기각 근거**도 ADR 에 기록 — 라우팅 대상 서비스 미분리 상태에서 게이트웨이 선행은 검증 비용↑.

## 8. 후속 (Out-of-Scope)

- 구현 ① PR2: `peekcart-common-auth` 실제 생성, JwtProvider sign/verify 분리, 5개 서비스 검증 배선.
- 구현 ③(ADR-0013): 게이트웨이 RS256 검증 이관 → 본 모듈 축소/제거.
