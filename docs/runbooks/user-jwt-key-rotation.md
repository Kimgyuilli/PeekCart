# Runbook — 사용자 토큰 서명키 회전 (User RS256)

> 대상: `app.jwt.rs256` 키쌍 교체 — user-service 가 서명하고 gateway 가 JWKS 로 검증하는 키
> 전제: `user-jwt-public-keys` / `user-jwt-binding` ConfigMap (구현 ③ 후속)
> **내부 토큰 회전(`docs/plans/done/task-impl3-pr3d-internal-token.md` §11)과 순서는 같지만 성질이 다르다.**
> 그대로 베껴 쓰면 안 되는 이유가 §1 이다.

---

## 0. 30초 요약

| 상황 | 할 일 |
|---|---|
| 정기 회전을 한다 | §2 를 ①→⑤ 순서대로. **역전 금지** |
| 키가 유출됐다 (긴급) | §5 — 30분을 기다릴 수 없다. 트레이드오프가 다르다 |
| 회전 중인데 401 이 쏟아진다 | §4.1 — 대개 JWKS 선배포가 전 Pod 에 안 퍼진 상태다 |
| 회전 중인데 503 이 난다 | §4.2 — JWKS 조회 자체가 실패. 키 문제가 아니다 |
| 개인키만 바꿨다 | §4.3 — **가장 흔한 사고**. 공개키는 쌍으로 바꿔야 한다 |

---

## 1. 내부 토큰 회전과 무엇이 다른가

같은 "선배포 → 수렴 → 전환 → 대기 → 정리" 형태지만, **소비자가 키를 배우는 경로가 다르다**.

| | 내부 토큰 | 사용자 토큰 (이 문서) |
|---|---|---|
| 소비자 | 5 도메인 서비스 | gateway 하나 |
| 키를 배우는 경로 | ConfigMap → env → 부팅 시 로드 | **JWKS HTTP 조회** (`/.well-known/jwks.json`) |
| 갱신 시점 | Pod 재시작 | **unknown kid 를 만난 순간 on-demand** |
| 토큰 수명 | `ttl-seconds: 30` | `access-token-expiry: 1800000` = **30분** |
| 조회 대상 | (없음) | `http://user-service:8080` — **Service, 즉 로드밸런싱됨** |

여기서 두 가지 위험이 나온다.

### 1.1 JWKS 는 Service 를 통해 조회된다 — refresh 가 구 Pod 에 떨어질 수 있다

gateway 는 모르는 kid 를 만나면 즉시 JWKS 를 다시 받는다(`JwksKeyRegistry`). 그 요청은
`user-service` **Service** 로 가므로 어느 Pod 가 응답할지 보장되지 않는다.

> **[실측 정정 2026-09-15]** 이것은 요청마다의 무작위가 **아니다**. gateway 는 커넥션을 풀링하므로
> **한 Pod 에 고정**된다 — 로컬 드릴에서 gateway 경유 로그인 121회가 전부 같은 Pod 에서 나왔고
> (무작위라면 `0.75^121`), 같은 Service 를 요청마다 새 커넥션으로 부르면 20회 중 7회가 다른 Pod 로
> 갔다. 결과가 더 나쁘다:
> - 한 번 구 Pod 에 물리면 **지속된다.** 아래 cooldown 을 기다려 재시도해도 같은 커넥션이라 같은
>   Pod 로 간다 — **자가치유를 기대하지 말 것.**
> - 반대로 신 Pod 에 물린 gateway 인스턴스는 끝까지 정상이다 → **일부 사용자만 401** 인 형태로
>   나타나고, gateway 인스턴스마다 갈린다. "전 요청 401" 을 장애 신호로 기다리면 놓친다.
> - 로그인 경로와 JWKS 경로는 **별개 커넥션 풀**이다. 그 둘이 서로 다른 Pod 에 물린 조합이 이 장애다.
>
> 증적: `docs/progress/evidence/user-key-rotation-drill-20260915-2040.md`

롤링 재시작 중에 active-kid 를 바꾸면 이런 상태가 생긴다:

```
pod A (구 설정) — 구 kid 로 서명, JWKS 에 구 키만 게시
pod B (신 설정) — 신 kid 로 서명, JWKS 에 신 키 게시

① 사용자가 pod B 에서 로그인 → 신 kid 토큰 발급
② gateway 가 신 kid 를 모름 → JWKS refresh
③ 그 요청이 pod A 에 떨어짐 → 신 키가 없는 JWKS 를 받음
④ UnknownKidException → 401
```

`jwks-refresh-cooldown: PT10S` 때문에 재시도도 최소 10초 뒤다. 그래서 **신 공개키가 전 Pod 의
JWKS 에 실린 뒤에야** active-kid 를 바꿔야 한다. 그게 §2 의 ①~② 다.

### 1.2 토큰이 30분 산다 — 구 공개키를 일찍 빼면 30분치 세션이 끊긴다

내부 토큰은 30초라 ④ 대기가 사실상 즉시지만, 사용자 토큰은 **30분**이다. ⑤(구 키 제거)를
서두르면 아직 유효한 토큰을 들고 있는 사용자가 전부 401 을 받는다. 재로그인으로만 복구된다.

---

## 2. 정기 회전 절차 (역전 금지)

> **불변식**: 어느 시점에도 "user-service 가 서명하는 kid" ⊆ "**모든** user-service Pod 의 JWKS 가
> 게시하는 kid" 여야 한다. 내부 토큰의 불변식과 달리, 소비자(gateway)가 아니라 **발급자 자신의
> 모든 Pod** 가 기준이다 — gateway 는 그 Pod 들에게 물어보기 때문이다.

| 단계 | 작업 | 다음 단계 진입 gate |
|---|---|---|
| ① 선배포 | `user-jwt-public-keys` ConfigMap 에 **신 공개키 파일 추가**(구 키 유지) + `user-jwt-binding` 에 `APP_JWT_RS256_PUBLICKEYS_1_KID` / `_1_LOCATION` 추가. **active-kid 는 건드리지 않는다** | — |
| ② 수렴 + JWKS 전수 확인 | user-service 롤링 재시작 → 전량 수렴 → **Pod 마다 개별로** JWKS 를 조회해 두 kid 가 모두 있는지 확인 (§3.1) | `rollout-convergence-gate.sh --workloads user-service` **+ §3.1 전수 통과** |
| ③ 전환 | Secret Manager 의 개인키 새 버전 등록 + `APP_JWT_RS256_ACTIVEKID` = 신 kid → user-service 롤링 | 수렴 gate + §3.2 (신 kid 로 발급되는지) |
| ④ 대기 | 구 kid 로 서명된 토큰이 전부 만료될 때까지: **access-token-expiry(30분) + 여유**. 경과 시각을 기록한다 | 시각 기록 |
| ⑤ 정리 | ConfigMap 에서 **구 공개키 파일·`_0_` 쌍 제거**(신 키를 `_0_` 로 재번호) → 롤링 | ② 와 동일 gate + `workload-key-ownership-lint` |

**③ 에서 개인키와 active-kid 를 함께 바꾼다.** 둘은 같은 키쌍의 양면이라 분리하면 §4.3 이 된다.

---

## 3. 검증

### 3.1 JWKS 전수 확인 (② gate — 이 runbook 의 핵심)

Service 경유가 아니라 **Pod IP 로 직접** 물어야 한다. Service 로 N번 때리는 방식은 로드밸런싱
때문에 특정 Pod 를 보증하지 못한다(내부 토큰 서명 probe 와 같은 이유).

```bash
# VPC 내부(loadgen VM 등)에서 실행한다.
NS=peekcart
for p in $(kubectl -n $NS get pods -l app.kubernetes.io/name=user-service \
             -o jsonpath='{range .items[*]}{.status.podIP}{"\n"}{end}'); do
  kids=$(curl -s --max-time 5 "http://$p:8080/.well-known/jwks.json" \
         | python3 -c 'import sys,json; print(",".join(k["kid"] for k in json.load(sys.stdin)["keys"]))')
  echo "$p -> $kids"
done
```

**전 Pod 가 구·신 kid 를 모두 보여야** ③ 으로 간다. 하나라도 구 kid 만 있으면 §1.1 의 401 구간이
열린다.

> **이 gate 는 ② 전용이다.** 통과 조건이 "kid 2개"이므로 ⑤ 정리 이후(kid 1개)에는 당연히 실패한다.
> 범용 헬스체크로 쓰지 말 것.
>
> **[실측 2026-09-15]** 이 gate 의 유효성을 음성 대조군으로 확인했다 — 신 바인딩 Pod 1 : 구 바인딩
> Pod 3 인 상태를 고정하고 돌렸더니 구 kid 만 가진 Pod 3개를 그대로 드러냈다(차단). 같은 상태에서
> 경로를 고정해 재현한 401 도 함께 확증했다(D1 10/10 401 ↔ D2 10/10 200, JWKS 출처만 상이).
> **Service 경유로 N번 때리는 방식이 무의미한 이유는 로드밸런싱이 안 되기 때문**이기도 하다(§1.1 정정).

### 3.2 발급 kid 확인 (③ gate)

```bash
# 로그인해서 받은 access token 의 헤더 kid 를 본다.
TOKEN=$(curl -s -X POST "$GW_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
          -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
        | sed -n 's/.*"accessToken"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
echo "$TOKEN" | cut -d. -f1 | base64 -d 2>/dev/null; echo
# 그 토큰으로 보호 경로가 200 이어야 한다 — 양성 대조군 없이 "회전 성공" 을 주장하지 않는다.
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" "$GW_URL/api/v1/orders"
```

### 3.3 키쌍 정합 확인 (개인키 ↔ JWKS)

**개인키만 바꾸는 사고(§4.3)를 미리 잡는다.** 모듈러스를 직접 대조한다:

```bash
# Pod 에 마운트된 개인키의 모듈러스
kubectl -n peekcart exec deploy/user-service -- cat /etc/peekcart/user-keys/jwt-private.pem \
  | openssl rsa -modulus -noout | sed 's/Modulus=//' \
  | xxd -r -p | base64 | tr -d '\n' | tr '+/' '-_' | tr -d '=' | head -c 40; echo

# JWKS 가 게시하는 n (active kid 의 것)
kubectl -n peekcart exec deploy/user-service -- \
  sh -c 'wget -qO- http://localhost:8080/.well-known/jwks.json' \
  | python3 -c 'import sys,json; [print(k["kid"], k["n"][:40]) for k in json.load(sys.stdin)["keys"]]'
```

앞 40자가 일치해야 한다. 어긋나면 **발급한 토큰을 아무도 검증할 수 없다**.

---

## 4. 장애 대응

### 4.1 회전 중 401 이 쏟아진다

거의 항상 **①~② 가 완료되지 않은 채 ③ 으로 넘어간 경우**다. gateway 가 신 kid 를 모르고,
refresh 가 구 Pod 에 떨어져 계속 못 찾는다(§1.1).

> **`active-kid` 만 되돌리면 복구되지 않는다.** ③ 은 `active-kid` 와 **개인키를 함께** 바꾸므로
> 되돌림도 둘 다여야 한다. kid 만 되돌리면 서비스는 **신 개인키로 서명된 토큰에 구 kid 라벨**이
> 붙은 것을 받아 계속 401 이다. 구현 ③ PR3d-b-2 세션에서 실측으로 확인했다(내부 토큰 도메인이지만
> 기전은 동일).

1. **개인키를 먼저 되돌린다** — Secret Manager 에 **구 키를 새 버전으로 다시 올린다**.
   ```bash
   gcloud secrets versions add peekcart-user-jwt-signing-key --data-file=<구 개인키.pem>
   ```
   **버전 비활성화(`versions disable`)로 되돌리지 말 것** — `versions/latest` 는 비활성 버전을
   건너뛰지 않고 그대로 가리키다 마운트를 통째로 실패시킨다:
   ```
   FailedPrecondition: Secret Version [.../versions/N] is in DISABLED state.
   ```
   그러면 401 이 아니라 Pod 이 `ContainerCreating` 에 고착된다 — 증상이 더 나빠진다.
2. **`APP_JWT_RS256_ACTIVEKID` 를 구 kid 로 되돌린다.** 구 공개키는 ① 에서 지우지 않았으므로
   JWKS 쪽은 손대지 않아도 된다.
3. 롤링 후 §3.3 으로 **개인키 ↔ JWKS 모듈러스 일치**를 확인한다. 이게 맞아야 복구다.
4. §3.1 로 전 Pod 의 JWKS 를 확인한다. 구·신이 다 보일 때까지 ③ 을 다시 시도하지 않는다.
5. `auth.failure{reason}` 메트릭에서 사유를 확인한다(ADR-0024 S9).

> 되돌림에 **이미지 교체는 없다** — env 하나와 Secret Manager 버전 하나다. 그 점은 내부 토큰
> rollback 과 같지만, "env 하나면 끝" 은 아니다.

### 4.2 회전 중 503 이 난다

401 이 아니라 503 이면 **kid 문제가 아니라 JWKS 조회 자체가 실패**한 것이다
(`JwksUnavailableException`). user-service 가 떠 있는지, Service 가 Pod 를 잡고 있는지,
`jwks-timeout: PT2S` 안에 응답하는지를 본다. 키를 되돌려도 낫지 않는다.

### 4.3 개인키만 바꿨다 (가장 흔한 사고)

Secret Manager 의 개인키만 새 버전으로 올리고 `user-jwt-public-keys` 는 그대로 둔 경우다.
user-service 는 **새 키로 서명하면서 옛 공개키를 게시**한다. 로그인은 200 인데 그 토큰으로
보호 경로가 전부 401 이 된다 — 증상이 "인증이 되는데 안 된다" 라 원인을 찾기 어렵다.

**구현 ③ PR3d-b-2 클러스터 세션에서 실제로 이 상태가 만들어졌다.** 그때는 공개키를 실을 자리가
아예 없어서(seam 부재) dev 키쌍으로 되돌리는 우회를 했다.

확인: §3.3 의 모듈러스 대조. 복구: 공개키를 개인키와 **쌍으로** 맞춘 뒤 롤링.

### 4.4 구 공개키를 일찍 지웠다

④ 대기(30분)를 건너뛰고 ⑤ 를 실행한 경우. 아직 유효한 구 토큰을 든 사용자가 전부 401 이다.

ConfigMap 에 구 공개키를 **다시 넣고** 롤링하면 복구된다(구 키가 폐기된 게 아니라 게시 목록에서
빠진 것뿐이다). 단 개인키가 유출돼서 지운 것이라면 되돌리면 안 된다 — §5 로 간다.

---

## 5. 긴급 회전 (개인키 유출)

정기 절차의 ④(30분 대기)는 **가용성을 위해 구 키를 살려두는** 단계다. 유출 상황에서는 그게
그대로 위험이므로 트레이드오프가 뒤집힌다.

1. ①~③ 을 정상대로 수행한다 (신 키 선배포 → 수렴 → 전환). 여기까지는 같다.
2. **④ 를 건너뛰고 즉시 ⑤** — 구 공개키를 ConfigMap 에서 제거하고 롤링.
3. 구 kid 로 서명된 토큰은 전부 무효가 된다. **사용자는 재로그인해야 한다** — 의도된 결과다.
4. refresh token 까지 무효화해야 하면 별건이다 — `auth:deny:family:<id>` 경로(ADR-0013 D4)를
   쓴다. 이 runbook 의 범위는 access token 서명키뿐이다.

> 유출 사실과 조치 시각을 기록한다. 구 kid 로 서명된 토큰이 30분 안에 쓰였는지는
> `auth.failure{reason}` 과 접근 로그로 사후 확인한다.

---

## 6. 미검증 / 범위 밖

- **§1.1 축과 §3.1 gate 는 실증됐다 (2026-09-15, 로컬 드릴).** 401 구간의 실재(결정적 인과쌍)와
  §3.1 이 그 상태를 차단한다는 것, 그리고 정상 순서 ①~⑤ 완주(③ 롤아웃 전 구간 401 = 0)를 확인했다.
  이 과정에서 §1.1 의 기술을 **커넥션 고정**으로 정정했다.
  증적: `docs/progress/evidence/user-key-rotation-drill-20260915-2040.md`
- **다만 이 절차를 GKE 운영 기구로 완주한 적은 없다.** 드릴은 minikube + k8s Secret 대체였고,
  **Secret Manager 버전 등록 · CSI · Workload Identity 축은 재현하지 않았다.** 그 축은 구현 ③
  PR3d-b-2 세션 2 가 **내부 토큰 도메인**에서 실증했다(P11/P12) — 기전은 같지만 User 도메인에서
  직접 돌린 기록은 아직 없다.
  증적: `docs/progress/evidence/pr3d-b2-gke-20260915-0854.md`
- **자연 롤아웃에서의 401 발생 빈도는 미측정이다.** 드릴은 6회 시도 전부 음성이었고(창이 닫힌 뒤
  토큰이 발급됨), G1 은 경로를 고정해 확증했다. "언제 열리는가" 가 아니라 "열리면 이렇게 된다" 만
  증명된 상태다.
- **§4.1 은 세션 2 의 발견으로 수정됐다** — 초판은 "`active-kid` 되돌림만으로 복구" 라고 썼으나
  실측에서 401 이 유지됐다. 개인키 버전 되돌림이 빠졌고, 비활성화가 아니라 재업로드여야 한다.
- **§3.1 전수 확인 스크립트가 없다.** 내부 토큰에는 `rollout-convergence-gate.sh
  --gateway-signing-probe` 라는 per-Pod gate 가 있는데, JWKS 판은 위 bash 조각뿐이다.
  P11 수행 시 스크립트화 여부를 판단한다.
- **refresh token 회전은 다루지 않는다** — ADR-0013 D4 의 reuse detection 영역이다.
