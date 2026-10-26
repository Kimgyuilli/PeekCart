# k6 도입 검토 보고서 — Phase 3 Task 3-4 세션 C

> 작성 시점: 2026-04-18
> 대상 Task: Phase 3 Task 3-4 세션 C (시나리오 2: 1,000 VUser 동시 주문 + 시나리오 3: Kafka Lag)
> 성격: 도구 선택 평가 (ADR 급 결정 아님 — `loadtest/` 내부 변경 범위)

---

## 1. 결론 (먼저)

**권장: 부분 전환 (JMeter → k6). 세션 B 의 nGrinder 결과는 그대로 유지.**

비용 절감은 부차적(≈ ₩300/세션)이고, 핵심 가치는 다음 네 가지.

1. Task 3-3 Grafana 스택과의 **네이티브 통합** (Prometheus remote write → Grafana 대시보드 19665)
2. JMX XML 213줄 → **JS ~60줄** 로 PR 리뷰 가능
3. **D-004 (nGrinder JDK 11 강제) 회피** — loadgen VM 에서 JVM 자체 제거 가능
4. 포트폴리오에서 "두 세대 도구(전통 nGrinder + 모던 code-as-test k6)" 를 모두 써본 서사

### 전환하지 말아야 할 조건

세션 C 를 한 주 내로 실행할 계획이고 JMX 가 이미 검증되어 있다면 그대로 진행하는 것도 합리적. k6 학습 + 스크립트 포팅 + pre-flight 리허설에 약 3~4 시간이 들고, 이 시간이 Task 3-5(HPA 검증)를 미루게 한다면 ROI 는 미묘.

---

## 2. 세 도구의 본질적 차이

| 축 | nGrinder | JMeter | k6 |
|---|---|---|---|
| 언어/런타임 | JVM (Groovy) | JVM (XML→Thread) | Go (JS via Goja) |
| 스크립트 형식 | Groovy 코드 | **JMX XML** (GUI 권장) | **JavaScript ES6** |
| 실행 모델 | Controller + Agent (web UI) | Master/Slave + GUI/CLI | CLI 단일 바이너리 |
| VU 당 메모리 | OS thread (~1MB) | OS thread (~1MB) | goroutine (수 KB) |
| 단일 머신 VU 한도 | ~1,000 | ~1,000 (tuning 후 수천) | **30,000~40,000** (단일 프로세스) |
| 같은 부하 기준 메모리 | 수백 MB | ~760 MB | **~256 MB** |
| Prometheus 출력 | 플러그인 필요 | Backend Listener 플러그인 | **기본 내장** (`--out experimental-prometheus-rw`) |
| Grafana 대시보드 | 별도 구성 | 별도 구성 | **공식 대시보드 2종** (ID 19665) |
| Git/코드리뷰 친화성 | 보통 (Groovy) | **나쁨** (XML diff 불가) | **우수** (JS diff/모듈화) |
| K8s 분산 실행 | — | JMeter Operator (서드파티) | **k6-operator (공식)** |
| JDK 버전 요구 | **JDK 11 고정** (D-004) | JDK 8/11/17 | 없음 |
| 한국 엔터프라이즈 친숙도 | **높음** (NHN 출신) | 높음 | 중간 (상승 중) |

### 2-1. nGrinder 의 포지션

NHN 이 Grinder 를 포크해 웹 UI 를 얹은 도구. 한국 QA/SRE 조직에서 친숙. 약점은 ① 컨트롤러/에이전트 분리 구조가 VM 2대를 요구, ② JDK 11 고정(D-004), ③ worker process fork 모델로 VU 한도가 낮음, ④ 모니터링은 내장 리포트 수준.

### 2-2. JMeter 의 포지션

가장 오래되고(1998~) 플러그인 생태계가 넓음. **다양한 프로토콜 지원**(JDBC, FTP, LDAP, JMS, TCP 등)이 필요하면 여전히 1순위. 약점은 ① XML 테스트 플랜이 PR 리뷰 불가, ② 자원 사용량 높음, ③ Prometheus 출력은 플러그인에 의존.

### 2-3. k6 의 포지션

Grafana Labs 산. **CI/CD · 코드리뷰 · 관측성 통합** 을 3대 셀링 포인트로 삼은 모던 도구. `k6 run script.js` 한 줄 실행. Grafana 가 만들었기 때문에 **Prometheus remote write → Grafana 대시보드** 가 사실상 1급 시민.

---

## 3. PeakCart 컨텍스트에서의 정량 비교

### 3-1. 리소스 / 비용

현재 loadgen VM 스펙(ADR-0004 / `loadtest/README.md` §전제조건): **e2-standard-2 (2 vCPU / 8GB), asia-northeast3-a, 온디맨드 ≈ $0.09/hr ≈ ₩120/hr**.

- **JMeter 1,000 VU**: 스레드당 ~1MB + heap 2~3GB 권장 → e2-standard-2 의 8GB 가 빠듯. 동일 VM 에 nGrinder agent 까지 올리면 경합.
- **k6 1,000 VU**: 공식 벤치마크 기준 "light load", ~500MB~1GB 메모리 + 1 vCPU 로 충분. **e2-small (1 vCPU / 2GB), 시간당 $0.022 ≈ ₩30/hr 로 다운사이징 가능**.

세션 C 약 4시간 가동 기준:

- JMeter 유지: **₩480**
- k6 전환 (VM 다운사이징): **₩120**
- **절감액: ₩360**

**비용 절감은 사실상 무시 가능**. 세션 C 의 불확실성 대비 VM 다운사이징 리스크가 더 크므로, **현실적으로는 e2-standard-2 유지 + k6 로 VM 여유분을 확보** 하는 판단이 낫다. 여유분은 jtl 파싱·Prometheus 스크레이핑·모니터링 에이전트에 소진.

### 3-2. 스크립트 복잡도 (현재 JMX 대비)

현재 `order-concurrency.jmx`: **213 줄 XML**, ThreadGroup + CSVDataSet + 3개 HTTPSamplerProxy + JSONPostProcessor + 3개 ResponseAssertion. Git 에서 PR 리뷰 불가 (diff 가 사람이 읽을 형식 아님).

k6 동등 스크립트 추정 분량: **~60~80 줄 JS**.

```js
// loadtest/scripts/order-concurrency.js (개략)
import http from 'k6/http';
import { check, SharedArray } from 'k6';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';

const users = new SharedArray('users', () =>
  papaparse.parse(open('./users.csv'), { header: true }).data);

export const options = {
  scenarios: {
    contention: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [{ duration: '30s', target: 1000 }, { duration: '1m', target: 1000 }],
    },
  },
  thresholds: {
    'http_req_failed{scenario:contention}': ['rate<0.1'],  // 재고소진 409는 허용
  },
};

export default function () {
  const u = users[__VU % users.length];
  const login = http.post(`${__ENV.BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email: u.email, password: u.password }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
  check(login, { 'login 200': (r) => r.status === 200 });

  const token = login.json('data.accessToken');
  const auth = { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };

  const pid = 1001 + Math.floor(Math.random() * 10);
  http.post(`${__ENV.BASE_URL}/api/v1/cart/items`,
    JSON.stringify({ productId: pid, quantity: 1 }), { ...auth, tags: { name: 'cart' } });

  const order = http.post(`${__ENV.BASE_URL}/api/v1/orders`,
    JSON.stringify({ receiverName: 'LT', phone: '010-0000-0000', zipcode: '06236', address: 'Seoul' }),
    { ...auth, tags: { name: 'order' } });
  check(order, { 'order 201|409|400': (r) => [201, 409, 400].includes(r.status) });
}
```

추가로 **threshold 로 합격/불합격이 exit code 에 반영** → CI 통합이 자연스럽다.

### 3-3. Task 3-3 Grafana 스택과의 통합

현재 Task 3-3 에서 구성된 것:

- `kube-prometheus-stack` (monitoring NS)
- 커스텀 대시보드 3종: API&JVM, Kafka Lag, Pod Resources&HPA
- Alert 규칙 (5xx > 5%, p95 > 2s)

k6 를 쓰면 **추가 설치 없이** `k6 run -o experimental-prometheus-rw` 로 Prometheus 에 `k6_*` 시리즈가 실시간 기록. Grafana 에 **대시보드 ID 19665 (`k6 Prometheus`) 를 import** 하면 TPS/p95/VU/에러율이 서버 측 대시보드와 같은 Grafana 인스턴스에서 side-by-side 로 보인다.

> **포트폴리오 가치**: "부하 발생기 지표" 와 "서버 관측성 지표" 가 **동일 Grafana 에서 시간축 정렬** → 단일 스크린샷으로 "p95 급증 순간 Pod CPU 튐" 을 보여줄 수 있다. JMeter 로는 jtl 파일 HTML 리포트 + Grafana 가 분리되어 타임라인 정합성 맞추기 수작업 필요.

### 3-4. D-004 (nGrinder JDK 11) 와의 관계

세션 B 에서 loadgen VM 에 JDK 11 을 default 로 설정했음. **세션 C 에서 JMeter 를 쓰려면 JDK 11 로도 OK (JMeter 는 11 호환)**. 그러나 nGrinder agent 를 재사용하는 시나리오는 없으므로 **k6 를 쓰면 JVM 자체를 제거 가능** (loadgen VM provisioning 단순화).

---

## 4. 조합 전략 3가지

### 옵션 A — 현상 유지 (nGrinder + JMeter)

**장점**: 전환 비용 0. 세션 C 스크립트 이미 검증됨. 절차(`loadtest/README.md` §C)가 문서화되어 있음.

**단점**: Grafana 통합 없음. JMX 는 PR 리뷰 불가. 향후 Phase 4 CI 통합 시 재작업 필요.

### 옵션 B — JMeter → k6 (**권장**)

**전환 범위**:

- `loadtest/scripts/order-concurrency.jmx` → `order-concurrency.js` 신규 작성 (기존 JMX 는 `legacy/` 로 이관 or 삭제)
- `loadtest/README.md` §C 섹션 갱신 (`jmeter -n -t ... ` → `k6 run -o experimental-prometheus-rw=... script.js`)
- loadgen VM provisioning 에서 JDK 의존성 제거, k6 바이너리 설치(`gpg --dearmor ... && apt install k6`)

**유지 범위**:

- nGrinder 세션 B 결과(`loadtest/reports/2026-04-09/REPORT.md`) 그대로
- `users.csv`, `seed.sql`, `verify-concurrency.sql`, `cleanup.sh` 그대로
- 시나리오 3 (Kafka Lag) 은 별도 부하 발생 없음 → 변경 불필요

**소요 시간 추정**: 2~3시간 (스크립트 전환 1h + README 갱신 30m + 로컬 docker-compose 리허설 1h).

**추가 과금**: 로컬 리허설은 무료. 세션 C 재프로비저닝 시 k6 대시보드 import 정도 추가.

### 옵션 C — 완전 전환 (세션 B 재측정 포함)

**비추천**. 세션 B 는 이미 실측 완료 (`loadtest/reports/2026-04-09/REPORT.md`). 재측정은 과금만 늘고 얻는 게 없음. 게다가 **이종 도구 경험 2건** 이 포트폴리오에서 오히려 강점 (nGrinder 의 한국 친화성 + k6 의 모던 성질 둘 다 어필).

---

## 5. 포트폴리오 서사 관점

| 관점 | 옵션 A (기존) | 옵션 B (권장) |
|---|---|---|
| 측정 스택 계보 | 전통 GUI 도구 2개 | 전통(nGrinder) + 모던(k6) 하이브리드 |
| 관측성 통합 | JMeter HTML 리포트 + Grafana 분리 | **Grafana 단일 대시보드에 부하·서버 동시** |
| 코드 리뷰성 | JMX XML diff 불가 | 스크립트 Git PR 가능 |
| CI 확장 여지 | JMeter Maven plugin 필요 | **GitHub Actions `grafana/k6-action` 1줄** |
| "왜 이 도구를 선택했나" 스토리 | "업계 표준" | **"시나리오별로 가장 적합한 도구를 선택하는 판단력"** |

포트폴리오 면접에서 "k6 는 왜 안 썼나요?" 대신 "nGrinder 와 k6 를 어떻게 나눠 썼나요?" 로 질문이 뒤집힘. 후자가 훨씬 답하기 좋다.

---

## 6. 전환 리스크 / 체크리스트

| 리스크 | 대응 |
|---|---|
| k6 로 `__Random(1001,1010)` 동등 표현 확인 필요 | `1001 + Math.floor(Math.random() * 10)` — 1줄. 동등 |
| JSON POST 로그인 후 토큰 추출 검증 필요 | `resp.json('data.accessToken')` — 1줄 |
| CSV 파일 로딩 성능 (1,100 users 공유) | `SharedArray` 로 VU 간 공유, 메모리 1회만 점유 (k6 권장 패턴) |
| Prometheus remote-write endpoint URL | monitoring/prometheus-k8s:9090/api/v1/write 포트포워드 or Service 노출 필요. 세션 C 에서 VPC 내부 통신으로 해결 |
| 로컬 pre-flight 리허설 환경 | `docker-compose up` + `k6 run` 로컬로 가능. 세션 A 가 했던 리허설 패턴 재활용 |
| ADR 필요 여부 | **옵션 B 는 ADR 수준 결정은 아님** (이전의 minikube→GKE, Kustomize 재구조화 급 결정은 아니며 도구 교체는 `loadtest/` 내부 변경). PHASE3 작업 이력에 결정 근거만 기록하면 충분 |

---

## 7. 실행 계획 (옵션 B 선택 시)

1. **로컬에서 `order-concurrency.js` 작성 + 검증** (docker-compose 환경, 50~100 VU 로 스모크) — 1.5h
2. **`loadtest/README.md` §C 갱신** — 30m
3. **loadgen VM provisioning 스크립트에서 JMeter 제거, k6 설치 추가** — 30m
4. **Grafana 대시보드 ID 19665 + (선택) 19666 을 `k8s/monitoring/shared/` 에 추가** (대시보드 SSOT 단일화 원칙 — P1-F 와 동일 구조) — 30m
5. **`docs/progress/PHASE3.md` 에 전환 근거·절차 기록** — 15m
6. **세션 C 에서 k6 로 시나리오 2 실행, Grafana k6 대시보드 + PeekCart API 대시보드 side-by-side 캡처** — 기존 세션 C 계획 그대로

**TASKS.md Task 3-4 수정 범위**:

- "JMeter 설치 + 설정 (loadgen VM)" → "k6 설치 + Grafana k6 대시보드 import"
- 시나리오 2 비고: "JMeter" → "k6"
- `order-concurrency.jmx` 제거 또는 legacy 로 이동 표기

---

## 8. 참고 자료

- [Comparing k6 and JMeter for load testing — Grafana Labs](https://grafana.com/blog/k6-vs-jmeter-comparison/)
- [K6 vs JMeter : Which Is Better? — PFLB](https://pflb.us/blog/k6-vs-jmeter/)
- [K6 vs JMeter in 2026 — Modern vs Legacy Performance Testing — QASkills](https://qaskills.sh/blog/k6-vs-jmeter-performance-testing)
- [JMeter vs Gatling vs k6: The Complete 2026 Comparison — Vervali](https://www.vervali.com/blog/jmeter-vs-gatling-vs-k6-the-complete-2026-comparison-benchmarks-ci-cd-scripting-and-use-cases/)
- [Modern Load Testing with Grafana k6: JavaScript vs JMeter XML — Dev|Journal](https://earezki.com/ai-news/2026-03-29-grafana-k6-has-a-free-load-testing-tool-write-performance-tests-in-javascript-not-xml/)
- [k6 Operator (GitHub) — grafana/k6-operator](https://github.com/grafana/k6-operator)
- [Running distributed tests — Grafana k6 documentation](https://grafana.com/docs/k6/latest/testing-guides/running-distributed-tests/)
- [Prometheus remote write — Grafana k6 documentation](https://grafana.com/docs/k6/latest/results-output/real-time/prometheus-remote-write/)
- [k6 Prometheus dashboard (ID 19665) — Grafana Labs](https://grafana.com/grafana/dashboards/19665-k6-prometheus/)
- [Running large tests — Grafana k6 documentation](https://grafana.com/docs/k6/latest/testing-guides/running-large-tests/)
- [Scalable Load Testing with K6 Operator, Prometheus, and Grafana on K8s — Medium](https://medium.com/@elyasimt/scalable-load-testing-with-k6-operator-prometheus-and-grafana-on-k8s-e8dac5062f7c)
