---
grade: L
# 사용자 지시(2026-09-22, `.cache/codex-off`) — Codex 리뷰 미호출.
# 계획 리뷰(§5) · /work diff 리뷰 모두 `의도적 생략(file: ...)`. 해제는 그 파일 삭제.
codex: off
---
# 계획 — D-029 base `mysql.yml` CPU 상한 승격 판정 (ADR-0027)

> 입력 증적 2건: [#119](https://github.com/Kimgyuilli/PeakCart/pull/119) `evidence/d002bc-gke-20260917.md`(주문 경로) ·
> [#129](https://github.com/Kimgyuilli/PeakCart/pull/129) `evidence/d026-d002a-read-ceiling-20260920.md`(읽기 경로)
> 등급 L 근거: ① 계약 표면 변화 — `mysql.yml` 은 **전 overlay 공유 모듈**이고 `resources` 는 공유 설정 키 ·
> ② 되돌림이 이 파일 밖으로 번진다 — 아래 §2 인바운드 표 참조

## §1 명제 (부정형)

다음 중 **하나라도** 남아 있으면 D-029 는 미완이다.

1. `k8s/base/infra/mysql/mysql.yml` 의 CPU 상한이 어떤 값이어야 하는지, 그리고 **왜 그 값인지**가
   ADR 로 고정되지 않았다
2. ADR 이 **주문 경로와 읽기 경로를 따로 논증**한다 (같은 숫자를 두 번 논증하게 된다 → 한 ADR 로 묶는다)
3. ADR 이 **모르는 채로 결정한다는 사실**을 적지 않았다 — 구체적으로 (a) 읽기 경로 list 배속 미확정
   (b) 2000m 에서도 남는 새 천장(detail OFF 904 rps)의 정체 미분리 (c) 캐시 ON 경로가 DB CPU 에
   반응한 이유 미분리
4. base 값을 바꿨는데 **"base 와 같은 값" 이라고 주장하는 주석**이 레포에 남아 있다 (§2 인바운드 표)
5. 변경 후 **전 overlay 가 렌더되는지** 확인되지 않았다 — 실패 주입(patch 제거 → base 값 복귀) 포함
6. `requests` 를 그대로 둘지에 대한 판단이 ADR 에 없다 — 실측 사용량이 `requests` 를 **5배** 넘겼다(§2 F4)

## §2 배경 — 코드로 검증한 전제

검증은 초안 작성 **전에** 수행했다. 표의 "판정" 은 문서 기억이 아니라 파일 직접 확인 결과다.

| # | 전제 | 판정 | 근거 |
|---|---|---|---|
| F1 | base MySQL 상한은 500m, requests 250m | **확증** | `k8s/base/infra/mysql/mysql.yml:64,67` |
| F2 | `mysql.yml` 을 전 overlay 가 공유한다 | **확증 · 경로 두 갈래** | `gke`·`minikube` 는 `../../base` 경유(`k8s/base/kustomization.yml`), `gke-d002a`·`gke-d002bc`·`minikube-rotation-drill` 은 `../../base/infra/mysql/mysql.yml` **직접 참조**. overlay 8개 전부 영향 |
| F3 | minikube 가 2 vCPU 를 내줄 수 있는가 (TASKS.md 트레이드오프 ①) | **전제가 거의 해소된다** | minikube = **4코어/8GB**(`TASKS-archive-phase1-3.md:339`). `limits` 는 스케줄링에 안 쓰이고 `requests` 만 쓴다. base requests 합 = 서비스 6×250m + mysql 250 + redis 100 + kafka 250 = **2100m** < 4000m → 상한만 올려도 **스케줄링 영향 0**. 게다가 현재 limits 합은 이미 **7250m**(4코어의 1.8배)로 **오버서브스크립션 상태** — 이 변경이 새 성질을 도입하는 게 아니다. 남는 리스크는 "버스트 시 로컬 체감 경합" 뿐 |
| F4 | `requests` 를 그대로 둬도 되는가 (트레이드오프 ②) | **여기가 진짜 쟁점** | 2000m 조건 주문 경로 실측 MySQL 사용량 **1,259m**(`d002bc-gke-20260917.md:43`). `requests 250m` 의 **5배**다. QoS 는 Burstable 이고 250m 초과분은 **보장 없는 버스트**다. 노드 압박 시 이 1,000m 는 먼저 깎인다 → 상한만 올리면 "잘 될 땐 3배, 안 될 땐 그대로" 가 된다 |
| F5 | 읽기 경로 detail 배속 ×2.30 이 확정값인가 | **확증** | A 393.4 → B 903.8 → A′ **392.6**(+0.2%), 되돌림 게이트 통과. 스로틀 147.6s→**0.1s** |
| F6 | 읽기 경로 list 배속이 확정값인가 | **아니다** | A′ 되돌림 **+11.5%** 로 게이트(±10%) 실패, 블록 B 산포 **44.5%**(개별 782.7/1406.4/1408.4 — 첫 런만 낮다, 버퍼풀 미가열). 방향은 확실, **숫자는 미확정** |
| F7 | 2000m 이면 천장이 사라지는가 | **아니다** | detail OFF **904 rps** 에서 새 천장. 스로틀은 0.1s 이므로 **MySQL CPU 가 아니다**. 정체 미분리 |
| F8 | 주문 경로도 같은 결론인가 | **확증** | 500m 54.0 → 2000m **158.6** 건/초(**×2.94**), p95 2.38s→879ms, 500m 조건에서 **498m/500m 한도 도달**(`d002bc-gke-20260917.md:42-43`) |
| F9 | Layer 1 문서(01~07)가 이 숫자를 인용하는가 | **아니다 — 인용 0건** | `grep "500m" docs/0*.md` = 0. ADR-0006 의 500m 은 prometheus 건으로 무관. **Layer 1 수정 불필요** |
| F10 | 다음 ADR 번호 | **0027** | 최신 `0026-product-detail-stock-cache-boundary.md` |
| F11 | Codex 게이트 | **blocked** | `hpx_codex_allowed` → `blocked / file / 사용자 지시(2026-09-22)`. §5 는 `의도적 생략` |

**구조 변경 아님** → `PLAN-BLINDSPOTS.md` 전항 수행은 대상 밖이다. 다만 공유 파일을 건드리므로
**B1 역의존 스윕만** 수행했다:

| 인바운드 참조 | 내용 | 처분 |
|---|---|---|
| `k8s/overlays/gke-d002a-mysql-500m/kustomization.yml:6` | "블록 A/A′ — 기준선 조건. **base 와 같은 값이다**(`mysql.yml:67`)" | base 가 바뀌면 **거짓이 된다** → 주석 정정 (P4) |
| `k8s/overlays/gke-d002bc/patches/mysql-deployment.yml` | "기본값은 base 와 **동일하게 둔다**" | 같은 이유로 거짓 → 주석 정정 (P4) |
| `k8s/overlays/gke-d002a-mysql-2000m/patches/mysql-cpu.yml` | 2000m 고정 | base 가 2000m 이면 **overlay 의 존재 이유가 사라진다** → P5 에서 처분 판단 |
| `docs/plans/done/*`, `docs/progress/*` | 완료 이력 | **수정 안 함** — Layer 3 은 당시 사실의 기록이다 |

### 범위 변화

**늘지도 줄지도 않았다.** 다만 무게중심이 이동했다 — TASKS.md 가 트레이드오프 ①(minikube 수용성)을
먼저 적었으나 F3 이 그것을 거의 해소했고, 실제 쟁점은 ②(`requests` 처분)다. ADR 의 Alternatives 는
이 순서로 쓴다.

## §3 작업 항목

- [x] **P1.** `docs/adr/0027-mysql-cpu-limit-baseline.md` 작성 — 주문·읽기 두 경로를 **한 ADR** 로 묶는다.
      Context 에 F5·F8 의 숫자, Consequences 에 F6·F7 + 증적 §미해결 2를 **"모르는 채로 결정한다"** 로 명기.
- [x] **P2.** Alternatives 를 최소 3개 쓴다 — ⓐ base 를 2000m 로 승격(requests 유지) · ⓑ base 승격 +
      `requests` 동반 상향 · ⓒ base 500m 유지하고 `gke` overlay 에만 2000m patch.
      ⓒ 는 **ADR-0007 과의 충돌 여부**를 판정해야 한다(overlay 는 프로파일이 아니므로 §"프로파일은
      연결 정보만" 이 직접 적용되지 않는다 — 그 논증을 적는다). 각 안에 GKE 노드 형상·비용 영향 포함.
- [x] **P3.** 결정된 값을 `k8s/base/infra/mysql/mysql.yml` 에 반영. `requests` 처분은 P2 의 결론에 따른다.
      숫자 위에 **왜 이 값인지 한 줄 + ADR-0027 참조** 주석.
- [x] **P4.** §2 인바운드 표의 주석 2곳 정정 (`gke-d002a-mysql-500m/kustomization.yml`,
      `gke-d002bc/patches/mysql-deployment.yml`).
- [x] **P5.** 측정 overlay `gke-d002a-mysql-500m` / `-2000m` 처분 결정 — base 가 2000m 이면 후자는
      no-op 이 된다. **삭제 / 주석으로 역사 보존 중 하나를 고르고 사유를 남긴다.**
      (`list` 재측정이 F6 로 남아 있으므로 삭제가 자명하지 않다.)
- [x] **P6.** `docs/adr/README.md` 인덱스 행 추가 (`<!-- INDEX:END -->` 위).
- [x] **P7.** `docs/TASKS.md` D-029 행 완료 표기 + `docs/progress/PHASE4.md` 엔트리.

## §검증 방법

항목마다 **실패를 주입한 뒤 상태로 확인**한다. "파일이 존재한다" 는 검증이 아니다.

| 항목 | 검증 | 실패 주입 |
|---|---|---|
| P3 | overlay **8개 전부** `kustomize build` 성공 + MySQL container `limits.cpu` 가 결정값으로 렌더 | `mysql.yml` 의 `limits:` 블록을 지우고 빌드 → **렌더값이 사라지는지** 확인. 8개 중 하나라도 실패하면 F2 의 "두 갈래 참조 경로" 전제가 틀린 것이다 |
| P3 | `gke-d002a-mysql-500m` 이 여전히 **500m** 로 렌더 (base 승격에도 불변) | 그 overlay 의 `patches/mysql-cpu.yml` 를 빼고 빌드 → **base 새 값으로 올라가는지** 확인. 올라가면 patch 가 실제로 효력이 있었다는 증거 |
| P4 | 주석이 렌더값과 일치 | `grep -n "base 와 같은 값\|동일하게 둔다" k8s/overlays/` 가 **0건**이거나, 남았다면 그 문장이 실제 base 값과 맞는지 대조 |
| P6 | `docs/adr/README.md` 인덱스가 실제 파일과 일치 | 인덱스 생성/검사 스크립트가 있으면 `--check`; 없으면 `ls docs/adr/0*.md` 개수와 인덱스 행 수 대조 |
| P1·P2 | ADR 이 명제 3의 (a)(b)(c) **세 미해결을 모두** 인용 | `grep` 으로 `list`·`904`·`캐시 ON` 세 키워드가 Consequences 안에 있는지 |

### 실행 결과 (2026-09-22)

| 검증 | 결과 |
|---|---|
| overlay 8개 렌더 | **8/8 성공** · 상속 5개가 2000m 로 함께 이동 · patch 3개 불변 |
| 주입 A (base `limits` 블록 제거) | **통과** — 상속 5개 전부 `lim=None` 으로 값이 사라졌고, 자체 patch 2개는 500m 유지(대조군 성립) |
| 주입 B (`-500m` patch 제거) | **통과** — `lim=2000m` 으로 올라갔다. 계획서 예측대로 **base 승격 후 이 overlay 의 역할이 대조군으로 반전**됐음이 실증됨 |
| 거짓 주석 스윕 | **3곳 정정** (§2 표가 2곳만 예측 — 1곳 초과, 아래 참조) |
| ADR 인덱스 | ADR 파일 27 = 인덱스 행 27 · 번호 순서 일치 |
| ADR 미해결 3건 인용 | Consequences §"이 결정은 세 가지를 모르는 채로 내려졌다" 에 1·2·3 으로 명시 |

> **§2 인바운드 표가 1곳을 놓쳤다.** 예측은 2곳(두 파일의 **헤더 주석**)이었으나 실제로는
> **3곳**이었다 — `gke-d002bc/patches/mysql-deployment.yml:31` 의 `# P6 에서 흔드는 손잡이.
> base 와 같은 값으로 시작한다.` 가 `resources:` 블록 **안쪽 인라인 주석**이어서
> 헤더만 훑은 스윕에 걸리지 않았다. 스윕을 파일 헤더가 아니라 **문구 grep**
> (`base 와 같은 값`, `동일하게 둔다`)으로 돌렸을 때 잡혔다.

### 렌더 스윕 — 기준선 실측 (2026-09-22, 변경 전)

`kind: Deployment` + `metadata.name: mysql` 의 컨테이너 `resources` 만 뽑는다. `name: mysql` 단순
grep 은 Service·PVC·secretRef 까지 잡아 **틀린 값을 낸다**(초안 검증 중 실제로 한 번 틀렸다).

```
for o in k8s/overlays/*/; do
  kustomize build --load-restrictor LoadRestrictionsNone "$o" | <mysql Deployment 컨테이너 resources 추출>
done
```

| overlay | 기준선(변경 전) | P3 이후 | 출처 |
|---|---|---|---|
| `gke` · `minikube` · `minikube-rotation-drill` · `gke-d002a` · `gke-probe-state1` | `lim=500m` | **`lim=2000m`** | base 상속 |
| `gke-d002bc` | `lim=500m` | `lim=500m` | **자체 patch** |
| `gke-d002a-mysql-500m` | `lim=500m` | `lim=500m` | 자체 patch |
| `gke-d002a-mysql-2000m` | `lim=2000m` | `lim=2000m` | 자체 patch (이제 no-op) |

`req=250m` 은 8/8 전부 불변. **8/8 빌드 성공.**

> **정정 (구현 중 발견).** 이 표의 초안은 `gke-d002bc` 를 **"base 상속"** 으로 분류하고
> 상속 overlay 를 **7개**로 셌다. **틀렸다 — 실제 상속은 5개다.** `gke-d002bc` 는
> `patches/mysql-deployment.yml` 로 500m 을 **자기가 박고 있었다.**
> 기준선에서는 그 patch 값과 base 값이 **둘 다 500m 이라 구분이 불가능했고**, 초안은
> kustomization 의 `resources:` 목록만 보고 상속이라 단정했다. base 를 2000m 로 올리는
> 이 변경이 그 둘을 갈랐다 — 500m 에 남은 것이 patch 를 가진 쪽이다.
> **같은 값일 때 상속과 명시를 렌더 결과로 구별할 수 없다**는 것이 교훈이고,
> 그래서 위 표에 **출처 열**을 추가했다.

**클러스터 실측은 이번 범위가 아니다.** 근거 측정 2건이 이미 있고, 새 GKE 세션은 비용을 쓴다.
이번 산출물은 **결정 문서 + 매니페스트 값**이고 검증은 **렌더 수준**에서 닫는다.

## §완료 조건

- ADR-0027 Status `Accepted`, `README.md` 인덱스 등재
- `kustomize build` 8/8 성공 · 실패 주입 2건 통과
- "base 와 같은 값" 을 주장하는 거짓 주석 0건
- TASKS.md D-029 ✅ · PHASE4.md 엔트리 1건

## §미해결 (범위 밖 — 처분과 함께)

1. **읽기 경로 `list` 배속 재측정** — F6. 블록 워밍업을 list 경로까지 확장해야 한다.
   → **이월.** 재검토 조건: 다음 GKE 측정 세션이 열릴 때 끼워 넣는다. ADR-0027 은 이 숫자 없이 결정한다.
2. **2000m 의 새 천장(detail OFF 904) 정체** — F7. → **이월.** D-002 계열 후속 축.
3. **캐시 ON 경로가 DB CPU 에 반응한 이유** — 증적 §미해결 2. 후보는 노드 레벨 CPU 경합.
   → **이월.** 추측으로 메우지 않는다.
4. **`product`(단수) 캐시 측정 누락** — 증적 §미해결 3. → **이월.** D-026 계열.
5. **익일 Billing 콘솔 확인** — 증적 §비용. `#129` 세션 잔여. → 이번 PR 과 무관하나 **미확인 상태**.
