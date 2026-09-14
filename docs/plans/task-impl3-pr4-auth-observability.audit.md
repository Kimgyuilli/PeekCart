# task-impl3-pr4-auth-observability — 계획 audit

## 2026-09-14 — 계획 수립 (Codex 리뷰 미실행)

- **Codex 리뷰 호출 안 함** (사용자 지시). 따라서 본 계획에 "P0/P1 = 0" 주장은 없다.
- 착수 전 코드 검증 16건(C-1~C-16) 수행 — §2.1 표.
- **범위가 커진 지점**:
  - C-3/C-4 — gateway 에 S1(histogram)·S2(application 태그)가 **둘 다 없음** → alert 편입 전에 관측성 기반부터 깔아야 함(P2 신설). 루트 가드 allowlist 확장 동반.
  - C-6 — 인증 실패가 전부 `InvalidTokenException` 1종이라 ADR-0009 S9 의 "서명오류/만료" 분리가 코드상 불가 → reason enum 부여(P3).
  - C-11/2.2 — 단일 ground truth(5)가 gateway 편입으로 **세 집합(태그 6 / Service name 6 / SM name 6, 원소 상이)** 으로 갈라짐 → lint 상수 분해(P10). 이 분해 없이 6으로만 늘리면 집합끼리 오탐.
  - C-12 — `gateway-exposure-lint` 의 "Service 정확히 1개"가 `gateway-metrics` 추가와 정면 충돌 → 2개 allow-list 계약으로 재작성.
  - C-16 — dashboard 는 promql lint 가 **전혀 읽지 않는** 무검증 표면이고, `kafka-lag` 변수에 소비자 0건인 `user-service` 가 들어 있음 → dashboard 검사 신설 + 정정(P9/P10).
- **범위가 줄어든 지점**:
  - C-14/C-15 — HS512 fallback 은 발급 주체(`JwtTokenSigner`)가 이미 RS256 단독이고, legacy `bl:` 는 **writer 0건**. 상위 계획 P22 가 전제한 "access token 최대 TTL 경과 증명 게이트"가 **불필요**해짐.
  - C-13 — NetworkPolicy 는 gateway Pod 를 선택하지 않으므로 scrape 를 위한 NP 변경 0건.
- **뒤집힌 전제**: 상위 계획 P21 의 "ADR 본문 수정 금지 — 존재/owner 정합만 검증"은 S9 에 한해 맞지만, canonical 5→6 은 ADR-0015 §Decision 의 계약 변경이라 **신규 ADR-0024 가 필요**(사용자 결정으로 P1 에 포함).

## 2026-09-14 — 구현 (Codex diff 리뷰 미실행)

- **Codex diff 리뷰 호출 안 함** (사용자 지시). 이 PR 에 "P0/P1 = 0" 주장은 없다.
- P1~P13 구현 완료. 계획 대비 **변경 3건**(계획서를 먼저 고치고 진행):
  1. **P11 ④ 방침 변경** — `TokenBlacklistPort.addToBlacklist` 를 삭제하지 않고 "호출자 0건"을 javadoc 으로 명시.
     삭제하면 gateway 의 `auth:blacklist:` **read 는 남는데 write 만 사라져** 계약의 반쪽이 죽는다.
     실제 삭제 대상은 writer 가 영영 없는 legacy `bl:` 하나였다.
  2. **P10 에 `observability-ssot-lint` 추가** — S2 검사 대상에 gateway base yml 편입. 안 하면 gateway
     태그가 `application-k8s.yml` 에서 덮여도 아무도 모른다(음성 확인 완료: 재선언 시 D5-V1 red).
  3. **P13 신설** — 부모 계획 P21 (a) 의 Layer 1(`02`/`04`) 동기화가 자식 계획 작성 시 누락돼 있었다.
- **구현 중 뒤집힌 전제 1건**: "`:peekcart-common-observability` 를 의존하면 S1 이 배선된다"가 **거짓**이었다.
  `MetricsConfig` 는 `com.peekcart.global.config` 에 있고 gateway 의 스캔 기점은 `com.peekcart.gateway` 라
  빈이 등록되지 않는다. 증상은 조용하다 — `http_server_requests_seconds` 는 발행되는데 `_bucket` 만 없어
  **p95 alert 만 NaN** 이 된다. `GatewayObservabilityConfig`(@Import)로 해소. 이 전제는 **실제 스크랩
  출력을 검사하는 통합테스트(P12 ⑤)가 잡았다** — MeterRegistry 를 직접 봤다면 통과했을 것이다.
- **검증**:
  - 전 10모듈 `./gradlew test` — **1건 실패**: `ProductCacheFallbackIntegrationTest.unresponsiveRedis_isBoundedByCommandTimeout`
    (1.648s > 1.5s 상한). **본 PR 과 무관한 부하 의존 타이밍 flake** — product-service 소스는 diff 에 없고
    (k8s secret.yml 의 미사용 `JWT_SECRET` 제거만), 격리 재실행 시 BUILD SUCCESSFUL. D-019 와 같은 종류.
  - lint: gateway-exposure(+self-test 29종) · servicemonitor-selector(+**신설** self-test 6종) ·
    observability-ssot · observability-promql(+self-test **17종**) · image-contract · kustomize-namespace ·
    networkpolicy-contract · internal/workload-key-ownership · dockerfile-module-sync · dead-letter-schema-parity ·
    replay-entrypoint · migration-grant · saga-contract-matrix · ci-test-matrix — **전부 통과**.
    (`observability-ssot-lint` 은 self-test 모드가 없다 — 인자를 무시하고 본 검사만 돈다.)
  - 렌더: minikube/gke `kubectl kustomize` OK.
- **미충족**: 실 클러스터 scrape 증적(SM 이 실제로 8081 을 긁는지)은 PR3d-b-2 와 같은 세션 몫. 리뷰 수렴 미판정.

## 2026-09-14 — /ship

- PR: https://github.com/Kimgyuilli/PeakCart/pull/108 (신규, base=main, 미머지)
- precheck: `ok` (warnings 0)
- 커밋 7개 — `fix(adr)` / `docs(adr)` / `feat(gateway)` / `feat(observability)` / `chore(auth)` / `test` / `docs(plan)`
- ship 단계 grep 증명에서 **추가 발견 1건**: 4서비스의 `jjwt-api` test dep 이 PR3d-a 이후
  사용처 0(`grep -rl jsonwebtoken <svc>/src` → 0). 주석이 "root signer 와 동일 HS256/app.jwt.secret"
  을 가리키고 있어 잔재 정의에 해당 → P11 ⑥ 으로 계획에 추가 후 제거.
- 갱신: `docs/TASKS.md` ③ 행(PR4 ✅ · ③ 자체는 PR3d-b-2 때문에 🔄 유지) ·
  `phase4-prep-debt-roadmap.md`(L-019 ✅, L-002 는 PR3d-b-2 종결 예정) · `PHASE4.md` 이력 ·
  부모 계획 P20~P23 + 완료 조건 2줄 · Layer 1(`02`/`04`)
