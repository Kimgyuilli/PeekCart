# 계획 — User 도메인 키 회전 실증 (로컬 드릴)

> 대상: `docs/runbooks/user-jwt-key-rotation.md` §6 이 "미검증" 으로 남긴 **User 고유 축**.
> 환경: minikube (사용자 결정 2026-09-15). GKE 재프로비저닝 기각 — 이 축은 k8s Service
> 의미론이라 로컬에서 동일하게 재현되고, Secret Manager/WI 축은 세션 2(P11/P12)가 이미 실증했다.

## 0. 무엇을 증명하려는가

구현 ③ PR3d-b-2 세션 2 는 **내부 토큰 도메인**의 회전을 완주했다. User 도메인은 기전이 같지만
**고유 축이 하나 있다** — runbook §1.1:

> gateway 는 모르는 kid 를 만나면 JWKS 를 다시 받는다(`JwksKeyRegistry`). 그 요청은
> `user-service` **Service** 로 가므로 **어느 Pod 가 응답할지 보장되지 않는다.**

내부 토큰은 소비자(gateway)가 공개키를 ConfigMap 으로 **직접** 받지만, User 는 소비자가
**발급자의 Pod 들에게 물어본다**. 그래서 불변식의 기준이 다르다 — "모든 user-service Pod 의
JWKS" 다. §3.1 전수 확인이 그 축을 막기 위한 gate 이고, **그 gate 의 유효성 자체가 미검증**이다.

**증명 대상 2개**:
- G1. §1.1 의 401 구간이 실재하는가 (순서를 역전하면 실제로 열리는가) — **음성 대조군**
- G2. §3.1 전수 확인이 그 상태를 **사전에 막는가** (gate 유효성)

G1 없이 G2 만 통과시키는 것은 "안 깨지는 걸 안 깨진다고 확인" 하는 것이라 증거 가치가 없다.

## 1. 사전 확인 (완료 — 코드 기준)

| 확인 | 결과 | 근거 |
|---|---|---|
| JWKS 가 Service 경유인가 | ✅ 그렇다 | `gateway/src/main/resources/application.yml:279` `jwks-uri: http://user-service:8080/...` |
| refresh cooldown | ✅ PT10S | 같은 파일 :281 |
| §2③ 의 `APP_JWT_RS256_ACTIVEKID` 가 바인딩되는가 | ✅ 된다 | `user-service/deployment.yml:62` `envFrom: user-jwt-binding` + `JwtKeyProperties.activeKid` relaxed binding |
| 정상 상태 Pod 수 | replicas 1 | `base/services/user-service/deployment.yml:11` — **단 롤링 중 구/신 공존**하므로 skew 창은 열린다 |
| minikube overlay 가 CSI 를 대체하는가 | ❌ 안 한다 | overlay 최종 수정 `3ed4fb4`(PR3c) < CSI 도입 `c5dadc9` — **드릴 선행 작업** |

## 2. 단계

| # | 작업 | verify | 결과 |
|---|---|---|---|
| P1 | minikube 기동 + 드릴 overlay(CSI→Secret, ServiceMonitor 제외) | 두 Pod Running · JWKS 200 | ✅ |
| P2 | 양성 대조군 | kid=`peekcart-dev-2026` · protected=200 | ✅ |
| P5' | **G1 1차 시도** — 금지된 순서(①+③ 동시) + 롤아웃 중 연속 요청 | 401 관측 | ❌ **음성** — 롤아웃이 skew 창보다 빨라 첫 신 kid 발급 시점에 이미 수렴 |
| P5'' | **방법 변경** — skew 상태 고정(신 1:구 3) 후 gateway 5회 재시작 × ~25 로그인 | 401 관측 | ❌ 음성(121/121 구 kid) → **발견**: gateway 가 커넥션 풀링으로 한 Pod 에 고정 |
| P5''' | **G1 결정적 재현** — 로그인/JWKS 경로를 각각 Pod IP 로 고정(D1/D2) | 단일 변수 인과 | ✅ **D1 401×10/10 ↔ D2 200×10/10** |
| P6 | **G2** — 같은 skew 상태에서 §3.1 | 음성을 음성으로 판정 | ✅ 구 kid 전용 Pod 3개 노출, 차단 |
| P3·P4 | 정상 순서 ① → §3.1 | 전 Pod 2 kid · gate 통과 | ✅ `gate_exit=0`, 발급은 구 kid·200 |
| P7 | ③ 전환(active-kid+개인키 동시) + 롤아웃 전 구간 연속 요청 | 신 kid + 200 | ✅ **401=0건**(79회) |
| P8 | §3.3 정합 · ⑤ 정리 · lint | modulus 일치 · lint green | ✅ |
| P9 | 증적 + runbook §1.1/§3.1/§6 갱신 + TASKS/PHASE4 | 문서 반영 | ✅ |

> **계획 대비 실제**: P5 를 "롤아웃 중 관측" 으로 잡은 것이 틀렸다. 그 축은 레이스라 1회 표본으로
> 판정할 수 없고, 6회 시도가 전부 음성이었다. 경로를 고정해 레이스를 제거한 뒤에야 인과가
> 확정됐다. 그 과정에서 계획에 없던 발견(커넥션 고정)이 나왔고, 그것이 runbook §1.1 정정으로 갔다.

## 3. 한계 (미리 적어둔다)

- **Secret Manager 버전 등록·CSI·Workload Identity 는 재현하지 않는다.** 드릴 overlay 가 k8s
  Secret 으로 대체한다. 그 축은 세션 2 가 내부 토큰 도메인에서 실증했다(증적
  `evidence/pr3d-b2-gke-20260915-0854.md`). 따라서 이 드릴은 **"§2 전 절차의 운영 완주"가
  아니라 "§1.1/§3.1 축의 실증"** 이다. 결론을 그 범위로만 쓴다.
- **ADR-0003(minikube)은 Deprecated** 다. 드릴 overlay 는 minikube 를 지원 환경으로 되살리는
  것이 아니라 **드릴 전용 하네스**다. 이름·주석으로 그것을 명시한다.
- `NetworkPolicy` enforcement 는 minikube CNI 미보장(기존 주석대로) — 이 드릴의 축이 아니다.

---

## 종결

머지 PR: [#116](https://github.com/Kimgyuilli/PeakCart/pull/116) — User 토큰 회전 축 실증 로컬 드릴

계획서 아카이브 판정을 위해 PR 링크를 명시한다. 작업은 위 PR 로 종결됐고 본문의
체크박스 상태는 당시 갱신되지 않은 것이라 완료 여부의 근거가 아니다.
