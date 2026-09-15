# minikube-rotation-drill — 회전 드릴 전용 하네스 (지원 환경 아님)

`docs/runbooks/user-jwt-key-rotation.md` §1.1 / §3.1 축을 실증하기 위한 **드릴 전용** overlay다.

## 이것이 아닌 것

- **minikube 를 지원 환경으로 되살리는 것이 아니다.** ADR-0003(로컬 minikube)은 `Deprecated`이고
  운영 환경은 GKE(ADR-0004)다. 이 overlay 는 운영 매니페스트의 후보가 아니다.
- **Secret Manager CSI / Workload Identity 를 재현하지 않는다.** 두 CSI 볼륨을 k8s Secret 으로
  대체한다 — 그 축은 구현 ③ PR3d-b-2 세션 2 가 내부 토큰 도메인에서 GKE 로 실증했다
  (`docs/progress/evidence/pr3d-b2-gke-20260915-0854.md`).

## 이것이 무엇인가

runbook §1.1 이 지목한 **User 고유 축** — gateway 가 JWKS 를 `user-service` **Service** 로 조회하므로
회전 중 구/신 Pod 가 공존하면 refresh 가 구 Pod 에 떨어져 401 이 나는 구간 — 을 재현하고,
§3.1 전수 확인 gate 가 그것을 막는지 판정하기 위한 최소 스택이다. 그 축은 k8s Service 의미론
그 자체라 클라우드 종속이 없다.

## 최소 스택 (base 전량이 아니다)

mysql · redis · user-service · gateway 만. 제외:
- **kafka** — user-service 는 Kafka 의존이 없다(`user-service/build.gradle` 에 spring-kafka 부재).
- **나머지 4 서비스** — 이 축에 관여하지 않는다.
- **ServiceMonitor** — CRD 미설치(monitoring 스택은 ADR-0006 로 분리).
- **SecretProviderClass** — CSI 드라이버 부재. 아래 Secret 으로 대체.
- **NetworkPolicy** — minikube CNI enforcement 미보장(base 주석과 동일 사유).

## 키

| 대상 | 드릴에서 쓰는 값 |
|---|---|
| user access token 개인키(구) | `local-keys/dev-jwt-private.pem` — base ConfigMap 의 `peekcart-dev-2026` 공개키와 모듈러스 일치 확인 |
| gateway 내부 토큰 개인키 | `local-keys/dev-gateway-internal-private.pem` — `internal-token-keys` 의 `peekcart-gateway-dev-2026` 과 일치 확인 |
| user access token 신 키쌍 | 드릴 중 생성(`peekcart-drill-2026`) — 산출물에 포함하지 않는다 |

Secret 은 매니페스트에 담지 않는다. `scripts/drill/user-key-rotation-drill.sh` 가 `local-keys/` 에서
생성한다 — 개인키를 레포에 넣지 않는 ADR-0013 D2 규약은 드릴에서도 유지한다.
