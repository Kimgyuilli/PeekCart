# task-jmeter-to-k6 — 부하 테스트 도구 JMeter → k6 전환

> 작성: 2026-04-22
> 관련 Phase: Phase 3 — Task 3-4 세션 C 선행 작업
> 관련 의사결정: `docs/progress/loadtest-tool-evaluation.md` 옵션 B (부분 전환)
> 관련 ADR: ADR-0004 (부하 스택 기재는 immutable — 본 task 범위 밖)

## 1. 목표

Phase 3 Task 3-4 세션 C (시나리오 2: 1,000 VUser 동시 주문) 실행 전에, 부하 도구를 JMeter 에서 k6 로 전환한다. 세션 B 에서 이미 측정 완료된 시나리오 1(nGrinder) 및 기존 인프라·시드·검증 쿼리는 변경하지 않는다.

본 계획서는 두 파트로 구성된다.

- **Part A — 기존 문서 동기화**: 설계 레이어 문서·Task 추적·로컬 산출물에서 "JMeter" 를 "k6" 로 치환한다. 설계 결정은 불변, 도구명·명령어만 교체.
- **Part B — k6 부하 테스트 A-to-Z**: 스크립트 작성부터 세션 C 실행·리포트 작성까지 전체 흐름을 단계화한다. 본 task 의 **실제 수행 범위는 P7~P9** (세션 C 진입 직전까지). **P10·P11 은 세션 C 본편** 으로 이관 (별도 과금 세션).

## 2. 배경 / 제약

- 평가 문서(§3-2, §6, §7)가 스크립트 초안·ADR 비대상 판단·실행 순서를 이미 제시했다. 본 계획서는 이를 **구현 지시서 레벨** 로 구체화한다.
- ADR-0004 §4 L44("부하 스택: nGrinder + JMeter 조합 유지")는 **immutable**. 평가 문서가 "옵션 B 는 ADR 급 결정 아님" 으로 결론지었으므로 ADR 수정·신규 작성 없이 PHASE3 작업 이력과 본 계획서로 결정 근거를 기록한다.
- 세션 B nGrinder 결과(`loadtest/reports/2026-04-09/REPORT.md`)는 **재측정하지 않는다** — 이종 도구 하이브리드(전통 nGrinder + 모던 k6)로 유지.
- 도구 교체는 `loadtest/` 내부 변경과 문서 동기화에 한정된다. 앱 소스(`src/`)·Flyway 매니페스트는 불변. **K8s 매니페스트도 원칙적으로 불변이며, monitoring values 의 remote-write receiver 활성화 1건만 예외** — k6 Prometheus 출력의 전제조건이므로 본 task 범위에 포함한다 (P8 참고).
- `docs/00-lagacy.md` 는 레거시 보존 문서로 **수정 금지**. `docs/TASKS.md` 완료 작업 이력 행(L487 등)도 과거 사실 기록이므로 **수정 금지**.

## 3. 작업 항목

### Part A — 문서 동기화 (수행 범위)

- [ ] **P1.** `loadtest/scripts/order-concurrency.jmx` 삭제 (git history 로 보존, legacy/ 이관 없음)
- [ ] **P2.** `loadtest/README.md` 동기화
  - §디렉토리 구조(L16~23): `.jmx / users.csv (JMeter 입력)` → `.js / users.csv (k6 입력)`
  - §전제조건 5(L41): `JDK 17, nGrinder, JMeter 설치` → `JDK 11 (nGrinder agent 전용), nGrinder, k6 v0.49+ 설치`
  - §A 시드(L49): "JMeter 입력 CSV" → "k6 입력 CSV"
  - §C 시나리오 2(L83~102): `jmeter -n -t ...` 블록을 `k6 run --summary-export=../reports/YYYY-MM-DD/k6-summary.json -e BASE_URL=http://<internal-lb>:8080 -o experimental-prometheus-rw=... order-concurrency.js` 블록으로 대체 (remote-write receiver 전제 — P8 참고)
  - §E 리포트(L113): `jmeter-html · results.jtl` → `k6-summary.json · Grafana k6 dashboard PNG` (파일명은 `k6-summary.json` 로 고정 — P7/P9 명령과 일치)
- [ ] **P3.** `loadtest/reports/TEMPLATE.md` L23 환경 표: `JMeter 5.6.3 / non-GUI` → `k6 v0.49+ / CLI (Prometheus remote-write)`. 첨부 산출물 항목명을 `k6-summary.json` 으로 통일 (P7/P9/P11 공용)
- [ ] **P4.** `loadtest/sql/seed.sql` 주석 L12 / L42 / L100 의 "JMeter" 문구를 "k6" 로 치환 (스키마 불변)
- [ ] **P5.** Layer 1 설계 문서 JMeter 언급 치환 (설계 결정 불변, 도구명만)
  - `docs/01-project-overview.md` L34 "nGrinder / JMeter" · L64 표 "nGrinder + JMeter" → "nGrinder + k6"
  - `docs/02-architecture.md` L100 mermaid 노드 `LB["nGrinder / JMeter 부하 테스트"]` → `LB["nGrinder / k6 부하 테스트"]`
  - `docs/03-requirements.md` L84 "JMeter 동시성 시나리오" → "k6 동시성 시나리오" (L82/L86 nGrinder 항목은 불변)
  - `docs/06-testing-strategy.md` L36 "동시 주문 폭주 / JMeter" · L39 "전체 플로우 E2E / JMeter" → "k6" (L35/37/38 nGrinder 항목은 불변)
  - `docs/07-roadmap-portfolio.md` L67 "JMeter 로컬 실행" · L83 "nGrinder + JMeter" → "k6 로컬 실행" / "nGrinder + k6" (L8/L94 nGrinder 항목은 불변)
- [ ] **P6.** `docs/TASKS.md` Task 3-4 진행 중 표 수정
  - L395 "JMeter 설치 + 설정 (loadgen VM)" → "k6 설치 + Grafana 대시보드 ID 19665 import 준비"
  - L396 시나리오 2 비고 "JMeter" → "k6"
  - (L487 Step 0-c 완료 이력 행은 수정 금지 — 당시 JMX 작성 사실 기록)

### Part B — k6 A-to-Z (P7~P9 수행 범위, P10~P11 후속 Task)

- [ ] **P7.** k6 스크립트 작성 + 로컬 리허설 (**수행 범위**)
  - 신규 파일: `loadtest/scripts/order-concurrency.js`
  - 요구사항
    - `SharedArray` 로 `users.csv` VU 간 공유 (메모리 1회 점유)
    - executor: `ramping-vus`, stages `[{30s → 1000}, {1m → 1000}, {30s → 0}]`
      - **이는 JMX 등가가 아닌 강화 시나리오**. JMX 는 `num_threads=1000 / ramp_time=30 / loops=1 / scheduler=false` (1회성)이지만, Grafana 에서 "부하 지속 구간의 p95 · Consumer Lag · Pod CPU 추이" 단일 타임라인 관찰이 포트폴리오 목적이므로 1m hold 구간을 추가. 대신 동시 주문 경합 정합성 판정은 여전히 유효 (재고 1,000 vs 총 주문 성공 수 비교는 시간과 독립).
    - 플로우: `POST /api/v1/auth/login` → `POST /api/v1/cart/items` → `POST /api/v1/orders`
    - 경합 타깃: `productId = 1001 + Math.floor(Math.random() * 10)` (JMX `__Random(1001,1010)` 동일)
    - 요청 태그: `tags: { name: 'login' | 'cart' | 'order' }` — Prometheus label
    - Threshold: `http_req_failed{scenario:contention}: ['rate<0.1']` (재고 소진 409 허용 상한)
    - 환경변수: `BASE_URL` (로컬 `http://localhost:8080`, 세션 C `http://<internal-lb>:8080`)
  - 로컬 docker-compose 리허설 (세션 A 리허설 패턴 재사용)
    1. `docker-compose up -d` + 앱 로컬 실행
    2. `bash loadtest/scripts/generate-users-csv.sh` (users.csv 재생성)
    3. `mysql < loadtest/sql/seed.sql` + 카운트 검증(users=1101, products=1010, 경합 stock=1000)
    4. `mkdir -p loadtest/reports/local && k6 run --vus 10 --duration 30s --summary-export=loadtest/reports/local/k6-summary.json -e BASE_URL=http://localhost:8080 loadtest/scripts/order-concurrency.js`
    5. 검증
       - 로그인 check 성공률 > 99%
       - 주문 응답 코드는 201 / 409 / 400 만 존재 (5xx 0건)
       - `verify-concurrency.sql` consistency=OK
       - `loadtest/reports/local/k6-summary.json` 파일 생성 + 파싱 가능 (`jq . < k6-summary.json` 이 non-empty)
- [ ] **P8.** loadgen VM 프로비저닝 절차 + Prometheus remote-write receiver 활성화 (**수행 범위**)
  - **P8-a. loadgen VM — 문서만**
    - 별도 프로비저닝 스크립트가 프로젝트에 없음 → `loadtest/README.md` §전제조건 갱신으로 해결
    - 설치 명령 (참고): `curl -fsSL https://dl.k6.io/key.gpg | sudo gpg --dearmor -o /usr/share/keyrings/k6.gpg && echo "deb [signed-by=/usr/share/keyrings/k6.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list && sudo apt-get update && sudo apt-get install -y k6`
    - JDK 17 요구 제거 (k6 는 JVM 불요). JDK 11 은 nGrinder agent 세션 B 재사용을 위해 유지 서술
  - **P8-b. Prometheus remote-write receiver 활성화 — 매니페스트 변경 (P9 전제조건)**
    - `k8s/monitoring/gke/values-prometheus.yml` 의 `prometheus.prometheusSpec` 에 `enableRemoteWriteReceiver: true` 추가
      ```yaml
      prometheus:
        prometheusSpec:
          enableRemoteWriteReceiver: true  # k6 experimental-prometheus-rw 수신용 (ADR-0006 보완)
      ```
    - minikube values 는 로컬 리허설 대상 아님 → 수정 불요 (P7 로컬 리허설은 Prometheus 미사용)
    - 검증 (smoke test — 빈 바디 POST): 세션 C 클러스터 기동 후 `kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090` + `curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:9090/api/v1/write`
      - **HTTP 400 → receiver 활성화 OK** (빈 바디는 snappy+protobuf 포맷이 아니므로 Prometheus 가 "malformed write request" 로 400 반환)
      - **HTTP 404 → receiver 비활성** (`/api/v1/write` 엔드포인트 자체가 닫혀있음 → values 반영 누락)
      - `204` 는 유효한 snappy+protobuf payload 성공 시에만 발생 → 본 smoke test 범위 밖 (실전 검증은 k6 실행으로 자연스럽게 확인됨)
    - 주석으로 "k6 Prometheus remote-write 수신 전용, 본 클러스터는 부하 테스트 기간에만 존재하며 외부 노출 없음" 명시
- [ ] **P9.** Grafana k6 대시보드 import 절차 문서화 (**수행 범위 — 절차만**)
  - `loadtest/README.md` §C 에 추가
    1. 세션 C 시작 시 Prometheus remote-write endpoint 확보 (P8-b 로 receiver 활성화된 상태 전제, 접근: `kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090`)
    2. k6 실행: `k6 run --summary-export=loadtest/reports/YYYY-MM-DD/k6-summary.json -e BASE_URL=http://<internal-lb>:8080 -o experimental-prometheus-rw=http://<prom-addr>:9090/api/v1/write loadtest/scripts/order-concurrency.js` (P7 로컬 명령과 동일한 `BASE_URL` 환경변수 형식 — `<internal-lb>` 은 세션 C 과금 전에 `kubectl -n default get svc` 로 확보)
    3. Grafana UI → Dashboard → Import → `19665` 입력 → Prometheus datasource 선택
  - **본 task 범위 외**: `k8s/monitoring/shared/` 에 k6 대시보드 JSON 파일 추가는 세션 C 당일 실제 다운로드·import 검증 후 **별도 PR** (SSOT 단일화 원칙 — 미검증 매니페스트 선제 커밋 금지)
- [ ] **P10.** 세션 C 실제 실행 (**후속 Task — 본 task 비대상**)
  - 시나리오 2(k6 1,000 VU) + 시나리오 3(Kafka Consumer Lag 관찰) + 정합성 검증 + cleanup
  - 절차 상세는 갱신된 `loadtest/README.md` 를 따름
- [ ] **P11.** 리포트 작성 (**후속 Task — 본 task 비대상**)
  - `loadtest/reports/YYYY-MM-DD/REPORT.md` 작성, Grafana 스크린샷 첨부, k6 summary.json 첨부

### Part C — 이력 기록

- [ ] **P12.** `docs/progress/PHASE3.md` 에 2026-04-22 엔트리 신규 추가
  - 전환 결정 근거는 평가 문서(`loadtest-tool-evaluation.md`) 상호 참조
  - 변경 스코프를 다음 4항목으로 분리 기록:
    1. Part A 문서 동기화 완료 범위 (JMeter 언급 치환)
    2. P7 k6 스크립트 작성 + 로컬 리허설 결과 (`k6-summary.json` 생성 확인)
    3. P8-a · P9 문서 절차 갱신 (loadgen VM 전제조건 + Grafana dashboard 19665 import)
    4. **P8-b GKE monitoring values 1줄 변경 — `enableRemoteWriteReceiver: true` 추가**. ADR-0006 (monitoring 스택 환경 분리) 원칙에 따른 **GKE 한정 운영 세부 조정**이며 **minikube values 는 미변경** 임을 명시 (로컬 리허설은 Prometheus 미사용)
  - P10/P11 은 후속 세션 C 에서 수행됨을 명시

## 4. 영향 파일

| 파일 | 종류 | 변경 요지 |
|------|------|----------|
| `loadtest/scripts/order-concurrency.jmx` | 삭제 | 213줄 JMX, git history 보존 |
| `loadtest/scripts/order-concurrency.js` | 신규 | k6 스크립트 (§3 P7 스펙) |
| `loadtest/README.md` | 수정 | 디렉토리 구조 / 전제조건 / §A / §C / §E 갱신 |
| `loadtest/reports/TEMPLATE.md` | 수정 | 환경 표 도구 행 |
| `loadtest/sql/seed.sql` | 수정 | 주석 3개 문구 치환 (스키마 불변) |
| `k8s/monitoring/gke/values-prometheus.yml` | 수정 | `enableRemoteWriteReceiver: true` 1줄 추가 (P8-b, P9 전제조건) |
| `docs/01-project-overview.md` | 수정 | L34 / L64 도구명 |
| `docs/02-architecture.md` | 수정 | L100 mermaid 노드 레이블 |
| `docs/03-requirements.md` | 수정 | L84 도구명 |
| `docs/06-testing-strategy.md` | 수정 | L36 / L39 도구명 |
| `docs/07-roadmap-portfolio.md` | 수정 | L67 / L83 도구명 |
| `docs/TASKS.md` | 수정 | L395 / L396 Task 3-4 진행 중 행 (L487 이력은 불변) |
| `docs/progress/PHASE3.md` | 수정 | 2026-04-22 엔트리 신규 |
| `docs/adr/0004-phase3-gcp-gke-migration.md` | **불변** | immutable, 수정 금지 |
| `docs/00-lagacy.md` | **불변** | 레거시 보존, 수정 금지 |
| `docs/progress/loadtest-tool-evaluation.md` | **불변** | 의사결정 기록, 수정 금지 |

## 5. 검증 방법

- **문서 동기화 누락 검증 (라이브 문서 화이트리스트 방식)**
  - 다음 파일 집합에서 `grep -n -i 'jmeter\|\.jmx'` 결과가 0건이어야 한다:
    - `docs/01-project-overview.md`, `docs/02-architecture.md`, `docs/03-requirements.md`, `docs/06-testing-strategy.md`, `docs/07-roadmap-portfolio.md`
    - `loadtest/README.md`, `loadtest/reports/TEMPLATE.md`, `loadtest/sql/seed.sql`
  - `docs/TASKS.md` 는 별도 검증: `grep -n -i 'jmeter' docs/TASKS.md` 결과가 완료 작업 이력 행(현재 L487 인근) 1건만 남고 Task 3-4 진행 중 표(L395/L396)에는 0건
  - 다음 파일은 본 검증에서 **명시적 제외** (수정 금지 대상):
    - `docs/adr/0004-phase3-gcp-gke-migration.md` (ADR immutable)
    - `docs/00-lagacy.md` (레거시 보존)
    - `docs/progress/loadtest-tool-evaluation.md`, `docs/progress/PHASE3.md`, `docs/progress/PHASE2.md` (의사결정·이력)
    - `docs/plans/done/task-jmeter-to-k6.md` (본 계획서 자체 — "JMeter → k6 전환" 서술 다수)
    - `loadtest/reports/2026-04-09/*` (세션 B 과거 리포트)
- **k6 스크립트 로컬 검증** (P7 리허설 기준)
  - `mkdir -p loadtest/reports/local && k6 run --vus 10 --duration 30s --summary-export=loadtest/reports/local/k6-summary.json -e BASE_URL=http://localhost:8080 loadtest/scripts/order-concurrency.js`
  - 로그인 check 성공률 > 99%, 주문 응답 5xx 0건, verify-concurrency consistency=OK
  - Threshold exit code 0, `loadtest/reports/local/k6-summary.json` 생성 + `jq .metrics < ... ` non-empty
- **Prometheus receiver 활성화 검증** (P8-b)
  - `kubectl kustomize k8s/overlays/gke` 는 본 task 에서 미변경 (Helm values 만 수정)
  - 세션 C 실제 배포 후 smoke test: `curl -s -o /dev/null -w "%{http_code}" -X POST http://<prom>:9090/api/v1/write` 의 기대값은 **400 (receiver enabled, 빈 바디가 malformed write request 로 거절됨)**. **404 면 receiver 미활성 → values 반영 누락**. `204` 는 유효한 snappy+protobuf payload 성공 시에만 해당하므로 smoke test 기준에서 제외 (실전 확인은 k6 실행 시 자동)
- **회귀 없음 확인**
  - 앱 소스 미변경 — 기존 244건 테스트 선제 실행 불요
  - `k8s/overlays/{minikube,gke}` 미변경 — `kubectl kustomize` 회귀 불필요 (Helm values 는 overlays 와 독립)
- **범위 검증**
  - 세션 C 실제 과금 실행 및 리포트 작성은 본 task 완료 조건이 아님 (후속 Task)
  - Grafana k6 대시보드 JSON 매니페스트 추가는 본 task 완료 조건이 아님 (세션 C 당일 별도 PR)

## 6. 완료 조건

- Part A 문서 동기화가 완료되어 §5 grep 검증(라이브 문서 화이트리스트)이 통과한다
- P7 k6 스크립트가 작성되고 로컬 docker-compose 리허설이 성공하며, `k6-summary.json` 이 생성된다
- P8-a (loadgen VM 전제조건 문서), P8-b (`values-prometheus.yml` receiver 활성화), P9 (대시보드 import 절차) 가 반영된다
- Part C PHASE3 이력이 기록된다
- 세션 C 진입 직전 상태(k6 스크립트·문서·절차·receiver 활성화 매니페스트 완비)가 확보된다

## 7. 트레이드오프 / 비대상

- **세션 B 재측정 없음** — 이종 도구 하이브리드 구성을 포트폴리오 관점의 강점으로 유지 (평가 문서 §5)
- **k6 대시보드 JSON 선제 커밋 없음** — 실제 import·렌더링 검증 없이 매니페스트를 추가하면 SSOT 단일화 원칙(P1-F)의 역방향. 세션 C 실증 후 별도 PR
- **ADR 신규 작성 없음** — `loadtest/` 내부 도구 교체는 ADR 급 결정 아님 (평가 문서 §6). Prometheus remote-write receiver 활성화(P8-b)도 ADR-0006 "monitoring 스택 환경 분리"의 운영 세부사항 범위이므로 ADR 비대상 (부하 테스트 기간 외에는 클러스터 자체가 폐기)
- **nGrinder 제거 없음** — 시나리오 1·결제 처리·HPA 시나리오는 nGrinder 유지 (설계 문서 `docs/06-testing-strategy.md` 표의 nGrinder 행 불변)
- **앱 소스·Flyway·K8s overlays 변경 없음** — 본 task 는 부하 도구 교체 범위로 한정. 단 `k8s/monitoring/gke/values-prometheus.yml` 1줄 (receiver 활성화) 은 예외
- **"JMX 등가" 서술 없음** — P7 k6 스크립트는 1m hold 구간을 추가한 **강화 시나리오** (Grafana 타임라인 관찰 목적). 경합 정합성 판정 기준(재고 1,000 vs 총 주문 성공)은 시간 독립이므로 비교 가능성 유지
- **세션 C 실제 실행(P10) · 리포트 작성(P11)** — 후속 과금 세션에서 별도 수행

## 8. 커밋 / 브랜치 전략

**브랜치**: `refactor/phase3-jmeter-to-k6`

**커밋 구조 (4분할, rollback-safe)**

원칙: receiver-의존 변경(README §C k6 remote-write 블록 + P8-b values + P9 Grafana import 절차) 을 **하나의 커밋에 묶어** partial revert 시 "문서는 receiver 전제, 배포는 미활성" 상태로 분기되지 않도록 한다.

1. `refactor(loadtest): replace JMeter with k6 script` — P1 + P7 (JMX 삭제 + k6 스크립트 신규)
2. `docs: sync non-monitoring load-test references from JMeter to k6` — **receiver 비의존 문서만**:
   - P2 의 §디렉토리 / §전제조건 (P8-a loadgen VM k6 설치) / §A 시드 / §E 리포트 파일명
   - P3 TEMPLATE.md, P4 seed.sql 주석, P5 설계 문서(01/02/03/06/07), P6 TASKS.md 진행 중 표
3. `feat(monitoring): enable prometheus remote-write receiver for k6 + procedure` — **receiver-의존 일체를 1커밋**:
   - P8-b `k8s/monitoring/gke/values-prometheus.yml` 에 `enableRemoteWriteReceiver: true` 추가
   - P2 README §C 시나리오 2 실행 블록 (`k6 run -e BASE_URL=... -o experimental-prometheus-rw=...`)
   - P9 README §C Grafana 19665 import 절차 + port-forward 안내
4. `docs(progress): record JMeter→k6 migration in PHASE3` — P12 (PHASE3 이력, commit 1~3 반영 사실 기록)

**Revert 시나리오 검증**:
- commit 3 단독 revert → README §C / values / Grafana 절차가 동시에 되돌려짐 → 배포·문서 상태 정합
- commit 2 단독 revert → JMeter 복원이지만 receiver 는 활성 (무해한 drift — Prometheus 는 추가 엔드포인트가 열려있을 뿐)
- commit 1 단독 revert → k6 스크립트 제거, 문서는 여전히 k6 지칭 (명시적 불일치지만 실행 시 즉시 발견됨)

## 9. 리뷰 이력

### 2026-04-22 11:20 — GP-2 (loop 1)
- 리뷰 항목: 4건 (P0:1, P1:2, P2:1)
- 사용자 선택: [2] 전체 반영
- 적용 요약:
  - item 1 (P0) → §2 스코프 조정(매니페스트 1줄 예외), P8 에 P8-b 신설 (`values-prometheus.yml` + `enableRemoteWriteReceiver: true`), §4 영향 파일 추가, §5 receiver 검증 추가, §7 비대상 조정, §8 커밋 3번 신설
  - item 2 (P1) → P7 "JMX 등가" → "강화 시나리오" 재서술 + 근거(정합성 판정 시간 독립), §7 비대상 항목 신설
  - item 3 (P1) → P7 로컬 명령에 `--summary-export=loadtest/reports/local/k6-summary.json` 추가, P9 명령에 `--summary-export=loadtest/reports/YYYY-MM-DD/k6-summary.json` 추가, P2 §C·§E 문구 동기화, P3 TEMPLATE 산출물 명칭 통일, §5 검증에 `jq` 파싱 확인 추가
  - item 4 (P2) → §5 grep 을 라이브 문서 화이트리스트 방식으로 전면 교체 + `TASKS.md` 별도 검증 분리
- raw: `.cache/codex-reviews/plan-task-jmeter-to-k6-1776857286.json`
- run_id: `plan:20260422T112003Z:709f77c4-8fcc-4390-8cad-435db6bafe13:1`

### 2026-04-22 11:43 — GP-2 (loop 2)
- 리뷰 항목: 2건 (P0:0, P1:1, P2:1)
- 사용자 선택: [2] 전체 반영
- 적용 요약:
  - item 1 (P1) → P8-b 검증 명령의 기대값을 `200/204` → `400(enabled) / 404(disabled)` 로 수정 (빈 바디 smoke test 는 snappy+protobuf 가 아니므로 Prometheus 가 400 반환이 정상). §5 검증 기준도 동일 기준으로 통일. `204` 는 smoke test 범위에서 제외하고 실전 k6 실행으로 확인
  - item 2 (P2) → P12 PHASE3 이력 지시를 4항목(문서 동기화 / P7 리허설 / P8-a·P9 문서 / **P8-b GKE values 1줄 + minikube 미변경**) 으로 분리. ADR-0006 환경 분리 원칙 맥락 명시
- raw: `.cache/codex-reviews/plan-task-jmeter-to-k6-1776858074.json`
- run_id: `plan:20260422T114000Z:969faa0f-1123-4c81-a071-6ee9d69b18c3:2`

### 2026-04-22 11:49 — GP-2 (loop 3, 최종 게이트)
- 리뷰 항목: 2건 (P0:0, P1:2, P2:0)
- 사용자 선택: [2] 전체 반영
- 적용 요약:
  - item 1 (P1) → P2 README §C 갱신 지시 + P9 실행 명령 둘 다 `-e BASE_URL=http://<internal-lb>:8080` 명시 추가. `<internal-lb>` 확보 절차(`kubectl get svc`) 도 함께 서술
  - item 2 (P1) → §8 커밋 4분할 재편. receiver-의존 일체(README §C k6 remote-write 블록 + P8-b values + P9 Grafana import 절차) 를 commit 3 에 묶어 rollback-safe 구조로 전환. commit 2 는 receiver 비의존 문서만 보유. §8 에 revert 시나리오 검증 서술 추가
- raw: `.cache/codex-reviews/plan-task-jmeter-to-k6-1776858461.json`
- run_id: `plan:20260422T114741Z:c82e4977-0908-4d10-a4c4-9b2560b73128:3`
- 비고: attempts 3/3 상한 도달 — 추가 /plan 호출 시 사용자 명시 확인 필요
