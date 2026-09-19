# User 토큰 회전 로컬 드릴 — §1.1/§3.1 축 실증 (2026-09-15T11:40Z)

> 계획서: `docs/plans/done/task-user-key-rotation-local-drill.md`
> 대상: `docs/runbooks/user-jwt-key-rotation.md` §6 이 "미검증"으로 남긴 **User 고유 축**
> 선행: 구현 ③ PR3d-b-2 세션 2 (`pr3d-b2-gke-20260915-0854.md`) — 내부 토큰 도메인 회전 실증

- minikube v1.38.1 (k8s v1.35.1) · docker driver · 4 vCPU / 6.5GB · NS `peekcart`
- overlay `k8s/overlays/minikube-rotation-drill` (드릴 전용 하네스 — 지원 환경 아님)
- 스택: mysql · redis · user-service · gateway **만**. kafka/4서비스/ServiceMonitor/CSI/NetworkPolicy 제외
- 키: 구 `peekcart-dev-2026`(= `local-keys/dev-jwt-private.pem`, base ConfigMap 공개키와 모듈러스 일치)
  · 신 `peekcart-drill-2026`(드릴 중 생성, 산출물 미포함)

## 무엇을 증명했나

| | 주장 | 결과 |
|---|---|---|
| **G1** | §1.1 의 401 구간이 실재한다 | ✅ **확증** — 결정적 인과쌍 (아래 D1/D2) |
| **G2** | §3.1 전수 확인이 그 상태를 사전에 막는다 | ✅ **확증** — 고정 skew 상태에서 gate 가 차단 |

---

## 양성 대조군 (P2)

```
signup=201
kid={"kid":"peekcart-dev-2026","alg":"RS256"}
protected=200        # GET /api/v1/users/me
```

> runbook §3.2 는 보호 경로로 `/api/v1/orders` 를 쓰지만 order-service 는 이 스택에 없다 →
> `/api/v1/users/me` 로 대체했다.

---

## 1차 시도는 실패했다 — 롤아웃이 skew 창보다 빨랐다

금지된 순서(① 선배포와 ③ 전환을 **한 번에**)를 적용하고 롤아웃 중 1초 간격으로 요청했다.
기대는 401 이었으나 **나오지 않았다**:

```
11:12:31 kid=peekcart-drill-2026 protected=200
```

gateway 로그가 이유를 보여준다 — 첫 신 kid 토큰이 발급된 바로 그 순간에 이미 전 Pod 가 수렴해 있었다:

```
11:12:31.098  JWKS snapshot 교체: [peekcart-dev-2026] -> [peekcart-drill-2026, peekcart-dev-2026]
```

**이 축은 1회 표본으로 판정할 수 없는 레이스다.** 여기서 멈추고 결정적 재현으로 바꿨다.

---

## 발견 ①: gateway 는 Service 뒤 특정 Pod 에 **고정**된다 (runbook §1.1 의 기술이 부정확)

skew 상태를 고정하고(신 바인딩 Pod 1 : 구 바인딩 Pod 3) gateway 를 5회 재시작하며 각 시행마다
~25회 로그인했다. **121회 전부 구 kid** 였다:

| 시행 | 결과 |
|---|---|
| 1 | 23× `kid=peekcart-dev-2026 code=200` · 1× login=FAIL |
| 2 | 24× `kid=peekcart-dev-2026 code=200` |
| 3 | 25× `kid=peekcart-dev-2026 code=200` |
| 4 | 24× `kid=peekcart-dev-2026 code=200` |
| 5 | 25× `kid=peekcart-dev-2026 code=200` |

요청마다 무작위 분배라면 신 Pod 를 한 번도 안 맞을 확률은 `0.75^121 ≈ 10^-15` 다. 대조 실험이
원인을 확정한다 — **같은 Service 를** 요청마다 새 커넥션으로 20회 부르면 분배가 된다:

```
직접 호출(curl 프로세스마다 새 커넥션) x20 → drill kid 7 / dev kid 13   (기대 25%)
gateway 경유(커넥션 풀 재사용) x121        → drill kid 0
```

**runbook §1.1 은 "어느 Pod 가 응답할지 보장되지 않는다"고 쓰여 있지만, 실제 동작은 요청마다의
무작위가 아니라 커넥션 단위 고정이다.** 이것이 실무적으로 더 나쁘다:

- 한 번 구 Pod 에 물리면 **그 상태가 지속된다** — `jwks-refresh-cooldown: PT10S` 를 기다려 재시도해도
  같은 커넥션을 재사용하므로 같은 Pod 에 간다. 자가치유를 기대할 수 없다.
- 반대로 운 좋게 신 Pod 에 물리면 그 gateway 인스턴스는 **끝까지 정상**이다 → 장애가 gateway
  인스턴스별로 갈린다(일부 사용자만 401). 1차 시도에서 401 을 못 본 것도 이 때문이다.
- 로그인 경로와 JWKS 경로는 **별개 커넥션 풀**이라 서로 다른 Pod 에 물릴 수 있다. 그 조합이
  정확히 §1.1 의 장애다.

## G1 — 결정적 인과쌍 (D1/D2)

두 경로를 각각 Pod IP 로 고정해 레이스를 제거했다. **토큰 kid 는 양쪽 동일**, JWKS 출처만 다르다:

| | 로그인 경로 | JWKS 경로 | 결과 |
|---|---|---|---|
| **D1** | 신 Pod `10.244.0.21` | **구** Pod `10.244.0.23` | `kid=peekcart-drill-2026 code=401` **10/10** |
| **D2** | 신 Pod `10.244.0.21` | 신 Pod `10.244.0.21` | `kid=peekcart-drill-2026 code=200` **10/10** |

단일 변수만 바뀌었으므로 **401 의 원인은 JWKS 응답 Pod 이다.** §1.1 확증.
D1 이 10초(cooldown)를 넘겨 10회 연속 401 인 점도 위의 "자가치유 없음"을 뒷받침한다.

## G2 — §3.1 전수 확인이 그 상태를 막는가

D1/D2 와 **같은 skew 상태**에서 §3.1 을 돌렸다:

```
user-service-796866775f-8k8pw       -> peekcart-drill-2026,peekcart-dev-2026
user-service-stale-64b47cd475-4sjlb -> peekcart-dev-2026
user-service-stale-64b47cd475-ddb2t -> peekcart-dev-2026
user-service-stale-64b47cd475-gpz8m -> peekcart-dev-2026
gate_exit=1   (차단)
```

gate 가 "구 kid 만 가진 Pod 3개"를 그대로 드러냈다. **§3.1 을 ③ 진입 전에 돌렸다면 이 회전은
막혔다.** gate 유효성 확증.

> 전제: §3.1 이 **Service 가 아니라 Pod IP 로 직접** 묻기 때문에 성립한다. 발견 ①(커넥션 고정)은
> Service 경유 N회 조회가 왜 무의미한지를 추가로 설명한다 — 로드밸런싱조차 안 되고 한 Pod 만 본다.

---

## 정상 순서 완주 (①~⑤)

| 단계 | 조작 | 검증 |
|---|---|---|
| ① 선배포 | 신 공개키 `_1_` 추가, active-kid·개인키는 **구** 유지 → 롤링 | 수렴 |
| ② gate | §3.1 전수 확인 | **전 Pod 2 kid · `gate_exit=0` 통과** · 발급은 여전히 구 kid, 200 |
| ③ 전환 | active-kid + 개인키를 **함께** 신 키로 → 롤링 | 신 kid 발급 + 200 |
| ⑤ 정리 | 구 공개키·`_0_` 제거, 신 키를 `_0_` 로 재번호 → 롤링 | JWKS = 신 kid 단독, 200 |

③ 롤아웃 **전 구간 연속 요청**(1초 간격, 79회):

```
70x kid=peekcart-dev-2026   code=200
 7x kid=peekcart-drill-2026 code=200
 2x login=FAIL            (Pod 교체 churn — 401 아님)
401 = 0건
```

kid 전환 경계(`11:39:16`)를 포함해 **401 은 한 건도 없다.** 순서를 지키면 §1.1 의 창이 열리지 않는다.

§3.3 키쌍 정합 (③ 이후):

```
private: zV-V2r_A1QoeSkEMuTZVOfkwrVp_gOzn6_Oc0ggu
jwks   : zV-V2r_A1QoeSkEMuTZVOfkwrVp_gOzn6_Oc0ggu   kid=peekcart-drill-2026
jwks   : m_giFVAWs6E18uYDDkCYx-zqPs1XirQa_ugvFfZn   kid=peekcart-dev-2026
```

`workload-key-ownership-lint` green (minikube, gke).

---

## 발견 ②: `jwks` gate 스크립트는 **② 전용**이다

`scripts/drill/user-key-rotation-drill.sh jwks` 의 통과 조건은 "kid 가 2개"다. 그래서 ⑤ 정리 이후
(kid 1개)에도 `gate_exit=1` 이 난다 — 이건 결함이 아니라 **overlap 구간 전용 gate** 라는 뜻이다.
runbook §2 도 ② 에서만 이 gate 를 요구한다. 범용 헬스체크로 오용하지 말 것.

---

## 미충족 / 한계

- **Secret Manager 버전 등록 · CSI · Workload Identity 미재현** — 드릴 overlay 가 k8s Secret 으로
  대체한다. 그 축은 세션 2 가 내부 토큰 도메인에서 GKE 로 실증했다. 따라서 이 드릴의 결론은
  "§2 전 절차의 **운영** 완주"가 아니라 **"§1.1/§3.1 축의 실증"** 으로만 읽어야 한다.
- **드릴 overlay 는 `workload-key-ownership-lint` 범위 밖이다**(스크립트가 `minikube`/`gke` 만
  하드코딩). 개인키 SPC 배타 소유 규칙을 의도적으로 위반하므로 **운영 매니페스트로 승격 금지**.
- **자연 롤아웃에서의 401 은 재현 못 했다** — 1차 시도 1회, 고정 skew 5회 시행 전부 음성.
  G1 은 경로를 고정한 D1/D2 로 확증했고, "그 상태에 **도달 가능**" 은 직접 호출 분배(7/20)로
  보였다. 자연 롤아웃에서의 **발생 빈도**는 측정하지 않았다.
- **replicas 1 의 실제 운영 값에서는 창이 더 좁다** — 이 드릴은 3~4 Pod 로 키웠다.
  base `user-service` 는 `replicas: 1` 이고 HPA 대상도 아니다(§드릴 전제와 다름).
- **Codex 리뷰 미호출** — "P0/P1 = 0" 주장 없음.
