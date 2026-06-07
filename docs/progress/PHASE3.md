# Phase 3 진행 보고서 — 인프라 / 테스트

> Phase 3 작업 이력, 주요 결정 사항, 이슈 기록
> 작업 상태 추적은 `docs/TASKS.md` 참고

---

## Phase 3 목표

**Exit Criteria**:
- [x] K8s에 모든 서비스 정상 배포 확인
- [x] Grafana 대시보드에서 API 응답시간/에러율/Kafka Lag 모니터링 확인 (세션 B: RPS/JVM/Pod, 세션 C: HPA/Kafka Lag — D-001 1차 수정 후 histogram bucket 정상 노출)
- [x] 부하 테스트 리포트 완성 (세션 B 캐싱 전/후 TPS + 세션 C 시나리오 2/3 + Task 3-5 + D-002 데이터 통합)
- [x] HPA 동작 확인 (Pod 1→3 자동 증설 + Grafana 스크린샷 — 세션 C Run 1)

→ **Phase 3 종결 (2026-04-29)**. 다음: Phase 4 MSA 분리 준비.

---

## 작업 이력

### 2026-04-03

#### Phase 3 Task 정의

**완료 항목**:
- `docs/TASKS.md`에 Phase 3 Task 5개 정의 (Task 3-1 ~ 3-5)
- `docs/progress/PHASE3.md` 생성

**Task 구성**:
1. Task 3-1: GitHub Actions CI (빌드·테스트·Docker 이미지 파이프라인)
2. Task 3-2: minikube K8s 배포 (매니페스트 작성, 서비스 배포)
3. Task 3-3: kube-prometheus-stack (Prometheus + Grafana 구축)
4. Task 3-4: 부하 테스트 (nGrinder + JMeter, TPS 측정)
5. Task 3-5: HPA 검증 (Order Service 자동 스케일아웃)

#### Task 3-1: GitHub Actions CI 완료

**완료 항목**:
- `Dockerfile` — 멀티스테이지 빌드 (eclipse-temurin:17-jdk → 17-jre), 의존성 레이어 캐싱, non-root 유저 실행
- `.github/workflows/ci.yml` — PR/push 트리거, 단일 job 구조 (빌드+테스트 + 조건부 GHCR push)
- 로컬 Docker 빌드 검증 완료

**주요 결정**:
- **런타임 이미지**: `eclipse-temurin:17-jre` (non-alpine) 선택. Alpine은 ARM64(Apple Silicon) 미지원으로 로컬 빌드 불가. CI(amd64)에서는 동작하지만 로컬 개발 호환성 우선.
- **CI 구조**: 단일 job. test와 Docker push를 분리하면 artifact 전달이 필요해 복잡도 증가. 조건부 step(`if: github.ref == 'refs/heads/main'`)으로 해결.
- **Testcontainers CI 설정**: 추가 설정 없음. ubuntu runner에 Docker 내장, `@ServiceConnection`이 자동 설정하므로 DinD 불필요.
- **이미지 태그**: `latest` + `${{ github.sha }}` — SHA로 추적성, latest로 편의성.

### 2026-04-04

#### Task 3-1: 코드 리뷰 개선 (P0~P1)

**완료 항목**:
- `.dockerignore` 추가 — `.git`, `build/`, `.gradle/`, `docs/`, `.github/` 등 빌드 컨텍스트 제외
- `build.gradle`에 `bootJar { archiveFileName = 'app.jar' }` + `jar { enabled = false }` — JAR 파일명 고정, plain JAR 비활성화
- `Dockerfile` COPY 대상 `*.jar` → `app.jar` 명시적 참조
- `ci.yml`에 `concurrency` 설정 추가 (동일 ref 중복 워크플로우 자동 취소)
- `ci.yml`에 `docker/setup-buildx-action@v3` + `cache-from`/`cache-to: type=gha` — Docker 레이어 캐시 CI 간 공유

**주요 결정**:
- **JAR 파일명 고정**: `archiveFileName = 'app.jar'`로 글로브 패턴 제거. Dockerfile에서 명시적으로 참조하여 복수 JAR 빌드 시 실패 방지.
- **Docker 캐시 전략**: GitHub Actions Cache(`type=gha`) 사용. Registry 캐시 대비 설정 간편하고 추가 인증 불필요.

#### Task 3-2: minikube K8s 배포 완료

**완료 항목**:
- `build.gradle`에 `spring-boot-starter-actuator` 의존성 추가
- `SecurityConfig`에 `/actuator/health/**` 공개 URL 추가 (K8s Probe 인증 없이 접근)
- `application-k8s.yml` — K8s Service DNS 접속 (`mysql:3306`, `redis:6379`, `kafka:29092`), Actuator health probe 노출
- `k8s/namespace.yml` — `peekcart` 네임스페이스
- `k8s/infra/mysql-deployment.yml` — MySQL 8.0 Deployment + Service + PVC(1Gi), readiness/liveness probe
- `k8s/infra/redis-deployment.yml` — Redis 7.2 Deployment + Service, readiness/liveness probe
- `k8s/infra/kafka-deployment.yml` — Apache Kafka 3.8.1 KRaft 단일 노드, Deployment + Service
- `k8s/app/configmap.yml` — `SPRING_PROFILES_ACTIVE=k8s`
- `k8s/app/secret.yml` — DB/JWT/Toss/Slack 키 (`stringData`)
- `k8s/app/peekcart-deployment.yml` — GHCR 이미지, Actuator Liveness/Readiness Probe, NodePort 30080

**주요 결정**:
- **외부 접근 방식**: NodePort(30080) 선택. minikube 환경에서 Ingress Controller 설치보다 단순하고 `minikube service` 명령으로 즉시 접근 가능.
- **Actuator 도입**: Liveness/Readiness Probe를 위해 `spring-boot-starter-actuator` 추가. `/actuator/health/liveness`, `/actuator/health/readiness` 엔드포인트만 노출하여 최소 공개.
- **인프라 리소스 제한**: minikube 8GB 제약 내에서 MySQL(512Mi~1Gi), Redis(128Mi~256Mi), Kafka(512Mi~1Gi), App(512Mi~1Gi) 할당.
- **Kafka 리스너 구조**: docker-compose의 EXTERNAL 리스너 제거. K8s 내부 통신은 `PLAINTEXT://kafka:29092`로 통일 (Service DNS).
- **imagePullPolicy: Never**: GHCR private 레포 인증 문제 회피. `eval $(minikube docker-env)` + 로컬 빌드로 minikube에 직접 이미지 적재.

#### Task 3-2: 코드 리뷰 개선 (P1~P2)

**완료 항목**:
- `k8s/infra/mysql-deployment.yml` — MySQL 크레덴셜 하드코딩 제거, `secretKeyRef`로 `peekcart-secret` 참조
- `k8s/infra/redis-deployment.yml` — PVC 512Mi 추가 + `volumeMount` (JWT 블랙리스트 영속화)
- `k8s/infra/kafka-deployment.yml` — PVC 1Gi 추가 + `volumeMount` (미소비 메시지 유실 방지)
- `k8s/app/peekcart-deployment.yml` — `startupProbe` 추가 (`failureThreshold: 30`, `periodSeconds: 5`, 최대 150초 기동 대기), readiness/liveness에서 `initialDelaySeconds` 제거 (startupProbe가 대체)
- 전체 매니페스트에 K8s 권장 labels 추가 (`app.kubernetes.io/name`, `app.kubernetes.io/component`, `app.kubernetes.io/part-of`)

**주요 결정**:
- **MySQL 크레덴셜 Secret 통합**: MySQL env에 하드코딩된 비밀번호를 `peekcart-secret`의 `DB_USERNAME`/`DB_PASSWORD`로 통일. 크레덴셜 관리 포인트 단일화.
- **Redis/Kafka PVC 추가**: Pod 재시작 시 JWT 블랙리스트 유실(보안 이슈) 및 Kafka 미소비 메시지 유실 방지. MySQL과 동일하게 PVC 영속화.
- **startupProbe 도입**: Spring Boot 기동 시간이 `livenessProbe.initialDelaySeconds`를 초과하면 Pod가 kill되는 문제 방지. startupProbe가 기동 완료를 보장한 후 liveness/readiness가 동작.
- **K8s 권장 labels**: Task 3-3 ServiceMonitor selector 설정 연계를 위해 `app.kubernetes.io/*` labels 사전 추가.

### 2026-04-05

#### Task 3-3: kube-prometheus-stack 완료

**완료 항목**:
- `build.gradle` — `micrometer-registry-prometheus` + `logstash-logback-encoder:8.0` 의존성 추가
- `application-k8s.yml` — Actuator 엔드포인트 `health,prometheus` 노출
- `SecurityConfig` — `/actuator/prometheus` 공개 URL 추가 + `MdcFilter` 등록 (JwtFilter 뒤)
- `logback-spring.xml` — `springProfile` 기반 분기: k8s=JSON (`LogstashEncoder`), local=plain text (Spring Boot 기본)
- `MdcFilter` — 요청마다 `traceId`(UUID 16자리) + `userId`(SecurityContext) MDC 설정
- `k8s/monitoring/values-prometheus.yml` — minikube 경량 Helm values (총 ~1.2GB, Alertmanager 비활성화)
- `k8s/monitoring/install.sh` — Helm 설치 스크립트
- `k8s/monitoring/servicemonitor.yml` — PeekCart 메트릭 스크래핑 (15초 간격, `/actuator/prometheus`)
- `k8s/app/peekcart-deployment.yml` — Service 포트 `name: http` 추가 (ServiceMonitor 연계)
- `k8s/monitoring/dashboards/` — Grafana 대시보드 3개 (API&JVM, Kafka Lag, Pod Resources&HPA) + ConfigMap 프로비저닝
- `k8s/monitoring/alerts/grafana-alerts.yml` — Grafana unified alerting 규칙 2개 (5xx 에러율 5% 초과, p95 응답시간 2s 초과)

**주요 결정**:
- **트레이싱 방식**: MDC UUID 수동 생성. Brave/Zipkin 미도입 — 모놀리스 Phase에서 cross-service tracing 불필요. Phase 4 MSA 전환 시 Micrometer Tracing 도입 예정.
- **Actuator 노출 범위**: `health,prometheus`만 노출. `metrics`, `env` 등은 보안상 비노출. SecurityConfig에 `/actuator/prometheus` 단일 추가 (와일드카드 `/actuator/**` 회피).
- **로깅 분기 전략**: `logback-spring.xml`의 `springProfile`로 k8s/local 분기. 로컬 개발 영향 없음 (plain text 유지). `LogstashEncoder`가 MDC 필드(`traceId`, `userId`, `orderId`)를 JSON에 자동 포함.
- **모니터링 네임스페이스 분리**: `monitoring` 네임스페이스 별도 생성. kube-prometheus-stack CRD/RBAC를 `peekcart`와 분리하여 관심사 격리.
- **Alertmanager 비활성화**: Grafana unified alerting으로 대체. 스택 단순화 + minikube 리소스 절약.
- **Kafka 모니터링**: JMX exporter 없이 Micrometer consumer lag 메트릭만 수집. `kafka_consumer_fetch_manager_records_lag_max`로 consumer 관점 lag 모니터링 충분.
- **리소스 할당**: Prometheus 512Mi, Grafana 256Mi, node-exporter 128Mi, kube-state-metrics 128Mi, operator 128Mi. 모니터링 총 ~1.2GB — 기존 인프라 ~2.5GB + App 1Gi와 합쳐 minikube 8GB 내 수용.

#### Task 3-3: 코드 리뷰 개선 (P0~P2)

**완료 항목**:
- `application-k8s.yml` — `management.metrics.tags.application: peekcart` 추가. 모든 PromQL 쿼리의 `{application="peekcart"}` 레이블 매칭 보장 (P0)
- `OrderCommandService` — `createOrder`/`cancelOrder`/`cancelExpiredOrder`에 `MDC.put("orderId", ...)` 추가 (P1)
- `OrderEventConsumer`/`PaymentEventConsumer`/`NotificationConsumer` — Kafka Consumer 5개 핸들러에 `MDC.put("orderId", ...)` + `try-finally MDC.remove()` 추가 (P1)
- `values-prometheus.yml` — Grafana `additionalDataSources`에 `uid: prometheus` 명시적 프로비저닝 (P1)
- `values-prometheus.yml` — subchart 리소스 키 수정: `nodeExporter` → `prometheus-node-exporter`, `kubeStateMetrics` → `kube-state-metrics` (P2)
- `values-prometheus.yml` — `retention: 2h` → `6h` (Task 3-4 부하 테스트 사후 분석 여유 확보) (P2)
- `configmap.yml` + `pod-resources-dashboard.json` — Pod CPU Usage 단위 `short` → `percentunit` (P2)
- `install.sh` — `helm install` → `helm upgrade --install` 멱등성 확보 (P2)

**주요 결정**:
- **P0 metrics.tags.application**: Spring Boot 3.x에서 `spring.application.name`이 Micrometer `application` 태그에 자동 매핑되지 않음. `management.metrics.tags.application` 명시 필수. 이 설정 없이는 대시보드 6개 패널 + Alert 2개 모두 `No data`.
- **orderId MDC 위치**: HTTP 요청 흐름은 MdcFilter가 `MDC.clear()`하므로 `MDC.put`만 호출. Kafka Consumer는 MdcFilter가 동작하지 않으므로 `try-finally { MDC.remove("orderId") }`로 명시적 정리.

#### Task 3-3: minikube 검증 + 매니페스트 수정

**완료 항목**:
- `k8s/app/peekcart-deployment.yml` — Service `metadata.labels`에 `app: peekcart` 추가. ServiceMonitor `selector.matchLabels`는 Service의 메타데이터 레이블을 매칭하므로 필수.
- `k8s/monitoring/servicemonitor.yml` — `release: kube-prometheus-stack` 레이블 추가. Prometheus `serviceMonitorSelector`가 이 레이블을 요구.
- `k8s/monitoring/values-prometheus.yml` — `serviceMonitorNamespaceSelector: {}` 전체 네임스페이스 허용. `peekcart` 전용이면 `monitoring` 네임스페이스의 kubelet/kube-state-metrics 등이 dropped.
- `k8s/monitoring/values-prometheus.yml` — `additionalDataSources` 제거. Helm 차트가 자동 프로비저닝하는 기본 datasource(uid: `prometheus`)와 중복되어 Grafana 기동 실패.

**minikube 검증 결과**:

| 항목 | 상태 | 비고 |
|---|---|---|
| `application="peekcart"` 메트릭 태그 | ✅ | `curl /actuator/prometheus`로 검증 |
| Prometheus 타겟 | ✅ | 16개 active (peekcart + kubelet + kube-state-metrics 등) |
| API & JVM 대시보드 | ⚠️ 부분 | JVM Heap/GC Pause 정상. API Response Time/Error Rate/RPS는 HTTP 트래픽 없어 No data (정상) |
| Kafka Consumer Lag 대시보드 | ✅ | 전체 패널 데이터 표시 |
| Pod Resources & HPA 대시보드 | ⚠️ 부분 | Pod Restarts 정상. CPU/Memory는 cAdvisor 수집 지연 추정. HPA는 Task 3-5에서 설정 예정 |
| Grafana Alert | ❓ | Alerting → Alert rules 확인 필요 |

**추가 확인 필요 항목** (Task 3-4 부하 테스트 시 연계 확인):
- API Response Time/Error Rate/RPS 패널: HTTP 트래픽 발생 후 데이터 표시 여부
- Pod CPU/Memory 패널: cAdvisor 메트릭 수집 정상화 여부
- Grafana Alert 2개: 프로비저닝 + 동작 확인

**주요 결정**:
- **serviceMonitorNamespaceSelector 전체 허용**: minikube 단일 클러스터에서 네임스페이스를 제한하면 monitoring 네임스페이스의 기본 ServiceMonitor(kubelet, kube-state-metrics 등)가 전부 dropped. 보안보다 관측성 우선.
- **additionalDataSources 제거**: kube-prometheus-stack Helm 차트가 기본 Prometheus datasource를 `uid: prometheus`로 자동 프로비저닝. 수동 추가 시 "Only one datasource per organization can be marked as default" 에러로 Grafana 기동 실패.

---

### 2026-04-06

#### Phase 3 GCP/GKE 재설계 — 환경 전환 + 문서/매니페스트 정합화

**배경**: Task 3-4 (부하 테스트) 계획 수립 중, 16GB 노트북에서 minikube(8GB) + nGrinder/JMeter 동시 구동 시 측정 정확도가 보장되지 않는 점이 명시적으로 드러남. 마침 GCP 신규 가입 크레딧 ₩453,008 확보 → Phase 3 후반(Task 3-4 ~ ) 부터 GCP/GKE 로 환경 전환 결정. 단순 환경 변경이 아니라 기존 설계 문서가 "로컬 호스팅"을 가정하고 작성되어 있었으므로, **부하 테스트 착수 전에 문서·ADR·매니페스트를 일관되게 재정렬**하는 작업으로 확장.

**작업 브랜치**: `refactor/phase3-gcp-redesign`

**완료 항목** (6개 세션 순차 실행):

| 세션 | 작업 | 산출물 |
|---|---|---|
| 1 | ADR 인프라 구축 | `docs/adr/template.md`, `docs/adr/README.md`(인덱스 + 원칙), `docs/check-consistency.sh` |
| 2 | ADR 0001~0005 작성 | `0001-layered-ddd-architecture` (Accepted) · `0002-monolith-to-msa-evolution` (Accepted) · `0003-phase3-initial-minikube` (Partially Superseded) · `0004-phase3-gcp-gke-migration` (Accepted) · `0005-kustomize-base-overlays-structure` (Accepted) |
| 3 | Layer 1 핵심 문서 갱신 | `01-project-overview.md` §4 운영 환경 신설 (SSOT), `04-design-deep-dive.md` §10-7 환경 맥락 재구성, `07-roadmap-portfolio.md` Phase 3 환경 전환 노트 |
| 4 | Layer 1 파생 문서 + TASKS 갱신 | `02-architecture.md` §4-3/§12 Phase 3 인프라 트리 추가, `03-requirements.md` §7-1 측정 환경 명시, `TASKS.md` Task 3-2/3-4/3-5 환경 정정, `CLAUDE.md` ADR 참조 맵 추가 |
| 5 | 매니페스트 재구조화 | `k8s/{app,infra,monitoring}/` 평면 → `k8s/base/` + `k8s/overlays/{minikube,gke}/` Kustomize 구조. minikube overlay 에 `imagePullPolicy: Never` + `NodePort 30080` 패치 분리 |
| 6 | 최종 정합성 점검 + PHASE3 기록 | `check-consistency.sh` 통과, 본 항목 |

**주요 결정**:
- **3-Layer 문서 모델 도입**: Layer 1 (01~07) = 현재 상태(What) · Layer 2 (adr/) = 결정 근거(Why, immutable) · Layer 3 (progress/) = 작업 이력(When). 새 결정은 ADR 우선, Layer 1 은 `(see ADR-NNNN)` 형태로 참조. 환경 같은 변동성 큰 사실의 SSOT 단일화를 통해 문서 불일치 차단.
- **ADR-0003 사후 정정**: 최초 작성 시 Phase 1·2 까지 minikube 로 잘못 귀속했으나, `progress/PHASE1.md`·`PHASE2.md` 를 재확인하니 실제로는 Docker Compose 였음. 같은 세션 내에서 `fix(adr):` 로 분리 커밋하여 정정 (해당 ADR 의 scope 를 Phase 3 Task 3-1~3-3 으로 축소, status 를 Partially Superseded 로 변경). ADR 의 immutability 원칙 안에서 "사실 오류 정정"은 명시적 커밋으로 허용한다는 운영 규칙 확립.
- **base/kustomization.yml 의 namespace 필드 미사용**: kustomize 의 `namespace:` 필드가 모든 리소스를 하나의 NS 로 강제 변환하면서 Grafana 대시보드 ConfigMap (원래 `monitoring` NS) 이 `peekcart` NS 로 이동하는 회귀를 발견. 각 매니페스트가 이미 명시적 namespace 를 가지고 있어 `namespace:` 필드를 제거하는 방식으로 해결.
- **GKE overlay 는 placeholder 로 남김**: Task 3-4 Step 0 에서 실제 GKE 환경 구성 (StorageClass, Internal LB, Artifact Registry 이미지 태그, 노드 사이즈에 맞춘 resources) 을 수행할 때 한 번에 추가. 미리 추측해서 패치를 작성하면 실제 GKE 환경에서 재작성이 필요해질 가능성이 큼.
- **부하 테스트 비용 추정**: GKE Standard e2-standard-4 × 1 + 별도 부하 발생기 VM, Phase 3 Task 3-4 전체 사이클 ~₩5,500. 크레딧 (₩453,008) 대비 1.2% 수준으로 안전.

**커밋 이력**:
- `648516d` 세션 1 — ADR 인프라
- `bce6ce5` 세션 2 — ADR 0001~0005
- `46693f7` 세션 3 — Layer 1 핵심 문서
- `eb3337e` 세션 4 (사전) — `fix(adr):` ADR-0003 Phase 귀속 정정
- `1bf5dd7` 세션 4 — Layer 1 파생 문서 + TASKS
- `12a5213` 세션 5 — Kustomize base/overlays 재배치
- (본 커밋) 세션 6 — PHASE3 기록

**검증** (수준 명시):
- `kubectl kustomize k8s/overlays/minikube/` → 19 리소스 정상 렌더링, NodePort 30080 + imagePullPolicy: Never 패치 적용 확인 (= "YAML 파싱 + Kustomize 머지 성공" 수준. 실제 클러스터 apply 검증 아님)
- `kubectl kustomize k8s/overlays/gke/` → 19 리소스 렌더링되지만 base 가 그대로 출력될 뿐 (overlay 가 placeholder). **GKE 배포 가능성을 보장하지 않음**. ADR-0005 §Consequences 참고
- `bash docs/check-consistency.sh` → exit 0. 단 자동 판정은 항목 2(ADR 파일 존재) 한 가지뿐이며 항목 1·3·4 는 grep/find 출력만 함. exit 0 은 "ADR 참조 깨짐 없음" 만 보장하고 Layer 1 의미적 일관성을 보장하지 않음 (3차 리뷰 후속으로 `docs/consistency-hints.sh` 로 rename 및 메시징 강등)

**이슈 / 후속 작업**:
- Task 3-4 Step 0 에서 GKE overlay 에 실제 패치 추가 필요 (StorageClass `standard-rwo`, Service Internal LB, Artifact Registry 이미지, resources 상향)
- Task 3-4 부하 테스트 결과로 `docs/03-requirements.md` §7-1 의 초기 목표값을 baseline 측정값으로 확정
- 환경 전환 후 첫 운영 시 비용 모니터링 (예산 알람 + 사용량 대시보드)

---

## 2026-04-07 — 3차 외부 리뷰 후속 (ADR/구조 보정)

**배경**: 2026-04-06 재설계 작업에 대해 3차 외부 리뷰(`review1.md`, `review2.md`)를 수행한 결과, 다음 세 가지 구조적 문제가 추가로 식별됨.

1. **`check-consistency.sh` 가 사실상 검증하지 않음** — 항목 2(ADR 파일 존재) 외에는 grep/find 출력만 함. 그럼에도 PHASE3 §6 에서 "exit 0 = 검증 통과" 로 서술해 false positive 내러티브.
2. **base/ 의 환경 무관 가정이 monitoring 영역에서 깨짐** — `k8s/base/monitoring/values-prometheus.yml:1` 이 "minikube 경량 설정" 으로 하드코딩, ServiceMonitor 의 NS(`peekcart`) 가 디렉토리(`base/monitoring/`) 와 불일치, install.sh 의 `--create-namespace` 가 fresh 클러스터 self-contained 를 깨뜨림.
3. **ADR-0003 의 타임라인 내적 모순** — `Decided: Phase 0` vs 본문 "K8s 는 Phase 3 부터 도입할 계획". Phase 0 시점에는 K8s 도입 자체가 결정되기 전이므로 minikube 선택을 "결정" 한다는 명제가 성립하지 않음.

**작업 브랜치**: `refactor/phase3-adr-kustomize-prep` (이전 `refactor/phase3-gcp-redesign` 에서 rename — 본 브랜치에 GKE 매니페스트 실체가 없어 제목이 산출물과 불일치했던 점 반영)

**완료 항목** (설계 변경만, 구현은 별도 브랜치):

| 항목 | 변경 |
|---|---|
| ADR-0006 신설 (Proposed) | `0006-monitoring-stack-environment-separation.md` — monitoring 스택을 base 에서 완전 분리하는 결정 + 6개 불변식. 실제 파일 이동/스크립트 수정은 `refactor/phase3-monitoring-split` (예정) 에서 수행 |
| ADR-0005 → Partially Superseded by ADR-0006 | monitoring 범위만 ADR-0006 으로 전환, app/infra 결정은 유지. Update Log 절 추가 (`check-consistency.sh` → `consistency-hints.sh` rename 정정) |
| ADR-0003 → Deprecated | 회고적 재구성의 타임라인 모순 인정. 본문은 이력 보존을 위해 유지하되 상단 Deprecation Note 추가, Decided 필드는 N/A 로 변경 |
| ADR-0004 §Context 확장 | ADR-0003 의 Phase 3 초기 minikube 선택 근거(비용/반복/오프라인) 를 흡수. 새 참조는 ADR-0004 §Context 사용 |
| `docs/check-consistency.sh` → `docs/consistency-hints.sh` rename | PASS/FAIL 메시징을 HINT/CHECK 로 강등. 자동 판정 항목과 수동 점검 항목을 라벨로 명시 분리. ADR 참조 무결성만 종료 코드에 반영 |
| Layer 1 ADR 참조 동기화 | `01 §4-1`/`01 §4-3`, `02 §4-3`/`02 §12`, `04 §10-7`, `TASKS Task 3-2` 의 ADR-0003 참조를 ADR-0004 §Context 로 변경. 02 의 환경 SSOT 참조에 ADR-0006 추가 |
| `TASKS.md` 완료 항목 보정 | 2026-04-06 행의 제목을 "Phase 3 GCP 전환 준비 (ADR/구조)" 로 완화, monitoring 구현이 별도 브랜치에서 수행됨을 명시 |

**구현이 본 브랜치에 포함되지 않는 항목** (다음 브랜치 `refactor/phase3-monitoring-split` 에서 수행):
- `k8s/base/monitoring/values-prometheus.yml` 환경별 분리 (minikube / gke 각 1개)
- `k8s/base/monitoring/servicemonitor.yml` → `k8s/base/services/peekcart/servicemonitor.yml` 이동
- `install.sh` 파라미터화 또는 환경별 진입점 분리, `--create-namespace` 위치 결정
- `k8s/base/namespace.yml` 에 `monitoring` Namespace 추가 여부 결정 (ADR-0006 불변식 5)
- `kubectl apply -k overlays/minikube/` 단독 실행 self-contained 검증

**주요 결정**:
- **설계와 구현 분리**: 본 브랜치는 ADR/문서/governance 만 다루고 매니페스트 변경은 별도 브랜치로 분리. 리뷰 단위와 롤백 단위를 일치시키기 위함.
- **ADR-0003 폐기 vs 유지**: 폐기 후 본문 흡수(option B) 를 채택. 1개 회고적 ADR + 1개 실제 ADR 구조보다 1개 통합 ADR 이 정직함. 본문은 이력 보존 차원에서 그대로 유지하고 Deprecation Note 만 추가.
- **`consistency-hints.sh` 로 강등**: 실제 검증 로직 추가(option A) 대신 메시징 정직성 우선(option B). Phase 3 규모에서 `kubectl kustomize` dry-run + Layer 1 근접성 검증 로직을 작성하는 비용이 가치를 넘어섬. Phase 4 진입 시 재평가.

**검증**:
- `bash docs/consistency-hints.sh` → exit 0 (ADR 참조 무결성만, 다른 항목은 사람 확인 필요라고 명시)
- ADR-0003 → ADR-0004 §Context 흡수 관계가 README 인덱스/Layer 1 문서 양쪽에 일관 반영됨
- `git grep -n "ADR-0003"` → 잔존 참조는 ADR 본문(0002, 0003, 0004, 0005) 과 progress 이력에만 존재. Layer 1(01~07/CLAUDE.md/TASKS) 활성 참조는 모두 ADR-0004 §Context 로 갱신됨

**이슈 / 후속 작업**:
- 차기 브랜치 `refactor/phase3-monitoring-split` 에서 ADR-0006 구현. 완료 시 ADR-0006 Status 를 Proposed → Accepted 로 전환
- Task 3-4 Step 0 체크리스트에 "GKE monitoring values 작성" 항목 추가 필요

---

## 2026-04-07 (2) — ADR-0006 구현 (monitoring 스택 base 분리)

**배경**: 직전 작업(`refactor/phase3-adr-kustomize-prep`)에서 ADR-0006 을 Proposed 로 신설하고 설계만 확정. 본 브랜치는 그 설계를 매니페스트와 스크립트에 반영하여 ADR-0006 을 Accepted 로 전환.

**작업 브랜치**: `refactor/phase3-monitoring-split`

### 사전 결정 (구현 시작 전 확정)

ADR-0006 §"본 ADR 이 결정하지 않는 것" 에 위임된 4개 항목을 다음과 같이 결론.

| 결정 | 채택 | 근거 |
|---|---|---|
| 디렉토리 배치 | `k8s/monitoring/` 최상위 트리 (Kustomize 밖). `shared/` (환경 무관) + `{minikube,gke}/` (환경별 Helm values + install.sh) | 대시보드/Alert 는 진짜 환경 무관이라 overlay 사본을 만들지 않음. Helm 과 Kustomize 의 도구 경계를 디렉토리 수준에서 정직하게 분리. ADR-0006 Alternative C 의 "Phase 4 ServiceMonitor 위치 재결정" 단점은 결정 2 와 결합해 해소 (ServiceMonitor 만 `base/services/<svc>/` 로 이동) |
| install.sh 분기 | 환경별 별도 진입점 (`{minikube,gke}/install.sh`). GKE 는 `exit 1` placeholder | ENV 인자 파싱보다 호출자 입장에서 직관적이며, GKE 의 미작성 상태가 호출 시점에 강제됨 (불변식 6) |
| monitoring NS SSOT | `k8s/monitoring/namespace.yml` 단일 생성 주체. install.sh 의 `--create-namespace` 제거 | "Helm 이 NS 생성 → Kustomize 가 재사용" 순환 의존 해소 (불변식 5) |
| ServiceMonitor CRD 선후 의존 해석 | "self-contained = app/infra 만, monitoring 스택 (CRD 포함) 선행 설치는 문서화" | ServiceMonitor 는 `monitoring.coreos.com/v1` CRD 의존. K8s 생태계 표준 패턴 (cert-manager, Istio, ArgoCD) 과 동일하게 CRD 선행 설치를 02-architecture.md §12 에 명시 |

### 완료 항목

| 항목 | 변경 |
|---|---|
| `k8s/monitoring/` 신규 트리 생성 | `namespace.yml`, `shared/{dashboards-configmap.yml, grafana-alerts.yml, *.json}`, `minikube/{values-prometheus.yml, install.sh}`, `gke/{values-prometheus.yml, install.sh}` (※ `shared/dashboards-configmap.yml` 는 이후 [P1-F](#2026-04-15--p1-f-대시보드-json-ssot-단일화) 에서 제거되고 `shared/kustomization.yml` configMapGenerator 로 대체) |
| `git mv` 로 이력 보존 이동 | `base/monitoring/values-prometheus.yml` → `monitoring/minikube/`, `base/monitoring/install.sh` → `monitoring/minikube/`, `base/monitoring/dashboards/configmap.yml` → `monitoring/shared/dashboards-configmap.yml`, `base/monitoring/alerts/grafana-alerts.yml` → `monitoring/shared/`, 대시보드 JSON 3종 → `monitoring/shared/` |
| ServiceMonitor 이동 (불변식 2) | `base/monitoring/servicemonitor.yml` → `base/services/peekcart/servicemonitor.yml`. `base/monitoring/` 디렉토리 삭제 |
| install.sh 정리 (불변식 5) | `--create-namespace` 제거. 사전 조건(`kubectl apply -f .../namespace.yml`) 헤더 코멘트로 명시 |
| `base/kustomization.yml` 갱신 | `monitoring/*` 3개 라인 제거, `services/peekcart/servicemonitor.yml` 추가. 헤더 코멘트에 ADR-0006 참조 추가 |
| GKE placeholder (불변식 6) | `monitoring/gke/values-prometheus.yml` 헤더에 작성 가이드(retention, resources, Service type), `install.sh` 는 `exit 1` 로 호출 시 명시적 실패 |
| `02-architecture.md` §4-3 / §12 갱신 | 새 디렉토리 트리(Phase 3 + Phase 4 양쪽), 4단계 배포 순서, "self-contained overlay 의 운영 해석" 노트 (CRD 선행 의존) |
| `TASKS.md` Task 3-4 Step 0 체크리스트 | "Step 0-a GKE 클러스터 프로비저닝" + "Step 0-b GKE monitoring values 작성" 항목 추가 |
| `TASKS.md` 완료 항목 표 갱신 | Task 3-3 의 monitoring 파일 경로를 새 위치로 이동, 마이그레이션 노트 동봉 |
| `ADR-0006` Status | `Proposed` → `Accepted` (인덱스 README 동기화) |

### 검증

- `kubectl kustomize k8s/overlays/minikube/` → 19→17 리소스. `kind` 분포: Namespace, ConfigMap, Secret, PVC, Deployment, Service, ServiceMonitor. **monitoring NS 리소스 0건 확인** (불변식 1)
- ServiceMonitor 1건이 peekcart NS 에 렌더링 (불변식 2)
- `bash docs/consistency-hints.sh` → exit 0 (ADR 참조 무결성)
- `kubectl kustomize k8s/overlays/gke/` → base 그대로 출력 (overlay placeholder 유지). monitoring 누락이 의도된 TODO 임은 `monitoring/gke/values-prometheus.yml` 헤더와 ADR-0006 §Decision 양쪽에 명시
- **검증 수준**: kustomize 머지 + 정적 분석까지. 실제 minikube fresh 클러스터에서 4단계 순서 apply 검증은 본 브랜치에 포함하지 않음 (Task 3-4 Step 0 GKE 환경 구성과 함께 한 번에 수행하는 편이 비용 효율적). 2026-04-06 §검증 노트 와 동일한 정직성 정책

### 미해결 / 후속

- `apply -k overlays/minikube/` 단독 실행 self-contained 검증은 minikube 가용 시점에 1회 수행 권장

---

## 2026-04-08 — Task 3-4 Step 0 (GKE overlay + monitoring values)

### 배경

ADR-0006 구현 직후 GKE overlay 와 GKE monitoring values 가 명시적 TODO 로 남아 있었음. Task 3-4 부하 테스트 진입 전 선행 작업으로, 실제 클러스터를 띄우기 전 매니페스트/values 가 오프라인 렌더링까지 통과하는 상태로 끌어올린다.

### 결정 수치 (사전 안내 없이 본 작업에서 확정)

| 항목 | 값 | 근거 |
|---|---|---|
| 이미지 운반 방식 | 일회성 수동 (crane copy / docker tag-push) | 부하 테스트 빈도 낮음, CI 변경 회피 (surgical) |
| `PROJECT_ID` 처리 | 커밋된 placeholder + `kustomize edit set image` (커밋 금지) | envsubst 도구 의존 회피, kustomize 표준 워크플로 |
| peekcart Deployment 리소스 | req 500m/1Gi, lim 2000m/2Gi | HPA max=3 시 req 합 1.5 vCPU/3Gi → e2-standard-4 allocatable 여유. CPU 오버커밋 K8s 정책 허용 |
| infra PVC StorageClass | `standard-rwo` (mysql/redis/kafka 공통) | ADR-0004 명시. PVC 패치 1개 파일 + `target.kind: PVC` 로 3개 일괄 적용 |
| Prometheus retention | 24h | 부하 테스트 1일 분석 범위 |
| Prometheus PVC | `standard-rwo` 5Gi | 24h × 메트릭 수용 여유 |
| Prometheus limits | 1500m / 2Gi | 노드 allocatable ~12% |
| Grafana Service | `LoadBalancer` + `networking.gke.io/load-balancer-type: "Internal"` | VPC 내부에서만 노출, 부하 발생기 VM 과 동일 VPC |
| Grafana persistence | 미사용 | 대시보드는 sidecar ConfigMap, 측정 후 클러스터 폐기 전제 |
| alertmanager | 미사용 (minikube 와 동일) | Slack 알림은 Grafana unified alerting |

### 완료 항목

| 항목 | 변경 |
|---|---|
| `k8s/overlays/gke/kustomization.yml` | placeholder 해제. `patches:` 3건 + `images:` 1건 (PROJECT_ID placeholder) |
| `k8s/overlays/gke/patches/peekcart-service.yml` | Service `type: LoadBalancer` + `networking.gke.io/load-balancer-type: "Internal"` annotation |
| `k8s/overlays/gke/patches/peekcart-deployment.yml` | resources req/lim 상향 |
| `k8s/overlays/gke/patches/pvc-storageclass.yml` | `target.kind: PVC` 로 mysql/redis/kafka PVC 3건 일괄 `storageClassName: standard-rwo` |
| `k8s/overlays/gke/README.md` | 신규 — 이미지 운반 절차, `kustomize edit set image` 사용법, ADR-0004 정리 명령 |
| `k8s/monitoring/gke/values-prometheus.yml` | 본문 작성 (TODO 헤더 제거). retention 24h, PVC standard-rwo 5Gi, Grafana Internal LB, 리소스 상향 |
| `k8s/monitoring/gke/install.sh` | `exit 1` 해제. minikube install.sh 와 동일 패턴 (helm upgrade --install, NS 사전 조건 헤더 코멘트) |
| `docs/02-architecture.md` §12 | k8s/overlays/gke 트리 갱신 (README.md, patches/ 추가) + GKE 4단계 배포 순서 추가 + 정리 체크리스트 참조 |
| `docs/TASKS.md` Task 3-4 | Step 0-a / 0-b 항목 ✅ 표시 + 결정 수치 비고. 완료 작업 표에 2026-04-08 행 추가 |

### 검증

- `kubectl kustomize k8s/overlays/gke/` → 407 줄, exit 0. 렌더링 결과에 다음 모두 확인:
  - peekcart Service: `type: LoadBalancer` + `networking.gke.io/load-balancer-type: Internal` annotation
  - peekcart Deployment image: `asia-northeast3-docker.pkg.dev/PROJECT_ID_PLACEHOLDER/peekcart/peekcart:latest`
  - mysql/redis/kafka PVC 3건: `storageClassName: standard-rwo`
- `kubectl kustomize k8s/overlays/minikube/` → 404 줄, exit 0 (회귀 없음)
- `helm template kube-prometheus-stack prometheus-community/kube-prometheus-stack -n monitoring -f k8s/monitoring/gke/values-prometheus.yml` → 6620 줄, exit 0. 다음 모두 확인:
  - Grafana Service annotation `networking.gke.io/load-balancer-type: Internal`, `type: LoadBalancer`
  - Prometheus `retention: "24h"`
  - Prometheus PVC template `storageClassName: standard-rwo`
- `bash docs/consistency-hints.sh` → exit 0
- **검증 수준**: kustomize 머지 + helm template 정적 분석. 실제 GKE 클러스터 apply 는 측정 직전 별도 작업 (ADR-0004 운영 체크리스트, 비용 보호)

### 미해결 / 후속

- Task 3-4 본체: 클러스터 프로비저닝 → 이미지 수동 push → 4단계 apply → nGrinder/JMeter 시나리오 3건
- Task 3-5: HPA 매니페스트 작성 (Step 0 범위 외, 의도적으로 분리)

---

## 2026-04-08 — Task 3-4 Step 0-c (세션 A: 로컬 준비 + 리허설)

### 배경

Step 0-a/0-b 로 매니페스트는 오프라인 렌더링까지 통과했으나, Task 3-4 본체에 바로 GKE 로 진입할 경우 (a) 시드·시나리오 스크립트의 오동작, (b) JMeter plan 의 JSONPath/페이로드 오류, (c) 캐시 토글 메커니즘 부재 등이 **과금 세션 안에서 발견되어 재측정을 유발**할 리스크가 있었다.

본 작업은 이러한 리스크를 로컬(과금 0) 에서 사전 소진하고, Task 3-4 를 3 세션으로 안전 분할하기 위한 **세션 A** 를 수행한다.

### 결정 수치 · 전략 (본 작업에서 확정)

| 항목 | 값 | 근거 |
|---|---|---|
| 3-세션 분할 | A 로컬 준비 / B 시나리오 1 만 (GKE) / C 시나리오 2+3 (GKE) | 과금 시간 최소화, 정리 누락 방지, 실패 시 롤백 지점 명확. 본 작업은 A 범위 |
| 캐시 토글 메커니즘 | `@ConditionalOnProperty(peekcart.cache.enabled, matchIfMissing=true)` + `NoOpCacheManager` | 이미지 2개 빌드 회피, ConfigMap 환경변수 1개로 재배포만으로 전환. 기본값 유지로 기존 테스트 영향 0 (대안: profile 분기 / 이미지 2개 → 모두 운영 복잡도 높음) |
| 시드 데이터 배치 | `loadtest/sql/seed.sql` (Flyway 독립) | 본 앱 migration 을 오염시키지 않음. 롤백은 재시드 또는 클러스터 재생성 |
| 시드 규모 | users 1,101 (admin 1 + loaduser 1100) · products 1,010 · 경합재고 1,000 | JMeter 1,000 VUser + 여유분, 경합 상품 10개 × 재고 100 = 오버셀링 검증용 총합 1,000 |
| 경합 상품 ID 고정 방식 | 명시 `VALUES` + `ALTER TABLE AUTO_INCREMENT=1001` | `innodb_autoinc_lock_mode=2` 로 `INSERT...SELECT` 가 블록 할당하여 ID 간극 발생 (리허설 중 실측: 1024..1033). JMeter `__Random(1001,1010)` 이 고정 범위를 참조하므로 ID 안정성 필수 |
| 비밀번호 해시 생성 | `htpasswd -bnBC 10 "" "LoadTest123!"` → `$2a$10$...` | 별도 헬퍼 스크립트/Java 실행 없이 표준 도구로 재생성 가능. `$2y$` → `$2a$` 치환으로 Spring Security 호환 |
| 리허설 범위 | docker-compose + 앱 로컬 실행 + curl 스모크 (JMeter/nGrinder 로컬 설치 없음) | 목적은 "수치 수집" 아닌 "파이프라인 동작 증명". JMeter/nGrinder 는 세션 B/C 에서 loadgen VM 에 설치 (일회성 VM) |

### 완료 항목

| 항목 | 변경 |
|---|---|
| `src/main/java/com/peekcart/global/config/CacheConfig.java` | `@ConditionalOnProperty(peekcart.cache.enabled)` 추가. true (기본) → `RedisCacheManager`, false → `NoOpCacheManager`. `@EnableCaching` 유지로 `@Cacheable` 어노테이션 pass-through |
| `k8s/base/services/peekcart/configmap.yml` | `PEEKCART_CACHE_ENABLED: "true"` 기본값 추가 (주석으로 토글 목적 명시) |
| `loadtest/sql/seed.sql` | 신규 — 1,101 users (BCrypt) + 5 categories + 1,010 products (일반 1000 + 경합 10) + inventories. 경합 상품은 명시 `VALUES` + `ALTER TABLE AUTO_INCREMENT` |
| `loadtest/sql/verify-concurrency.sql` | 신규 — 시나리오 2 정합성 검증 쿼리 3건 (상품별 consistency / 총 판매량 oversell_check / 주문 상태 분포) |
| `loadtest/scripts/ngrinder-product-query.groovy` | 신규 — 시나리오 1 (목록 80% / 상세 20%, `grinder.peekcart.baseUrl` 프로퍼티화) |
| `loadtest/scripts/order-concurrency.jmx` | 신규 — 시나리오 2 JMeter plan (로그인 → JSONPath accessToken 추출 → 장바구니 → 주문, 1000 VUser/30s ramp) |
| `loadtest/scripts/users.csv` + `generate-users-csv.sh` | JMeter CSVDataSet 입력 (기본 1,100 users 재생성) |
| `loadtest/cleanup.sh` | ADR-0004 운영 체크리스트 스크립트화 (dry-run 지원, cluster/vm/disks/addresses 4 단계) |
| `loadtest/reports/TEMPLATE.md` | §10-7 (a)~(f) 기록 스켈레톤 |
| `loadtest/README.md` | 절차 / 사전조건 / A..F 단계별 가이드 |
| `docs/TASKS.md` Task 3-4 | Step 0-c 행 추가(✅), nGrinder/JMeter 항목 비고에 "스크립트 준비 완료, 세션 B/C 에서 VM 설치" 명시. 완료 작업 표에 2026-04-08 행 |

### 검증

docker-compose (MySQL/Redis/Kafka) + `SPRING_PROFILES_ACTIVE=local` 앱 구동 후:

- `ProductCacheIntegrationTest` 5건 통과 (`tests=5 failures=0`) — 캐시 토글 후 기본 동작 보존
- `seed.sql` 적용 → `users=1101, products=1010, inventories=1010, contention_stock=1000` (카운트 일치)
- **버그 1건 발견·수정**: 첫 적용 시 경합 상품 ID 가 1024..1033 으로 생성 (MySQL 8 기본 `innodb_autoinc_lock_mode=2` 블록 할당). 명시 `VALUES` 로 수정 후 재적용 → 1001..1010 연속 생성 확인
- curl 플로우: `POST /api/v1/auth/login` 200 → `$.data.accessToken` 추출 → `POST /api/v1/cart/items {productId:1001}` 201 → `POST /api/v1/orders` 201 → DB `orders.status=PENDING`, `inventories.stock 100→99` 차감 확인
- `verify-concurrency.sql`: 1건 주문 후 `consistency=OK`, `oversell_check=OK`, 주문 상태 분포 `PENDING: 1` 출력
- `cleanup.sh --dry-run`: 4 단계 명령 정상 표시

### 검증 수준

- 파이프라인 동작 증명까지. **수치 수집 아님**.
- JMeter/nGrinder 는 로컬 설치 없이 `.jmx` / `.groovy` 파일 작성만 수행. 실제 도구 실행은 세션 B/C 에서 loadgen VM 위에서.
- 캐시 OFF 실측은 세션 B 에서 GKE 환경에 `PEEKCART_CACHE_ENABLED=false` 로 재배포하여 측정.

### 미해결 / 후속

- **사전 확인 3 (GCP 환경)**: 프로젝트 ID, Artifact Registry 레포 존재 여부, `gcloud auth configure-docker` 상태, billing alert ₩50,000 설정 — **세션 B 착수 직전 확인 필요**
- 세션 B: GKE 클러스터 + loadgen VM 생성 → 4단계 apply → 스모크 → 시나리오 1 만 측정 → `cleanup.sh` 실행
- 세션 C: 재프로비저닝 → 시나리오 2+3 측정 → 리포트 최종화 → `cleanup.sh`

---

## 2026-04-09 — Task 3-4 세션 B 준비 (GCP 환경 · 이미지 운반)

### 배경

Step 0-c 종료 시점의 "미해결 / 후속 — 사전 확인 3" 을 과금 구간 진입 전 선제적으로 해결한다. 실제 측정(세션 B) 은 시간적 여유가 확보된 별도 세션으로 분리하고, 본 작업은 **과금 0 에 수렴하는 준비 단계**만 수행한다. `loadtest/README.md §전제 조건 1~6` 과 본 계획 §1-a / §1-b 범위.

### 결정 수치 · 전략 (본 작업에서 확정)

| 항목 | 값 | 근거 |
|---|---|---|
| GCP 프로젝트 ID | `peekcart-loadtest` (신규 생성) | 기존 프로젝트에 영향 없는 격리. 세션 B/C 종료 후 `gcloud projects delete` 로 전체 리소스 일괄 shutdown 가능 (cleanup 최종 안전망) |
| Billing budget | ₩50,000 · 50% / 90% / 100% 이메일 알림 | Task 3-4 계획 §1-a 체크 2. 소프트 제한 — 자동 차단 없음, 가드레일 #3 (F 단계 강제 실행) 과 이중 보호 |
| 활성화 API | container / compute / artifactregistry (+ billingbudgets 부수) | 클러스터 / VM / 이미지 레지스트리 / 예산 API. monitoring 은 helm 설치 경로라 Cloud Monitoring API 별도 활성화 불필요 |
| Artifact Registry | `asia-northeast3` / `peekcart` (DOCKER / STANDARD) | `k8s/overlays/gke/kustomization.yml` 의 경로 `asia-northeast3-docker.pkg.dev/PROJECT_ID_PLACEHOLDER/peekcart/peekcart` 에 맞춤. 리전은 ADR-0004 / §10-7 환경 선언과 동일 zone |
| 이미지 태그 전략 | `:3352c14`(git sha) + `:latest` **이중 태그** | `:latest` 는 kustomization 주석에 문서화된 경로 — 치환 편의. `:<sha>` 는 리포트 재현성 — 측정 결과가 어느 빌드였는지 digest 와 함께 추적. 한 번의 buildx 푸시로 두 태그 동시 등록 |
| 빌드 플랫폼 | `--platform linux/amd64` | GKE e2-standard-4 노드 아키텍처. 로컬이 arm64(macOS) 여도 강제 amd64 빌드 필요 |
| 빌드 방식 | 로컬 `./gradlew bootJar` 생략, buildx multi-stage 내부 빌드 | 기존 `Dockerfile` 이 stage 1 에서 gradle wrapper 로 jar 를 생성하는 구조. 2중 빌드 회피 |

### 완료 항목

| 항목 | 상태 | 비고 |
|---|---|---|
| GCP 프로젝트 `peekcart-loadtest` 생성 + billing 연결 + 활성화 | ✅ | `gcloud projects create` → `billing projects link 01ED06-93F4FB-74A973` → `config set project` |
| ₩50,000 예산 알림 생성 (filter = projects/peekcart-loadtest) | ✅ | Budget ID `f9b7a21a-fd72-4646-8ed0-8cfa1a8c57fd`. 첫 실행 시 `billingbudgets.googleapis.com` 활성화 필요 (본 작업에서 함께 수행) |
| API 활성화 3종 | ✅ | container / compute / artifactregistry — `gcloud services list --enabled` 로 확인 |
| Artifact Registry 레포 `peekcart` 생성 | ✅ | location `asia-northeast3`, format DOCKER, mode STANDARD |
| Docker credHelper 등록 | ✅ | `gcloud auth configure-docker asia-northeast3-docker.pkg.dev` — 기존 `~/.docker/config.json` 에 이미 등록되어 있어 멱등 처리 |
| peekcart 이미지 빌드 + AR push | ✅ | buildx multi-stage, `BUILD SUCCESSFUL in 55s`, 두 태그 모두 manifest list digest `sha256:f86eb82c1ba310646640f68d7f501c4a67ffe7162f90f956ecbe04b503cebae1`, 이미지 크기 ~193MB |

### 검증

- `gcloud config get-value project` → `peekcart-loadtest`
- `gcloud services list --enabled` → container / compute / artifactregistry 3건 확인
- `gcloud artifacts repositories list --location=asia-northeast3` → `peekcart DOCKER STANDARD_REPOSITORY`
- `gcloud artifacts docker images list .../peekcart --include-tags` → 동일 digest 에 `3352c14,latest` 태그 2개 확인
- 환경 도구: gcloud 564.0.0 · kustomize v5.8.1 · helm v4.1.3 · kubectl v1.35.3 · docker buildx v0.32.1

### 검증 수준

- **GCP 리소스 존재 · 접근성까지만.** 클러스터 프로비저닝 · 이미지 pull from GKE 검증은 세션 B 범위.
- **이미지 기능성 미검증**: 이미지가 실제 환경에서 기동하는지는 세션 B 스모크(curl `/api/v1/auth/login`) 에서 최초 확인. 로컬 docker-compose 리허설(Step 0-c) 이 기능성 스모크 역할을 대신함.

### 현재 과금 실태

- **실질 과금 0 에 수렴**: 유일한 과금 항목은 AR 스토리지 ~0.19 GB × $0.10/GB/month ≈ $0.02/month ≈ 30원/month (프리 티어 0.5 GB 에 흡수 가능). GKE 클러스터·Compute VM 아직 없음 → 관리 수수료 없음.
- 세션 B 착수 시점부터 GKE control plane + 노드 + loadgen VM 합계 약 500~600원/hour 발생 예상. ₩50,000 예산 기준 ~80시간 여유.

### 미해결 / 후속

- 세션 B 본체: §2 실행 계획 Step 0~F
- 세션 C: 시나리오 2+3 재프로비저닝 측정

## 2026-04-09 — Helm v4.1.3 호환성 사전 검증 (세션 B 블로커 해소)

### 배경

2026-04-09 세션 B 준비 기록 "미해결/후속" 의 Helm 버전 호환성 이슈를 과금 세션 진입 전 로컬에서 선제 검증. `k8s/monitoring/gke/install.sh` + `values-prometheus.yml` 이 helm v4.1.3 에서 경고·에러 없이 렌더링되는지 확인.

### 검증 절차 · 결과

| 항목 | 명령 | 결과 |
|---|---|---|
| repo 최신화 | `helm repo update prometheus-community` | ✅ |
| 최신 chart 버전 | `helm search repo kube-prometheus-stack` | 83.4.0 / App v0.90.1 |
| chart kubeVersion 제약 | `helm show chart …` | `>=1.25.0-0` (GKE 1.30+ 만족) |
| 기본 렌더링 | `helm template kps … -f values-prometheus.yml` | ✅ exit 0 · stderr 공백 · 6620 줄 |
| 명시 kube-version 렌더링 | `helm template … --kube-version 1.30.0` | ✅ exit 0 |
| 사용 apiVersion 전수 검사 | `grep -hE "^apiVersion:" \| sort -u` | `v1` · `apps/v1` · `batch/v1` · `rbac.authorization.k8s.io/v1` · `admissionregistration.k8s.io/v1` · `monitoring.coreos.com/v1` — 모두 stable, deprecated 없음 |

### 결론

- `k8s/monitoring/gke/install.sh` · `values-prometheus.yml` **수정 불필요**
- 세션 B Step 2 monitoring 설치 시 기존 스크립트를 그대로 실행해도 안전 (helm v4.1.3 + chart 83.4.0 기준)
- 세션 B 진입 시 `helm repo update` 후 동일 버전(83.4.0) 사용 권장. 이후 더 높은 버전이 나와도 같은 세션 내 튜닝 금지 원칙은 유지
- 해당 follow-up 항목은 본 검증으로 종결

## 2026-04-09 / 2026-04-10 — 세션 B 실행: 시나리오 1 상품 조회 TPS 측정 (캐시 OFF/ON 비교)

### 배경

과금 세션 B (시나리오 1 전용). GKE 클러스터 프로비저닝 → 4단계 배포 → 시드 → nGrinder 측정 → 결과 수집 → cleanup 을 하나의 세션에서 완료. 가드레일 5개 항목 모두 준수.

### 실행 절차 요약

| 단계 | 내용 | 결과 |
|---|---|---|
| Step 0 | GKE 클러스터 생성 (`e2-standard-4 × 1`) + loadgen VM (`e2-standard-2`) | ✅ |
| Step 1 | 4단계 apply (namespace → helm monitoring → shared configs → kustomize overlay) | ✅ 전 파드 Running |
| Step 2 | seed.sql 적용 (users=1101, products=1010, contention_stock=1000) | ✅ |
| Step 3 | nGrinder 3.5.9-p1 설치 (controller + agent, JDK 11) | ✅ |
| Step 4 | warm-up 1분/10 VUser | ✅ 239.2 TPS, 에러 0 |
| Step 5 | **Baseline (캐시 OFF)** 5분/50 VUser | ✅ 265.0 TPS / 188.38 ms / 에러 0 |
| Step 6 | **캐시 ON** 전환 후 5분/50 VUser | ✅ 612.7 TPS / 81.87 ms / 에러 0 |
| Step 7 | nGrinder raw 데이터 + UI export 수집 | ✅ `loadtest/reports/2026-04-09/ngrinder-{raw,ui}/` |
| Step 8 | Grafana 스크린샷 6장 수집 | ✅ `loadtest/reports/2026-04-09/grafana/` |
| Step 9 | REPORT.md 작성 | ✅ `loadtest/reports/2026-04-09/REPORT.md` |
| Step F | cleanup (클러스터 + VM + orphan PD 4개 삭제, kustomization.yml 복원) | ✅ |

### 측정 결과

| 지표 | 캐시 OFF | 캐시 ON | 변화 |
|---|---:|---:|---:|
| TPS (평균) | 265.0 | 612.7 | **×2.31 (+131%)** |
| TPS (최고) | 328.0 | 783.0 | ×2.39 |
| 평균 테스트시간 | 188.38 ms | 81.87 ms | **−56.5%** |
| 에러율 | 0% | 0% | — |

### 주요 결정 사항

| 결정 | 선택 | 이유 |
|---|---|---|
| nGrinder JDK 버전 | JDK 11 (system default 전환) | nGrinder 3.5.9-p1 worker 가 JDK 17 바이트코드(major 61) 미지원. `update-java-alternatives -s java-1.11.0-openjdk-amd64` |
| 목표 미달 (×2.31 < ×3) 처리 | 그대로 기록, 튜닝 금지 | 가드레일 #2 "목표 수치 미달도 유효한 측정 결과". 개선은 별도 Task 분리 |
| p95/p99 수집 불가 | 후속 Task 분리 | Grafana `PeekCart — API & JVM` 대시보드 p95/p99 패널 "No data". `http_server_requests_seconds` histogram 활성화 또는 label 불일치 추정. 가드레일 #1 "클러스터 안에서 디버깅하지 않는다" |
| nGrinder baseUrl | loadgen → Internal LB IP 직접 (`10.178.0.6:8080`) | loadgen VM 은 K8s 클러스터 외부 — cluster DNS 미해석. Validate 시 스크립트 default URL 사용하므로 스크립트 본문에서 직접 변경 |

### 관측된 이슈 (후속 작업 분리 대상)

1. **p95/p99 메트릭 미수집**: Grafana API Response Time p95/p99 / Error Rate 패널 "No data". nGrinder raw CSV 에서 사후 계산 가능하나, Prometheus 기반 실시간 모니터링이 의도된 설계. 별도 Task 에서 histogram metric 활성화 필요
2. **Kubernetes Pod 대시보드**: 03-k8s-pod 스크린샷의 pod selector 가 kafka 로 설정됨 — peekcart 파드 네트워크 데이터 미수집. 보조 자료로만 보관

### 미해결 / 후속

- **리뷰 개선 작업**: 세션 B 이후 전반적 리뷰 수행 → 세션 C 전에 P0/P1 개선 반영 (아래 2026-04-10 항목 참조)
- **세션 C**: 시나리오 2 (1,000 VUser 동시 주문) + 시나리오 3 (Kafka Consumer Lag). 환경 재프로비저닝 후 실행
- **p95/p99 메트릭 활성화**: D-001 에서 해결 완료 (`fix/d001-metrics-histogram`, 커밋 `715bcfa`)
- **TPS ×3 목표 미달 원인 분석**: 튜닝 방안 검토 (커넥션 풀, 캐시 TTL, 쿼리 최적화 등)

---

## 2026-04-10 — 전반적 리뷰 종합 + 세션 C 전 개선 계획 수립

### 배경

D-001 해결(`fix/d001-metrics-histogram`) 이후, 설정/config/모니터링 구조 전반에 잠재적 취약점이 있는지 검토. 3건의 독립 리뷰(review1.md — 설정/모니터링 중심, review2.md — 구조/설계 중심, codex-통합 리뷰.md — 교차 검증) 수행 후, 통합 보고서 작성 → Codex 피드백 2회차로 공격/방어 토론 → 최종 확정.

### 리뷰 검증 과정

| 단계 | 내용 |
|---|---|
| 1차 | review1 (설정/모니터링), review2 (구조/설계) 독립 리뷰 |
| 2차 | codex-통합 리뷰 — 두 리뷰 교차 검증 + 우선순위 9개 |
| 3차 | 통합 보고서 — 코드 대조 후 유효/과장/해당없음 분류 (최종 보고서에 흡수, 원본 삭제) |
| 4차 | Codex 피드백 — 수정 범위 과소(3건), 기각 판단 과도(1건) 지적 |
| 5차 | Codex 토론 7개 영역 — Outbox consistency bug 반론(IdempotencyChecker), MDC 부분 수용 |
| 최종 | `docs/review/final-report.md` — 합의된 최종 판단 |

### 최종 확정 개선 항목

**세션 C 전 실행 (Task 3-4 리뷰 개선)**:

| # | 영역 | 수정 범위 |
|---|---|---|
| P0-A | Outbox 실패 경로 Slack 예외 격리 | `OutboxPollingService.java:39-41` — `slackPort.send()` try/catch. DLQ 경로(`KafkaConfig.java:88-91`)와 처리 철학 통일 |
| P0-B | management 설정 공통화 | `management.endpoints.web.exposure.include` + `management.metrics.tags.application` → `application.yml` 이동. k8s 전용(`probes.enabled`, `show-details`)만 `application-k8s.yml` 잔류 |
| P1-D | 관측성 회귀 테스트 | `@SpringBootTest` — 비즈니스 엔드포인트(`GET /api/v1/products`) 호출 후 `/actuator/prometheus` 응답에서 non-actuator URI histogram bucket + `application="peekcart"` 태그 검증 |
| P1-E | Error Rate PromQL NaN 가드 | `api-jvm-dashboard.json` 분모 `> 0` + `grafana-alerts.yml` `($B > 0) and (...)` + `dashboards-configmap.yml` 동기화 → 실제 적용 식/결정은 **[2026-04-14 (2)](#2026-04-14-2--p1-e-error-rate-promql-nan-가드)** 참조 |
| P1-F | 대시보드 JSON SSOT | standalone JSON vs ConfigMap inline 이중 관리 해소 |

**기술 부채 신규 등록 (D-005 ~ D-008)**:

| # | 영역 | 우선순위 | 요약 |
|---|---|---|---|
| D-005 | 관측성 계약 5파일 분산 | 중간 | 1차 봉합(P0-B + P1-D). 완전 해결은 Phase 4 전 |
| D-006 | YAML 프로파일 병합 원칙 미명문화 | 중간 | "연결 정보만 override" 원칙 CLAUDE.md/ADR 기록 필요 |
| D-007 | Kafka Consumer MDC 불완전 | 중간 | Kafka 경로에서 traceId/userId 부재. Phase 4 전 정리 |
| D-008 | Grafana datasource UID 하드코딩 | 낮음 | Helm 기본값 일치 중. 업그레이드 시 확인 |

### Codex 토론에서 기각된 항목

| 항목 | 기각 근거 |
|---|---|
| Outbox "publish/persist consistency bug" | `findPendingEvents`는 `WHERE status='PENDING'`만 조회. 최악 시나리오(이중 save 실패)의 중복 발행은 at-least-once 설계 의도이며 `IdempotencyChecker`(14파일)가 소비자 측 보호 |
| @ConfigurationProperties 타입화 | 설정 키 수 적고 기동 시 즉시 발견. Phase 4 MSA 전환 시 도입 |
| Outbox 발행 확장성 | 순차 발행은 현 규모에서 합리적. 중기 검토 |

### 주요 결정

- **리뷰 개선을 세션 C 전에 삽입**: 세션 C 측정 시 Grafana 대시보드가 정상 동작해야 측정 결과의 신뢰성 확보. 특히 P0-B(management 공통화)와 P1-E(PromQL NaN 가드)는 세션 C 대시보드 관측에 직접 영향
- **TASKS.md Phase 표기 수정**: 최상단 `현재 Phase: Phase 1` → Phase 3 섹션에 `현재 Phase` 표기 이동. 문서 기반 운영 신뢰도 복구
- **D-001 상태 갱신**: `fix/d001-metrics-histogram` 브랜치에서 해결 완료로 표기

### 상세 보고서 참조

- 전체 리뷰 과정 및 코드 대조 결과: `docs/review/final-report.md`
- 1차 통합 보고서: 최종 보고서에 흡수되어 삭제됨

---

## 2026-04-11 — D-006 해결 (YAML 프로파일 병합 원칙 명문화)

### 배경

세션 C 전 기술 부채 정리 순서로 D-006 선정. 근거:
- **선행성**: 리뷰 개선 P0-B(management 공통화)의 이동 범위 결정이 본 원칙에 의존. 원칙 없이 P0-B를 실행하면 "어떤 키가 base 이동 대상인가" 가 해석 분기됨
- **근본 원인성**: D-001 실패 원인(YAML 병합 규칙의 비직관적 동작)을 문서·규약 차원에서 봉합 → 재발 방지
- **저비용**: 순수 문서 작업, 런타임 변경 없음

### 완료 항목

- `docs/adr/0007-yaml-profile-merge-principle.md` — 결정 근거 (Why, immutable)
  - Context: D-001 실패 원인(`management.*` 트리 병합에서 `distribution` 하위 키 소실)
  - Decision: "환경마다 달라지는 연결 정보·자격증명만 프로파일, 동작 정책은 base/Java Config"
  - Alternatives: 전면 Java Config(기각) / YAML 유지 + 숙지(기각) / 하이브리드(채택)
  - Consequences: YAML 전수 감사표 스냅샷 포함 (2026-04-11 기준)
- `CLAUDE.md` — `### 설정 / YAML 프로파일 규칙` 섹션 신규 추가 (ADR-0007 참조)
  - 원칙 · 판단 기준 · 허용/금지 키 목록 · 예외 선언 규칙 · Java Config 우선 케이스
- `docs/adr/README.md` — ADR-0007 인덱스 행 추가
- `docs/TASKS.md` — D-006 상태 해결됨 표기, D-005 비고 업데이트, P0-B 에 ADR-0007 참조 추가

### YAML 전수 감사 결과 (ADR-0007 Consequences 섹션 원본)

| 파일 | 키 | 분류 | 조치 |
|---|---|---|---|
| `application-k8s.yml` | `management.metrics.tags.application: peekcart` | 위반(식별자) | P0-B 에서 base 이동 |
| `application-k8s.yml` | `management.endpoints.web.exposure.include` | 위반(보안 정책) | P0-B 에서 base 이동(최소 노출) |
| `application-k8s.yml` | `management.endpoint.health.probes.enabled` | 회색지대 | 예외 허용 — k8s Probe 운영 기능 |
| `application-k8s.yml` | `management.endpoint.health.show-details: never` | 회색지대 | 후속 검토(base 기본값 권장) |
| `application-local.yml` | `spring.jpa.show-sql`, `format_sql` | 회색지대 | 예외 허용(로컬 디버깅) |
| `application-*.yml` | `logging.level.*` | 회색지대 | 예외 허용(관용) |
| `application-test.yml` | `app.jwt.access-token-expiry` | 회색지대 | 예외 허용(테스트 단축) |

### 경계 사례 결정

- `management.metrics.tags.application`: 순수 식별자(환경 무관) → **base 강제 이동**
- `management.endpoints.web.exposure.include`: 보안 정책. 최소 노출(`health,prometheus`) 을 base 기본값으로 두고, 프로파일 override 는 "추가 노출" 에만 예외 허용

### 주요 결정

- **CLAUDE.md 에 규칙 본문 직접 기술**: CLAUDE.md 는 저빈도 변경되는 프로젝트 컨벤션을 담는 곳. ADR 은 immutable 결정 이력 — 본문은 CLAUDE.md 에, 근거는 ADR 에 분리 기록
- **브랜치 베이스**: `docs/d006-yaml-profile-principle` 는 `fix/d001-metrics-histogram` 위에 스택. D-006 은 D-001 해결의 논리적 후속(재발 방지 원칙) 이므로 같은 머지 체인에 포함
- **기존 위반 정리는 P0-B 에 위임**: 본 작업은 문서·규약 확정까지. 실 코드 수정은 리뷰 개선 P0-B 에서 수행

### 다음 작업

- P0-A: Outbox 실패 경로 Slack 예외 격리
- ~~P0-B: ADR-0007 감사표의 위반 2건 정리~~ → 2026-04-12 완료
- P1-E: Error Rate PromQL NaN 가드

## 2026-04-12 — D-005 1차 봉합 (P0-B + P1-D)

### 배경

세션 C 전 관측성 계약 봉합. P0-B(management 설정 공통화) + P1-D(관측성 회귀 테스트) 동시 처리.

### 완료 항목

**P0-B: management 설정 base 이동**
- `application.yml`(base): `management.endpoints.web.exposure.include: health,prometheus` + `management.metrics.tags.application: peekcart` 추가
- `application-k8s.yml`: 위 두 키 제거. `management.endpoint.health.probes.enabled`, `management.endpoint.health.show-details: never`만 잔류 (ADR-0007 회색지대 예외)
- 런타임 검증: `bootRun --spring.profiles.active=local` → `curl /actuator/prometheus` 200 OK, `application="peekcart"` 태그 정상 포함

**P1-D: 관측성 회귀 테스트**
- `src/test/java/com/peekcart/global/observability/ObservabilityMetricsIntegrationTest.java` 신규
- `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `@Testcontainers` + `@AutoConfigureObservability`
- 검증 항목: (1) `application="peekcart"` common tag, (2) 비즈니스 URI(`/api/v1/products`) histogram bucket 노출
- 전체 테스트 스위트 통과 확인

### 핵심 발견: Spring Boot 3.5 테스트 observability 기본 비활성화

테스트 과정에서 `/actuator/prometheus`가 테스트 컨텍스트에서만 500(`NoResourceFoundException`)을 반환하는 문제 발생. 근본 원인:
- Spring Boot 3.5의 `ObservabilityContextCustomizerFactory`가 `@SpringBootTest` 컨텍스트에서 `management.defaults.metrics.export.enabled=false` 를 기본 주입
- 이로 인해 `PrometheusMeterRegistry` 빈이 생성되지 않아 `PrometheusScrapeEndpoint`도 미등록
- 해결: `@AutoConfigureObservability` 어노테이션으로 테스트 observability 명시 활성화 (Spring Boot 공식 메커니즘)

### 주요 결정

- **브랜치**: `refactor/d005-observability-contract-sealing` (main 기반)
- **P0-B + P1-D 단일 커밋**: 논리적으로 하나의 봉합 작업 (management base 이동 + 그 설정의 회귀 테스트)
- **`@ActiveProfiles` 미사용**: 기존 통합 테스트 컨벤션 준수 (base `spring.profiles.active: local` + `@ServiceConnection` 조합)

## 2026-04-14 — P0-A Outbox Slack 예외 격리

### 배경

세션 C 측정 전 유일하게 남은 P0 리뷰 개선 항목. Outbox 폴링 루프에서 `slackPort.send()` 가 try/catch 없이 호출되어, Slack outage 시 `markFailed()` 직후 예외 전파로 **FAILED 상태 DB 저장이 누락** 될 수 있었음. 폴링은 다음 주기에 동일 이벤트를 재조회하여 Kafka 발행을 반복 시도(이미 실패 확정된 이벤트에 대한 불필요한 재시도).

DLQ 경로(`KafkaConfig.java:88-91`) 는 이미 동일 패턴을 try/catch 로 격리하고 있어 **처리 철학 통일** 차원의 봉합.

### 완료 항목

- `OutboxPollingService.java:37-46` — `slackPort.send(...)` try/catch + `log.warn("Outbox FAILED Slack 알림 발송 실패 — eventId={}", ..., slackEx)` 감쌈. DLQ 경로와 동일 패턴
- `OutboxPollingServiceTest` 신규 (단위 테스트 2건)
  - **Slack 실패 격리**: `kafkaTemplate.send` + `slackPort.send` 양쪽 throw 시에도 `pollAndPublish()` 는 예외 전파 없이 완료되고, `OutboxEvent` 는 FAILED 상태로 save 됨을 `ArgumentCaptor` 로 검증
  - **MAX_RETRY 정상 경로**: `retryCount=4` 에서 Kafka 실패 1회 → `retryCount=5` 도달 → Slack 알림 메시지에 `[Outbox FAILED]` + eventId 포함 확인
- `ReflectionTestUtils.setField` 로 `retryCount=4` 초기화 — MAX_RETRY 도달 경로를 한 번의 `pollAndPublish()` 로 검증 (루프 5회 반복 회피)

### 주요 결정

- **브랜치**: `fix/p0a-outbox-slack-isolation` (main 기반)
- **@TransactionalEventListener 미도입**: 설계 문서의 "outbox FAILED Slack 알림" 은 비동기 이벤트가 아닌 폴링 루프 내 동기 호출. DLQ 경로와 동일하게 try/catch 단일 기법으로 충분
- **재시도/백오프 도입 안 함**: Slack 은 best-effort 알림. FAILED 상태는 DB 에 영속되므로 운영자는 쿼리로 복구 가능 (`retry_count>=5 AND status='FAILED'`)

### 다음 작업

- P1-E: Error Rate PromQL NaN 가드
- P1-F: 대시보드 JSON SSOT 단일화

## 2026-04-14 — D-007 Kafka Consumer MDC 봉합 (옵션 B)

### 배경

D-007: Kafka 경로 로그에 `traceId`/`userId` 부재. 기존엔 listener 본문에 `MDC.put/remove("orderId")` 만 5곳 산재 — `logback-spring.xml` 이 기대하는 3개 필드(traceId/userId/orderId) 중 2개가 비어 있음.

설계 검토 결과 **end-to-end 추적**은 별도 사안으로 분리:
- 모든 production publish 가 `OutboxPollingService` (별도 스케줄 스레드) 경유 → 원 HTTP MDC 가 이미 소멸한 상태에서 발행
- 진정한 end-to-end 는 `OutboxEvent` 에 trace_id/user_id 컬럼 추가(Flyway) 필요 → **D-010 신규 부채로 분리**
- 본 작업은 Consumer 측 로그 필드 부재만 봉합 (옵션 B), forward-compatible 설계로 D-010 흡수 가능하게 둠

### 완료 항목

**신규 인프라**:
- `KafkaTraceHeaders` — `X-Trace-Id`, `X-User-Id` 헤더 키 상수 (D-010 에서 Producer 측 동일 키 사용 예정)
- `MdcPayloadExtractor` — payload JSON 에서 `eventId`/`userId`/`orderId` best-effort 추출, 파싱 실패 silent
- `MdcRecordInterceptor` — `RecordInterceptor<String,String>` 구현체. `intercept()` 에서 MDC 주입, `afterRecord()` finally 단계에서만 정리

**traceId fallback 우선순위**: 헤더(`X-Trace-Id`) → payload `eventId` → 신규 UUID 16자리
- **eventId 우선의 의미**: 같은 이벤트의 재처리·DLQ 경로가 동일 traceId 로 묶여 추적 가능. `KafkaMessageParser` 가 `eventId` 존재를 강제하므로 사실상 항상 적용

**Consumer 정리**:
- `OrderEventConsumer`, `PaymentEventConsumer`, `NotificationConsumer` 의 수동 `MDC.put/remove("orderId")` 5개 위치 제거
- `import org.slf4j.MDC` 도 제거 — interceptor 가 단일 책임

**Wire**:
- `KafkaConfig#kafkaListenerContainerFactory` 에 `setRecordInterceptor(new MdcRecordInterceptor(...))` 추가

**테스트** (`MdcRecordInterceptorTest`, 단위 6건):
- 헤더 traceId 사용
- 헤더 부재 시 eventId fallback
- 헤더·eventId 모두 부재(JSON 파싱 실패) 시 신규 UUID
- payload 의 userId/orderId 가 없으면 MDC 미설정
- afterRecord 호출 후에 MDC 비워짐
- listener 예외 → failure() → CommonErrorHandler 사이에 MDC 가 살아있어야 함 (장애 로그 추적성 보장)

### 주요 결정

- **브랜치**: `refactor/d007-kafka-consumer-mdc` (main 기반)
- **traceId fallback 에 신규 UUID 가 아닌 eventId 우선**: 단순한 신규 UUID 는 retry 시마다 다른 traceId → 추적 의미 약화. eventId 는 메시지 단위 식별자라서 retry/DLQ 까지 묶임. D-007 의 "추적성" 목적에 정확히 부합
- **payload 파싱 2회 수용**: interceptor 의 `MdcPayloadExtractor` 와 listener 의 `KafkaMessageParser` 가 각각 1회씩 — record 당 수십~수백 byte JSON 파싱 비용은 무시 가능. ThreadLocal 캐싱은 과도한 추상화
- **interceptor 에서 파싱 실패는 silent**: 정식 검증은 listener 의 `KafkaMessageParser` 책임. interceptor 는 best-effort MDC 주입만 담당
- **MDC 정리는 `afterRecord()` 단일 hook**: Spring Kafka 호출 순서가 `failure()` → `CommonErrorHandler` → `afterRecord()` 이므로, `failure()` 에서 정리하면 `KafkaConfig` 의 DLQ 발행/Slack 알림 로그(가장 중요한 장애 로그) 에서 MDC 가 비어버림. `afterRecord()` 가 finally 단계라서 단일 hook 만으로 충분 — 초기 "3중 hook 방어" 설계는 finally 의미를 오해한 것이라 정정
- **헤더 키 외부화**: D-010 에서 Producer Interceptor 가 동일 상수 참조 → 키 불일치 위험 제거

### 다음 작업

- P1-E: Error Rate PromQL NaN 가드
- P1-F: 대시보드 JSON SSOT 단일화
- D-010 (Outbox trace context): Phase 4 전 ADR 작성 후 진행

---

## 2026-04-14 (2) — P1-E Error Rate PromQL NaN 가드

### 배경

Task 3-4 리뷰 개선 P1-E. Error Rate 대시보드 패널과 Grafana 알림이 `5xx_rate / total_rate * 100` 구조라 idle 구간(분모=0)에서 NaN/NoData 로 전이. 세션 C 부하 테스트 직전 idle UX + 알림 안정성 보강.

### 결정

- **Idle UX 목표 확정**: "강제 0% 표시" 선택. 트래픽 단절 구간에서도 그래프 연속성 유지로 D-001 스타일 오인 제거. 반대 대안(gap/N/A)은 의미론적으로는 더 정확하나 포트폴리오 시연용 대시보드에서 공백은 혼란 유발.
- **합성식 패턴**: `(A / (B > 0) * 100) or vector(0)`. `(B > 0)` 가 분모에서 0 샘플을 drop → NaN 미생성. 트래픽 완전 부재 구간은 전체 식이 빈 결과 → `or vector(0)` 로 0 fallback.
- **Grafana math 표기 통일**: alert expression 은 PromQL 이 아니므로 `&&`/`||` 사용. PromQL 의 `and`/`or` 와 구분.

### Trade-off — 신호 손실 수용 + 보완 신호 분리

- `or vector(0)` 는 세 상태를 모두 0% 로 표시: ① 트래픽 없음 (정상) ② 스크랩 실패 (target down, relabel 오류) ③ 서비스 다운. Error Rate 패널 단독으로는 ②③ 를 식별 불가 → information loss.
- **보완 설계 (동일 브랜치 후속 커밋)**: 관심사 분리로 해결.
  - `Service Up (Scrape Health)` stat 패널 추가 — `min(up{namespace="peekcart", service="peekcart"}) or vector(0)`. UP=1(green) / DOWN=0(red) 로 스크랩 건강성 독립 표시
  - `peekcart-target-down` 알림 — `max(up{...} == 0) > 0`, severity=critical. 스크랩 시도 후 실패 (네트워크/인증/5xx) 감지
  - `peekcart-scrape-absent` 알림 — `absent(up{...})`, severity=critical. ServiceMonitor 미매칭 / 네임스페이스·서비스 삭제 등 series 구조적 부재 감지
  - 두 알림 분리 근거: `up == 0` (series 존재, 값 0) 과 `absent(up)` (series 자체 부재) 는 Prometheus 데이터 모델상 배타적 메커니즘. 단일 알림으로는 둘 다 커버하지 못함 (`absent()` 는 전자 무감, 그 반대도 성립)
- 결과: Error Rate 는 시각적 연속성(0%), 스크랩 건강성은 별도 채널에서 확인. 원칙상 "대시보드 한 패널이 두 신호를 겸하지 않음".

### 변경 파일

- `k8s/monitoring/shared/api-jvm-dashboard.json` — Error Rate expr 합성식 + Service Up stat 패널 추가
- `k8s/monitoring/shared/dashboards-configmap.yml` — 동일 식/패널 동기 (당시 이중 정의 유지, [P1-F](#2026-04-15--p1-f-대시보드-json-ssot-단일화) 에서 해당 파일 제거 + `kustomization.yml` configMapGenerator 로 대체)
- `k8s/monitoring/shared/grafana-alerts.yml` — Error Rate `($B > 0) && (($A / $B) * 100 > 5)` + `peekcart-scrape-absent` 알림 추가

### 비수정 (범위 통제)

- Slow Response 알림(p95): 분수 아님, 가드 불필요
- kafka-lag / pod-resources 대시보드: 분수형 없음
- D-008 (datasource UID 하드코딩): 범위 외
- P1-F (SSOT 단일화): 별도 작업

### 검증 절차 (실행 시점)

> **재실행 주의**: 아래 1번의 `-f` 명령은 2026-04-14 당시 실행된 형태이다. [P1-F](#2026-04-15--p1-f-대시보드-json-ssot-단일화) 이후 `dashboards-configmap.yml` 는 삭제되었으므로, 현재 이 검증을 재현하려면 1번 명령을 `kubectl apply -k k8s/monitoring/shared/` 로 바꿔 실행해야 한다.

1. (당시 실행) shared 리소스 직접 재적용: `kubectl apply -f k8s/monitoring/shared/dashboards-configmap.yml` + `... grafana-alerts.yml`
2. monitoring 스택은 overlay 와 독립이므로 overlay 재적용 불필요 (`k8s/overlays/minikube/kustomization.yml` 주석 근거)
3. Grafana sidecar 자동 reload → `PeekCart / API & JVM` 대시보드에서 idle 0% 표시 확인
4. Alerting → `peekcart-high-error-rate` Preview 로 idle Normal 상태 확인

### 런타임 검증 (2026-04-14, minikube)

- **PromQL live 질의 (Prometheus 9090)**: Service Up=1, Error Rate=0 (idle), target-down=empty, scrape-absent=empty, 분모=0.166 (헬스체크) — 모두 기대값
- **알림 rule 로드**: 4건 (`high-error-rate`, `slow-response`, `target-down`, `scrape-absent`) 모두 Grafana provisioning API 확인
- **런타임 이슈 2건 발견 및 수정** (커밋 `7e472e4`):
  - Grafana math 가 `range:true` + 단일변수 조합을 time-series 로 인식 → `health=error`. `instant:true, range:false` 로 전환. 기존 high-error-rate 는 `$A/$B` 두 변수 조합이라 암묵적 reduce 적용돼 영향 없었음
  - 쿼리 empty vector 반환 시 NoData → pending 오탐. `or on() vector(0)` fallback 으로 항상 값 반환. `count(up{...}==0) or on() vector(0)` / `absent(up{...}) or on() vector(0)` 로 갱신
- **Firing 실증**: `kubectl scale deploy peekcart --replicas=0` 후 3분 → scrape-absent `state=firing`, target-down `state=inactive`. 배타적 분리 확인. 복원 후 전체 inactive 회귀 (90초 내)

### 다음 작업

- P1-F: 대시보드 JSON SSOT 단일화 (standalone vs ConfigMap inline 이중 정의 해소)
- 세션 C: 시나리오 2 (동시 주문 1,000 VUser), 시나리오 3 (Kafka Lag)
- D-010 (Outbox trace context): Phase 4 전 ADR 작성 후 진행

---

## 2026-04-15 — P1-F 대시보드 JSON SSOT 단일화

### 배경

Task 3-4 리뷰 개선 마지막 항목. `k8s/monitoring/shared/` 에 standalone `*.json` (3종) 과 `dashboards-configmap.yml` 인라인 JSON 이 동일 정의를 이중 보유. P1-E 적용 시 두 파일 수동 동기화 비용이 실제로 발생 — 드리프트 잠재 리스크를 구조적으로 제거.

### 결정

- **옵션 A 채택**: standalone JSON = SSOT, ConfigMap = Kustomize `configMapGenerator` 산출물로 분리. Grafana UI export → JSON 교체 워크플로우와 정합, ConfigMap 을 "빌드 산출물" 로 선언하여 편집 대상 아님을 구조적으로 명시.
- **배포 진입점 통일**: `kubectl apply -f dashboards-configmap.yml -f grafana-alerts.yml` (2파일) → `kubectl apply -k k8s/monitoring/shared/` 단일 명령.
- **`disableNameSuffixHash: true`**: sidecar 는 label 스캔이라 hash suffix 가 기능적으로 문제는 없음. 실 이득은 **ConfigMap 이름 유지 → 운영 가시성 / diff 정합성 / 기존 문서 서술 유지**.
- **`grafana-alerts.yml` 성격 재확인**: Prometheus `PrometheusRule` CRD 가 아니라 **Grafana alert provisioning ConfigMap** (`kind: ConfigMap`, `labels.grafana_alert: "1"`). resources 경로로 그대로 포함.
- **ADR 불필요**: ADR-0006 범위 내 구현 정합성 개선. 트레이드오프는 본 엔트리 + `TASKS.md` P1-F 비고로 기록.

### 변경 파일

- `k8s/monitoring/shared/kustomization.yml` — 신규. configMapGenerator 3개 (api-jvm/kafka-lag/pod-resources) + `options.labels: grafana_dashboard: "1"`, `generatorOptions.disableNameSuffixHash: true`, resources `[grafana-alerts.yml]`
- `k8s/monitoring/shared/dashboards-configmap.yml` — 삭제 (kustomize 산출물로 대체)

### 비수정 (범위 통제)

- `*.json` 3종 / `grafana-alerts.yml` / `install.sh` / overlay: 불변 (ADR-0006 불변식 1: monitoring overlay 비포함 유지)
- D-008 (datasource UID 하드코딩): 범위 외

### 검증 (3단계)

**(a) 렌더링** — `kubectl kustomize k8s/monitoring/shared/`
- 3개 ConfigMap (`grafana-dashboard-{api-jvm|kafka-lag|pod-resources}`) — hash suffix 없음, `labels.grafana_dashboard: "1"`, `namespace: monitoring`, data key `{파일명}.json` 유지
- `grafana-alerting-rules` ConfigMap 그대로 렌더

**(b) 적용** — `kubectl apply -k k8s/monitoring/shared/`
- 3개 dashboard CM `configured`, `grafana-alerting-rules` `unchanged` (기존 리소스와 스펙 동등 → 적용 권한/스키마 이상 없음)

**(c) 런타임** (minikube)
- `kubectl get cm -n monitoring -l grafana_dashboard=1` → 3개 PeekCart CM 포함 (+ kube-prometheus-stack 기본 대시보드)
- `kubectl get cm -n monitoring -l grafana_alert=1` → `grafana-alerting-rules` 1개
- Grafana API `/api/search?type=dash-db` → `PeekCart — API & JVM` / `PeekCart — Kafka Consumer Lag` / `PeekCart — Pod Resources & HPA` 로드 (uid 유지: `peekcart-api-jvm`, `peekcart-kafka-lag`, `peekcart-pod-resources`)
- Grafana API `/api/v1/provisioning/alert-rules` → 4개 rule 로드 유지 (`peekcart-high-error-rate`, `peekcart-slow-response`, `peekcart-target-down`, `peekcart-scrape-absent`)

### 커밋 구조

- 커밋 1 (원자): `kustomization.yml` 추가 + `dashboards-configmap.yml` 삭제 — 중간 HEAD 가 "이중 로드" 또는 "대시보드 소실" 상태가 되지 않도록 단일 커밋 강제
- 커밋 2: 문서 갱신 (02-architecture / overlays/gke/README / PHASE3 / TASKS)

### 다음 작업

- 세션 C: 시나리오 2 (동시 주문 1,000 VUser), 시나리오 3 (Kafka Lag) — TASKS.md L395-397
- D-010 (Outbox trace context): Phase 4 전 ADR 작성 후 진행

---

## 2026-04-16 — D-009 통합 테스트 인프라 표준화 (1차 목표)

### 배경

통합 테스트 7개가 각각 독립적으로 MySQL/Redis/Kafka 컨테이너를 선언하고, cleanup 전략과 TestConfig가 분산되어 있었음. 공통 베이스 클래스가 없어 신규 테스트 작성 방식이 불명확하고, cleanup·SlackPort mock이 반복됨. Phase 3 마무리 시점에 1차 목표(cleanup 규약 표준화 + SlackPort mock 공통화 + 베이스 클래스 도입)를 수행.

### 완료 항목

| 항목 | 변경 |
|---|---|
| `src/test/java/com/peekcart/support/AbstractIntegrationTest.java` | 신규 — `cleanDatabase()` (FK 역순 DELETE, try/catch/finally 예외 안전) + `cleanCaches(CacheManager)` (Spring CacheManager 범위만 정리). 컨테이너 선언 없음, cleanup 유틸리티만 제공 |
| `src/test/java/com/peekcart/support/IntegrationTestConfig.java` | 신규 — no-op `SlackPort` mock `@TestConfiguration`. Outbox/Idempotency 테스트가 `@Import`로 재사용 |
| `ProductCacheIntegrationTest` | extends base, cleanup → `cleanDatabase()` + `cleanCaches()`, Kafka 컨테이너 추가 |
| `InventoryConcurrencyTest` | extends base, 명시적 `cleanDatabase()` 추가 (기존 fresh-container 묵시적 가정 제거), Kafka 컨테이너 추가 |
| `ShedLockIntegrationTest` | extends base만 (cleanup 불필요 — shedlock 레코드 존재 검증) |
| `ObservabilityMetricsIntegrationTest` | extends base만 (cleanup 불필요 — 메트릭 노출만 검증) |
| `OutboxKafkaIntegrationTest` | extends base, inner TestConfig 제거 → `@Import(IntegrationTestConfig)`, cleanup → `cleanDatabase()` |
| `IdempotencyIntegrationTest` | extends base, inner TestConfig 제거 → `@Import(IntegrationTestConfig)`, cleanup → `cleanDatabase()` |
| `DlqIntegrationTest` | extends base만 (자체 TestConfig 유지 — 커스텀 SlackPort 카운터 + error handler) |
| `docs/TASKS.md` | D-009 상태 **해결됨 (1차 목표)** 갱신, 완료 작업 표에 2026-04-16 행 추가 |

### 주요 결정 사항

| 결정 | 선택 | 이유 |
|---|---|---|
| 컨테이너 수명 모델 | per-class 유지 (각 자식이 `static @Container` 선언) | Java static 필드는 상속 시 클래스별 복제되지 않아, base에 두면 모든 자식이 같은 인스턴스 공유 → per-class 전제 깨짐. 선언 중복은 수명 모델 안전성과의 의도적 트레이드오프 |
| `cleanDatabase()` 자동 실행 여부 | `@BeforeEach` 미사용, 자식이 명시 호출 | ShedLock/Observability 테스트는 DB cleanup 불필요. 규약은 javadoc에 명시 |
| ProductCache/InventoryConcurrency에 Kafka 추가 | 추가 | `@SpringBootTest` full context에서 Kafka auto-config 정합성 확보 |

### 달성/미달성

**달성**: DB cleanup 규약 단일화, Spring Cache cleanup 규약 추가, SlackPort mock 3중 중복 → 1곳 집약, InventoryConcurrencyTest 묵시적 fresh-state 가정 → 명시적 cleanup 전환, 신규 테스트 작성 규약 확립

**달성하지 않음 (의도적)**: 컨테이너 선언 중복 제거, Spring context 캐시 적중률 개선, CI 시간 단축 — 2차 목표로 분리

### 검증

- `./gradlew test` 전체 244건 통과 (기존 235건 + 테스트 추가 분)
- DlqIntegrationTest DLQ 라우팅 + Slack 카운트 검증 기존과 동일
- InventoryConcurrencyTest cleanDatabase() 추가 후 동시성 테스트 결과 불변
- ProductCacheIntegrationTest Kafka 추가 + cleanCaches() 전환 후 캐시 동작 불변

## 2026-04-22 — Task 3-4 세션 C 선행: 부하 도구 JMeter → k6 전환

### 배경

세션 B(시나리오 1, nGrinder)는 이미 측정 완료. 세션 C(시나리오 2: 1,000 VU 동시 주문) 실행 전에 부하 도구를 k6 로 교체한다. 전환 결정 근거·대안 비교는 `docs/progress/loadtest-tool-evaluation.md` 옵션 B (부분 전환) 참조. 설계 결정(ADR-0004 L44 "nGrinder + JMeter 조합")은 immutable — 본 작업은 `loadtest/` 도구 교체 + 문서 동기화 범위로 한정.

### 변경 스코프

1. **Part A — 문서 동기화 (JMeter 언급 치환)**
   - Layer 1: `docs/01-project-overview.md`, `02-architecture.md`, `03-requirements.md`, `06-testing-strategy.md`, `07-roadmap-portfolio.md` 의 도구명만 교체 (nGrinder 항목 불변)
   - `loadtest/README.md` 디렉토리/전제조건/§A 시드/§E 리포트 파일명 동기화
   - `loadtest/reports/TEMPLATE.md` 환경 표 행 + scenario 2 첨부 산출물에 `k6-summary.json` 추가
   - `loadtest/sql/seed.sql` 주석 3개 (L12/L42/L100) 치환 (스키마 불변)
   - `docs/TASKS.md` Task 3-4 진행 중 행 (L395/L396) 치환 (L487 완료 이력 행은 불변)
2. **P7 k6 스크립트 작성 + 로컬 리허설 안내**
   - 신규 `loadtest/scripts/order-concurrency.js`: `SharedArray` + `ramping-vus` (30s→1000 / 1m hold / 30s→0) + Threshold `http_req_failed<0.1`
   - 기존 `order-concurrency.jmx` 삭제 (git history 로 보존)
   - 로컬 리허설 명령은 `loadtest/README.md` §A/§C 에 반영되어 있음. **실제 docker-compose 기반 리허설·`k6-summary.json` 생성 확인은 본 task 범위에서 deferred** — 로컬에 k6 미설치 + 리허설 자체가 과금 없는 선택 활동이라 operator 가 세션 C 준비 시 수행. 본 task 는 Codex diff 리뷰로 스크립트 정적 품질을 보장하고 실행 검증은 세션 C 준비 단계로 분리 (계획서 §6 완료 조건 일부 유보 — 스크립트 작성·문서 동기화·receiver 활성화는 충족, 런타임 리허설만 연기)
3. **P8-a · P9 문서 절차 갱신**
   - loadgen VM 전제조건: JDK 17 요구 제거, JDK 11 (nGrinder agent 전용) + k6 v0.49+ 로 교체
   - §C 실행 블록: `k6 run -e BASE_URL=http://<internal-lb>:8080 -o experimental-prometheus-rw=...` 으로 전환
   - Grafana k6 대시보드 ID `19665` import 절차 추가. 대시보드 JSON 매니페스트 선제 커밋은 SSOT 단일화 원칙(P1-F)에 따라 세션 C 실증 후 별도 PR 로 분리
4. **P8-b GKE monitoring values 1줄 변경 — `enableRemoteWriteReceiver: true` 추가**
   - `k8s/monitoring/gke/values-prometheus.yml` `prometheus.prometheusSpec` 에 추가. k6 `experimental-prometheus-rw` 수신 전제
   - ADR-0006 (monitoring 스택 환경 분리) 원칙에 따른 **GKE 한정 운영 세부 조정** — minikube values 는 **미변경** (로컬 리허설은 Prometheus 미사용)
   - ADR 신규 작성 없음: 부하 테스트 기간 외에는 클러스터 자체가 폐기되므로 ADR-0006 운영 세부 범위

### 비대상 (후속)

- **P10 세션 C 실제 실행** (1,000 VU / Kafka Lag 관찰 / 정합성 검증 / cleanup) — 별도 과금 세션
- **P11 세션 C 리포트 작성** — 후속 Task
- Grafana k6 대시보드 JSON 파일(`k8s/monitoring/shared/`) 선제 커밋 — 세션 C 당일 실증 후 별도 PR

### 2026-04-29

#### Task 3-4 세션 C + Task 3-5 완료 (Phase 3 종결)

GKE 1회 과금 세션 (~35분, 클러스터 + loadgen VM + AR push) 으로 시나리오 2/3 + Task 3-5 + D-002 데이터 수집을 통합 실행. 본 entry 는 Part A~D 요약 + Run 1/2 비교 + 후속 추적 안건.

**브랜치**: `test/phase3-loadtest-session-c`
**PR**: https://github.com/Kimgyuilli/PeakCart/pull/27
**리포트**: `loadtest/reports/2026-04-29/REPORT.md` (REPORT.md + grafana 4장 + run1/2 산출물)

**Part A (무과금 리허설)**:
- 로컬 docker-compose + k6 docker 컨테이너 (`grafana/k6` 이미지) 로 P1 5항목 게이트 통과: login 성공률 100% / cart 실패율 0% / order 5xx 0건 / consistency=OK / summary metrics 파싱

**Part B (GKE 환경 기동)**:
- 클러스터 `peekcart-loadtest` (asia-northeast3-a, e2-standard-4 × 1, pd-standard 50GB)
- loadgen VM `peekcart-loadgen` (e2-standard-2, Ubuntu 22.04, `--scopes=cloud-platform` 으로 metadata SA 인증)
- 이미지 재빌드 (`docker buildx build --platform=linux/amd64`, 태그 `f7ea932` + `latest`, digest `sha256:25068882...`) — 세션 B 의 2026-04-09 이미지는 src/ 9개 commit 변경으로 stale (D-001/D-007/P0-A/P0-B/P1-D 포함)
- 4단계 배포 순서 준수 (ADR-0006): namespace → install.sh → monitoring/shared → overlays/gke
- smoke 4종 통과: HPA target 정상 (`cpu: 4%/60%`), `/actuator/health` UP, **D-001 회귀 검증** (`http_server_requests_seconds_bucket{application="peekcart",uri="/api/v1/products"}` 라인 존재), Prometheus receiver POST → HTTP 400

**Part C (실측 — 2 runs)**:

Run 1 (1 pod cold-start, HPA 활성):
- HPA 1→3 전이 확인 (`kubectl get hpa -w`): CPU 269% → 400% saturation → scale-out → 안정화 90% → 15%
- 신규 pod 65초 내 Ready, r58t4 readiness 일시 손실 후 회복
- k6: 60.59% http_req_failed, login 46.3%, 25 successful orders, 정합성 OK
- → **Task 3-5 핵심 산출물 + D-002 1차 병목 (CPU saturation) 확증**

Run 2 (3 pods pre-warmed, HPA 일시 제거 + manual scale=3, DB 재시드):
- k6: 35.90% http_req_failed (-24.7pp), login 96.7% (+50.4pp), 110 successful orders (×4.4)
- p95 30.21s 로 오히려 증가 — 깊은 단계 (cart insert / inventory decrement) 도달 요청들이 DB 커넥션 / 락 대기에서 timeout
- 정합성 OK (모든 경합상품 + 오버셀링 0건)
- → **D-002 2차 병목 = MySQL 커넥션 풀 / Redis 분산 락 contention 식별**

시나리오 3 (Kafka Consumer Lag):
- 메트릭 출처는 **Micrometer-Kafka client** (`kafka_consumer_fetch_manager_records_lag_max`) — kafka-exporter 미배포
- 라벨 구조: `client_id` (그룹 정보 임베디드), `topic`, `partition`. `kafka_consumer_group_id` 라벨 부재
- 실제 토픽: `order_created`, `order_cancelled`, `payment_completed`, `payment_failed` (계획서 §3 P5 placeholder `order-created` / `payment.approved` 와 상이 — underscore + `payment.approved` 부재)
- steady-state: lag 0 또는 NaN, peak 후 5분 내 빈 결과 복귀 ✅

**Part D (정리)**:
- cleanup.sh 실행 — GKE 클러스터 정상 삭제, **VM 삭제 단계 실패** (script 변수 `loadgen=loadgen` 이 실제 이름 `peekcart-loadgen` 과 불일치)
- 운영자 수동 보완: VM + 5개 디스크 (1× pd-standard 20GB + 4× pvc-* pd-balanced) 일괄 삭제
- 콘솔 육안 확인: instances/disks/clusters/addresses 모두 빈 결과
- AR 이미지 보존 (재사용 가치)

**P9 문서 갱신**:
- TASKS.md: Task 3-4 / 3-5 ✅, Phase 3 헤더 ✅, Phase 3 Exit Criteria 모두 [x], D-002 행에 세션 C 데이터 추가, 완료 작업 표 새 행
- PHASE3.md: 본 entry 신규
- `.gitignore`: `loadtest/reports/local/` 추가 (계획서 §4)

**부산물 / 후속 안건**:
- **cleanup.sh VM 이름 변수 버그**: `loadgen=loadgen` → `loadgen=peekcart-loadgen` 로 정정 필요. 별도 후속 task
- **kafka-lag-dashboard.json legend `{{kafka_consumer_group_id}}`**: 본 프로젝트의 Micrometer client metric 에 해당 라벨 부재 — 빈 문자열 렌더. 별도 dashboard 수정 안건
- **계획서 §3 P5 placeholder PromQL 부정확성**: 본 프로젝트 metric 출처와 라벨 구조에 맞게 실측 쿼리 치환됨 (REPORT.md §(f) 에 기록)
- **D-002 후속 추적**: MySQL 리소스 + HikariCP 풀 튜닝 후 재측정, 분산 락 acquisition latency metric, Phase 4 Order Service 격리 측정 (TASKS.md D-002 행 우선순위 갱신)

**Phase 3 Exit Criteria 모두 충족 → Phase 3 종결**. 다음: Phase 4 MSA 분리 준비 (Gradle 멀티모듈, Spring Cloud Gateway, Choreography Saga, CQRS 로컬 캐시).

---

## 2026-05-01 — task-d010-outbox-trace-context (Outbox trace context 영속화 + Producer 헤더 전파)

**범위**: Phase 3 잔여 부채 D-010 해결. Phase 4 MSA 분리 진입 전 모놀리스 단계에서 Outbox-only end-to-end 추적 인프라 선결. 결정 근거: ADR-0008.

**Part A — ADR 작성**:
- `docs/adr/0008-outbox-trace-context-propagation.md` 신규 (Status `Proposed` → 종결 시 `Accepted` 전환)
- 4 대안 비교: A. 컬럼 추가 + MDC 캡처 + 헤더 주입 (선택) / B. payload envelope 임베드 / C. 별도 테이블 / D. OpenTelemetry 즉시 도입
- 선택 근거: 기존 `MdcRecordInterceptor` 헤더 우선순위 인프라 (D-007 옵션 B) 와 정합 + Phase 4 forward-compat (`traceparent` 한 단계 추가만으로 동작)
- 핵심 결정: **MDC 캡처 책임은 Publisher**, `OutboxEvent.create(...)` 는 명시 인자 (옵션 a) — 도메인 횡단 엔티티의 SLF4J 결합 회피

**Part B — 스키마 + 엔티티**:
- Flyway V4 (`outbox_events` 에 `trace_id` / `user_id` VARCHAR(64) NULL 컬럼) — backfill 불필요, 신규 이벤트부터 채워짐
- 인덱스 미생성 — 기존 `idx_outbox_status_created` 가 폴링 쿼리 (`status='PENDING' ORDER BY created_at ASC`) 커버. trace 기반 조회는 사후 ad-hoc
- `OutboxEvent.create(...)` 시그니처 확장 (traceId/userId 명시 인자), 호출부 3곳 갱신
- `MdcSnapshot.current()` 정적 헬퍼 (`global/kafka/`) — Snapshot record (traceId, userId)

**Part C — Producer 헤더 주입**:
- `OutboxPollingService.kafkaTemplate.send(...)` 3-인자 → `ProducerRecord` 빌드 + `KafkaTraceHeaders.TRACE_ID` / `USER_ID` 헤더 주입
- 헤더 부재 정책: traceId/userId null 이면 헤더 자체 미추가 (빈 문자열 헤더 추가 금지) — `MdcRecordInterceptor.headerValue()` 의 `value.isBlank() ? null` 분기와 정합

**Part D — 테스트**:
- 단위 (Mockito): MdcSnapshotTest 3건, OrderOutboxEventPublisherTest 3건 (MDC 미설정 / traceId-only / both), PaymentOutboxEventPublisherTest 3건, OutboxPollingServiceTest 헤더 검증 2건 (set / null)
- 단위 (interceptor 경계 확장): MdcRecordInterceptorTest 신규 2건 — blank X-Trace-Id → eventId fallback, X-Trace-Id 만 + X-User-Id 부재 → userId null
- 통합: OutboxKafkaIntegrationTest raw consumer 헤더 전파 2건 (MDC set / MDC clear), DlqIntegrationTest 헤더 보존 1건 — DlqIntegrationTest 는 ProducerRecord 직접 생성 + KafkaTraceHeaders 부착 방식
- 안전망: 신규 통합 테스트에 `@BeforeEach`/`@AfterEach` MDC.clear() — JVM thread 재사용 시 케이스 간 누수 방지

**Part E — 문서 동기화**:
- TASKS.md: D-010 행 `중간` → `해결됨`, 완료 작업 표 새 행
- 본 PHASE3.md 엔트리
- 02-architecture.md 패키지 트리 (`global/kafka/MdcSnapshot.java` 추가)
- adr/README.md 인덱스에 ADR-0008 행
- 05-data-design.md outbox_events ERD 에 trace_id/user_id 컬럼 + 인덱스 미생성 명시 (Why → ADR-0008)
- ADR-0008 Status `Proposed` → `Accepted` (별도 커밋)

**검증**:
- `./gradlew test` 전체 통과 (244 + 신규 14건) — 5xx/회귀 0건
- raw KafkaConsumer 로 ProducerRecord 헤더 자체 검증 (Spring `RecordInterceptor` 통과 우회) — 외부 poll 경로의 `MdcRecordInterceptor.afterRecord()` MDC 제거 한계는 별도 `MdcRecordInterceptorTest` 단위 테스트로 보호

**커밋 구조 (5분할)**:
1. `docs(adr): add ADR-0008 outbox trace context propagation (Proposed)`
2. `feat(outbox): persist trace context columns on outbox events`
3. `feat(outbox): inject trace headers on kafka publish`
4. `test(outbox): verify end-to-end trace header propagation`
5. `docs: mark D-010 resolved + ADR-0008 accepted`

브랜치: `feat/d010-outbox-trace-context`. 계획 리뷰 3 loops 누적 14건 전부 반영. /work loop 1 진행.

## 2026-05-02 — task-d011-harness-hardening (`/plan`·`/work` 공용 shell helper 4건 정비)

**범위**: Phase 3 잔여 부채 D-011 해결 (Phase 4 MSA 분리 진입 전 harness 안전성 베이스라인 확보). harness 자체 변경이므로 회귀 방지 자동화(Bats) 도 동일 task 범위에 포함.

**Part A — 경로 인젝션 차단** (a, 우선순위 1):
- `hpx_task_id_validate` 헬퍼 신규 (`shared-logic.sh` 공통 유틸 섹션) — allowlist `[A-Za-z0-9._-]+`, 길이 1~128, `..` 부분문자열 금지, 선두 `-`/`.` 금지 (옵션/dotfile 오인 방지)
- 진입부 검증 추가 (1지점 커버 + 직접 보간 helper 별도 호출):
  - `hpx_lock_dir`, `hpx_state_path` (1지점 → 호출자 자동 보호)
  - 별도: `hpx_lock_acquire`, `hpx_lock_force_release`, `hpx_lock_release`, `hpx_state_exists/read/write`, `hpx_plan_lint`, `hpx_audit_append`, `hpx_diff_capture`, `hpx_ship_pr_body_data` — 각 진입부 1회 검증
- `.claude/commands/{plan,work}.md` Step 1 직후 1회 검증 라인 명시 (`bash -c '... hpx_task_id_validate "$TASK_ID"' || exit 1`)
- 검증: 악성 `task_id` (`../etc`, `foo;rm` 등) 가 `mkdir`/`rm -rf`/file write 에 도달하지 않음 (sentinel guard)

**Part B — `timeout_wrapper.py` 견고화** (d, 우선순위 2):
- `seconds <= 0`, `math.isnan`, `math.isinf` 거부 → exit 2 + stderr `invalid seconds:` 출력
- 종료 코드 2 (잘못된 인자) 유지 — 124 (정상 timeout) 침범 차단
- `1e309` (Python `float()` 가 `inf` 로 받아들임) 도 동일 거부

**Part C — `hpx_diff_capture` 부작용 제거** (b, 우선순위 3):
- 실제 `.git/index` 에 `git add -N` 을 실행하던 기존 방식 → 격리된 임시 `GIT_INDEX_FILE` 로 전환
- 흐름: `mktemp -d` 임시 dir → `cp $GIT_DIR/index $tmp_dir/index` (없으면 빈 index — unborn repo 가드) → `GIT_INDEX_FILE=$tmp_index git ls-files --others -z | xargs -0 sh -c '... git add -N -- "$@"'` (NUL 파이프 직결) → `GIT_INDEX_FILE=$tmp_index git diff "$base"` → `rm -rf $tmp_dir`
- 사용자 staged 상태 무손상: `.git/index` sha256 호출 전후 동일 + `git status --porcelain` 동일
- staged 신규 파일 / 공백·개행 파일명 untracked 모두 diff 산출물에 포함 (read-tree HEAD 방식 회귀 차단)

**Part D — Bats 회귀 테스트** (c, 우선순위 4):
- 신규 디렉토리 `.claude/scripts/tests/bats/` + README (설치/실행 가이드)
- `task_id_validate.bats` — 정상 (task-foo, task-d011-harness-hardening) + done/ basename 회귀 가드 + 거부 케이스 (`../foo`, `foo..bar`, `-foo`, `.foo`, 공백, 128자 초과, 슬래시/세미콜론/달러/탭/개행)
- `lock_state_paths.bats` — 정상 idempotent re-enter + 부정 (`../etc`, `foo;rm` 등) 에서 lock/state 파일 생성 0건, sentinel rm 도달 0건
- `plan_audit_paths.bats` — `hpx_plan_lint` / `hpx_audit_append` 외부 경로 거부 + 부작용 0건
- `diff_capture.bats` — 임시 git repo 격리 시나리오 (staged modified + staged 신규 + untracked + 공백/개행 untracked → `.git/index` sha 불변, git status 불변, 모든 변경분 diff 포함, unborn repo 도 동작)
- `timeout_wrapper.bats` — 거부 11건 (0/-1/-0.5/NaN/nan/Inf/+Inf/Infinity/1e309/abc/empty) + 정상 3건 (작은 timeout pass-through, 진짜 timeout=124)
- 미실행 환경: 본 task 작성 시점에 `bats-core` 미설치. 모든 테스트 시나리오는 별도 bash 스크립트로 수동 검증 완료 (sha 불변, status 불변, 거부 11건, 정상 3건 모두 OK). 사용자가 `brew install bats-core` 후 `bats .claude/scripts/tests/bats/` 로 자동 실행 가능

**Part E — 문서 동기화**:
- TASKS.md: D-011 행 `중간` → `해결됨`, 완료 작업 표 새 행
- 본 PHASE3.md 엔트리
- `docs/02-architecture.md` 변경 없음 (§12 가 `.claude/` 트리 미언급 — `grep -c '.claude' = 0` 확인)

**검증**:
- 본 task `/work` 의 GP-3 (diff 캡처) 단계에서 P6 변경 동작 자체 검증 — `.git/index` sha256 호출 전후 동일 (`b807c52e...`)
- 본 task 진행 중 lock/state 정상 동작 (`task-d011-harness-hardening` 가 신규 allowlist 통과)
- `./gradlew test` 영향 없음 (Java 코드 변경 0건)

**브랜치**: `chore/task-d011-harness-hardening`. 계획 리뷰 3 loops 누적 11건 전부 반영. /work loop 1 (Codex 리뷰 4건 모두 반영). PR: https://github.com/Kimgyuilli/PeakCart/pull/29

## 2026-05-04 — task-adr-observability-ssot (관측성 계약 SSOT ADR-0009 작성)

**범위**: D-005 잔여 리스크 1차 봉합 후, "어느 surface 의 SSOT 가 어느 파일·레이어인가" 의 결정 부재를 ADR 로 못 박는 작업. Phase 4 모듈 분리 진입 전 처리 (D-005 자체 해결은 후속 task 범위로 분리).

**Part A — 사전 감사 (P1, P2)**:
- 9 surface 위치 재확인 (라인 인용): S1 `MetricsConfig.java:17-37`, S2 `application.yml:38-40`, S3 `application.yml:33-37` (회색지대 `application-k8s.yml:14-19`), S4 `SecurityConfig.java:47-48`, S5 `servicemonitor.yml:10-20`, S6.a~d `grafana-alerts.yml:17-52/53-83/84-110/111-137`
- 자동 회귀 검증 범위 확정: 기존 `ObservabilityMetricsIntegrationTest` 가 S1/S2/S3 happy path/S4 happy path 검증. 잔여 4 공백 (S3 whitelist 정확도, S4 health 경로, S5 selector, S6 PromQL) 식별

**Part B — Decision (P3, P4)**:
- 3안 비교 (5축: 변경 범위 / ADR-0007 정합성 / Phase 4 비용 / 검증 가능성 / 채택·기각 사유)
- **Alt B 채택** = surface 별 SSOT 명시 (현 위치 유지). Alt A (Java Config 통합) 는 후속 task 범위로 분리. Alt C (자동 생성) 는 Phase 4 진입 전 부담 과다로 기각

**Part C — ADR-0009 본문 (P5, P6)**:
- `docs/adr/0009-observability-contract-ssot.md` 신규 (Status `Proposed` → 동일 task 내 `Accepted` 전환)
- §Decision 6 컬럼 강제 표 (모든 행 "TBD"/"추후 검토" 금지): Surface / 현 SSOT (파일:라인) / 본 task 변경 / Phase 4 owner / 이동·복제 금지 규칙 / 검증 수단
- 본 task 변경 컬럼 = 모든 행 "없음" (코드/매니페스트 변경 0건. 통합은 후속 task)
- Phase 4 owner: S1/S3/S4 = `peekcart-common-observability` 공통 모듈, S2 = 각 서비스 own (서비스 이름 = `application=` 값), S5 = per-service ServiceMonitor, S6 = `monitoring/shared/` 유지
- 미검증 4 동작 공백 (S3 whitelist / S4 health / S5 selector / S6 PromQL) 을 후속 action id D5-V3~V6 으로 명시. 위치/복제 정적 검증은 D5-V1, D5-V2 로 별도 정의 (후속 task plan §3 source)
- ADR-0007 Extends (Supersede 아님), ADR-0006 위치 분담 유지

**Part D — 인덱스/참조 동기화 (P7~P10)**:
- `docs/adr/README.md` INDEX 표에 행 9 추가
- `bash docs/consistency-hints.sh` exit 0 (ADR 참조 파일 존재 확인. INDEX↔파일 frontmatter 자동 검증은 비대상 — 수동 확인)
- `CLAUDE.md` §설정/YAML 프로파일 끝에 1줄 참조 추가 ("관측성 계약 SSOT — see ADR-0009"). plan P9 원안 ("1~2 문장 + 참조") 준수, 본문 미복제. 결정 빈도 낮은 영역에 별도 §관측성 계약 절을 두는 over-spec 회피
- `docs/02-architecture.md` 변경 없음 — §관측성 또는 §설정 전용 절 부재 확인 (`§4-3 인프라 전략` 의 ADR-0006 참조 라인은 위치 분담만 다루고 SSOT 결정과는 다른 축)

**Part E — 문서 동기화 (P11, P12)**:
- TASKS.md: D-005 행 우선순위 변경 없음 (해결은 후속 task). "완료된 작업" 표에 본 task 행 추가 — 결정 문서화 완료, 코드 통합 미실행 명시
- 본 PHASE3.md 엔트리

**검증**:
- §5 수동 체크리스트 (C1~C3, D1~D3, A1~A2, CQ1~CQ2): ADR 본문 작성 시 모든 컬럼/대안축 채움 확인
- `bash docs/consistency-hints.sh` exit 0 통과
- `./gradlew test` 영향 없음 (Java 코드 변경 0건)
- 후속 task `task-d005-observability-consolidation` 작업 항목이 본 ADR §Decision 표에서 1:1 도출 가능 (CQ1, CQ2 충족)

**브랜치**: `docs/adr-0009-observability-ssot`. 계획 리뷰 1 loop (Codex 6건 — P0:0/P1:4/P2:2 — 전체 반영). /work 3 loops (Codex 12건 — P0:0/P1:8/P2:4 — 전체 반영). PR: https://github.com/Kimgyuilli/PeakCart/pull/30

## 2026-05-06 — task-d005-observability-consolidation (D-005 강제 메커니즘 격상)

**범위**: ADR-0009 §Decision 표 6번째 컬럼 ("검증 수단") 의 D5-V1~V6 action 을 1:1 격상 (D5-V6 는 라벨 invariant 만 부분 격상 — PromQL syntax 는 §7 R1 트레이드오프). surface 의 *위치* 변경 0건 — Phase 4 멀티모듈 분리 task 가 ADR-0009 인용으로 수행할 부분은 본 task 비대상.

**Part A — lint script 3종 신규**:
- **`scripts/observability-ssot-lint.sh`** (D5-V1 + D5-V2): yaml 파싱(pyyaml) + grep. base `application.yml` 가 SSOT 인 키 (`management.metrics.tags.application` / `management.endpoints.web.exposure.include`) 의 다른 프로파일 재선언 검출 + `MeterFilter`/`MeterRegistryCustomizer<>` 가 `MetricsConfig.java` 외 클래스에 있는지 검사 + application 태그 값 'peekcart' 외 검출. 화이트리스트는 ADR-0007/0009 회색지대 분류 (probes.enabled/show-details).
- **`scripts/servicemonitor-selector-lint.sh`** (D5-V5): `kubectl kustomize` 로 양 overlay (minikube, gke) 빌드 → ServiceMonitor `spec.selector.matchLabels` 와 `endpoints[].port` 가 같은 namespace Service 의 `metadata.labels` / `spec.ports[].name` 과 매칭되는지 정적 검증. kubectl 미존재 시 skip exit 0.
- **`scripts/observability-promql-lint.sh`** (D5-V6 부분): grafana-alerts ConfigMap → 내부 yaml → 각 rule 의 `data[].model.expr` 추출. alert uid 별 required-label matrix 적용 — S6.a/b (`peekcart-high-error-rate`/`peekcart-slow-response`): `application` 필수 + S2 ground truth (`application.yml` `management.metrics.tags.application`) 일치 / S6.c/d (`peekcart-target-down`/`peekcart-scrape-absent`): `namespace`+`service` 필수 + S5 ground truth (servicemonitor namespace + selector `matchLabels.app`) 일치. presence 부재 + value mismatch 모두 검출.

**Part B — 통합 테스트 메서드 2건 추가** (`ObservabilityMetricsIntegrationTest`):
- **D5-V3** (`actuatorExposure_whitelistsExactlyHealthAndPrometheus`): health/prometheus 200 + info/env 는 200 미반환 (`isNotEqualTo(HttpStatus.OK)`). Spring Security filter 가 actuator 이전에 동작하므로 `/actuator/info` 는 PUBLIC_URLS 미포함 → 401. plan §3 P3 가 `NOT_FOUND` 로 단정한 부분은 보안 레이어 작동 순서 미고려 — 실제 ground truth 는 401(security) 또는 404(actuator). 어느 쪽이든 200 미반환이 화이트리스트 회귀 신호 (`isNotEqualTo(OK)` 로 양쪽 케이스 모두 커버).
- **D5-V4** (`actuatorHealth_noAuthRequired`): `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` 가 인증 없이 200. K8s liveness/readiness Probe 의존.
- 클래스 레벨 `@TestPropertySource(properties = "management.endpoint.health.probes.enabled=true")` 추가 (ADR-0007/0009 회색지대 재결정 회피 — `application-test.yml` 에 management 정책 추가 안 함).

**Part C — CI 통합** (`.github/workflows/ci.yml`):
- `chmod +x gradlew` 다음 + `./gradlew build` 이전 위치에 step 3건 추가:
  - `Install lint dependencies` — `python3 -m pip install --user pyyaml`
  - `Set up kubectl` — `azure/setup-kubectl@v4`
  - `Run observability lints` — 3 lint script 순차 실행
- 정책 위반은 build (수분) 비용 부담 전 fail. 후속 step 은 default `if: success()` 로 skip. `upload-artifact` 의 `if: always()` 는 그대로 (lint 실패 시에도 빈 artifact 업로드 — 디버깅 가치 유지).

**Part D — 문서 동기화**:
- TASKS.md: D-005 행 우선순위 `중간 — 1차 봉합 완료, 잔여 리스크` → ~~중간~~ **해결됨**. "완료된 작업" 표 새 행.
- 본 PHASE3.md 엔트리.
- `docs/02-architecture.md` 변경 없음 (§12 패키지 구조가 `scripts/` 미언급 상태).
- `CLAUDE.md` 변경 없음 (ADR-0009 참조는 ADR task 에서 추가됨).

**부동성 (ADR-0009 §Decision 표 4번째 컬럼 = "없음" 유지)**:
- `MetricsConfig.java` (S1) / `application.yml` (S2/S3 base) / `application-k8s.yml` 회색지대 키 / `SecurityConfig.java` (S4) / `servicemonitor.yml` (S5) / `grafana-alerts.yml` (S6.a~d) 변경 0건.

**검증**:
- `./gradlew test`: 244 + 신규 2건 (P3+P4) = 246건 통과 (ObservabilityMetricsIntegrationTest 4건).
- 3 lint script 현 트리에서 exit 0 (positive).
- negative detector branch 11건 모두 exit 1 (PR 본문 §검증 부록에 명령 + exit code 첨부): D5-V1 두 키 / D5-V2 Java 중복 + base yaml 값 변경 / D5-V5 selector + port / D5-V6 value mismatch 3 + label absence 2 family 대표.
- D5-V3/V4 는 통합 테스트 assertion 자동 회귀 — negative 별도 증빙 불필요.
- `bash docs/consistency-hints.sh` exit 0.

**잔여 (D5-V6 부분 격상 — §8 후속)**:
- PromQL syntax check (ADR-0009 §Decision S6 검증 수단의 절반): `promtool promql format` 의 experimental 기능 의존 + CI 설치 step + expr 추출 비용 비대칭으로 본 task 기각. 재검토 트리거 — Phase 4 OpenTelemetry alert 수 증가 / promtool stable 승격 / 운영 인시던트.

**브랜치**: `chore/task-d005-observability-consolidation`. 계획 리뷰 3 loops (Codex 7건 — P0:0/P1:4/P2:3 — 전체 반영). /work loop 1 + Codex split review 1 loop (3 chunks 9건 — P0:1/P1:4/P2:4 — 7건 반영 + 1건 부분 + 2건 거부). PR: https://github.com/Kimgyuilli/PeakCart/pull/32

## 2026-05-07 — cleanup.sh VM 이름 정정 (Task 3-4 세션 C 부산물 fix)

**범위**: 세션 C 측정 (2026-04-29) 시 발견된 cleanup.sh 기본값 불일치 정리. TASKS.md 491번 행 "별도 fix 안건" 으로 유지되던 잔여. 9 lines / 4 files. /plan→/work 워크플로우 우회 + 수동 ship (state 파일 부재로 /ship harness 미구동).

**변경**:
- `loadtest/cleanup.sh` — `LOADGEN_NAME` 기본값 `loadgen` → `peekcart-loadgen` (헤더 주석 + 변수 기본값 동시 갱신)
- `loadtest/README.md:41` — 전제조건 VM 이름 동기화
- `loadtest/reports/TEMPLATE.md:123` — 정리 체크리스트 명령 동기화

**근거 (이름 결정)**:
- `docs/progress/PHASE3.md:981` — "loadgen VM `peekcart-loadgen` (e2-standard-2 ...)" 실제 프로비저닝 명세
- `:1018` — "`loadgen=loadgen` → `loadgen=peekcart-loadgen` 로 정정 필요" 명시
- cluster `peekcart-loadtest` prefix 와 일관성

**비변경 (immutable 이력)**:
- `loadtest/reports/2026-04-09/REPORT.md`, `loadtest/reports/2026-04-29/REPORT.md` — 이력 기록은 미수정. 미래 측정 세션 진입점 (TEMPLATE 복사) 과 운영 스크립트 기본값만 정정.

**검증**:
- `bash loadtest/cleanup.sh --dry-run` 출력에서 `loadgen=peekcart-loadgen` + `gcloud compute instances delete peekcart-loadgen --zone=asia-northeast3-a --quiet` 정상 치환 확인.
- 코드/매니페스트 변경 없음 → 단위·통합 테스트 영향 0.

**브랜치**: `fix/cleanup-loadgen-vm-name`. 커밋 3개 (`fix(loadtest)` / `docs(tasks)` / `docs: append PR link`). PR: https://github.com/Kimgyuilli/PeakCart/pull/33

## 2026-05-07 — D-008 해결 (Grafana datasource UID 명시 pin)

**범위**: D-008 ("Grafana datasource UID 하드코딩 — Helm 업그레이드 시 변경 가능성") 해결. 차트 기본값 의존을 끊고 values 에서 UID 를 명시 pin 하여 회귀 차단. dashboard JSON · alert YAML 변경 0건 (기존 `uid: prometheus` 참조 그대로 유지).

**변경**:
- `k8s/monitoring/minikube/values-prometheus.yml` — `grafana.sidecar.datasources.uid: prometheus` 추가 + D-008 의도 주석
- `k8s/monitoring/gke/values-prometheus.yml` — 동일

**비변경 (의도)**:
- `k8s/monitoring/shared/api-jvm-dashboard.json` (7건), `kafka-lag-dashboard.json` (3건), `pod-resources-dashboard.json` (4건) 의 `"uid": "prometheus"` 14건 — values pin 과 동일 값이라 변경 불필요
- `k8s/monitoring/shared/grafana-alerts.yml` 의 `datasourceUid: prometheus` 8건 — 동일
- `install.sh` chart version pin — 별도 결정 영역 (D-008 범위 외)

**근거 (kube-prometheus-stack 차트 기본값 점검)**:
- `helm show values prometheus-community/kube-prometheus-stack` → `grafana.sidecar.datasources.uid: prometheus` 가 차트 기본값. 현재 작동하는 이유.
- 차트 87+ 또는 grafana subchart 변경 시 기본 UID 변경 위험 — 명시 pin 으로 forward-compat.

**검증**:
- `helm template kube-prometheus-stack prometheus-community/kube-prometheus-stack -n monitoring -f k8s/monitoring/minikube/values-prometheus.yml` → datasource ConfigMap 에 리터럴 `uid: prometheus` 렌더링 확인 (gke 동일).
- 코드 변경 없음 → 단위·통합 테스트 영향 0.

**ADR-0009 영향**: S6 (Grafana alerts) surface 의 `datasourceUid` 는 본 task 범위 외. ADR-0009 §Decision 표 4번째 컬럼 ("본 task 변경" = "없음") 부동성 유지 — pin 은 *값 ground truth* 가 아니라 *해석 안정성* 격상.

**브랜치**: `fix/d008-grafana-datasource-uid-pin`

## 2026-06-07 — Tier A 즉시 정정 (Phase 4 진입 전 버킷 1)

**범위**: Phase 4 진입 전 기술부채 로드맵 §2 "작업 1 — Tier A: 즉시 정정". D- 승격 없이 폐기하는 명백한 문서/주석/쿼리 오류 5건 정정. Phase 3 산출물의 운영 안내·검증 쿼리·리포트 해석·대시보드 범례 오류를 바로잡는 범위이며 애플리케이션 런타임 코드 변경은 0건.

**변경**:
- **L-018 (Docs/Cost)**: ADR-0004 운영 체크리스트와 GKE README 의 정리 명령을 `bash loadtest/cleanup.sh` 단일 진입점으로 통일. 스크립트 기본값(`peekcart-loadtest` / `peekcart-loadgen` / `asia-northeast3-a` / `asia-northeast3`) 을 문서에 명시하고, 실행 후 `disks list` / `addresses list` 출력 확인을 유지. `loadtest/reports/TEMPLATE.md` 도 같은 진입점으로 갱신해 향후 리포트 템플릿에서 오류가 재생산되지 않게 함.
- **L-021 (Testing)**: `loadtest/sql/verify-concurrency.sql` A쿼리의 판매량 집계를 `SUM(oi.quantity)` 에서 `SUM(CASE WHEN o.id IS NOT NULL THEN oi.quantity ELSE 0 END)` 로 정정. `LEFT JOIN orders ... AND status NOT IN (...)` 에서 필터 탈락한 주문 아이템이 판매량에 포함되는 false mismatch/false pass 가능성 차단.
- **L-022 (Docs)**: `loadtest/reports/2026-04-29/REPORT.md` 의 HPA CPU `400%` 해석 정정. 노드 전체 vCPU 기준 해석이 아니라 Pod request `500m` 대비 `400%` = `2000m` = Pod CPU limit 도달로 기록.
- **L-016(b) (Deploy)**: `k8s/overlays/gke/patches/peekcart-deployment.yml` 의 `imagePullPolicy` 주석 정정. base image tag 가 `:latest` 이므로 Kubernetes 기본값은 `IfNotPresent` 가 아니라 `Always`.
- **L-020(1) (Observability)**: `k8s/monitoring/shared/kafka-lag-dashboard.json` 범례를 `{{kafka_consumer_group_id}}` 에서 `{{client_id}}` 기반으로 정정. 본 프로젝트는 Micrometer Kafka client metric 을 사용하며 consumer group 정보가 `client_id` 에 임베디드되어 있어 기존 범례가 빈 문자열로 렌더될 수 있었음.
- **TASKS.md**: `Tier A 즉시 정정` 상태를 `✅` 로 완료 표기.

**비변경 (의도)**:
- L-016(a) image digest 고정과 L-020(2) consumer group 독립 라벨 노출은 로드맵 §3 버킷 2 로 유지. 이번 PR 은 주석/범례의 명백한 오류만 정정.
- `loadtest/cleanup.sh` 동작 변경 없음. 이미 `fix/cleanup-loadgen-vm-name` 에서 기본값 정정 완료된 스크립트를 문서가 따르도록 맞춤.
- 애플리케이션 Java 코드, K8s 리소스 spec 동작, Grafana datasource UID, alert rule 변경 없음.

**검증**:
- `git diff --check HEAD~6..HEAD` 통과 (Tier A 6개 커밋 기준 공백 오류 없음).
- `jq empty k8s/monitoring/shared/kafka-lag-dashboard.json` 통과.
- 낡은 cleanup 명령, 잘못된 CPU 해석, 잘못된 `imagePullPolicy` 주석, dashboard JSON 내 부재 라벨 잔존 검색 통과.
- 코드 변경 없음 → `./gradlew test` 미실행.

**브랜치**: `chore/tier-a-immediate-fixes`. 커밋 6개 (`docs(loadtest)` / `fix(loadtest)` / `docs(loadtest)` / `docs(k8s)` / `fix(monitoring)` / `docs(tasks)`). PR: 미생성.

## 2026-06-08 — D-012 해결 (CI 품질 게이트: PR build·smoke·branch protection·NS lint)

**범위**: Phase 4 진입 전 기술부채 로드맵 §2 "작업 2 — D-012: CI 품질 게이트". 기존 CI 가 `./gradlew build` 중심이라 PR 이미지 빌드·런타임 smoke·main branch protection·Kustomize namespace 누출 검증이 빠져 있던 상태를 버킷 1 범위에서 닫음.

**변경**:
- **L-017 (K8s/CI)**: `scripts/kustomize-namespace-lint.sh` 신규 추가. `kubectl kustomize` 로 `k8s/overlays/{minikube,gke}` 렌더링 후 namespaced 리소스가 기대 namespace(`peekcart`) 를 직접 선언했는지 PyYAML 로 검증. `base` 의 `namespace:` 필드 미사용 원칙을 유지하면서 overlay 산출물의 namespace 누락/불일치만 CI 에서 차단.
- **L-015 (Docker build gate)**: `.github/workflows/ci.yml` PR 경로에 `docker/build-push-action@v5` 이미지 빌드 게이트 추가. `push: false`, `cache-from: type=gha`, 태그 `peekcart-ci:${{ github.sha }}` 로 빌드 성공 자체를 검증하고 main push 의 GHCR publish 경로와 분리.
- **L-015 (Runtime smoke gate)**: `scripts/docker-health-smoke.sh` 신규 추가. PR 빌드 이미지를 `load: true` 로 로컬 Docker daemon 에 적재한 뒤 기존 `docker-compose.yml` 의 MySQL/Redis/Kafka 를 올리고 앱 컨테이너를 같은 compose 네트워크에 붙여 `/actuator/health` HTTP 200 을 확인. 앱 조기 종료/timeout 시 `docker logs` 를 출력해 실패 원인을 CI 로그에 남김.
- **L-014 (Branch protection)**: GitHub API 로 `main` branch protection 적용. required status check 는 현재 단일 CI job 이름인 `build`, `strict: true`, force push/delete 금지. PR 리뷰 강제와 admin enforcement 는 본 항목 범위 밖이라 미적용.
- **TASKS.md**: `CI 품질 게이트` PR 단위와 `D-012` 상태를 `✅` 완료로 갱신.

**비변경 (의도)**:
- CI job 분리는 하지 않음. 현 워크플로우에서 lint, Gradle build, PR Docker build, smoke 가 모두 `build` job 안에 있으므로 branch protection required check 도 `build` 하나로 지정.
- PR 경로의 `cache-to` 는 추가하지 않음. main push 캐시를 읽되 PR 에서 새 캐시를 쓰지 않아 fork/권한 이슈와 PR 캐시 오염을 피함.
- Branch protection 에 PR 리뷰 필수, admin 강제, linear history 강제는 추가하지 않음. D-012/L-014 정의는 required status check 지정이므로 설정 범위를 그 안에 제한.
- Kafka 대기는 `/actuator/health` 200 판정 자체보다는 `@KafkaListener` 포함 런타임 기동 안정화 목적. 비용은 있지만 smoke 범위 안에 유지.

**검증**:
- `PATH=/tmp/peakcart-lint-venv/bin:$PATH bash scripts/kustomize-namespace-lint.sh` 통과.
- fake `kubectl` negative 검증에서 minikube/gke 양 overlay 위반을 한 번에 보고하고 exit 1 반환 확인.
- `docker build -t peakcart-ci:local .` 통과.
- `bash scripts/docker-health-smoke.sh peakcart-ci:local` 통과 — MySQL/Redis/Kafka 기동 후 앱 `/actuator/health` 200 확인.
- `gh api repos/Kimgyuilli/PeakCart/branches/main/protection --jq ...` 로 `strict=true`, `contexts=["build"]`, `checks=["build"]`, `allow_force_pushes=false`, `allow_deletions=false` 확인.
- `bash -n scripts/docker-health-smoke.sh`, `git diff --check` 통과.

**브랜치**: `chore/d012-ci-quality-gates`. 커밋 5개 (`docs(tasks)` / `ci(k8s)` / `ci(docker)` / `ci(docker)` / `docs(tasks)`). PR: 미생성.
