# task-impl3-pr4-auth-observability — 구현 ③ PR4: 인증 관측성 S9 + HS512 잔재 제거

> 상위 계획 `task-impl3-spring-cloud-gateway.md` 의 **PR4(P20~P23)** 를 본 문서(P1~P12)가 정본으로 대체한다.
> 선행: ADR-0009 §Decision S9(행 기존재) · ADR-0013 D5 · ADR-0015(canonical 5 계약) · ADR-0017(내부 토큰).
> **신규 ADR-0024 를 본 계획 P1 에서 작성한다** — canonical 5→6 확장은 ADR-0015 §Decision(S5 "5서비스 각 1파일" · S6 "regex 값 == 5서비스 ground truth")의 계약 변경이라, README §14 상 Update Log 로 우회할 수 없다. PR3b 자신도 "SM·alert·lint 6 확장은 PR4 에서 **ADR 과 함께** 일괄"로 이연해 뒀다(상위 계획 §PR3b 결정 가).
> Codex 리뷰 미호출(사용자 지시) — 본 문서에 "P1 = 0" 주장은 없다.

## 1. 목표

**명제(부정형 — 무엇이 성립하면 미완인가):**

1. Gateway 가 요청을 **거부했는데**(401/403/429/503) 그 사유를 메트릭으로 셀 수 없으면 미완이다.
2. reuse 감지·로그아웃이 **일어났는데** user-service 메트릭에 남지 않으면 미완이다.
3. Gateway 메트릭이 **존재하는데 수집되지 않으면**(ServiceMonitor 부재) 미완이다. 수집되는데 alert/dashboard 의 대상 집합에서 빠져 있어도 미완이다.
4. Gateway 를 alert 대상에 넣었는데 **p95 패널이 NaN** 이면(= S1 histogram bucket 부재) 미완이다.
5. lint 가 위 네 가지 중 **어느 하나라도 빠진 상태를 green 으로 통과**시키면 미완이다 — 특히 "6개여야 하는데 5개"·"SM 은 있는데 Service 가 매칭되지 않음"·"메트릭 이름은 있는데 alert 식이 무력화됨".
6. HS512 fallback·legacy `bl:` dual-read·`app.jwt.secret` 이 **아무도 쓰지 않는 채로 설정 표면에 남아** 있으면 미완이다(다음 사람이 "아직 대칭키를 쓴다"고 읽는다).

## 2. 배경 / 제약

### 2.1 착수 전 코드 검증 (grep/파일 확인, 2026-09-14)

| # | 검증 대상 | 결과 | 계획에 미치는 영향 |
|---|---|---|---|
| C-1 | gateway 메트릭 코드 존재 여부 | `grep -r "MeterRegistry\|Counter" gateway/src` → **0건** | S9 gateway 측은 전부 신설 |
| C-2 | gateway `management.metrics.tags.application`(S2) | **없음**(`gateway/src/main/resources/application.yml`). 5서비스는 각 yml 에 자기 이름 보유 | gateway 메트릭은 지금 `application` 라벨이 없다 → alert regex 에 넣어도 매칭 0 |
| C-3 | gateway 의 S1(histogram bucket) | `MetricsConfig`(`peekcart-common-observability`)는 gateway 미적용. gateway build.gradle 에 해당 project 의존 **없음** | p95 alert 에 gateway 를 넣으면 `_bucket` series 부재 → NaN. S1 편입이 선행 |
| C-4 | gateway 가 그 모듈을 의존할 수 있는가 | `peekcart-common-observability/build.gradle` = `actuator` + `micrometer-core`(api) + `spring-tx`(implementation). **servlet/JPA/web 0** | 의존해도 WebFlux 부팅 깨지지 않음. 단 루트 가드 `assertGatewayHasNoServletDeps`(`build.gradle:187-240`)의 allowlist 가 `:internal-token-contract` **단독** → 확장 필요 |
| C-5 | 인증 실패 계측점 | `GatewayAuthenticationFilter.reject(exchange, status, reason)` 단일 지점. 사유 문자열 5종 이미 존재(`missing_token`/`invalid_token`/`internal_token_refused`/`dependency_unavailable`/`rate_limiter_unavailable`) | 계측점 신설 불요 — **사유 분해**만 필요 |
| C-6 | 사유 granularity | `GatewayJwtVerifier` 의 실패가 **전부** `InvalidTokenException` 1종(`:62,:73,:79,:97,:104`). 서명오류·만료·unknown kid·alg 불허·exp 부재가 message 로만 구분 | ADR-0009 S9 가 요구하는 "서명오류/만료" 분리 불가 → **bounded reason enum** 을 예외에 부여(메시지를 태그로 쓰면 카디널리티 폭발) |
| C-7 | 429 발생 지점 | 우리 필터가 아니라 SCG `RequestRateLimiter`. 우리 코드에서 429 를 쓰는 곳 **0건**. `FailClosedRedisRateLimiter.isAllowed` 가 allowed=false 를 반환하는 유일 지점 | 429 계측은 limiter 안 — 필터 reject 에 얹을 수 없다 |
| C-8 | 403(인가 실패) 소유 | gateway 는 authz 를 하지 않는다(DLQ admin 라우트 주석: ROLE_ADMIN 판정은 리소스 서비스 소유). 5서비스에 403 카운터 **0건** | ADR-0009 S9 owner 는 Gateway/User 뿐 → gateway 가 **다운스트림 응답 403 을 관측**해 센다(서비스 5곳 변경 0, 이름 1개소) |
| C-9 | user-service reuse/logout 경로 | `AuthService.detectReuse`(`:159`) 단일 수렴점(3경로), `AuthService.logout`(`:85`) | 계측점 2개로 충분. 둘 다 트랜잭션 안 → `CommitAwareMetrics` 사용(선례: `OrderSagaMetrics`) |
| C-10 | ServiceMonitor canonical | `scripts/servicemonitor-selector-lint.sh:95` `CANONICAL` = 도메인 5 **정확 일치**(extra 도 위반). gateway 디렉터리에 `servicemonitor.yml` **없음** | SM 신설 = 즉시 lint red → 같은 PR 에서 함께 고쳐야 함 |
| C-11 | promql lint ground truth | `scripts/observability-promql-lint.sh:253` `EXPECTED_SERVICES`(5)가 **세 곳에 동시 사용** — ① app 태그 집합(glob `*-service/src/main/resources/application.yml`) ② SM 매칭 Service name 집합 ③ scrape-absent rule uid 집합. `APP_REGEX`(:302) 5값 | gateway 는 디렉터리명이 `gateway`(접미사 없음)라 **glob 에 아예 안 잡힌다**. 게다가 세 집합의 원소가 서로 달라진다(아래 2.2) → 단일 상수 분해 필요 |
| C-12 | gateway 노출 lint 충돌 | `scripts/gateway-exposure-lint.sh:121-125` = "gateway Pod 를 selector 로 선택하는 Service **정확히 1개**" + `:163` "그 Service selector == `{app: gateway}` 정확 일치" | `gateway-metrics` Service 추가 시 즉시 red → 명시 allow-list 로 2개 계약으로 재작성(상위 계획 §PR3b 결정 나에 예고됨) |
| C-13 | NetworkPolicy 영향 | `k8s/base/networkpolicy.yml` podSelector = `component: backend`. gateway Pod 는 `component: gateway` → **어떤 NP 도 gateway 를 선택하지 않음** | 8081 scrape 에 NP 변경 불요. `networkpolicy-contract-lint`(고정 5 Deployment) 무영향 |
| C-14 | HS 서명 발급자 잔존 | `JwtTokenSigner.issue`(`user-service`)는 **RS256 단독**(`signWith(privateKey, Jwts.SIG.RS256)`). `JwtAuthProperties.secret` 을 읽는 코드 **0건**(expiry 2개만 사용) | HS512 fallback 은 "혹시 남은 레거시 토큰"이 아니라 **이미 발급 주체가 사라진** 경로 → 제거에 TTL 대기 게이트 불요 |
| C-15 | legacy `bl:` writer | 저장소 전체에서 `bl:` 키를 **쓰는 코드 0건**. `TokenBlacklistRepository` 는 신키(`auth:blacklist:<sha256hex>`)만 기록. gateway `TokenDenyLookup:39` 는 read-only | dual-read 제거 안전. 추가 발견: `TokenBlacklistPort.addToBlacklist` 는 **호출자 0건**(header-trust 전환으로 logout 이 denyFamily 로 대체됨) |
| C-16 | dashboard 대상 집합 | `api-jvm-dashboard.json`·`kafka-lag-dashboard.json` 의 `application` 은 **custom 변수에 5서비스 하드코딩**. promql lint 는 dashboard 를 **전혀 읽지 않음**(`grep dashboard scripts/observability-promql-lint.sh` → 0건) | dashboard 드리프트는 현재 무검증 표면. 또 `kafka-lag` 목록에 `user-service` 가 있으나 user-service 는 `@KafkaListener` **0건** — 존재하지 않는 lag series 를 고르게 하는 오기 |

### 2.2 세 집합이 더 이상 같지 않다 (본 PR 의 핵심 구조 변경)

ADR-0015 는 "5서비스"라는 **하나의** ground truth 로 세 가지를 동시에 표현할 수 있었다. gateway 를 넣으면 원소가 갈라진다:

| 집합 | 값 | 왜 다른가 |
|---|---|---|
| A. `application` 메트릭 태그 | `gateway` + 도메인 5 = **6** | 태그 값은 `spring.application.name`(=`gateway`)을 따른다 |
| B. SM 이 매칭하는 **Service metadata.name**(= `up{service=}`) | `gateway-metrics` + 도메인 5 = **6** | gateway 공개 Service 는 8080 단일(LB 로 patch 되므로 8081 게시 금지, PR3b 결정 나) → 관리 포트 scrape 용 **별도 ClusterIP Service** 필요 |
| C. ServiceMonitor `metadata.name` | `gateway` + 도메인 5 = **6** | per-service 1파일 규칙(`services/<dir>/servicemonitor.yml`)의 디렉터리명 = `gateway` |

→ lint 의 단일 상수 `EXPECTED_SERVICES` 를 **세 상수로 분해**한다. 이 분해 없이 6 으로만 늘리면 B 와 A 가 서로를 오탐한다.

### 2.3 B1 — 역의존 스윕 (인바운드 간선과 처분)

| 인바운드 간선 | 현재 | 처분 |
|---|---|---|
| `assertGatewayHasNoServletDeps` allowlist(`build.gradle:204`) | `[':internal-token-contract']` | **확장(P2)**: `+ ':peekcart-common-observability'`. (a2) 무오염 검사도 그 모듈에 적용 — 나중에 그 모듈이 `starter-web`/JPA 를 물면 allowlist 가 우회로가 된다 |
| `GatewayJwtVerifier.InvalidTokenException` 생성 5곳 | message 만 | **reason 부여(P3)**: 호출부 전부 수정. 예외 타입은 유지(필터의 `isAuthFailure` 분기 불변) |
| `GatewayAuthenticationFilter` 생성자 | verifier/denyLookup/issuer/publicProps | **+`GatewayAuthMetrics`(P3)** — bean graph 1건 |
| `GatewayAuthenticationFilterTest`(mock 생성자 호출) | 4인자 | **수정(P12)**: 5인자 + `SimpleMeterRegistry` |
| `FailClosedRedisRateLimiter` 생성자 | redis/configService | **+`MeterRegistry`(P4)**. `FailClosedRedisRateLimiterTest` 동반 수정 |
| `AuthService` 생성자 | 5인자 | **+`UserAuthMetrics`(P6)**. `AuthServiceTest`(생성자 직접 호출, `:57`) 동반 수정 |
| `k8s/base/kustomization.yml` | gateway 리소스 2개 | **+`services/gateway/servicemonitor.yml`(P7)** |
| `scripts/observability-promql-lint.sh` self-test | 조작 케이스 N종 | **확장(P10)**: 6-집합 불일치·SM 누락·dashboard 드리프트 케이스 추가 |
| `scripts/gateway-exposure-lint.sh` self-test 13종 | Service 1개 전제(`:400` extra-service 케이스가 **위반**으로 기대) | **재정의(P10)**: 승인된 2번째 Service 는 통과, 그 외 extra 는 여전히 위반 |
| `JwtAuthProperties`(record `secret`) | common-auth 소유, 바인딩 3필드 | **필드 삭제(P11)** — 인바운드는 `AuthService`/`JwtTokenSigner`/테스트 2곳(모두 expiry 만 사용) |
| `k8s/base/services/*/secret.yml` ×5 `JWT_SECRET` | 평문 템플릿 | **삭제(P11)** — 바인딩 대상이 사라지므로 env 만 남으면 거짓 신호 |

### 2.4 트레이드오프

- **gateway → `:peekcart-common-observability` 의존**: WebFlux 격리 규칙에 예외를 하나 더 만든다. 대가로 S1 MeterFilter 의 "1개소" 계약이 유지된다. 반대 선택(gateway 에 MeterFilter 재선언)은 의존 그래프는 깨끗하지만 ADR-0009 S1 "서비스 모듈 재선언 금지"를 정면으로 어긴다 — **계약 위반보다 allowlist +1 이 싸다**(사용자 결정, 2026-09-14).
- **403 을 gateway 가 관측**: 다운스트림이 낸 403 을 gateway 가 세므로, 서비스가 직접 호출된 경우(NetworkPolicy 를 뚫은 경로)는 세지 않는다. 그 경로는 애초에 없어야 하는 것이고, 있다면 NP smoke 가 잡는 축이다. 5서비스에 카운터를 복제하는 대안은 "이름 1개소" 계약을 깬다.
- **reason 태그 카디널리티**: enum 으로 고정(≤10). 예외 message 는 절대 태그가 되지 않는다 — 메시지는 사용자 입력 파편을 품을 수 있다.
- **alert 식 정본을 6으로 넓히는 비용**: `ALERT_EXPR_CONTRACTS` 는 식을 **정확 문자열**로 고정하므로 alert 를 고칠 때 lint 도 함께 고쳐야 한다. 그게 ADR-0019 의 의도(식 변경 = 계약 변경)이고 본 PR 도 그 규약을 따른다.
- **HS512 제거의 되돌리기 비용**: 레거시 HS 토큰을 다시 받아야 하는 상황이 오면 코드 복구가 필요하다. C-14 로 발급 주체가 이미 없음을 확인했으므로 그 상황은 "레거시 토큰이 남아 있다"가 아니라 "RS256 전환을 되돌린다"는 뜻이고, 그건 ADR-0013 자체의 롤백이다.

## 3. 작업 항목

### ADR / 계약

- [x] **P1.** **ADR-0024 작성** — "관측성 canonical 집합 = 도메인 5 + 인프라 1(gateway)". 내용: ① 2.2 의 **세 집합(A/B/C) 분리**를 계약으로 명문화 ② gateway 의 S1/S2 편입과 그 근거(의존 allowlist 확장) ③ S6.a/b regex 6값·S6.d `gateway-metrics` equality rule 추가 ④ S9 의 **구현 계약 확정**(메트릭 이름·태그 enum·소유 모듈) ⑤ ADR-0015 무효화 범위 = "canonical 5 정확일치"에 한정(per-service SSOT 위치·`by (application)` grouping·ground truth 정의는 유효). ADR-0009/0013/0015 본문은 **수정하지 않는다**(S9 행은 ADR-0009:58 에 이미 존재). `docs/adr/README.md` 인덱스에 행 추가 + ADR-0015 Status 를 `Partially Superseded by ADR-0019, ADR-0024` 로 갱신.

### Gateway 관측성 기반 (S1/S2)

- [x] **P2.** gateway 를 S1/S2 계약에 편입 — `gateway/build.gradle` 에 `implementation project(':peekcart-common-observability')`, `build.gradle:204` allowlist 에 해당 모듈 추가 + **(a2) 무오염 검사를 그 모듈에도 적용**(project 의존 금지는 제외 — 그 모듈은 `spring-tx` 를 쓴다. 금지 축은 `spring-boot-starter-web`/`spring-webmvc`/`jakarta.servlet`/JPA), `gateway/src/main/resources/application.yml` 에 `management.metrics.tags.application: gateway`.

### S9 계측 (Gateway)

- [x] **P3.** 인증 실패 사유 분해 + counter — `GatewayJwtVerifier.InvalidTokenException` 에 **bounded reason**(`MALFORMED`/`BAD_SIGNATURE`/`EXPIRED`/`UNKNOWN_KID`/`ALG_NOT_ALLOWED`/`MISSING_EXP`/`DENIED`) 부여(생성 5곳 + 필터의 deny 분기 1곳). `parseClaims` 에서 `ExpiredJwtException` 을 먼저 잡아 `EXPIRED` 로 분리. `GatewayAuthMetrics`(gateway 소유) 신설 — `auth.failure` counter, tag `reason`(위 7종 + `missing_token`/`internal_token_refused`/`dependency_unavailable`/`rate_limiter_unavailable`). `reject()` **단일 지점**에서만 증가시킨다.
- [x] **P4.** 429 counter — `FailClosedRedisRateLimiter.isAllowed` 의 allowed=false 분기에서 `auth.ratelimit.rejected{route=<routeId>}` 증가. Redis 장애(503)는 여기서 세지 않는다 — 그건 P3 의 `rate_limiter_unavailable` 이다(한도 초과와 판정 불가를 합치면 알람 대응이 갈린다).
- [x] **P5.** 403 counter — gateway 전역 post-filter(`GlobalFilter`, 인증 필터보다 낮은 우선순위)에서 다운스트림 응답 status==403 일 때 `auth.forbidden{route=<routeId>}` 증가. gateway 자신은 403 을 내지 않으므로 값은 전부 리소스 서비스 판정이다.

### S9 계측 (User)

- [x] **P6.** `UserAuthMetrics`(user-service 소유) 신설 — `auth.token.reuse.detected` counter(`AuthService.detectReuse` 단일 수렴점), `auth.logout` counter(`AuthService.logout`). 둘 다 `CommitAwareMetrics.increment` — 원장 revoke 가 롤백됐는데 카운터만 남으면 alert 근거가 거짓이 된다.

### 수집 / alert / dashboard

- [x] **P7.** scrape 표면 — `k8s/base/services/gateway/deployment.yml` 에 `gateway-metrics` Service 추가(ClusterIP, labels `{app: gateway, monitoring-role: metrics}`, selector `{app: gateway}`, port name `management` 8081→8081). **같은 파일에 둔다** — promql lint 의 Service 수집 glob 이 `k8s/base/services/*/deployment.yml` 이라 별도 파일은 안 읽힌다(C-11). `k8s/base/services/gateway/servicemonitor.yml` 신설(name `gateway`, selector = **두 라벨 논리곱**, endpoint port `management`, path `/actuator/prometheus`, interval 15s, `release: kube-prometheus-stack`) + `k8s/base/kustomization.yml` 등록. overlay 는 patch 하지 않는다(두 리소스 모두 환경 비종속).
- [x] **P8.** alert 갱신(`k8s/monitoring/shared/grafana-alerts.yml`) — ① `peekcart-high-error-rate`·`peekcart-slow-response` 의 regex 를 **6값**(`gateway|` 선두, 사전순)으로 ② `peekcart-scrape-absent-gateway-metrics` rule 신설(집합 B 기준) ③ **신규** `peekcart-token-reuse-detected`(`sum by (application)(increase(auth_token_reuse_detected_total{application=~"user-service"}[5m]))`, `$A > 0`) — 임계 근거가 필요 없는 유일한 S9 신호다(발생 자체가 사건). auth failure rate alert 는 baseline 부재로 **비대상**(§7).
- [x] **P9.** dashboard 갱신 — `api-jvm-dashboard.json` 의 `application` custom 변수를 6값으로(options 배열 + query 문자열 양쪽). `kafka-lag-dashboard.json` 은 **소비 4서비스**로 정정(`user-service` 제거 — `@KafkaListener` 0건이라 고를 수 없는 값이었다). gateway 는 Kafka 소비자가 아니므로 kafka-lag 에 넣지 않는다.

### 검증 도구

- [x] **P10.** lint 3종 갱신 + false-green 차단 — ① `observability-promql-lint.sh`: `EXPECTED_SERVICES` 를 `APP_TAGS`(A, 6) / `SCRAPE_SERVICES`(B, 6) / `ALERT_APP_REGEX`(A 사전순 파이프 결합) 로 분해, app 태그 수집에 `gateway/src/main/resources/application.yml` 을 **명시 경로로 추가**(glob 확장 금지 — glob 은 축소 false-green 을 만든다), scrape-absent uid 집합을 B 기준으로, **dashboard 변수 검사 신설**(`api-jvm`=A, `kafka-lag`=소비 4 — 파일별 기대집합 명시) ② `servicemonitor-selector-lint.sh`: `CANONICAL` → 집합 C(6) + **`--self-test` 신설**(현재 없음: SM 1개 삭제·selector 오타·endpoint port 오타·extra SM 4종이 실제로 red 인지) ③ **`observability-ssot-lint.sh`(구현 중 추가)**: S2 검사 대상에 gateway base yml 을 명시 경로로 편입 — 안 하면 gateway 태그가 프로파일(`application-k8s.yml`)에서 조용히 덮여도 아무도 모른다(음성 확인: 재선언 시 D5-V1 red) ④ `gateway-exposure-lint.sh`: "Service 정확히 1개" → **"승인된 2개 정확 일치"**(`gateway`=8080/8080 + `gateway-metrics`=8081/8081·ClusterIP 고정·NodePort/LB patch 금지), self-test 에 "gateway-metrics 가 8080 을 게시"·"3번째 Service 추가"·"gateway-metrics 가 overlay 에서 LB 로 승격" 케이스 추가. CI(`.github/workflows/ci.yml`)에 신설 self-test 배선.

### 잔재 제거 (상위 P22)

- [x] **P11.** HS512·legacy 잔재 sweep — ① gateway: `JwtGatewayProperties.hs512FallbackEnabled/hs512Secret` + `GatewayJwtVerifier.hs512Key`/HS512 분기 + yml 2키 삭제(alg allow-list = RS256 단독) ② `TokenDenyLookup.LEGACY_PREFIX`(`bl:`) dual-read 삭제 ③ `JwtAuthProperties.secret` 필드 + `user-service/application.yml:38` + `k8s/base/services/*/secret.yml` ×5 의 `JWT_SECRET` 삭제 ④ ~~`TokenBlacklistPort.addToBlacklist` 삭제~~ → **구현 중 방침 변경**: 삭제하지 않고 "현재 호출자 없음"을 javadoc 에 명시한다. 삭제하면 gateway 의 `auth:blacklist:` **read 는 남는데 write 쪽만 사라져** 계약의 반쪽이 죽는다(read 는 ADR-0014 D1-c 가 정의한 표면이고 per-token deny 가 필요해지면 그대로 쓰인다). 삭제 대상은 **writer 가 영영 없는 legacy `bl:` 뿐**이다 ⑥ **(ship 단계 grep 증명 중 발견)** 4서비스(order/product/payment/notification) `build.gradle` 의 `jjwt-api` testImplementation + "root signer 와 동일 HS256/app.jwt.secret" 주석 제거 — PR3d-a 가 서비스 측 사용자 토큰 검증을 없애면서 **사용처가 0** 이 됐다(`grep -rl jsonwebtoken <svc>/src` → 0). 주석이 이미 사라진 대칭키 서명을 가리키고 있어 잔재의 정의에 정확히 해당한다 ⑤ **ADR-0013 Update Log 추가**(`:10,14,29,30` 의 "HS256" → 실제 **HS512**. 사실 오류 정정이므로 README 규칙대로 Update Log + `fix(adr):` 커밋, `:65` 알고리즘 대안 비교는 결정 근거라 원문 유지).

### Layer 1 문서 동기화

- [x] **P13.** Layer 1 현재 상태 반영(부모 계획 P21 (a) 요구, **자식 계획 작성 시 누락 → 구현 중 추가**) — `docs/02-architecture.md` 의 Phase 4 k8s 트리에 `services/gateway/servicemonitor.yml`, 모듈 트리에 S9 컴포넌트(`GatewayAuthMetrics`·`observability/ForbiddenResponseMetricsFilter`·`GatewayObservabilityConfig`) 추가. `docs/04-design-deep-dive.md §10-2` 에 **거부 사유 관측 계약**(reason 은 메트릭에만 분해, 응답은 합쳐서) 한 단락. 결정 근거는 ADR-0024 참조로만 남긴다(Layer 1 은 What).

### 테스트

- [x] **P12.** 회귀 고정 — ① `GatewayAuthenticationFilterTest` 의 **`@Nested AuthFailureMetrics`**(별도 클래스 대신 기존 필터 하니스 재사용 — mock verifier/issuer/chain 이 이미 거기 있다): reason 각각이 해당 상황에서만 증가(사유 오분류 시 red), 전 reason series 가 부팅 시 0 으로 등록, 성공 요청은 0 ② `FailClosedRedisRateLimiterTest`: 한도 초과 시 429 counter +1 / Redis 장애 시 429 counter **불변**(503 축과 분리) ③ gateway 403 post-filter 테스트(다운스트림 403/200 스텁) ④ `UserObservabilityMetricsIntegrationTest` 에 reuse/logout counter 케이스 — **reuse 트랜잭션이 롤백되면 카운터도 증가하지 않음**(CommitAwareMetrics 계약) ⑤ gateway 관측성 통합테스트 신설: `/actuator/prometheus`(8081)에 `application="gateway"` 태그와 `http_server_requests_seconds_bucket` 이 **둘 다** 존재(S1/S2 편입 회귀) ⑥ HS512 제거 회귀: HS512 서명 토큰이 fallback 설정 없이 **401**, gateway 부팅 정상.

## 4. 영향 파일

- *신설*: `docs/adr/0024-observability-canonical-with-infra.md` · `gateway/.../auth/GatewayAuthMetrics.java` · `gateway/.../observability/ForbiddenResponseMetricsFilter.java` · `user-service/.../infrastructure/metrics/UserAuthMetrics.java` · `k8s/base/services/gateway/servicemonitor.yml` · gateway 관측성 통합테스트
- *수정*: `build.gradle`(allowlist) · `gateway/build.gradle` · `gateway/src/main/resources/application.yml` · `GatewayJwtVerifier`/`GatewayAuthenticationFilter`/`FailClosedRedisRateLimiter`/`TokenDenyLookup`/`JwtGatewayProperties` · `AuthService`/`JwtAuthProperties`/`TokenBlacklistPort`/`TokenBlacklistRepository` · `user-service/application.yml` · `k8s/base/services/gateway/deployment.yml` · `k8s/base/kustomization.yml` · `k8s/base/services/*/secret.yml`(5) · `k8s/monitoring/shared/grafana-alerts.yml` · `api-jvm-dashboard.json` · `kafka-lag-dashboard.json` · `scripts/{observability-promql-lint,servicemonitor-selector-lint,gateway-exposure-lint}.sh` · `.github/workflows/ci.yml` · `docs/adr/README.md` · `docs/adr/0013-*.md`(Update Log)
- *미포함(의도)*: 5서비스 애플리케이션 코드(403 은 gateway 관측) · NetworkPolicy(C-13) · overlay patch(P7 리소스는 환경 비종속) · Secret Manager/CSI(PR3d-b-2 소관)

## 5. 검증 방법

**실패를 주입한 뒤 상태로 확인한다 — "존재한다"는 검증이 아니다.**

| id | 주입 | 기대 |
|---|---|---|
| V-1 | 서명이 깨진 토큰 / 만료 토큰 / unknown kid / alg=HS256 을 각각 제시 | `auth_failure_total` 이 **서로 다른 reason** 으로 +1. 하나라도 `invalid_token` 으로 뭉치면 red |
| V-2 | 유효 토큰으로 정상 요청 | 모든 reason 의 counter **불변**(성공 경로 오염 탐지) |
| V-3 | 한도 초과 반복 호출 | `auth_ratelimit_rejected_total{route=...}` +N, `auth_failure_total` 불변 |
| V-4 | Redis 다운 후 호출 | `auth_failure_total{reason="rate_limiter_unavailable"}` +1, **429 counter 불변**(503/429 혼동 탐지) |
| V-5 | reuse 감지 트랜잭션을 강제 롤백 | `auth_token_reuse_detected_total` **불변**(CommitAwareMetrics 계약). 정상 커밋 시 +1 |
| V-6 | gateway `/actuator/prometheus`(8081) 스크랩 | `application="gateway"` 태그 **와** `http_server_requests_seconds_bucket` 이 동시 존재. 하나라도 없으면 red(= p95 NaN 예방) |
| V-7 | `gateway-metrics` Service 의 port 를 8080 으로 조작 / 3번째 Service 추가 / overlay 에서 LB 승격 | `gateway-exposure-lint` self-test 가 각각 red |
| V-8 | `servicemonitor.yml` 1개 삭제 / selector 오타 / endpoint port 오타 | `servicemonitor-selector-lint --self-test` 가 각각 red (현재는 self-test 자체가 없어 vacuous) |
| V-9 | alert regex 에서 `gateway` 제거 / scrape-absent rule 1개 삭제 / 식을 `0 * <식>` 으로 무력화 | `observability-promql-lint` 가 각각 red |
| V-10 | dashboard 변수에서 서비스 1개 제거 / 존재하지 않는 서비스 추가 | promql lint 의 **dashboard 검사**가 red (현재 무검증 표면) |
| V-11 | HS512 서명 토큰 제시 | 401. 설정 키(`hs512-*`)로 되살릴 수 없음(키 자체가 없음) |
| V-12 | 전 10모듈 `./gradlew build` + lint 전량 + self-test 전량 | 그린 |

## 6. 완료 조건

- [x] ADR-0024 Accepted + README 인덱스/ADR-0015 Status 동기
- [x] V-1~V-11 통과 · **V-12 부분 미충족** — 전 10모듈 테스트 중 `ProductCacheFallbackIntegrationTest.unresponsiveRedis_isBoundedByCommandTimeout` 1건 실패(1.648s > 1.5s 상한). 본 PR diff 에 product-service 소스가 없고 격리 재실행은 통과 → 부하 의존 타이밍 flake(D-019 동류). lint·self-test 전량 통과
- [x] gateway 메트릭이 `application="gateway"` 로 수집되고, 6-집합이 alert/dashboard/lint 세 곳에서 **정확 일치** (실 클러스터 scrape 증적은 §7 미해결)
- [x] HS512·legacy `bl:`·`app.jwt.secret`·`JWT_SECRET`(k8s 5) 잔재 **0건** — grep 증명: `hs512-fallback-enabled`/`hs512Secret`/`hs256-fallback-enabled`/`JWT_SECRET`/`LEGACY_PREFIX`/`app.jwt.secret` 각 0, `JwtAuthProperties.secret()` 참조 0
- [x] 상위 계획 §완료 조건의 PR4 항목 2줄 체크 (`task-impl3-spring-cloud-gateway.md`)

## 7. 미해결 / 범위 밖

- **auth failure rate alert 미도입**: 정상 운영의 401 기저율(만료 토큰 재시도 등)을 모르는 상태에서 임계를 정하면 그 숫자가 곧 거짓 계약이 된다. 메트릭을 먼저 쌓고 baseline 이 생기면 별도로 올린다. reuse 만 "발생 자체가 사건"이라 alert 로 넣는다(P8).
- **실 클러스터 scrape 증적 미확보**: SM 이 실제로 gateway 8081 을 긁는지는 GKE 가 필요하다. PR3d-b-2 와 **같은 세션**에서 확인한다(진입 조건 동일: GKE 재기동 + CSI Driver + Secret Manager 키).
- **gateway 8081 의 클러스터 내부 도달 범위**: NP 가 gateway Pod 를 선택하지 않아(C-13) 네임스페이스 내 어떤 Pod 든 관리 포트에 닿는다. 본 PR 이 만든 상태가 아니라 PR3b 이래의 기존 상태이므로 여기서 바꾸지 않는다 — 좁히려면 `component: gateway` 대상 NP 신설이 필요하고, 그건 ADR-0013 D3 신뢰 경계의 확장이다.
- **`TokenBlacklistPort.addToBlacklist` 는 호출자 0건으로 남는다**(위 P11 ④). header-trust 전환으로 로그아웃이 family deny 로 바뀌면서 비어버린 경로이고, 되살릴지(per-token deny) 접을지는 blacklist 표면 자체를 다루는 별도 결정이다. 본 PR 은 그 결정을 하지 않고 사실만 주석으로 남긴다.
- **`http.server.requests` 의 WebFlux ↔ MVC 라벨 차이**: 같은 이름이지만 `uri` 라벨의 템플릿화 방식이 다르다. 본 PR 은 error-rate(`status`)·latency(`le`) 만 쓰므로 영향 없으나, 향후 `uri` 별 패널을 만들면 gateway 행이 다르게 보일 수 있다.
