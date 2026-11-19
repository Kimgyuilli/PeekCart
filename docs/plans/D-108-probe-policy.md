---
grade: M
---
# D-108-probe-policy — 백엔드 서비스 base 프로브의 부하 중 kill 늦추기

기준: 2026-11-20, main `5d66f566`. D-108 ③ 의 남은 결정이다(`docs/plans/done/D-108.md`).
사용자 결정(2026-11-20): base 수치를 완화하고 백엔드 5개 서비스(user·product·order·payment·notification) 모두에 적용한다.
관리 포트 분리와 현행 유지는 기각했다.

등급 M: 고치는 곳은 `k8s/` 하나이고 계약 표면(공개 API·이벤트·DB·설정 키)은 그대로다. 되돌림도 이 PR 의 revert 로 끝난다.
다만 5개 서비스의 운영 재시작 동작이 함께 바뀌어 높은 쪽을 골랐다. ADR 신호(새 의존성·경계·인프라)는 없다.

## 1. 명제

다음 중 하나라도 성립하면 미완이다.

- `gke` 또는 `minikube` overlay 를 렌더하면 백엔드 5개 서비스 중 하나라도 liveness 가 기본값(timeout 1초·3회, 약 30초)이다.
- readiness 가 liveness 보다 먼저 판정하지 않는다(트래픽 제외보다 kill 이 먼저 온다).
- gateway, startupProbe, 프로브 경로·포트가 main 렌더와 달라진다.
- 측정 overlay `gke-d078-contention` 의 order-service 렌더가 main 과 달라진다(D-078 측정 조건 변경).

### 확인한 사실 (main `5d66f566`)

| 항목 | 확인 결과 | 처분 |
|---|---|---|
| base 프로브 | 5개 서비스 `k8s/base/services/<svc>/deployment.yml` 이 같다. 8080 의 `/actuator/health/{liveness,readiness}`, `periodSeconds: 10`, `timeoutSeconds`·`failureThreshold` 미선언(기본 1초·3회). startup 은 liveness 경로 5초 × 30회 | liveness·readiness 에 두 값을 명시한다 |
| health 그룹 | 5개 서비스 `application-k8s.yml` 은 `probes.enabled: true` 만 둔다. 그룹 구성을 바꾼 곳이 없어 liveness·readiness 는 상태값만 본다. 즉 실패 원인은 응답 지연뿐이다 | 그룹은 건드리지 않는다 |
| gateway | 관리 포트 8081(외부 노출 차단 사유, ADR-0024). 10-25 재시작 대상이 아니다 | 범위 밖 |
| 운영 overlay | `gke` 의 order 는 CPU 500m/2000m, HPA 1~3. 프로브 patch 없음. `minikube` 도 프로브 patch 없음 | base 값이 그대로 운영에 간다 |
| 측정 overlay | `gke-d078-contention/patches/order-service-probes.yml`: liveness 5초 × 12회(약 2분), readiness 5초 × 6회(약 1분). 이 값에서 10-25 이후 재시작 0(2026-10-26 세션 w2~c2) | liveness 는 base 가 같은 값이 돼 patch 에서 뺀다. readiness 는 patch 에 남긴다. order-service 렌더는 그대로다. 이 overlay 는 `gke` 를 상속해 나머지 서비스는 base 값으로 바뀐다 |
| 재시작 원인 | 10-25 w1·w2 의 kill(exit 143)이 CPU 포화 때문인지, Hikari 대기에 묶인 Tomcat 스레드 고갈 때문인지 가르지 않았다 | 두 원인 모두 "응답 지연 → timeout" 이라 수치 완화가 양쪽에 듣는다. 원인 분리는 §미해결 |
| 참조처 | 프로브 수치를 검사하는 스크립트·CI 없음(`gateway-exposure-lint.sh` 는 gateway 경로만 본다). 설계 문서는 "Probe 로 비정상 Pod 재시작" 한 줄뿐 | 문서 수정 없음 |

### 수치

| | timeout | failureThreshold | 판정까지 | 근거 |
|---|---|---|---|---|
| liveness | 5초 | 12 | 약 2분 | 10-26 세션에서 재시작 0 을 낸 유일한 실측 값. 1분 안팎은 10-25 kill 시점(부하 약 1분 뒤)과 겹쳐 근거가 없다 |
| readiness | 5초 | 3 | 약 30초 | 1초는 부하 중 스레드·CPU 대기만으로 넘는다. 횟수는 기본값을 두어 liveness 보다 먼저 endpoint 에서 뺀다 |

트레이드오프: 실제 교착(스레드 전부 영구 대기)을 kill 로 복구하는 데 약 2분이 걸린다(기존 약 30초). 그 동안 readiness 가 먼저 빠져
replicas 1 인 서비스는 Service endpoint 가 비어 503/연결 거절이 난다. 기존에도 kill·재기동 구간에 같은 거절이 났고,
부하 중 오판 kill 은 재기동·콜드 JVM·다음 run 워밍업으로 이어져 더 길었다(10-25).

## 2. 작업 항목

- [x] P1. 5개 서비스 base deployment 의 liveness 에 `timeoutSeconds: 5`·`failureThreshold: 12`, readiness 에 `timeoutSeconds: 5`·`failureThreshold: 3` 을 명시하고, 수치 근거를 order-service 쪽에 주석으로 남긴다(나머지는 그 주석을 가리킨다).
- [x] P2. 측정 patch `order-service-probes.yml` 에서 liveness 블록을 빼고 주석의 "운영 정책은 별도 결정" 을 이 계획 참조로 고친다. readiness 만 남긴다.
- [x] P3. 렌더 대조 스크립트(scratchpad, 커밋하지 않음)로 §3 을 확인하고 결과를 audit 에 남긴다.

## 3. 검증 방법

main 과 이 브랜치에서 `kubectl kustomize` 로 `gke`·`minikube`·`gke-d078-contention` 을 렌더해 Deployment 별 컨테이너 프로브를 비교한다.

- `gke`·`minikube`: 바뀐 필드가 정확히 5개 서비스 × {liveness.timeoutSeconds, liveness.failureThreshold, readiness.timeoutSeconds, readiness.failureThreshold} 이다.
  gateway·startup·경로·포트와 프로브 밖 필드는 같다.
- `gke-d078-contention`: order-service Deployment 가 main 과 같다. 나머지 백엔드 서비스는 `gke` 와 같은 규칙으로 바뀐다.
- `readiness 판정 시간 < liveness 판정 시간` 을 서비스별로 계산해 확인한다.
- 실패 주입: (a) 한 서비스의 base 수정을 되돌리면 첫 항목이 그 서비스를 지목해 실패한다. (b) P2 에서 readiness 까지 지우면 측정 overlay 동일성 검사가 실패한다.
  (c) readiness failureThreshold 를 12 로 올리면 판정 순서 검사가 실패한다. 각각 확인 후 되돌린다.
- 기존 lint(`scripts/*-lint.sh`)가 통과한다.

실제 kubelet 동작(부하 중 kill 이 늦춰지는지)은 다음 과금 세션 전까지 관측하지 않는다. 측정 overlay 가 같은 liveness 값으로 이미 재시작 0 을 보였다.

### 정정 이력
- 2026-11-20 /work: 초안은 측정 overlay 렌더 전체가 main 과 바이트 동일하다고 적었다. 이 overlay 는 `gke` 를 상속하므로 user·product·payment 의
  프로브도 base 값으로 바뀐다. 렌더 대조에서 드러났다. 기준을 "order-service 는 동일, 나머지는 `gke` 와 같은 규칙" 으로 고쳤다.
  나머지 서비스의 변경은 kubelet 판정만 늦추고 요청 경로·자원은 그대로라 D-078 측정 조건(order 수신·발행)과 무관하다.

## 미해결

- 재시작 원인(CPU 포화 vs Tomcat 스레드 고갈) 분리: 처분 없음. 다음 과금 세션에서 부하 중 재시작이 다시 나면 그때 관리 포트 분리를 재검토한다. 지금은 수치 완화로 증상이 사라져 재현 근거가 없다.
- startupProbe(timeout 1초): 10-26 동시 콜드 기동에서 product·payment 가 startup 실패로 재시작했다. 기동 순서 문제라 이번 범위 밖이다. 반복되면 부채로 등록한다.
- 5개 서비스 값이 갈라지지 않게 막는 lint: 만들지 않는다. 요청 범위 밖이다.
