# task-impl3-pr3d-b2-cluster-session — 구현 ③ PR3d-b-2 + PR4 잔여: GKE 클러스터 세션

> 부모 계획: `docs/plans/done/task-impl3-pr3d-internal-token.md` (§7 롤아웃 · §10.3 b-2 범위 · §11 회전 runbook · §12 rollback 행렬)
> 선행: PR3d-a [#80] · Layer1 동기화 [#81] · PR3d-b-1 [#83] · PR4 [#108] — 전부 머지됨
> 이 PR 로 **구현 ③ 이 🔄 → ✅ 로 종결**된다. 남은 것은 코드가 아니라 **실 클러스터 증적**뿐이다.

---

## 1. 명제 (부정형 — 무엇이 성립하면 미완인가)

다음 중 **하나라도 참이면 이 작업은 미완이다**:

- N1. gateway/user-service 개인키가 **Secret Manager + CSI 파일 마운트**로 Pod 안에 있다는 것이 실 클러스터에서 확인되지 않았다 (k8s Secret 경유 = 미충족, PR3c 편차 #2 의 재발).
- N2. §7 ①~⑥ 중 **실행되지 않은 단계가 있다**, 또는 각 단계의 **수렴 gate 를 스크립트로 판정하지 않고** 눈으로 넘어간 단계가 있다.
- N3. barrier ①(network preflight) 또는 barrier ②(signed-only crypto)의 **양성 대조군이 없다** — 즉 "검사가 실패를 실제로 감지한다"가 먼저 증명되지 않은 채 PASS 를 기록했다.
- N4. P8 키 회전 overlap 을 1회 수행하지 않았다, 또는 **순서 역전(③ 을 ② 보다 먼저 / ⑤ 를 ④ 보다 먼저)을 의도적으로 재현해 gate 가 막는 것**을 보지 않았다.
- N5. gateway ServiceMonitor 가 **실제로 8081 을 긁는지**가 Prometheus 쪽 사실(`up{job=~"...gateway..."}==1` + `http_server_requests_seconds_bucket{application="gateway"}` 존재)로 확인되지 않았다.
- N6. 부하 하 gateway **서명 비용의 event-loop 영향**과 **auth failure 기저율**이 수치로 기록되지 않았다.
- N7. 증적 문서가 렌더/lint 성공을 barrier 통과로 기록했거나, 리허설을 "무중단 전환 실증"으로 서술했다.
- N8. 계약과 다르게 수행한 부분(배포 편차)이 증적에 **열거되지 않았다**.

---

## 2. 배경 — 착수 전 코드 검증 (2026-09-14)

부모 계획 §9.1·§10.1 이 전제를 뒤집은 전례가 두 번 있어 같은 검증을 돌렸다. **6건 확인 · 2건 뒤집힘**.

| # | 전제 | 결과 | 근거 |
|---|---|---|---|
| W1 | b-1 산출물(SPC·CSI volume·ConfigMap·lint·스크립트)이 이미 존재 | **확인** | `k8s/base/services/{gateway,user-service}/secretproviderclass.yml` · `gateway/deployment.yml:48-70` CSI volume+mount 양쪽 `readOnly` · `k8s/base/services/internal-token-keys.yml`(공개키 CM + `internal-token-binding`) · `scripts/{rollout-convergence-gate,workload-key-ownership-lint,internal-key-ownership-lint}.sh` |
| W2 | b-1 이 V3(`CANARY_RESULT` 서브셸) 버그를 고쳤다 | **확인** | `gke-security-smoke.sh:262` `printf ... >"$RESULT_FILE"` + `:301` 파일 경유 읽기. `--barrier` / `--crypto-barrier` 단독 모드도 존재 |
| W3 | `DUAL_ACCEPT` 는 env 로 왕복 가능(이미지 재빌드 불필요) | **확인** | `InternalTokenProperties.Mode`(기본 `SIGNED_ONLY`) · `InternalTokenAuthenticationFilter:62` 평문 분기 · `InternalTokenModeInvariant` 부팅 불변식 |
| W4 | 공개키 리스트는 ConfigMap 이 **통째로 대체**(병합 아님) | **확인** | 부모 §11.2 — `InternalTokenPropertiesBindingTest` 가 고정. 회전 ⑤(old kid 제거)가 실제 폐기로 이어지는 근거 |
| W5 | gateway SM 매니페스트가 존재(PR4 산출) | **확인** | `k8s/base/services/gateway/servicemonitor.yml` · `servicemonitor-selector-lint.sh` self-test 6종 |
| W6 | barrier ① 양성 대조군용 State 1 overlay 가 준비돼 있다 | **확인** | `k8s/overlays/gke-probe-state1/`(PR3c 가 지운 5서비스 Internal LB patch 복원, 커밋 제외 상태) |
| **W7** | **§7 ②/③ 리허설의 "before" 상태를 현재 이미지로 만들 수 있다** | **뒤집힘** | gateway 는 평문 `X-User-*` 를 **더 이상 주입하지 않는다** — `GatewayAuthenticationFilter:59-63` 은 strip 만 한다(PR3d-a 가 주입 삭제). 따라서 ②(dual-accept 에서 평문 smoke)·③(평문 주입 → 서명 주입 전환)은 **PR3c 시점 gateway 이미지**를 "before" 로 배포해야 재현된다. CI 가 `github.sha` 태그로도 push 하므로(`ci.yml:524`) PR3c 머지 커밋 이미지를 AR 로 승격해 쓴다. **불가 시 ②/③ 은 "평문 수용 가능 상태 관측"까지만 하고 평문 실제 통과는 미검증으로 기록한다** (§8 U1) |
| **W8** | **Workload Identity 결선이 매니페스트에 있다** | **뒤집힘** | `k8s/` 전체에 `kind: ServiceAccount` **0건**, `serviceAccountName` **0건**. SPC 주석은 "노드 인증은 Workload Identity"라고 규정하는데(`gateway/secretproviderclass.yml:12`), 그 주체가 될 **KSA 가 없다** → 현재 `default` KSA. GCP provider 는 driver 의 TokenRequest 로 KSA 신원을 쓰므로 `automountServiceAccountToken: false`(`gateway-exposure-lint.sh:346` 이 강제)와 충돌하지 않지만, **전용 KSA + IAM 바인딩이 없으면 CSI 투영이 실패**한다 → **P2 로 매니페스트 추가**(b-2 의 "증적만" 경계가 깨지는 지점, 사유 명시) |

### 이 검증이 범위에 미친 영향

- **늘었다**: P2(KSA + WI 바인딩 매니페스트) — b-1 이 놓친 결선. 이게 없으면 P3(실 키 주입)이 애초에 부팅하지 않는다.
- **줄었다**: W7 로 인해 §7 ②/③ 의 "평문 경로 실제 통과" 는 PR3c 이미지 확보 여부에 조건부가 된다. 확보 실패 시 축소 범위를 §8 U1 에 고정한다.

### 구조 변경 여부

모듈 경계·코드 이동·peel·rename **없음** → `PLAN-BLINDSPOTS.md` B1 역의존 스윕 대상 아님. 변경되는 자바 코드 0줄(P2 의 매니페스트 2종 + 증적 문서가 전부).

### ADR 판단

새 GCP 리소스(Secret Manager secret 2개 · GSA · IAM 바인딩)와 KSA 2개가 생기지만 **새 ADR 불필요** — 키 저장소 결정은 ADR-0013 D2·ADR-0017 D2 가 이미 보유하고, KSA/WI 는 그 결정을 GKE 에서 성립시키는 **결선 수단**이지 새로운 결정이 아니다. SPC 주석이 이미 "Workload Identity" 를 명시하고 있다.

---

## 3. 작업 항목

### 준비 — 클러스터·키 (진입 조건)

- **P1. GKE 재기동 + CSI 드라이버.** `peekcart-loadtest` 규격 재생성(Standard · `asia-northeast3-a` · `e2-standard-4`, `overlays/gke/README.md §전제`) + **Workload Identity 풀 활성화** + Secrets Store CSI 드라이버·GCP provider 설치(GKE 관리형 Secret Manager 애드온 우선, 불가 시 upstream helm). monitoring 스택 → 앱 overlay 순서는 README §배포 순서 그대로.
- [x] **P2. KSA + WI 바인딩 매니페스트 (W8).** `k8s/base/services/{gateway,user-service}/serviceaccount.yml` 신설(`iam.gke.io/gcp-service-account` 어노테이션) + 각 Deployment 에 `serviceAccountName` 추가(`automountServiceAccountToken: false` **유지**). GSA 2개 생성 + 각 GSA 에 해당 secret 의 `roles/secretmanager.secretAccessor` **만** 부여(교차 부여 금지 — 키 도메인 분리 ADR-0017 D3), `roles/iam.workloadIdentityUser` 로 KSA↔GSA 바인딩.
  - **구현 중 확장 (계획 대비 추가)**: 매니페스트만 넣으면 이 결선은 **클러스터에서만 검증 가능**한 상태로 남는다. 그런데 Secret Manager 접근 권한은 **볼륨이 아니라 신원**에 붙으므로, GSA 가 바인딩된 KSA 로 뜨는 Pod 는 **CSI 를 마운트하지 않고도** 개인키를 읽는다 — 기존 `workload-key-ownership-lint` 의 CSI 전수 검사가 통째로 우회된다. → 같은 lint 에 **WKO-012**(소유자 워크로드의 전용 KSA + 승인 GSA exact 대조) · **WKO-013**(승인 KSA 를 다른 워크로드가 빌려 씀)를 추가하고 음성 self-test 5종(`ksa_default` · `ksa_annotation_removed` · `ksa_foreign_gsa` · `ksa_object_removed` · `ksa_borrowed_by_job`)을 신설했다. `ksa_borrowed_by_job` 은 **볼륨을 하나도 마운트하지 않는** 변이라, 기존 검사 전부가 그린인 상태에서만 red 가 된다.
- **P3. 실 키 등록·주입.** RSA 키쌍 2종 생성 → 개인키를 `peekcart-gateway-internal-signing-key` / `peekcart-user-jwt-signing-key` 로 Secret Manager 등록(레포 비포함, `local-keys/` 는 `.gitignore:53`). 대응 공개키 → gateway 내부토큰 공개키는 `internal-token-keys` ConfigMap 운영 kid 로 교체(베이크 dev 키 override), user 공개키는 `app.jwt.rs256.public-keys` 로. `PROJECT_ID_PLACEHOLDER` 치환 후 **커밋하지 않는다**.

### §7 롤아웃 리허설 (전 단계 실행 — 부모 §10.1 V6 결정)

- **P4. ① network preflight barrier.** `gke-security-smoke.sh --barrier`. **양성 대조군 선행**: `overlays/gke-probe-state1` 로 State 1(직접경로 200 ×5) → NetworkPolicy 적용 State 2(000) → ClusterIP 환원 State 3(000). PR3c 3상태 표와 같은 형식으로 기록. 불가 시 rollout **중단**.
- **P5. ② 서비스 dual-accept 배포.** 5서비스 `APP_INTERNALTOKEN_MODE=DUAL_ACCEPT` ConfigMap override + 롤링(`maxUnavailable=0`). gate = `rollout-convergence-gate.sh --workloads user-service,product-service,order-service,payment-service,notification-service --expect-image <digest>` + 양방식 smoke(서명 200 · 평문 200, 평문은 W7 조건부).
- **P6. ③ Gateway 서명 주입 배포.** gateway 를 digest 고정으로 배포. gate = `rollout-convergence-gate.sh --workloads gateway --gateway-signing-probe --expect-image <digest>` — Pod 별 synthetic 요청으로 downstream 에서 `X-Internal-Auth` 존재 **AND** `X-User-*` 부재 확인(무트래픽 vacuous-green 차단, 부모 §8 loop3 #1).
- **P7. ④ signed-only 전환.** 5서비스 `APP_INTERNALTOKEN_MODE=SIGNED_ONLY` 되돌림 + 롤링. gate = 전량 수렴 + `InternalTokenModeInvariant` 위반 없이 부팅(모드/필터 집합이 readiness 로 노출됨).
- **P8. ⑤ signed-only crypto barrier.** `gke-security-smoke.sh --crypto-barrier` — 위조 `X-Internal-Auth` 401 · 평문 직접주입 무시 · 직접경로 Bearer 거부 · **정상 서명 200 양성 대조군**(PR3c 검사(5) 교훈). 실패 시 ⑥ 진입 금지, ④ 를 되감아 dual-accept 복귀.
- **P9. ⑥ 후속 정리 확인.** Gateway Authorization 전달 중단·서비스 verifier 물리 삭제는 PR3d-a 에서 코드로 완료됨 → 여기서는 **실행 중인 Pod 에서 그 상태를 관측**(다운스트림이 Authorization 헤더를 받지 않음, verifier bean 0).
- **P10. rollback 행렬 1회 실행.** 부모 §12 표 중 **④ 출발 행**(`SIGNED_ONLY` → `DUAL_ACCEPT` env 되돌림, 이미지 교체 없음)을 실제로 되감고 수렴 gate 로 판정 → 다시 ④ 로 복귀. "1순위 되돌림이 env 하나"라는 설계 주장을 실측으로 고정한다.

### P8 키 회전 (부모 §11)

- **P11. 회전 overlap 1회.** ① new 공개키 선배포(`_1_` 쌍 추가, old 유지) → ② 5서비스 수렴 gate → ③ gateway `active-kid` 전환 + Secret Manager 새 버전 → ④ ttl(30s)+skew(5s)+여유 경과 기록 → ⑤ old 제거·재번호 → 수렴 gate + `workload-key-ownership-lint`.
- **P12. 순서 역전 1회 재현.** ③ 을 ② 보다 먼저 실행 → **전 요청 401 발생을 관측**하고 gate 가 진입을 막는지 확인 → ① 로 되감아 복구. 렌더로는 증명되지 않는 축(부모 §11.1 명시 요구).

### PR4 잔여 + 측정

- **P13. gateway scrape 실증 (N5).** Prometheus 에서 `up{namespace="peekcart"}` 에 gateway 타깃 포함 확인 + `http_server_requests_seconds_bucket{application="gateway"}` **와** `auth_failure_total` 시계열 존재. **음성 대조**: SM selector 를 `app: gateway` 단독으로 되돌리면 타깃이 8081 아닌 Service 로 잘못 붙거나 사라지는지 1회 확인 후 원복(lint self-test 가 렌더 축에서만 보던 것을 실제 수집 계층에서 재확인).
- **P14. 부하 하 측정 (N6).** loadgen VM 에서 기존 시나리오(`loadtest/scripts/order-concurrency.js`) 실행 중:
  - (a) gateway `http_server_requests_seconds` p95/p99 — **인증 경로 vs 공개 경로**를 분리해 서명 비용을 격리.
  - (b) event-loop 영향 대리 측정 — 부하 중 관리 포트(8081) `/actuator/health` 를 고정 간격 probe 하여 지연 분포를 무부하 대비로 기록. **전용 계측이 없으므로 이것은 lag 의 직접 측정이 아니다**(§8 U2).
  - (c) `auth_failure_total` reason 별 기저율(req 대비 비율) — PR4 가 "baseline 없이 임계를 정하면 거짓 계약"이라 미룬 alert 임계의 입력값.
  - 마이크로벤치 baseline(RSA2048 p95 1.80ms / 3072 3.00ms)과 대조. 예산 초과 시 bounded scheduler 격리는 **별건**으로 올린다(이 PR 범위 아님).

### 기록

- **P15. 증적 문서.** `docs/progress/evidence/pr3d-b2-gke-<YYYYMMDD-HHMM>.md` — 단계별 raw 출력 + 수렴 gate 판정 + 3상태 표 + 회전 타임라인 + 측정 수치 + **배포 편차 목록**(PR3c 증적 §배포 편차 형식). 리허설임을 명시(부모 §10.1 V6).
- **P16. 문서 동기화.** `docs/TASKS.md` ③ 행 🔄 → ✅ + b-2 ✅ 링크 · `docs/progress/PHASE4.md` 세션 기록 · PR4 미충족 중 해소분(scrape 증적·auth baseline) 종결 표기.

---

## 4. 검증 방법 (항목별 — "실패 주입 후 상태 확인")

| 항목 | 실패 주입 | 기대 |
|---|---|---|
| P2/P3 | GSA 에서 `secretAccessor` 를 뺀 상태로 Pod 기동 | CSI 마운트 실패 → Pod `ContainerCreating` 고착. 권한 부여 후 기동 성공. **Secret Manager 를 실제로 경유한다는 증거** |
| P3 | gateway Pod 에 `kubectl exec -- ls -l /etc/peekcart/gateway-keys` + 쓰기 시도 | 파일 1개 존재 · 쓰기 실패(readOnly) · `kubectl get secret` 에 개인키 Secret **부재**(etcd 미경유) |
| P2 | gateway GSA 로 user 키 접근 시도 | 403 — 키 도메인 교차 접근 불가(ADR-0017 D3) |
| P2 (오프라인) | `serviceAccountName` 제거 / KSA 어노테이션 제거 / user KSA 를 gateway GSA 로 / KSA 객체 삭제 | `workload-key-ownership-lint` WKO-012 ×1 (self-test 4종) |
| P2 (오프라인) | **볼륨 없이** KSA 만 빌려 쓰는 Job 추가 | WKO-013 ×1 — CSI 마운트 검사가 전부 그린인 상태에서 red (신원 층위 우회 차단) |
| P4 | State 1(NetworkPolicy 제거·LB 복원) | 직접경로 200 ×5 → 검사가 도달을 **감지함**. 이후 000 이 증거가 됨 |
| P5~P7 | 수렴 전에 다음 단계 진입 시도 | `rollout-convergence-gate.sh` exit≠0 (`observedGeneration`·replica 3종·구 RS 0·digest 불일치 중 하나로) |
| P6 | gateway Pod 개별 synthetic 요청 | downstream 관측: `X-Internal-Auth` 있음 **AND** `X-User-*` 없음. 하나라도 어긋나면 ④ 진입 금지 |
| P8 | 위조 서명 토큰 / 평문 `X-User-*` 직접 주입 / 정상 서명 | 401 / 무시(401) / **200** — 3상태 전부. 200 이 없으면 "전부 거부"가 barrier PASS 로 위장됨 |
| P10 | `SIGNED_ONLY` 상태에서 평문 요청 → mode 되돌림 → 재요청 | 401 → (env 변경·롤링) → 200. 이미지 교체 0회임을 digest 로 확인 |
| P12 | 회전 ③ 을 ② 보다 먼저 | 전 요청 401 관측 + gate 가 ③ 진입을 차단. ① 로 되감아 복구 확인 |
| P13 | SM selector 를 `app: gateway` 단독으로 변경 | Prometheus 타깃 집합이 바뀜(잘못된 포트/추가 타깃) → 원복 후 정상. 두 라벨 논리곱이 수집 계층에서도 강제됨 |
| P14 | 무부하 대비 부하 중 | probe 지연·p95/p99·auth_failure 비율이 **수치로** 기록됨(없으면 미완) |

---

## 5. 완료 조건

- §1 N1~N8 전부 거짓.
- 부모 계획 §6 전부 충족 — P10 ①② 실 클러스터 증적 · P8 overlap 1회 · Layer1 동기화(이미 [#81]) · ADR-0014 D2-c exit 성립.
- **렌더/lint 성공을 barrier 통과로 기록하지 않았다.**
- 증적 문서에 배포 편차가 열거돼 있다.
- P2 매니페스트 추가분: 10모듈 그린 + `kubectl kustomize` 양 overlay 렌더 + lint 14종 통과(특히 `gateway-exposure-lint` 의 `automountServiceAccountToken is False` 계약 유지).

---

## 6. 세션 순서 (비용 — 클러스터는 켜져 있는 동안만 과금)

```
P1 클러스터+CSI → P2 매니페스트(로컬 선작업 가능) → P3 키 등록
  → P4 barrier① (+State1/2/3 양성 대조군)
  → P5 ② → P6 ③ → P7 ④ → P8 barrier② → P9 ⑥ 관측
  → P10 rollback 1회 → P11 회전 → P12 역전 재현
  → P13 scrape 실증 → P14 부하 측정
  → 클러스터 삭제 → P15/P16 문서화 (오프라인)
```

P2 는 클러스터 없이 끝나므로 **P1 이전에 먼저 커밋**한다 — 클러스터를 켜둔 채 매니페스트를 고치는 시간이 곧 비용이다.

---

## 7. 미해결 / 범위 밖

- **U1 (W7 조건부)**: PR3c 시점 gateway 이미지를 GHCR 에서 확보하지 못하면 §7 ②/③ 의 "평문 실제 통과"는 재현 불가 → "dual-accept 수용 상태 관측"까지만 기록하고 **평문 경로 실측은 미검증**으로 남긴다. 대안(현재 이미지에 평문 주입을 임시 부활)은 **채택하지 않는다** — 삭제한 공격 표면을 증적을 위해 되살리는 것이 본말전도.
- **U2**: event-loop lag **직접 계측 없음**. P14(b) 는 관리 포트 probe 지연이라 대리 지표다. 전용 계측(reactor-netty 바인더 또는 lag probe)은 코드 변경이라 별건.
- **U3**: 실트래픽 하 무중단 전환은 여전히 미검증(fresh deploy 리허설, 부모 §10.1 V6).
- **U4**: auth failure alert **임계 확정은 이 PR 이 하지 않는다** — P14(c) 는 입력값 산출까지. 임계 결정은 관측성 후속.
- **U5**: gateway 8081 의 NS 내부 도달 범위(`component: gateway` 가 기존 NetworkPolicy 대상 아님)는 ADR-0013 D3 신뢰 경계 확장이라 범위 밖(PR4 에서 동일 처분).
- **U6**: Codex 리뷰 **미호출**(사용자 지시) → 이 계획서는 "P1=0" 수렴을 주장하지 않는다.
