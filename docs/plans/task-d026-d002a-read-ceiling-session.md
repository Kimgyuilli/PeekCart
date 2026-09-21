---
grade: L
# 이번 세션 한정 — 사용자 지시(2026-09-20) "코덱스 리뷰 돌리지 마세요".
# 라운드 1은 지시 전에 이미 수행됨. 이후 /work diff 리뷰까지 차단. 해제는 이 줄 삭제.
codex: off
---
# 계획 — D-026 배속 재측정 + D-002a 읽기 경로 공통 천장 분리 (GKE 세션)

> 기준선: `docs/progress/evidence/d002a-gke-20260916-0030.md` 조건 B(product CPU 3000m · 400 VU)
> 선행: D-026 구현 [#124](https://github.com/Kimgyuilli/PeakCart/pull/124)(ADR-0026, 재고 전용 캐시 TTL 5s) ·
> D-002 격리 재측정 [#119](https://github.com/Kimgyuilli/PeakCart/pull/119)
>
> 이 세션이 닫는 것은 둘이다 — **D-026 N8**(배속 미측정인데 해소로 적지 않는다)과
> **D-002a 읽기 경로 잔여**(캐시 OFF 공통 천장 detail 378.1 ≈ list 380.0 을 MySQL CPU 로 분리 못 함).
>
> **명명 정정 (라운드 1 지적 #9).** 초안은 이것을 "D-002 P5" 라 불렀으나 **틀렸다.**
> `task-d002-bc-session.md:85` 의 P5(= MySQL `limits.cpu` 500m 를 흔든다)는 **주문 생성 경로에서
> 이미 수행**됐고(`d002bc-gke-20260917.md:36-46`, ×2.94) `TASKS.md:67` 도 D-002 를 완료로 적는다.
> 미분리로 남은 것은 **D-002a 읽기 경로 기준선의 후속 질문**이다. 완료된 행을 되살리지 않는다.

## 1. 명제 (부정형)

다음 중 **하나라도** 성립하면 미완이다.

- **N1** detail 배속이 `×1.23 → ?` 로 **재측정되지 않았는데** D-026 을 "완료" 로 표기한다.
- **N2** 재측정이 **기준선과 비교 불가능한 조건**에서 이뤄졌다 — product CPU·VU·시드·엔드포인트·
  **부하 키 순회 패턴**·스토리지 클래스 중 하나라도 조건 B 와 다른데 증적이 그 차이를 명시하지 않는다.
- **N3** 측정에 쓴 이미지가 **D-026 캐시를 포함한다는 것이 digest 로 고정되지 않았다.**
  "오늘 빌드한 이미지" 나 "메트릭이 보인다" 는 *어떤* 이미지라는 말일 뿐, overlay 가 의도한
  산출물을 돌렸다는 증명이 아니다.
- **N4** `list` **대조군이 움직였는데** 그 사실이 해석되지 않았다. `ProductListDto` 에 stock 이
  없으므로(D-026 §V4) list 배속은 기준선 ×2.02 근처로 재현되어야 한다.
- **N5** 측정 조건(product CPU · MySQL CPU · 캐시 토글)이 **렌더 가능한 파일로 선언되지 않고**
  ad-hoc `kubectl patch` 나 주석 토글로만 존재한다. D-002a 가 정확히 이 방식으로 실패했다 —
  "`gke-d002a` 가 infra 를 patch 하지 않아 MySQL 은 내내 500m 였다"(TASKS.md D-002 ①).
- **N6** MySQL CPU 상한의 효과가 **시간·재기동과 분리되지 않았다.** `mysql.yml:24-34` 가
  `Recreate` 이므로 CPU 변경은 **버퍼풀 콜드 리스타트를 동반**한다. 되돌림(reversal) 없이
  단방향으로 관측하면 그 차이는 CPU 효과가 아니라 재시작·드리프트 효과일 수 있다.
- **N7** 측정값이 **포화 조건에서 얻어지지 않았다.** `TPS ≈ VU/지연` 을 만족하면 천장이 아니라
  동시성에 묶인 값이다(기준선 §방법론 주의).
- **N8** 각 셀이 **1회만 측정돼** 변동성과 신호를 가를 수 없다.
- **N9** 캐시 적중률이 **누적 counter 의 절대값**으로 계산됐다 — run 간 오염이 섞인다.
- **N10** 세션 종료 후 **GCP 자원이 남아 과금이 계속된다.** D-002a 증적의 "잔여 0 확인" 은
  사실과 달랐고 PVC 3개가 남아 있었다(PHASE4 §부수 정정).
- **N11** 결과가 증적 + PHASE4 엔트리 + TASKS.md 행에 **반영되지 않았다.**

---

## 2. 배경 — 착수 전 검증

**전제는 ADR 이 아니라 현재 코드·현재 클라우드 상태로 확인했다.**
V1~V4·V12 는 **변하는 상태**라 명령·시각·원시 출력을 P0 preflight 증적에 남긴다(지적 #13).

| # | 전제 | 확인 결과 | 영향 |
|---|---|---|---|
| **V1** | `peekcart-gke` 가 활성 gcloud 계정에 있다 | **아니다.** 활성 계정 `rlarbdlf222@gmail.com` 에 없고 소유는 **`momens.dev@gmail.com`**(프로젝트 번호 1076531038524). 기존 config 는 무관한 `momens-prod` 를 가리켰다 | **조치 완료** — 별도 configuration `peekcart` 로 account/project/zone 격리. `default` 무변경(복귀: `gcloud config configurations activate default`) |
| **V2** | AR 이미지를 재빌드하지 않아도 된다 (d002a 노트) | **무효.** AR `product-service` 전 태그가 **2026-09-14T22:07**, D-026 커밋 `5536130`·`7a353ef` 는 **09-18** | **N3.** 재빌드가 선행. **`latest` 가 이 혼동을 만들었다** → 이번엔 digest 고정, `latest` 미갱신 |
| **V3** | 클러스터·loadgen VM 이 남아 있다 | **아니다.** 클러스터 0 · 인스턴스 0 | 전부 신규 생성 |
| **V4** | 쿼터가 12 vCPU 다 (d002a 계획서) | **정정 — 24.** `asia-northeast3` `E2_CPUS` limit **24.0** / usage **0.0** | 노드 8 + loadgen 2 = **10/24**. 그래도 **기준선 형상을 유지**한다 — 비교 가능성이 여유보다 우선(N2) |
| **V5** | D-026 캐시가 실제로 들어가 있다 | **확증.** `CacheConfig:60` `PRODUCT_STOCK_CACHE="productStock"` · `:75` 키 `peekcart.cache.product-stock-ttl` · `:94` **기본 5s** · `:122-129` 전용 TTL · `ProductCacheService:68` `@Cacheable(...)` | 측정 대상 실재. TTL 은 Java Config 기본값이라 k8s 미설정으로도 5s |
| **V6** | 대조군 토글이 재고 캐시에도 걸린다 | **결론은 그렇다. 단 초안 서술이 틀렸다** — 초안은 "`CacheConfig` 전체가 `@ConditionalOnProperty` 하위" 라 적었으나 **클래스 선언은 `@Configuration`+`@EnableCaching` 뿐**(`:49-51`). 실제 구조는 **상호배타 두 빈**: `cacheManager` `@ConditionalOnProperty(havingValue="true", matchIfMissing=true)`(`:90-92`) ↔ `noOpCacheManager` `havingValue="false"`(`:138-141`). `productStock` 은 `cacheManager` 안에 배선(`:129`)되므로 OFF 면 NoOp 로 함께 죽는다 | **정정 이력 (라운드 1 지적 #11).** 단일 스위치라는 결론은 유지 — OFF 가 기준선과 같은 의미를 갖는다(N2) |
| **V7** | MySQL CPU 상한이 base 에 500m 다 | **확증.** `mysql.yml:62-68` `requests 250m` / **`limits 500m`**. **추가 발견**: `:24-34` `strategy.type: Recreate`(D-023, RWO PVC 단일 인스턴스) — 주석이 "D-002 측정 세션에서 실제로 밟았다" 고 기록 | 레버 확인. **동시에 N6 의 근거** — CPU 전환 = Pod 재기동 = 버퍼풀 콜드. 기준선 378/380 이 MySQL 500m 값이라는 뜻이기도 하다 |
| **V8** | overlay 가 조건 B(product 3000m)를 선언한다 | **아니다 — N5 의 근거.** `patches/product-service-deployment.yml` 은 **image 와 `PEEKCART_CACHE_ENABLED` 만** 설정. CPU 3000m 도 MySQL 500m 도 overlay 에 **없다** → 지난 세션은 손으로 올렸고 **MySQL 은 잊었다** | **범위 증가.** 조건을 렌더 가능한 파일로 정본화(P1) |
| **V9** | 부하 스크립트를 재사용할 수 있다 | **그렇다. 단 주석이 낡았다.** `d002a-product-read.js` 의 `TARGET/VUS/DUR/IDS/EP` 로 충분. `:11-12` 주석이 "detail = 캐시 적중해도 재고를 DB 에서 읽는다" — **D-026 이 뒤집은 서술** | 로직 **0줄**, 주석만 정정. **키 순회(`:34` `__ITER % IDS`)는 바꾸지 않는다** — 기준선과 같아야 하는 조건이다(N2, 지적 #2 절반 기각 근거) |
| **V10** | TTL miss 가 상품당 5초 1회다 | **전제로 쓸 수 없다.** `:34` 가 `__ITER % IDS` 라 400 VU 가 비슷한 시점에 같은 ID 를 밟고, `@Cacheable`(`ProductCacheService:68`)에 **`sync=true` 가 없다** → TTL 만료 순간 동일 키 stampede 가 가능하다 | **정정 (지적 #2).** `100/5=20회/초`를 **예측이 아니라 측정 대상**으로 내린다. miss 차분으로 실측한다 |
| **V11** | 측정 표면(Internal LB)이 있다 | **있다.** `measurement-lb.yml` — `product-service-measure`, 라벨 `peekcart.io/purpose: measurement-only` | 신규 작성 0 |
| **V12** | k6 가 로컬에 있다 | **없다.** 기준선도 **loadgen VM 별도**에서 돌렸다 — co-location 경합 제거를 위한 의도적 설계 | 로컬로 갈음하지 **않는다**. 로컬에서 쏘면 네트워크가 변수로 들어와 N2 위반 |
| **V13** | overlay 가 스토리지 형상을 고정한다 | **`gke-d002a` 는 안 한다.** `gke-d002bc/patches/pvc-storageclass.yml` 에는 `/spec/storageClassName` patch 가 있는데 **`gke-d002a` 엔 없다** → 기본 StorageClass 가 바뀌면 I/O 가 달라진다 | **비교 가능성 구멍 (지적 #12).** MySQL 천장을 묻는 세션에서 디스크 형상이 자유변수면 안 된다 → P1 에서 고정 |
| **V14** | 정리 스크립트가 있다 | **있고, 이미 한 번 실패해서 고쳐졌다.** `loadtest/cleanup.sh`(클러스터/VM/PD/예약IP, `--dry-run`, `CLUSTER_NAME`/`LOADGEN_NAME`/`ZONE`/`REGION` override). D-002a 가 **PVC 3개를 남겼고** 그 뒤 잔여를 exit code 로 검증하도록 수정됐다(PHASE4 §부수 정정) | **새로 쓰지 않는다 (지적 #10 축소).** 재사용 + trap 배선만 |
| **V15** | Redis 캐시 초기화가 재배포로 된다 | **보장되지 않는다.** `redis.yml:1-11` 이 **PVC(RWO 512Mi)** 를 쓴다 → 재배포해도 데이터가 살아남을 수 있다 | **절차 버그 (지적 #4).** 초기화는 명시적 Redis 명령 **하나**로 고정하고 전후 key count 를 증적화 |

### 구조 변경 여부

모듈/경계 이동·peel·rename **아니다**. `PLAN-BLINDSPOTS.md` 전체 수행 대상이 아니다.
overlay 변경의 인바운드만 확인했다:

| 대상 | 인바운드 | 처분 |
|---|---|---|
| `k8s/overlays/gke-d002a/**` | 측정 전용. CI 렌더 대상 여부는 P1 에서 확인 | 조건 patch 추가. base 무변경 |
| `k8s/base/infra/mysql/mysql.yml` | 전 overlay | **건드리지 않는다.** CPU 변경은 overlay patch 로만. base 승격은 측정 뒤 ADR(§5) |
| `loadtest/scripts/d002a-product-read.js` | 기준선 증적 · D-026 계획서 §V12 | **주석만.** 로직·환경변수·키 순회 불변 |

---

## 3. 측정 설계

### 3-1. 블록 순서 — MySQL CPU 축을 되돌린다 (N6)

고정 순서(500m 전부 → 2000m 전부)는 CPU 효과를 **시간·재기동과 결합**한다. `Recreate`(V7) 때문에
전환마다 버퍼풀이 식으므로 단방향 관측은 CPU 를 증명하지 못한다. **A → B → A′** 로 간다.

| 블록 | MySQL CPU | 셀 | 목적 |
|---|---|---|---|
| **A** | 500m | 4셀 (detail/list × OFF/ON) | 기준선 재현 + D-026 판정 |
| **B** | **2000m** | 4셀 | CPU 효과 |
| **A′** | 500m 복귀 | **2셀** (detail OFF · list OFF) | **되돌림 게이트** — 천장이 A 범위로 돌아오는가 |

A′ 가 A 로 안 돌아오면 B 의 차이는 CPU 가 아니다. **그 경우 CPU 결론을 쓰지 않는다.**
블록 전환마다: rollout 완료 확인 → DB readiness → **동일한 버퍼풀 워밍업 종료 조건** 적용.
블록 안에서 셀 순서는 교차한다(고정 순서가 만드는 드리프트 방지).

### 3-2. 셀당 3회 반복 (N8)

각 셀을 **60s × 3회** 돌려 **중앙값과 산포**를 기록한다. 총 (4+4+2)×3 = **30 run ≈ 30분 부하**.
판정은 단일 값이 아니라 **같은 세션 안의 paired ratio 중앙값**으로 한다.

고정 조건(기준선 조건 B 와 동일 — N2): product CPU **limit 3000m** · **400 VU** · `DUR=60s` ·
`IDS=100` · **k6 키 순회 패턴 불변** · 시드 `d002a-product-seed.sql` · 노드 `e2-standard-4`×2 ·
loadgen `e2-medium`×1 · **StorageClass 고정**(V13).

### 3-3. 게이트

| 게이트 | 기준 | 실패 시 |
|---|---|---|
| **재현** (N2) | A 블록 detail OFF · list OFF 중앙값이 기준선 378.1 / 380.0 **±10%** | **측정 중단.** 형상 차이를 먼저 규명 — 오염된 숫자로 D-026 을 닫지 않는다 |
| **대조군** (N4) | A 블록 list ON 이 767.1 **±15%** | 오염 의심. detail 숫자도 보류 |
| **되돌림** (N6) | A′ 가 A 의 **±10%** | CPU 결론 **미기재** |
| **포화** (N7) | run 1·5 VU 램프(100/200/400)에서 TPS 평평 + 지연 선형 | 그 조건에서 배속 주장 안 함 |

±10% 는 **환경 이상 탐지용 보조 게이트**다. D-026·CPU 효과 판정은 반복된 paired ratio 로 한다(지적 #3).

---

## 4. 작업 항목

- [x] **P0. preflight 증적** (지적 #13).
  `gcloud config list` · 계정/프로젝트/존 · 쿼터 · 클러스터 0 · 인스턴스 0 · AR 이미지 목록을
  **명령·시각과 함께** `docs/progress/evidence/` 밑 preflight 파일에 남긴다. P3 직전 재실행.
  → 검증: V1~V4·V12 가 그 파일로 재검증 가능한가.
- [x] **P1. 측정 조건을 렌더 가능한 파일로 정본화한다** (N5, 지적 #8·#12).
  product `limits.cpu: 3000m` patch 추가 · **MySQL CPU 500m/2000m 을 각각 렌더되는 명명된
  overlay(또는 component) 로 선언** · **StorageClass patch 를 `gke-d002bc` 선례대로 추가**(V13).
  **주석 토글 방식은 채택하지 않는다** — 실행 중 수동 편집 상태를 남겨 N5 목적을 깬다.
  같은 커밋에서 `d002a-product-read.js:11-12` 주석을 D-026 이후로 정정(V9).
  → **검증 완료**: `gke-d002a-mysql-500m` / `-2000m` 두 overlay 가 모두 렌더되고
  mysql cpu = **500m / 2000m**, product cpu = **3000m**(양쪽), PVC 3개 전부 `standard-rwo`.
  **실패 주입 통과** — `patches/mysql-cpu.yml` 을 빼면 base 값 **500m 으로 복귀**, 복원 시 2000m.
  **선재 결함 발견**: `gke-d002a` 는 `../../base/*.yml` 을 파일 참조해 kustomize root 이탈 제한에
  걸린다 → **기본 설정으로 한 번도 렌더된 적이 없다**(HEAD 상태에서도 동일 실패 확인).
  구조 수정은 범위 밖이라 렌더 명령에 `--load-restrictor LoadRestrictionsNone` 을 정본으로
  명시하고 overlay 주석에 사유를 남겼다.
- [x] **P2. D-026 을 포함한 이미지를 digest 로 고정한다** (N3, 지적 #6).
  현재 HEAD 의 **git SHA 기반 불변 태그 하나만** 푸시한다. **`latest` 는 갱신하지 않는다** —
  가변 태그가 V2 의 혼동을 만들었고, 다른 배포가 나중에 이 이미지를 받는 위험도 만든다.
  → **검증 (3/4 완료, 나머지는 P3 에서)**:
  - push digest **`sha256:07817fd3…f61d9db4`** (태그 `d026-b0d8fd9`) = **렌더된 Deployment `image`**
    (두 overlay 모두 `product-service@sha256:07817fd3…` 확인) ✅
  - 빌드 대상 **git SHA `b0d8fd9`** · `product-service/` 미커밋 변경 **0개** ✅
  - **이미지 안에 D-026 코드 실재** — 꺼낸 jar 의 바이트코드에서 `productStock` ·
    `${peekcart.cache.product-stock-ttl:5s}` · `ProductCacheService.getStock` 확인 ✅
    (런타임 이미지에 `unzip` 이 없어 컨테이너 안 검사는 **빈 결과**를 낸다 — 도구 부재
    아티팩트이지 이미지 증거가 아니다. 호스트로 꺼내 검사해야 한다.)
  - **실행 Pod `imageID` 대조는 P3 몫** — 클러스터가 있어야 한다. N3 는 그때 닫힌다.
  - `latest` 는 **갱신하지 않았다** (AR 에서 여전히 2026-09-14 를 가리킴).
  - 플랫폼: 로컬이 arm64 이고 GKE 노드는 amd64 라 **`--platform linux/amd64`** 로 빌드했다
    (`docker image inspect` = `linux/amd64` 확인). 이걸 빼면 Pod 가 기동하지 않는다.
- [x] **P3. 클러스터·loadgen VM 생성 + 배포** (V3).
  GKE `e2-standard-4`×2 (asia-northeast3-a) · loadgen `e2-medium`×1 동일 VPC · overlay apply ·
  시드 적용 · Internal LB 주소 확보 · loadgen 에 k6 설치(**버전 기록**).
  **생성 직후 P8 의 cleanup trap 을 먼저 건다**(지적 #10).
  → 검증: `/api/v1/products/1` 200 + 시드 100건.
- [x] **P4. 30런 측정** (§3).
  각 run 전: Pod UID · cache env · **effective CPU limit** · rollout status 캡처(지적 #8).
  각 ON run 전: **명시적 Redis 초기화 1개 명령** + 전후 key count 기록(V15) → **결정적 워밍업**
  (detail 100키 + list 키를 채운다) → 안정화 → 측정. **워밍업 트래픽은 측정 구간에서 제외**.
  → 검증: §3-3 게이트. 재현 게이트 실패 시 **중단**.
- [x] **P5. 메커니즘을 차분으로 확인한다** (N9, 지적 #5·#7).
  - **캐시**: run **직전·직후** `cache_gets_total` hit/miss 원값 + Pod UID + 시작시각을 저장하고
    **delta 로 적중률 계산**. Pod 재시작 시 counter reset 을 명시 처리. ON 조건에서 hit·miss 가
    **둘 다 증가**했는지 양성 대조.
  - **CPU**: run 전체의 timestamped 사용량 + **cgroup `cpu.stat` 의 `throttled_usec` 차분**
    (product · MySQL). **Prometheus 스택은 넣지 않는다** — overlay 가 얇은 것이 의도이고
    측정 대상 시스템을 바꾸면 안 된다(지적 #7 절반 기각).
  - **k6**: `connect`/`blocked`/`failed` 를 기록해 LB·네트워크 변동을 병목과 분리.
  → 검증: V10 이 내린 `20회/초`가 **실측과 맞는지**. 다르면 stampede 가 일어난 것이고 그 자체가 발견.
- [x] **P6. 증적 작성** (N11).
  `docs/progress/evidence/d026-d002a-read-ceiling-<YYYYMMDD>.md` — 기준선 형식(결론 → 행렬 →
  읽는 법 → 배제표)을 따르고 **환경 fingerprint**(이미지 digest · GKE/k8s 버전 · k6 버전 ·
  StorageClass · 디스크 타입 · LB/zone)를 병기한다. 확인 불가한 값은 **`동일` 로 단정하지 않고
  편차로 기록**(지적 #12). 예측 대비 실측을 표로 넣고 빗나간 칸을 발견으로 적는다.
- [x] **P7. 문서 반영** (N11).
  PHASE4 엔트리 · TASKS.md D-026 행("부분 해소" → 판정에 맞게). **D-002 행은 완료 상태를
  유지**하고 읽기 경로 결과는 D-002a 후속으로 구분해 적는다(지적 #9).
  → **완료**: TASKS.md D-026 을 **✅ 완료**로 전환하되 헤드라인은 세션 간 `×2.30` 이 아니라
  **세션 내 paired ratio 0.60 → 1.01** 로 적었다(대조군 게이트 실패 해석, §읽는 법 ①).
  D-002 행은 손대지 않았다. PHASE4 엔트리 추가.
- [x] **P8. 리소스 정리 — 무조건 실행** (N10, 지적 #10).
  `loadtest/cleanup.sh` 를 이번 세션 이름으로 재사용하고(V14) **어떤 실패 경로에서도 도달하도록
  trap 으로 건다**(P3 에서 선배선). 클러스터·VM·PD·주소에 더해 **forwarding rules · backend
  services · health checks** 를 세션 식별자로 조회한다 — Internal LB 가 만드는 자원이다.
  `gcloud config configurations activate default` 로 컨텍스트 복귀.
  → **완료**: 클러스터·loadgen VM·**미부착 PD 3개** 회수, forwarding-rules·예약 IP **0**.
  gcloud 컨텍스트 `default` 복귀.
  **단 `cleanup.sh` 는 exit 1 로 끝났다** — 새로 넣은 검사가 `backend-services` 5 ·
  `health-checks` 1 을 잡았는데, 확인 결과 **이번 세션 것이 아니라 2026-09-14 잔재**다
  (삭제된 구 클러스터의 instance group 참조 · description 이 5서비스 Internal LB · 참조
  forwarding rule 0). **처분 완료**(사용자 승인) — 삭제 후 재실행한 `cleanup.sh` 가
  **7종 전부 `[0]`** 으로 정상 종료했다. 익일 Billing 확인만 미수행.
  즉 새 검사는 **6일 묵은 선재 누수를 드러냈고 그것까지 닫았다.**

---

## 5. 범위 밖 (§미해결)

- **base `mysql.yml` CPU 상한 승격.** B 블록이 천장 이동을 보이면 근거가 생기지만 **전 overlay 에
  영향**이라 ADR 이 필요하다. 이번 세션은 측정까지. (#119 가 주문 경로에서 같은 결론을 냈으므로
  ADR 은 두 경로를 묶어 쓰는 것이 낫다.)
- **2차 병목의 잔여 축.** CPU 로 천장이 안 움직이면 다음 후보는 PVC IOPS · MySQL 단일 Pod 구조.
  계측이 이 세션 예산 밖 → 결과에 따라 부채 승격.
- **`@Cacheable(sync=true)` 도입 판정.** V10 이 stampede 가능성을 드러냈으나 이는 **ADR-0026 의
  캐시 정책 변경**이라 별건이다. P5 가 입력(실측 miss 수)을 만든다.
- **키 분포를 현실적 분포로 바꾸는 부하 모델 개선** (지적 #2 후반). 기준선과의 비교 가능성을
  깨므로 이번엔 하지 않는다. 별도 세션에서 **기준선을 새로 잡는 방식**이 맞다.
- **ADR-0026 C안(쓰기 경로 `afterCommit` 무효화) 승격 판정** · **`ProductUpdatedPayload.availableStock`
  미사용 필드** · **`docs/04-design-deep-dive.md` §9-1 의 낡은 분산 락 서술**(D-025 범위).

## 6. 검증 방법 요약

| 명제 | 무엇으로 닫나 |
|---|---|
| N1 | A 블록 detail ON 중앙값이 증적 행렬에 있다 |
| N2 | 재현 게이트 통과 + fingerprint 병기 |
| N3 | push digest = 렌더 image = Pod `imageID` = git SHA |
| N4 | A 블록 list OFF/ON 이 380.0 / 767.1 범위 |
| N5 | 두 MySQL 조건이 `kustomize build` 로 렌더된다 · ad-hoc patch 0 |
| N6 | A′ 가 A 의 ±10% |
| N7 | VU 램프에서 TPS 평평 + 지연 선형 |
| N8 | 셀당 3회 · 중앙값과 산포 기록 |
| N9 | 적중률이 run 전후 counter **delta** 로 계산됐다 |
| N10 | 잔여 0 출력 + 익일 Billing 확인 |
| N11 | 증적 + PHASE4 + TASKS.md |
