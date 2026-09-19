# ADR-0017: Gateway 서명 내부 토큰 — header-trust 를 평문 헤더에서 서명 assertion 으로 격상

- **Status**: Accepted
- **Decided**: 2026-07-25 (Proposed) → 2026-07-25 (Accepted, 경로 A)
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 4 (MSA 분리) — 구현 ③ PR3d 재정의

## Context

구현 ③ PR3c([#77](https://github.com/Kimgyuilli/PeakCart/pull/77))로 header-trust 를 도입했다. Gateway 가 사용자 JWT 를 검증한 뒤 평문 `X-User-Id`/`X-User-Role`/`X-User-Family-Id` 를 주입하고(`GatewayAuthenticationFilter.withTrustedHeaders()` — `gateway/.../GatewayAuthenticationFilter.java:160`), 리소스 5서비스는 그 헤더를 **서명 검증 없이 신뢰**한다(`HeaderAuthenticationFilter` — `peekcart-common-auth/.../security/HeaderAuthenticationFilter.java:24` 주석: "서명 검증은 하지 않는다").

### C1. 현 신뢰 모델의 단일 통제(single control)

header-trust 의 안전성은 **"Gateway 외에는 아무도 리소스 서비스에 도달할 수 없다"** 라는 네트워크 격리 하나에 전적으로 걸려 있다:

- 외부 유입 `X-User-*` 는 Gateway 가 항상 strip 후 재주입(`GatewayAuthenticationFilter:153,163`)
- 직접 경로 차단 = ClusterIP 환원 + NetworkPolicy(PR3c P34/P35, `k8s/base/networkpolicy.yml`)

즉 방어선이 **NetworkPolicy 단 한 겹**이다. 이것이 깨지는 경로:

1. NetworkPolicy 오배포 / CNI(Dataplane V2) 미enforce / `component:backend` 라벨 드리프트 → 담이 조용히 사라져도 서비스는 여전히 평문 헤더를 신뢰
2. 클러스터 내 파드 1개 컴프로마이즈(SSRF·RCE·공급망) → 그 파드에서 `X-User-Id: 1 / X-User-Role: ADMIN` 주입 시 **로그인 없이 관리자 위장**

ADR-0013 D3 Consequences 는 이미 이 리스크를 트레이드오프로 명시했다("헤더 신뢰 모델 리스크 — NetworkPolicy/헤더 제거가 깨지면 spoofing 가능"). PR3c 가 GKE 실 클러스터 보안 smoke 증적을 **PR3d 진입 필수 게이트**로 남긴 것 자체가, 이 단일 통제가 load-bearing 이며 미검증 상태임을 방증한다.

### C2. 재활용 가능한 암호 인프라(이미 존재)

ADR-0013 D1 로 RS256/JWKS 를 구축한 결과, 서명 assertion 을 도입하는 데 필요한 배선이 이미 대부분 존재한다:

- `JwtKeyProperties`(`app.jwt.rs256.public-keys` = kid→PEM 리스트) · `RsaPublicKeyRegistry.find(kid)` · `PemKeyLoader` — 리소스 서비스가 이미 로드/보유(common-auth main)
- User-service 개인키 파일 마운트·서명 패턴(ADR-0013 D2)
- Gateway 는 현재 **검증만** 하고 서명은 하지 않음 — 서명 키페어만 없다

### C3. 결정 시점

PR3c 는 머지됐으나 **GKE 실 클러스터 rollout 증적 미확보** 상태다. 즉 평문 header-trust 는 아직 **영구 상태로 배포되지 않았고**, rollback 창(transitional)으로만 존재한다. 이 시점에서 PR3d 를 "평문 신뢰를 굳히고 verifier 삭제"로 정의할지, "서명 assertion 으로 격상"으로 정의할지가 갈린다.

## Decision

**Gateway 가 평문 `X-User-*` 대신 자기 개인키로 서명한 짧은 수명 내부 JWT(`X-Internal-Auth`)를 주입하고, 리소스 서비스는 Gateway 공개키로 서명·발행자·만료·kid 를 검증한 뒤 claims 에서 신원을 추출한다. 평문 신뢰 헤더는 폐기한다.** NetworkPolicy 는 대체하지 않고 **두 번째 방어 겹**으로 유지한다.

### D1. 서명 assertion 운반체 (평문 헤더 폐기)

- Gateway 는 검증 성공 시 `X-Internal-Auth: <JWT>` **단일 헤더**를 주입한다. claims = `sub`(userId) · `role` · `fid`(familyId, 전환기 선택) · `iss=peekcart-gateway` · `iat` · `exp`. JWT 헤더에 `kid`.
- 평문 `X-User-Id`/`X-User-Role`/`X-User-Family-Id` **주입 중단**. 리소스 서비스 필터는 헤더가 아니라 **검증된 claims** 에서 신원을 얻는다.
- 외부 유입 `X-User-*` **및 `X-Internal-Auth`** 를 Gateway 가 **항상 strip**(공개 경로 포함) — 클라이언트가 내부 토큰을 위조 주입할 표면 제거.
- **결과: 리소스 서비스에 신뢰하는 평문 헤더가 0 이 되어 스푸핑 대상 자체가 사라진다.**

### D2. Gateway 서명키 (신규 개인키 소유자)

- Gateway 가 내부 토큰 전용 RS256 개인키를 소유(kid 예: `gw-int-1`). **user-service 사용자 서명키와 별개 kid** — 두 발행자를 명확히 분리한다.
- 주입 = 파일/CSI 마운트, **환경변수 금지**(ADR-0013 D2 정합). Gateway 전용 Secret 신설.
- 회전 = user 서명키와 동일 원칙(active/previous overlap > 내부 토큰 최대 TTL). 내부 토큰 TTL 이 초 단위라 overlap 창이 매우 짧다.

### D3. 서비스 검증 (기존 인프라 재활용 + 핀 고정)

- 리소스 서비스는 `RsaPublicKeyRegistry` 에 **gateway 공개키 kid 를 추가**(`app.jwt.rs256.public-keys`)하고, 내부 토큰 검증 시:
  - `iss=peekcart-gateway` **필수 일치**(require issuer)
  - `kid` = gateway 발행 kid 로 **핀** — 그 외 kid(사용자 access token 서명 kid 포함) 거부
  - `exp` 검증 + 소량 clock skew leeway(≤5s)
  - HS fallback **불허**(내부 토큰은 RS256 전용)
- **iss/kid 핀이 핵심 보안 조건**: 이를 생략하면 클라이언트가 자기 정상 사용자 access token 을 `X-Internal-Auth` 에 넣어 신원을 위조할 수 있다(서비스가 사용자 공개키도 갖고 있으므로 서명 자체는 통과). "gateway 가 서명한 것만" 수용해야 한다.
- **TTL 초단기(예: 30s)**: gateway→service hop 만 살아남으면 된다. blacklist/family-deny/reuse detection·logout 은 **Gateway 경계에 그대로 유지**(ADR-0013 D3/D4) — 서비스는 순간 assertion 만 신뢰하므로 서비스 측 Redis 조회가 불필요하다.

### D4. 신뢰 경계 이중화 (defense-in-depth)

- 신원 위조는 이제 **NetworkPolicy(네트워크 위치) AND Gateway 개인키(서명)** 를 동시에 뚫어야 가능하다.
- NetworkPolicy 단독 실패(오배포·CNI 미enforce·파드 컴프로마이즈)로는 위조 불가 — 서명키가 없으면 유효한 `X-Internal-Auth` 를 만들 수 없다.
- NetworkPolicy 는 여전히 가치가 있다(미인증 직접 타격·DoS·공격표면 축소) — 제거하지 않는다.

### D5. PR3d 재정의 (verifier 삭제 → 용도 변경)

- ADR-0014 D2-c(전환기 servlet 검증 exit)는 여전히 성립한다: 리소스 서비스는 **사용자 access token 재검증을 중단**하고 Gateway 는 **Authorization 전달을 중단**한다.
- 다만 검증 기계(`RsaPublicKeyRegistry`/`PemKeyLoader`/JWT 파서)는 **삭제하지 않고 내부 토큰 검증기로 용도 변경**한다. 리소스 서비스에서 삭제되는 것은 **사용자 토큰 검증 필터·blacklist lookup**(deny 는 Gateway 소유)이다.
- 이는 ADR-0013 의 트레이드오프("헤더 신뢰 모델 리스크")를 재해석하는 결정 변경이므로 본 신규 ADR 로 기록한다(ADR-0013 본문 정정 아님).

## Alternatives Considered

### Alternative A: 평문 header-trust 영구화 (현 PR3c 상태 유지)
- **장점**: 추가 작업 0. 서비스당 요청당 암호 연산 없음(최저 지연). 가장 단순.
- **단점**: 신뢰 근거가 NetworkPolicy 단일 통제. 네트워크 격리 실패·클러스터 내 파드 컴프로마이즈 시 임의 신원 위조.
- **기각 사유**: 단일 실패점. 인증 경계 전체가 하나의 kubernetes 리소스 정합성에 종속된다. defense-in-depth 부재.

### Alternative B: HMAC 공유 비밀로 헤더 서명
- **장점**: 대칭키라 서명/검증 모두 빠름. 키 배포 단순(1 secret).
- **단점**: 서비스가 검증하려면 **모든 서비스가 서명 비밀을 보유** → 서비스 1개만 컴프로마이즈돼도 임의 신원 위조 가능(blast radius = 전체 메시).
- **기각 사유**: 비대칭(Gateway 개인키 서명 / 서비스 공개키 검증)이 blast radius 를 Gateway 하나로 국한하므로 엄격히 우월. 검증 비용 차이는 무의미(sub-ms).

### Alternative C: mTLS (Istio/Linkerd 서비스 메시)
- **장점**: 호출자 신원의 암호학적 증명(SPIFFE) — "이 요청은 진짜 Gateway 다"를 전송 계층에서 보장. 가장 근본적.
- **단점**: 서비스 메시 인프라(사이드카·control plane) 도입 부담. 운영 복잡도·리소스 오버헤드 큼.
- **기각 사유**: Phase 4 범위 대비 과대. 본 결정(서명 assertion)이 메시 없이 신원 위조 방어의 핵심 이득을 얻는다. mTLS 는 Phase 5+ 후보로 보류.

### Alternative D: 원본 JWT 전파 + 서비스별 재검증 (header-trust 이전 상태)
- **장점**: 각 서비스가 토큰을 직접 검증 — 신뢰 헤더 개념 자체가 불요.
- **단점**: 인증 로직 5중 중복 + 서비스별 blacklist Redis 조회 재도입(header-trust 로 제거한 바로 그 지연/결합).
- **기각 사유**: header-trust 로 이미 벗어난 상태로 회귀. 내부 토큰(짧은 TTL·서명만 검증·deny 는 Gateway)이 중복/지연 없이 동등한 신뢰를 제공.

## Consequences

### 긍정적 영향
- **defense-in-depth**: 신원 위조에 NetworkPolicy AND 서명키 동시 돌파 필요. 단일 통제 해소.
- **스푸핑 표면 제거**: 리소스 서비스가 신뢰하는 평문 헤더 0 → 위조 대상 소멸.
- **기존 크립토 ~80% 재활용**: `JwtKeyProperties`/`RsaPublicKeyRegistry`/`PemKeyLoader`/JWT 파서 그대로. Gateway 서명 키페어만 신규.
- **경량성 유지**: deny/blacklist/reuse/logout 은 Gateway 경계 유지 → 서비스는 서명 검증(sub-ms)만, 서비스 측 Redis 조회 없음.

### 부정적 영향 / 트레이드오프
- **서비스당 요청당 RSA 서명 검증 1회**(sub-ms, 무거운 Redis 조회는 없음). ES256/EdDSA 로 더 낮출 여지 있으나 RS256 이 기존 인프라 재활용.
- **Gateway 개인키 관리 추가**: 새 Secret·마운트·회전 절차. 단 Gateway 는 이미 인증 경로 SPOF 라 새 SPOF 를 만들지 않는다.
- **clock skew 의존**: 내부 토큰 exp 가 초 단위 → gateway/service 시계 동기(NTP) 전제 + 검증 leeway 필요.
- **계약 이중 지점**: 내부 토큰 claims/iss/kid 계약이 발행(Gateway)·검증(common-auth) 두 곳에 존재 → conformance(golden vector) 테스트로 동등성 고정.

### 후속 결정에 미치는 영향
- **PR3d 재정의**: `task-impl3-spring-cloud-gateway.md` PR3d(P14 삭제분·P18 ⑤) 및 클래스 처분표(loop2 #3)를 재작성 — "삭제"였던 `RsaPublicKeyRegistry`/`PemKeyLoader`/JWT 파서를 "내부 토큰 검증기로 용도 변경"으로. 상세: `docs/plans/done/task-impl3-pr3d-internal-token.md`.
- **PR4 무영향**: HS512 fallback 제거(P22)는 사용자 access token 검증(Gateway) 소관으로 별개. 내부 토큰은 처음부터 RS256 전용.
- **롤아웃**: PR3c 가 GKE 증적 미확보라, 본 결정을 **PR3d 로 흡수(경로 A)** 하면 평문 header-trust 를 영구 배포하지 않고 서명 assertion 을 header-trust rollout 으로 직행. GKE 보안 smoke 는 서명 assertion 상태에서 1회 수행.
- **Layer 1 동기화**: `docs/02-architecture.md`·`docs/04-design-deep-dive.md §10-2`(Gateway 헤더 신뢰 모델)에 서명 assertion 반영.

## References
- ADR-0013(Gateway 보안 — D1 RS256/JWKS, D2 키 저장, D3 헤더 신뢰 모델·SPOF), ADR-0014(전환기 인증 모듈 — D2-c servlet 검증 exit), ADR-0011(common-auth 구조)
- 코드: `gateway/.../GatewayAuthenticationFilter.java:153,160,163`(strip/주입), `peekcart-common-auth/.../security/HeaderAuthenticationFilter.java:24,44`(평문 신뢰), `peekcart-common-auth/.../jwt/RsaPublicKeyRegistry.java:39`·`JwtKeyProperties.java:22`(재활용 대상), `k8s/base/networkpolicy.yml`
- 계획: `docs/plans/done/task-impl3-pr3d-internal-token.md`
- `docs/04-design-deep-dive.md §10-2`(Gateway 인증·헤더 신뢰)·§10-6(Redis SPOF)

## Update Log
- **2026-07-25** (계획 리뷰 loop1 #1, 사실 정정): D2 의 "Gateway 전용 Secret 신설" 표현이 k8s Secret 으로 오독될 수 있어 명확화한다. D2 가 이미 규정한 대로 gateway 개인키는 **GCP Secret Manager 전용 secret + Secrets Store CSI read-only 파일 마운트**를 의미하며, **k8s Secret(`secretKeyRef`/`secret:` volume)·환경변수 는 금지**한다(ADR-0013 D2 정합, `scripts/gateway-exposure-lint.sh` 의 gateway Secret 참조 금지 규칙과도 정합). 결정 변경이 아닌 표현 정정. 상세: `docs/plans/done/task-impl3-pr3d-internal-token.md` P5/P7.
