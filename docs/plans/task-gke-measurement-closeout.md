---
grade: L
ship_scope: P1-P5
---
# 계획 — GKE 실측 잔여 종결 (1세션 2단계)

> 선행 세션: `task-impl3-pr3d-b2-cluster-session.md`([#115]) · `task-d002a-cache-speedup-session.md`([#117]) ·
> `task-d002-bc-session.md`([#119]) · `task-d026-detail-stock-cache-boundary.md`([#124]).
> 이 세션은 위 셋이 `미충족` 으로 남긴 것 중 **실측이라야 닫히는 것**만 회수한다.

## 1. 명제

다음 중 하나라도 남으면 이 작업은 **미완**이다.

1. **D-026 이 "부분 해소" 로 남아 있다** — ADR-0026 재고 전용 캐시 도입 후 detail 배속이
   ×1.23 에서 얼마가 됐는지 **조건 B 와 동일 조건에서** 측정되지 않았다.
2. **현재 jitter 적용 빌드의 DLQ 기준선이 부하 하에서 산출되지 않았다** — 로컬 결과를
   클러스터 결과로 주장하지 않기로 했고([#119] 미충족), 그 약속이 아직 이행되지 않았다.
   **jitter 의 "효과"(감소 여부)는 이 세션의 명제가 아니다** — 토글이 없어 동시 A/B 가
   불가능하다(V9). 여기서 닫는 것은 기준선 산출까지다(리뷰 7 반영, §6 으로 이월).
3. **gateway event-loop 포화가 관리 포트 probe 지연 하나로만 관측된다** — 큐 깊이를
   직접 읽는 계측이 없다.
4. **auth alert 임계가 입력값 없이 비어 있다** — 401 기저율이 부하 하에서 산출되지 않았다.
5. **측정 조건이 코드에 남지 않았다** — 조건 B(CPU 3000m·400VU·IDS)가 overlay 가 아니라
   세션 중 수동 patch 로만 존재해 재현할 수 없다.
6. **과금 자원이 남아 있다** — cleanup 미확인.

## 2. 배경 — 코드로 검증한 전제

착수 전 grep/파일/바이트코드로 확인했다. **7건 중 3건이 뒤집혔고 1건은 이미 닫혀 있었다.**

| # | 초안 전제 | 코드 사실 | 판정 |
|---|---|---|---|
| V1 | `getProduct` 가 재고를 캐시에서 읽는다 | `ProductQueryService:42-43` `productCacheService.getStock()`. javadoc 에 ADR-0026 D1/D2 | ✅ |
| V2 | ×1.23 의 조건을 원 세션과 동일하게 재현할 수 있다 | 조건 B = product `limits.cpu` **3000m** · **400 VU** 는 증적에 있다. 그러나 **IDS·DUR·워밍업·집계 구간은 어디에도 기록돼 있지 않다**(증적·계획서·README 전수 grep, 0건) | ⚠️ **부분 반증** |
| V3 | 측정 스크립트를 그대로 쓴다 | `d002a-product-read.js:12-13` 이 `detail = 캐시 적중해도 재고를 DB 에서 읽는다` 로 **반증된 전제를 여전히 진술** | ❌ 정정 필요 |
| V4 | overlay `gke-d002a` 재사용 | MySQL patch 없음 → base **500m** 유지 = 원 세션과 동일 조건이라 **비교 가능** | ✅ |
| V5 | 이미지 재빌드 불필요 (overlay 주석) | AR `product-service:latest` = **2026-09-14T22:07 빌드**. ADR-0026 은 [#124] **09-18 머지** → 그 이미지로 재면 ×1.23 이 그대로 재현된다 | ❌ **거짓** |
| V6 | overlay 가 조건 B 를 재현한다 | patch 는 이미지·캐시토글만. CPU 는 base **1000m**. 조건 B 는 수동 patch 였고 코드에 없다 | ❌ **미재현** |
| V7 | 오버셀링 정합성이 미검증이다 (세션2 미충족) | [#119] 가 `replicas=3` 에서 `d002bc-verify.sql` 로 **이미 검증**(음수재고 0 · 품목별 diff 0) | ✅ **이미 닫힘 → 범위에서 제외** |
| V8 | event-loop lag 을 설정만으로 직접 잴 수 있다 | reactor-netty **1.2.16** 에 있는 것은 `reactor.netty.eventloop.pending.tasks` **Gauge 하나뿐**이다(`EventLoopMeters` 의 enum 상수도 `PENDING_TASKS` 단 1개). 이것은 **큐 깊이이지 lag(시간) 이 아니다** — lag ≈ 깊이 / 소진율. 게다가 등록 게이트가 `TransportConfig.metricsRecorder != null`(바이트코드 확인)이라 `metrics(true)` 커스터마이저가 필요하다 | ⚠️ **이중 반증** — 순수 config 아니고, lag 직접 지표도 아니다 |
| V9 | jitter A/B 대조를 켤 수 있다 | `ProductKafkaConfig:118` 에 `new JitteredSequenceBackOff(...)` **하드코딩**, 토글 없음 | ⚠️ 이력 대조로 대체 |
| V10 | d002bc 가 경합을 재현한다 | `gke-d002bc/patches/product-service-deployment.yml:8` **`replicas: 1`**. [#119] 가 "경합은 replicas=1 에서 구조적으로 불가능" 을 실측 | ❌ patch 필요 |

### 범위 변화

- **줄었다**: V7 로 오버셀링 정합성이 빠졌다. 세션2 의 `미충족` 을 그대로 옮기면 [#119] 가 이미
  한 일을 다시 하는 계획이 됐다.
- **늘었다**: V5(이미지 재빌드) · V6(조건 B overlay 고정) · V10(replicas=3 patch) — 셋 다
  **없으면 측정이 무의미해지는** 항목이다. V5 가 특히 그렇다: 구 이미지로 재면 ×1.23 이 다시
  나오고 그것을 "개선 없음" 으로 오독하게 된다.
- **형태가 바뀌었다**: V8 로 D 는 "설정 토글" 이 아니라 **측정 전용 빌드**가 됐다.
  V9 로 B 는 동시 A/B 가 아니라 **[#119] 이력 대조**가 됐다(§4 에 한계 명시).

### 쿼터 제약과 2단계 순차의 근거

쿼터 `CPUS_ALL_REGIONS = 12`. `task-d002a-cache-speedup-session.md` §0 이 범위를 하나로 줄인
이유가 이것이고, 세션 2 는 그것을 어겨 **"측정에 co-location 경합이 섞였다"를 편차로 남겼다.**

requests 합만 보면 A(≈1.1 vCPU)와 B(3.6 vCPU, [#119] P1 실측)가 노드 allocatable 7.8 안에
동시에 들어간다. **그럼에도 분리한다** — A 의 조건 B 는 product-service `limits` 3000m 으로
노드를 **의도적으로 포화**시키는 측정이라, 같이 띄우면 그 포화가 B 스택을 굶긴다. 산술이 아니라
측정 성질 때문에 분리한다.

→ **클러스터 1회 기동 · 스택 2단계 순차**. 부하발생기는 양 단계 모두 **클러스터 밖 VM**
(`e2-medium`)을 유지한다 — 세션 2 의 co-location 편차를 다시 들이지 않는다(= 잔여 F 의 처분).

## 3. 작업 항목

### 단계 0 — 준비 (과금 전, 로컬)

- [x] **P1.** `d002a-product-read.js:12-13` 주석을 ADR-0026 반영으로 정정한다. 측정 도구가
      측정 대상을 틀리게 진술한 채로 증적을 만들지 않는다.
- [x] **P2.** `gke-d002a` 에 **조건 B 를 고정**한다 — product-service `limits.cpu: 3000m` patch
      추가. overlay 주석의 `재빌드 불필요` 를 삭제하고 **ADR-0026 이후 재빌드 필수**로 바꾼다.
      **IDS·VUS·DUR 를 주석에 숫자로 못 박는다**(V2 가 반증됐으므로 원 세션 값을 인용할 수
      없다 — 이번 세션이 그 값을 **신설**한다. §4 참조).
- [x] **P3.** `gke-d002bc` product-service patch 를 **`replicas: 3`** 으로 올린다. V10 근거와
      "[#119] 와 같은 조건" 을 주석에 적는다. 다른 서비스 replicas 는 건드리지 않는다.
- [x] **P4.** gateway **측정 전용 빌드의 코드와 경계** (단계 0):
      - 브랜치 `meas/gateway-eventloop`(main 기준, 별도 워크트리)에 `NettyServerCustomizer`
        빈 **1개만** 추가해 `metrics(true)` 를 켠다. 커밋 `ce07333`.
      - **V8 을 실패 주입으로 확인했다**: `metrics(true)` 없이 `reactor.netty.eventloop.pending.tasks`
        가 **0개**, 켜면 **1개**. 바이트코드에서 읽은 게이트 조건이 런타임에서 성립한다.
        (확인용 임시 테스트는 브랜치에 남기지 않았다.)
      - **base 미변경 guard 통과**: `git diff --stat main -- gateway/src` = **1 file changed**.
      - `gke-d002bc` 의 `images:` 가 gateway 를 **digest 로** 고정하도록 바꿨다
        (`GATEWAY_MEAS_DIGEST_PLACEHOLDER`). 가변 태그를 측정 대상으로 삼지 않는다.
      - **이 빌드는 어떤 경우에도 main 에 머지하지 않는다.**
- [x] **P5.** product-service **digest 고정의 배선** (단계 0) — `gke-d002a` patch 와
      `gke-d002bc` 의 `images:` 를 `:latest` 에서 `PRODUCT_ADR0026_DIGEST_PLACEHOLDER` 로 바꿨다.
      overlay 주석의 `재빌드 불필요` 를 삭제하고 **ADR-0026 이후 재빌드 필수**로 정정했다.

> **단계 0 과 단계 1 의 경계** — P4·P5 는 **배선까지**이고 실제 **빌드·push 는 P6**(세션 시작)이다.
> digest 는 push 이후에야 정해지므로 overlay 에 미리 적을 수 없고, 이미지를 미리 올려두면
> 세션이 며칠 뒤일 때 코드와 어긋난다. 그래서 overlay 는 레포의 기존 관례인
> **PLACEHOLDER 치환**(`PROJECT_ID_PLACEHOLDER` 선례)으로 배선만 해두고, 실제 값은 P6 에서
> 채운다. 초안은 이 둘을 한 항목에 묶고 있었고 그대로는 단계 0 에서 완료 판정이 불가능했다.

### 단계 1 — 스택 A (`gke-d002a`) · D-026 배속

- [ ] **P6.** (세션 시작) 이미지 빌드·push·치환:
      1. `gcloud config set project peekcart-gke` · `gcloud auth configure-docker asia-northeast3-docker.pkg.dev`
      2. product-service 를 **main 기준**으로 빌드해 push (ADR-0026 포함 코드여야 한다)
      3. gateway 를 **`meas/gateway-eventloop` 기준**으로 빌드해 push
      4. 두 digest 를 `gke-d002a` patch 와 `gke-d002bc` kustomization 의 PLACEHOLDER 에 치환
         (`PROJECT_ID_PLACEHOLDER` 도 같이). **치환한 digest 를 증적에 적는다.**
      5. `git diff --stat main -- gateway/src` 가 1 file 인지 다시 확인한다(P4 guard 재검).

### 단계 1 — 스택 A (`gke-d002a`) · D-026 배속

- [ ] **P7.** 클러스터(`e2-standard-4` × 2) + loadgen VM(`e2-medium`) 기동. 스택 A 배포,
      `loadtest/sql/d002a-product-seed.sql` 시드. **`kubectl -n peekcart get deploy -o wide`
      로 이미지 digest 가 P6 의 것인지 확인**한 뒤 진행한다(V5 재발 방지).
- [ ] **P8.** 조건 B 로 `{detail, list} × {캐시 ON, OFF}` 4조합. **각 조합마다 run 격리
      절차를 고정한다**(리뷰 2):
      1. `PEEKCART_CACHE_ENABLED` 를 바꿔 배포하고 `kubectl rollout status` 로 Ready 확인
      2. Redis 상태 초기화 — `kubectl exec deploy/redis -- redis-cli FLUSHALL`
      3. **워밍업 60s**(집계 제외) — 상품 정보 캐시(TTL 30분)를 채우는 것이 목적이다.
         재고 캐시(TTL 5초)는 워밍업으로 "채워두는" 대상이 아니라 측정 구간 내내
         5초 주기로 채워졌다 비는 것이 **정상 상태**다. 이전 초안의 "stock 캐시를 채운
         상태로 시작하지 않는다"는 문장은 자기모순이라 삭제했다.
      4. **측정 300s** — 이 구간만 집계한다.
      5. 같은 조합을 **3회 반복**한다(리뷰 3).
      IDS=100 · VUS=400 · DUR=300s 를 사용하고 **실제 사용한 값을 증적 표의 머리에 적는다.**
- [ ] **P9.** 배속 = TPS_on / TPS_off, **3회의 중앙값**으로 낸다. 판정 순서를 고정한다:
      1. **list 대조군을 먼저 본다.** list 배속 중앙값이 **×2.02 ± 15%**(즉 1.72~2.32)
         밖이면 환경이 달라진 것이므로 **detail 결과를 폐기**하고 원인을 먼저 찾는다.
      2. 안에 들면 detail 배속을 ×1.23 과 나란히 적는다.
      3. list 가 범위를 벗어났는데 원인을 못 찾으면, 이번 값을 ×1.23 의 재현이 아니라
         **신규 기준선**으로 기록하고 그 사실을 증적에 명시한다(리뷰 1 — 원 세션의
         IDS·DUR 이 기록돼 있지 않아 직접 비교를 보증할 수 없다).

### 단계 2 — 스택 B (`gke-d002bc`, replicas=3) · B·D·E

- [ ] **P10.** 스택 A teardown(`kubectl delete -k`) 후 스택 B 배포. **노드는 유지**한다.
- [ ] **P11.** `GW_URL=... bash scripts/auth-roundtrip-gate.sh` 통과 확인. [#119] 세션이 이
      단계를 건너뛰어 키쌍 불일치 2건을 배포 후에 발견했고 40분을 썼다(D-022).
- [ ] **P12.** DLQ 기준선 산출. **경로와 계정을 명시한다**(리뷰 4):
      ```
      # reset (run 마다)
      kubectl -n peekcart exec -i deploy/mysql -- \
        mysql -upeekcart_order -ppeekcart_order peekcart_order   < loadtest/sql/d002bc-reset.sql
      kubectl -n peekcart exec -i deploy/mysql -- \
        mysql -upeekcart_product -ppeekcart_product peekcart_product < loadtest/sql/d002bc-product-reset.sql
      # 검증 (run 후)
      kubectl -n peekcart exec -i deploy/mysql -- \
        mysql -upeekcart_product -ppeekcart_product peekcart_product < loadtest/sql/d002bc-verify.sql
      ```
      reset 직후의 orders·outbox·DLQ 건수와 product stock 초기값을 **증적에 먼저 적는다**
      (0 에서 시작했음을 증명하지 못하면 증가분이 무의미하다). 그 뒤
      `d002bc-order-create.js` 로 [#119] 와 같은 부하 프로파일을 건다. 측정:
      DLQ 유입 건수 · `OptimisticLockingFailureException` 건수 · `PRD-004` 건수.
      [#119] 의 `(충돌 7 = DLQ 7, PRD-004 0)` 을 **참고값으로 병기**하되 감소 주장은 하지 않는다.
- [ ] **P13.** P11 부하 구간 동안 gateway 관리 포트에서 큐 깊이를 수집한다(리뷰 5):
      `curl -s <gw>:8081/actuator/prometheus | grep reactor_netty_eventloop_pending_tasks`
      로 **series 와 `application="gateway"` 라벨이 실제로 붙는지 먼저 확인**한다.
      **부하 전/중/후 3점**을 뜬다 — 단일 스냅샷은 귀속 근거가 못 된다([#119] P5 교훈).
      기존 관리 포트 probe 지연도 같이 떠서 **큐 깊이와 대리 지표가 같은 방향인지** 본다.
      증적에는 **`lag` 이 아니라 `pending tasks(큐 깊이)`** 로 적는다.
- [ ] **P14.** 같은 구간에서 401 기저율을 산출한다(정상 부하 하 인증 실패 건수/전체).
      **alert 룰을 쓰지 않는다** — 임계 결정의 **입력값만** 낸다(ADR 선행 없이 측정만).
- [ ] **P15.** 증적 `docs/progress/evidence/gke-closeout-<date>.md` 작성 — 조건·파라미터
      (IDS·VUS·DUR·반복 횟수)·원시 수치·한계.
- [ ] **P16.** `loadtest/cleanup.sh --dry-run` → `cleanup.sh`. 스크립트가 다루는 범위는
      클러스터·VM·디스크·IP 다.
- [ ] **P17.** **AR 측정 이미지 삭제** — `cleanup.sh` 범위 밖이다(리뷰 8, `cleanup.sh:47-115`
      에 AR 단계 없음을 확인). `gcloud artifacts docker images list` 로 P6 이 올린 digest 를
      찾아 `delete` 하고, **재조회가 0건인 것을 확인**해 증적에 남긴다.
- [ ] **P18.** billing 콘솔에서 **당일·익일** 과금 확인.

## 4. 검증 방법

측정 세션이라 "테스트가 green" 이 검증이 아니다. **각 항목이 거짓일 때 실제로 red 가 되는 경로**를 적는다.

| 항목 | 실패를 주입해 확인하는 방법 |
|---|---|
| P2·P6 | **구 이미지로 한 번 재본다.** 조건 B 에서 detail 배속이 ×1.23 근방이면 구 이미지가 맞고, 신 digest 로 바꿔 숫자가 움직이면 P6 이 실제로 효과를 냈다는 증거다. 이 대조 없이는 "개선됐다" 가 이미지 교체 때문인지 측정 오차인지 못 가른다 |
| P3 | `replicas: 1` 로 한 번 돌려 **낙관락 충돌 0** 이 나오는지 본다. [#119] 가 "경합은 replicas=1 에서 구조적으로 불가능" 을 실측했으므로, 0 이 나와야 patch 가 실제로 경합 조건을 만든 것이다 |
| P4 | 커스터마이저를 뺀 digest 로 8081 을 긁어 `reactor_netty_eventloop_pending_tasks` 가 **부재**함을 먼저 확인한다(V8 게이트가 실재함의 증거). 켠 digest 에서 나타나면 배선이 작동한 것이다. 추가로 `git diff --stat main -- gateway/src` 가 1파일 초과면 그 자체로 red — 측정 빌드가 범위를 넘었다는 뜻이다 |
| P8 | **캐시 OFF run 에서 DB 조회가 실제로 늘어야 한다.** MySQL `Com_select` 증가분을 run 전후로 떠서 OFF 가 ON 보다 크지 않으면 토글이 안 먹은 것이므로 배속을 계산하지 않는다. FLUSHALL 직후 `DBSIZE` 가 0 인 것도 확인한다 — 0 이 아니면 run 간 상태가 샌 것이다 |
| P9 | **list 가 대조군이다.** ×2.02 ± 15% 를 벗어나면 detail 결과를 폐기한다. 3회 반복의 **중앙값**을 쓰고 최대-최소 폭도 증적에 적는다 — 폭이 중앙값의 20% 를 넘으면 측정이 불안정한 것이므로 회수를 늘린다 |
| P12 | run 후 `d002bc-verify.sql` 의 음수재고·품목별 diff 가 0 이 아니면 부하가 정합성을 깬 것이므로 DLQ 수치를 해석하지 않는다. reset 직후 DLQ 가 0 이 아니면 그 자체로 red |
| P13 | 부하 전 스냅샷에서 pending tasks 가 이미 높으면 측정이 부하를 반영하지 못하는 것이다. **전/중/후 차분**이 있어야 귀속이 성립한다. series 가 아예 안 잡히면 P4 로 되돌아간다 |
| P17 | `delete` 후 `gcloud artifacts docker images list` 재조회가 0건이어야 한다. 남아 있으면 태그가 다른 digest 를 더 물고 있는 것이다 |
| P18 | `cleanup.sh --dry-run` 이 지울 것을 나열하는데 실제 콘솔에 자원이 남아 있으면 스크립트 범위 밖 자원이 있는 것이다. **콘솔 과금 0 이 최종 판정**이고 스크립트 exit 0 은 아니다 |

### 미리 적어두는 한계

- **원 세션의 IDS·DUR 이 기록돼 있지 않다**(V2 부분 반증). 이번 세션은 IDS=100·DUR=300s 를
  **신설해 고정**하고 그 값을 증적에 남긴다. 따라서 ×1.23 과의 비교는 **list 대조군이
  ×2.02 ± 15% 안에 들 때만** 직접 비교로 취급하고, 벗어나면 신규 기준선으로 기록한다.
- **재고 캐시 TTL 이 5초라 detail 배속은 `키당 초당 요청수 × TTL` 의 함수다.** 그래서 IDS 를
  고정하는 것이고, 이 값이 달라진 측정끼리는 비교할 수 없다.
- **jitter 대조는 이번 세션에서 성립하지 않는다**(V9 — 토글이 없다). 산출하는 것은
  **현재 빌드의 DLQ 기준선**이고, [#119] 대비 감소는 **주장하지 않는다**(리뷰 7). 클러스터가
  다른 시점이라 인과가 아니라 정황이다.
- **`pending.tasks` 는 lag 이 아니라 큐 깊이다**(V8). 시간 단위 lag 을 원하면 소진율이 함께
  필요하고 그 계측은 이번 범위 밖이다.
- **P14 는 임계를 정하지 않는다.** 입력값만 낸다. 룰 확정은 ADR-0009/0015 관할이라 별건이다.
- **캐시 OFF 천장은 이 변경으로 안 내려간다**([#124] 기록) — detail OFF 378.1 / list OFF 380.0
  이 거의 같다는 것은 공통 자원이 천장을 쥔다는 뜻이다. 이 세션이 개선을 보일 곳은 **ON 쪽뿐**이다.

## 5. 완료 조건

- §1 의 6개 명제가 전부 거짓이 됐다.
- `docs/TASKS.md` 의 **D-026 행이 `🔄 부분 해소` → `✅`** 로 갱신됐다(배속 숫자·조건과 함께).
  단 P9 판정이 "신규 기준선" 으로 떨어진 경우에는 **그 사실을 행에 적고** 닫는다.
- 세션2·[#119] 의 `미충족` 중 이 계획이 회수하기로 한 항목이 증적에서 **각각 지목되어** 처분됐다.
- 조건 B(IDS·VUS·DUR 포함)가 overlay 와 증적에 남아 **다음 사람이 같은 숫자를 다시 낼 수 있다.**
- 과금 자원 0(콘솔 확인) · AR 측정 digest 재조회 0건.

## 6. 미해결 (이번 범위 밖)

- **jitter 의 DLQ 감소 효과 실증** — 이월(리뷰 7). `ProductKafkaConfig:118` 에 토글이 없어
  동시 A/B 가 불가능하다. **재검토 조건**: 재시도 백오프에 측정용 토글을 넣는 별건이 생기거나,
  같은 클러스터에서 두 빌드를 번갈아 돌릴 예산이 생길 때. 이번 세션은 기준선만 남긴다.
- **`docs/04-design-deep-dive.md` §9-1 락 서술 드리프트** — ADR-0025 가 제거한 Redis 분산 락을
  설계문서가 여전히 기술한다. [#124] 세션이 "선재 갭, 손대지 않음" 으로 남긴 것이고 이번에도
  범위 밖으로 둔다(사용자 확인). 재검토 조건: 설계문서 정합 작업을 별건으로 잡을 때.
- **다른 4개 서비스 error handler 에 jitter 없음** — `ProductKafkaConfig` 만 배선돼 있다
  ([#123] 미충족). 실측이 아니라 구현 과제라 이 세션 밖이다.
- **자연 롤아웃에서 키 회전 창 발생 빈도** — 6회 시도 전부 음성(세션2). 확률 문제라 측정 세션
  1회로 닫히지 않는다.
- **event-loop lag 을 시간 단위로 재는 계측** — `pending.tasks` 는 큐 깊이뿐이다(V8). 시간
  단위 lag 과 메트릭의 운영(base) 승격은 ADR-0009/0015 관할이고 P13 의 숫자가 그 입력이 된다.

[#115]: https://github.com/Kimgyuilli/PeakCart/pull/115
[#117]: https://github.com/Kimgyuilli/PeakCart/pull/117
[#119]: https://github.com/Kimgyuilli/PeakCart/pull/119
[#123]: https://github.com/Kimgyuilli/PeakCart/pull/123
[#124]: https://github.com/Kimgyuilli/PeakCart/pull/124
