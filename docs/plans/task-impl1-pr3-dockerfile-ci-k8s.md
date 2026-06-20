# task-impl1-pr3-dockerfile-ci-k8s — PR3: 서비스별 Dockerfile / CI / k8s 재구성

> 구현 ① 의 마지막 조각. 5개 서비스 풀 분해(#65, root app 소멸) 이후, 단일 `peekcart` 모놀리스를 전제하던 **배포 표면(Dockerfile·CI 이미지·k8s 매니페스트·관측성 lint/alert/dashboard)을 서비스별(5)로 재구성**한다. 이로써 구현 ① 종료.
> 선행 ADR: ADR-0004(GCP/GKE), ADR-0005(Kustomize base/overlays), ADR-0006(monitoring NS 분리), ADR-0009(관측성 SSOT), ADR-0011(멀티모듈 빌드/이미지 계약). 편입 부채: **L-016a**(이미지 digest 고정), **D-016**(image promotion 자동화).
> **GP-1(2026-06-19)**: 신규 ADR 대부분 불필요(GHCR/GKE/Kustomize/모듈 계약 기결정). **예외 — ADR-0009**: §Decision 의 `application=peekcart` 단일값 불변식(D5-V2 lint 강제)이 per-service 태그로 깨진다. 사용자 게이트 → **본 PR 작업 항목(P12)으로 편입**, 진행. **단 ADR 운영 규칙(README §8/§14 — 본문 immutable·결정 변경 시 신규 ADR·부분 무효화는 `Partially Superseded by`)에 따라, ADR-0009 본문 직접 수정이 아니라 신규 ADR-0015(observability per-service contract) 작성 + ADR-0009 Status=Partially Superseded 표기로 처리**(Codex GP-2 #2).
> **PR 분할(PR3a ✅ 머지 #66)**: 3 PR — **PR3a ✅** Dockerfile+CI 이미지(빌드/스모크/푸시)+image-contract-lint per-service(전환기 `IMAGE_CONTRACT_TRANSITION=1` suspended) → **PR3b** k8s base/overlays per-service 재구성 + **ServiceMonitor 매니페스트(5)·selector-lint 실질검증**(GP-2 #1 — ADR-0006 ServiceMonitor=`services/<svc>/` 소속 불변식, PR3c 에서 당김) + **image-contract-lint transition flag 해제(full 5/5)** → **PR3c** 관측성 per-service 재설계(alert/dashboard)+observability lint 2종 재활성+ADR-0009 개정(ADR-0015). 각 PR 끝에서 그린 체크포인트.
> **GP-2(2026-06-20, loop 1) 반영**: ServiceMonitor PR3c→PR3b 당김(#1)·image-contract flag 해제 P8 명시(#2)·Secret 표 코드정정+Toss fail-fast(#3)·**HPA order-service만**(#4, 로드맵 §16 정합)·minikube/gke overlay per-service patch 확정(#5)·flywayMigrateShared 후속부채 명문화(#6)·product 전용 `PEEKCART_CACHE_ENABLED` ConfigMap 유지(#7).
> **GP-2(2026-06-20, loop 2) 반영**: servicemonitor-selector-lint count==5 강제+P9→P8 실행순서(#1, vacuous-green 차단)·no-op SlackPort fallback property-gate 화+notification fail-fast(#2, silent 알림 유실 차단)·P2/트레이드오프 flywayMigrateShared 문구 공식 flyway 이미지로 통일(#3)·§1 성공기준 ADR-0015 명문화 표현 정정(#4).
> **GP-2(2026-06-20, loop 3) 반영**: Slack no-op 게이팅 근본수정(#1) — 4서비스 base yml `slack.webhook.url` placeholder 기본값이 presence-based `@ConditionalOnProperty` 를 항상 true 로 만들어 fail-fast·no-op 둘 다 깨짐 → 기본값 제거 + real↔no-op 명시 property 상호배타(PLAN-BLINDSPOTS B6 함정² 추가)·D-016 P14 를 PR3b 로 배치+§5/§6 검증 명시(#2).

## 1. 목표

5개 서비스(notification/user/product/order/payment)를 각각 **독립 컨테이너 이미지**로 빌드·푸시하고, **k8s 에 서비스별 Deployment/Service/ConfigMap/Secret/ServiceMonitor 로 배포**하며, **관측성(alert/dashboard/lint)을 서비스별 `application=<service>` 라벨 기준으로 재설계**한다. 단일 `peekcart` 이미지·Deployment·`application=peekcart` 전제를 제거하고, D-015 이미지 참조 계약(ci==base==gke)을 서비스별로 유지한다.

**성공 기준**: (1) `docker build` 로 5개 서비스 이미지 로컬 빌드 성공 + 각 이미지 `docker-health-smoke.sh` (profile `k8s`) `/actuator/health` 200. (2) `kubectl kustomize k8s/overlays/{minikube,gke}` 렌더 성공 + `kustomize-namespace-lint`·`servicemonitor-selector-lint`·`image-contract-lint`(per-service) 그린. (3) `observability-ssot-lint`·`observability-promql-lint` **재활성** 후 그린(per-service ground truth). (4) ci.yml 매트릭스가 5개 서비스 이미지 빌드/스모크/푸시. (5) **ADR-0015 §Decision** 이 per-service 관측성 SSOT 를 명문화하고, **ADR-0009 Status 가 `Partially Superseded by ADR-0015`(본문 불변)** 로 바뀌며, D5-V2 lint 가 새 불변식과 정합(GP-2 loop2 #4 — ADR-0009 본문 직접 수정 아님).

## 2. 배경 / 제약

### 현재 코드 (grep 검증 완료, 2026-06-19)

- **Dockerfile 0개** — root 단일 Dockerfile 은 `90236f6`(root 단일 이미지 제거)에서 삭제. 복원해 본 직전 버전은 **stale**(common/observability/auth/notification/user 만 COPY — Product/Order/Payment peel 이전) + base 이미지 `eclipse-temurin:17-jdk`(digest 미고정) + root `app.jar` 산출(현재 미존재). → **참조 구조로만 사용, 그대로 복원 금지**.
- **5개 서비스 모두 `bootJar` 산출**(`{service}/build.gradle` `id 'org.springframework.boot'`+`bootJar{}` 확인). 공통은 `:common`/`:peekcart-common-auth`/`:peekcart-common-observability` 의존.
- **5개 서비스 모두 `application.yml`(+`-k8s`·`-local`) 보유** + `management.metrics.tags.application: <service-name>` **이미 per-service**(notification-service/user-service/product-service/order-service/payment-service). → 관측성 ground truth 는 코드상 이미 per-service. 깨진 건 **lint/alert/dashboard 가 아직 단일 `peekcart` 하드코딩**인 점.
- **ci.yml** 현재: `gradlew build` + lint 3종(kustomize-namespace·image-contract·servicemonitor-selector) **활성**, observability-ssot·promql 2종 **비활성**(PR-b 주석 — 삭제된 root `application.yml` SSOT 전제). Docker build/smoke/GHCR push 블록 **제거됨**(PR-b). `IMAGE_NAME: ghcr.io/<owner>/peekcart` 단일.
- **k8s base** = 단일 `services/peekcart/`(deployment+configmap+secret+servicemonitor). deployment: 1 컨테이너 `peekcart`, image `ghcr.io/kimgyuilli/peekcart:latest`, port 8080, probes `/actuator/health/{liveness,readiness}`, envFrom config/secret. **overlays(minikube/gke)** = 단일 `peekcart` Deployment/Service 를 name 기반 strategic-merge 패치. gke: `images[] ghcr.io/kimgyuilli/peekcart → AR`, `hpa.yml peekcart`.
- **monitoring/shared**: `grafana-alerts.yml`(4 alert: error-rate/slow-response → `application="peekcart"`, target-down/scrape-absent → `namespace="peekcart",service="peekcart"`), dashboards(api-jvm `application="peekcart"`, kafka-lag `application="peekcart"`, pod-resources `namespace="peekcart"`).
- **docker-compose.yml**: mysql:8.0 / redis:7.2 / kafka:3.8.1 (스모크 인프라, `docker-health-smoke.sh` 가 사용).

### B1/B1b — 역의존 스윕 (단일 `peekcart` 식별자의 인바운드 간선 처분)

peel(코드 이동)이 아니라 **인프라 재구성**이지만, 단일 `peekcart` 이름(이미지/Deployment/`application` 라벨)이 lint·overlay·alert·dashboard 에 **문자열로 하드코딩**돼 있어 B1b(string-level 결합) 가 그대로 적용된다. 각 인바운드 처분:

| # | 인바운드 (단일 `peekcart` 참조) | 종류 | PR | 처분 |
|---|---|---|---|---|
| 1 | `ci.yml` `IMAGE_NAME: …/peekcart` | CI env | 3a | **per-service** — 매트릭스 `peekcart-<service>` 5개 |
| 2 | `scripts/image-contract-lint.sh` — `k8s/base/services/peekcart/deployment.yml` + 단일 ci IMAGE_NAME 3-way | lint | 3a→3b | **per-service N-way** — 5 서비스 loop(ci 매트릭스 ↔ base/services/`<svc>`/deployment ↔ gke images[] entry). PR3a 에서 1서비스 시범, PR3b 에서 5 완성 |
| 3 | `k8s/base/kustomization.yml` resources `services/peekcart/*` | kustomize | 3b | **per-service** — 5 서비스 디렉토리 resources |
| 4 | `k8s/base/services/peekcart/{deployment,service,configmap,secret,servicemonitor}.yml` | manifest | 3b | **분할** → `services/<svc>/*` 5세트 **전부 3b**(deployment/service/configmap/secret/**servicemonitor**) — GP-2 #1: ServiceMonitor 를 PR3c→3b 로 당겨 ADR-0006 소속 불변식 충족(alert/dashboard/observability-lint 재설계만 3c) |
| 5 | `overlays/minikube/kustomization.yml` + `patches/peekcart-{deployment,service}.yml` | overlay | 3b | **per-service** — 5 서비스 패치(또는 공통 패치 components) |
| 6 | `overlays/gke/kustomization.yml`(patches+`images[] …/peekcart`) + `patches/peekcart-{deployment,service}.yml` + `hpa.yml` | overlay | 3b | **per-service** — 5 deployment/service 패치, `images[]` 5 entry(AR rewrite), HPA 처분(아래 트레이드오프) |
| 7 | `overlays/gke/README.md` — AR repo path·image ref·hpa cmd | docs | 3b | **per-service** 갱신(What 동기화) |
| 8 | `monitoring/shared/grafana-alerts.yml` — `application="peekcart"`(S6.a/b)·`namespace/service="peekcart"`(S6.c/d) | alert | 3c | **per-service** — alert 를 service 별로(아래 트레이드오프: 템플릿 복제 vs service 라벨 by-clause) |
| 9 | `monitoring/shared/{api-jvm,kafka-lag}-dashboard.json` `application="peekcart"` | dashboard | 3c | **Grafana 템플릿 변수 `$application`**(label_values) 도입 — 단일 대시보드 var 구동. `pod-resources`(`namespace="peekcart"`)는 5서비스 동일 NS 라 무변(verify) |
| 10 | `scripts/observability-ssot-lint.sh` — SSOT=root `src/main/resources/application.yml`(**삭제됨**), `value=="peekcart"` | lint | 3c | **재설계+재활성** — SSOT=서비스별 `<svc>/src/main/resources/application.yml`, `application` 값 ∈ 서비스명 집합(각 모듈은 자기 service 명) |
| 11 | `scripts/observability-promql-lint.sh` — ground truth root `application.yml` + `base/services/peekcart/servicemonitor.yml` | lint | 3c | **재설계+재활성** — per-service ground truth(서비스별 app.yml + `services/<svc>/servicemonitor.yml`), alert uid 매트릭스 per-service |
| 12 | `scripts/servicemonitor-selector-lint.sh` | lint | 3b | **수정(보강·GP-2 loop2 #1)** — 현 스크립트는 ServiceMonitor 0개면 vacuous-green → **count==5 + canonical 서비스명 집합 강제** 추가 후 ServiceMonitor↔Service `app=<svc>` 매칭 검증(P9 선행) |
| 13 | `scripts/kustomize-namespace-lint.sh` | lint | 3b | **무변(제네릭)** — `EXPECTED_NAMESPACE=peekcart` 유지(5서비스 동일 NS) |
| 14 | `scripts/docker-health-smoke.sh` | smoke | 3a | **무변(제네릭)** — `<image-tag>` 인자·profile `k8s`. CI 매트릭스가 서비스별 호출. 병렬 job 격리(컨테이너명/포트 고정 무해) |

> **검증 강제**: PR3a 후 `grep -rn "peekcart\b" .github scripts | grep -iv "peekcart-common\|peekcart-<service>"` 로 단일 `peekcart` 잔존이 의도된 곳(NS·common 모듈)뿐인지 확인. PR3c 후 `grep -rn 'application="peekcart"\|application: peekcart' k8s scripts` → 0(전부 per-service 또는 템플릿 변수).

### B5 — Dockerfile COPY 멀티모듈 컨텍스트 (memory: project_multimodule_dockerfile_context)

각 서비스 이미지는 **자기 모듈 + 의존 common 3종 + 의존 해석용 전 모듈 `build.gradle`** 을 빌드 컨텍스트로 COPY 해야 한다(settings.gradle include 정합 — gradle 설정 단계가 전 모듈 평가). 처분:
- **단일 Dockerfile + ARG `SERVICE`**(루트 1 Dockerfile, `./gradlew :${SERVICE}:bootJar`, COPY 는 전 모듈 컨텍스트) **vs 서비스별 Dockerfile 5개**. **권고: 단일 Dockerfile + ARG** — COPY 목록 표류(stale Dockerfile 선례) 단일 지점화, 모듈 추가 시 1곳만 갱신. 단 `COPY <module>/ <module>/` 는 전 모듈 나열 필요(settings.gradle 정합) → settings.gradle 변경 시 Dockerfile 동기 + **로컬 `docker build` 검증 필수**(로컬 gradle 그린 ≠ Docker 그린).
- 런타임 stage 는 `COPY --from=build /app/<service>/build/libs/*.jar app.jar`(서비스별 bootJar 경로). non-root user 유지.

### L-016a — base 이미지 digest 고정

삭제된 Dockerfile 은 `eclipse-temurin:17-jdk`(tag, 가변). → `FROM eclipse-temurin:17-jdk@sha256:<digest>` / `17-jre@sha256:<digest>` 로 **digest 고정**(재현성). 앱 이미지 푸시도 tag + digest 병행, k8s 는 가능하면 digest 참조(또는 CI 가 digest 를 매니페스트에 주입 — D-016 과 연계).

### D-016 — image promotion 자동화

GHCR(빌드) → AR(GKE pull) 승격. 현재 gke overlay 가 `images[] newName` 으로 AR 경로 rewrite 만 함(수동 copy 전제, README). **최소 범위**: CI 가 GHCR 푸시 후 digest 를 산출하고, 승격(GHCR→AR copy 또는 AR 직접 푸시)을 **스크립트/문서로 형식화**. **본 PR 범위 판단**: 자동 copy 파이프라인 전체는 무거움 → P14 에서 **digest 산출 + 승격 절차 스크립트화(수동 트리거 가능)** 까지, 완전 자동 트리거(태그/릴리스 훅)는 필요 시 후속. (사용자/Codex 게이트로 범위 확정.)

### B2 — ADR 타깃 ≠ 현재 코드

- "서비스별 이미지/Deployment"(ADR-0011)·"Kustomize base/overlays"(ADR-0005) 는 **현재 단일 `peekcart` 만 존재** → "서비스별로 만든다"를 명시 작업 항목(P1·P4·P6·P7)으로 승격. ADR 은 목표, 코드는 현재.
- ADR-0009 §Decision `application=peekcart` 단일값은 **코드(서비스별 태그)와 이미 불일치** — **신규 ADR-0015 로 supersede**(P12, ADR 본문 immutable 원칙). ADR-0006 "모든 리소스 peekcart NS" 불변식은 **유지**(5서비스 동일 NS).

### 트레이드오프

- **Dockerfile: 단일+ARG vs 5개**: 단일+ARG 권고(COPY 표류 단일화·DRY) ↔ 서비스별은 명시적이나 5중 COPY 표류 위험. **선택: 단일+ARG.**
- **Alert per-service: 템플릿 복제(5×4=20 rule) vs service 라벨 by-clause(rule 수 유지, `by(application)` + service 라벨 알림)**: 복제는 서비스별 임계/무음화 유연하나 비대, by-clause 는 간결하나 서비스별 튜닝 불가. **권고: by-clause(`sum by(application)…`) + alert 라벨에 `{{ $labels.application }}`** — rule 수 폭증 회피, 어느 서비스인지 식별. promql-lint 매트릭스도 그에 맞춤.
- **Dashboard: 템플릿 변수 `$application` vs 5× 복제**: 변수 권고(단일 대시보드, 드롭다운 전환).
- **HPA(gke): 5 서비스 전부 vs order-service만**: **확정(GP-2 #4, 사용자 게이트 2026-06-20): order-service HPA 1개만**. 근거 — 로드맵 §16(`docs/07-roadmap-portfolio.md:80-85`)이 "Phase 4 MSA 이후 HPA=Order Service HPA"를 What 으로 명시 → Layer 1 정합(문서 무수정) + CLAUDE.md §2 simplicity. 5서비스 균일안은 기각(로드맵 What 변경·문서 갱신 비용). 타 서비스 HPA 는 필요 시 후속.
- **DB 미분리 유지**: 5서비스 동일 MySQL(② 이연). B5 런타임 마이그레이션 = order-service 가 승계(#65 P13). k8s 에서 **cold-start ordering**(order-service 마이그레이션 → 타 서비스 validate-부팅) 형식화 필요 → **확정: 비-order 서비스 deployment 에 order-service readiness 폴링 initContainer**(P4). CI smoke 는 공식 flyway 이미지 선마이그레이션(P2·PR3a 완료; root `flywayMigrateShared` 깨짐 → 후속 부채, k8s 재사용 금지). 단일 이미지 시절엔 없던 신규 표면.
- **Secret 관리**: 현재 base `secret.yml`(평문 매니페스트). payment-service 만 Toss 키 필요 → 서비스별 secret 분리(payment-service secret 에 Toss). KMS/Vault(L-002)는 ③ Gateway 보안 묶음 — 본 PR 범위 외, 평문 secret 유지(현행).

## 3. 작업 항목

### PR3a — 서비스별 Dockerfile + CI 이미지(빌드/스모크/푸시) + image-contract-lint

- [ ] **P1.** **단일 Dockerfile + ARG `SERVICE`**(루트, B5): multi-stage — build stage 가 전 모듈 `build.gradle`(8) + 전 모듈 소스 COPY(settings.gradle include 정합), `RUN ./gradlew :${SERVICE}:bootJar -x test --no-daemon`. runtime stage `COPY --from=build /app/${SERVICE}/build/libs/*.jar app.jar`, non-root user, EXPOSE 8080, ENTRYPOINT java -jar. **L-016a**: `FROM eclipse-temurin:17-jdk@sha256:…`·`17-jre@sha256:…` digest 고정. **로컬 검증**: 5개 서비스 `docker build --build-arg SERVICE=<svc>` 전부 성공.
- [ ] **P2.** ci.yml **이미지 매트릭스** 도입: `strategy.matrix.service: [notification-service,user-service,product-service,order-service,payment-service]`. 각 service: docker build(`--build-arg SERVICE`) → `docker-health-smoke.sh <local-tag>`(profile k8s, `/actuator/health` 200) → GHCR push `ghcr.io/<owner>/peekcart-<service>:{sha,latest}` (+ digest 산출). `env.IMAGE_NAME` 제거/매트릭스화. PR-b 가 제거한 build/smoke/push 블록을 per-service 로 복원. build job(gradle 전체 빌드/테스트)은 매트릭스 선행(또는 별 job)으로 유지.
  - **⚠️ smoke 전 공유 스키마 마이그레이션 필수(Codex GP-2 #1)**: 비-order 서비스(notification/user/product/payment)는 Flyway disabled + JPA `ddl-auto:validate` 라, `docker-health-smoke.sh` 가 인프라(빈 MySQL)만 올리고 앱을 바로 띄우면(`docker-health-smoke.sh:40`) **health 200 전에 schema validate 로 죽는다**(런타임 마이그레이터는 order-service 단독, `order-service/.../application.yml:10`). 처분(**PR3a 완료·#66**): **`docker-health-smoke.sh` 가 앱 컨테이너 실행 전에 compose MySQL 대상으로 공유 스키마(V1~V12)를 적용**한다 — **정본 = 공식 flyway 이미지(`flyway/flyway:11.7.2@digest`)**. ⚠️ 당초 계획한 `./gradlew flywayMigrateShared`(root)는 flyway 플러그인이 mysql DB 플러그인을 미해석해 **깨진 상태** → 폐기·정본 아님(GP-2 loop1 #6 / loop2 #3). root task 는 후속 수복/폐기 부채로만 잔존. (order-service 자신은 런타임 Spring Boot Flyway 자가 마이그레이션이라 무해·별개 경로.)
- [ ] **P3.** `scripts/image-contract-lint.sh` **per-service 재작성**(D-015): 5 서비스 loop — ci 매트릭스 이미지명(`peekcart-<svc>`) ↔ `k8s/base/services/<svc>/deployment.yml` image ↔ `k8s/overlays/gke/kustomization.yml` images[] entry(`<svc>`) 3-way 일치. base/gke per-service 구조는 PR3b 산출 → **PR3a 에서는 lint 가 1서비스(예: notification)만 단언하거나, 구조 미존재 시 graceful**; PR3b 머지 시 5서비스 완성. (ci 머지 순서: 매니페스트 없는 서비스에 lint 가 exit 2 로 깨지지 않게 PR3a/3b 경계에서 lint 기대치 동기.)

### PR3b — k8s base/overlays 서비스별 재구성

- [ ] **P4.** `k8s/base/services/` **5 서비스 분할**: `services/<svc>/{deployment,service,configmap,secret}.yml`(+ `servicemonitor.yml` 은 P9 — GP-2 #1 로 PR3b 편입). deployment 는 모놀리스 `peekcart` 구조 템플릿화(name/labels/selector=`<svc>`, image `ghcr.io/<owner>/peekcart-<svc>`, port 8080, probes `/actuator/health/{liveness,readiness}`, envFrom config/secret per-service, resources 보수적). **cold-start ordering — 확정 메커니즘(Codex GP-2 #5·B4)**: order-service(**런타임 Spring Boot Flyway 마이그레이터 — 정본**, #65 P13; `order-service/.../application.yml:10-16` flyway enabled 확인. ⚠️ root `flywayMigrateShared` gradle 태스크는 깨진 상태이며 k8s init/Job 에 **재사용 금지** — GP-2 #6)는 부팅 시 공유 스키마 마이그레이션 후 ready → **order-service readiness 200 ⟹ 스키마 적용 완료** 신호. **비-order 4 서비스(notification/user/product/payment) `deployment.yml` 에 `initContainers` 추가** — `curlimages/curl`(digest 고정) 가 `http://order-service.peekcart.svc:8080/actuator/health/readiness` 를 200 될 때까지 폴링(`until curl -fsS …; do sleep 2; done`) 후 앱 컨테이너 시작. order-service 자신은 init gate 없음(자가 마이그레이션). 이로써 빈 DB 에서 비-order 서비스의 validate-부팅 실패 0. (② DB 물리분리 시 각 서비스 자가 마이그레이션 → init gate 자연 제거.) `base/kustomization.yml` resources 를 5서비스(각 deployment/service/configmap/secret/servicemonitor)로 갱신.
- [ ] **P5.** **ConfigMap/Secret per-service**: `SPRING_PROFILES_ACTIVE=k8s` + 서비스별 env(DB/redis/kafka 접속은 `application-k8s.yml` 소유 — ADR-0007). 평문 secret 유지(KMS/Vault 는 ③ L-002 범위). **서비스별 secret 키 표(GP-2 #3 — 코드 grep 검증 2026-06-20)**:

  | key | notification | user | product | order | payment | 비고 |
  |---|---|---|---|---|---|---|
  | `DB_USERNAME`/`DB_PASSWORD` | ✅ | ✅ | ✅ | ✅ | ✅ | 공유 DB(② 전 동일 자격) |
  | `JWT_SECRET`(검증) | ✅ | ✅ | ✅ | ✅ | ✅ | common-auth blacklist/verify(ADR-0014) 전 서비스 |
  | `SLACK_WEBHOOK_URL` | ✅필수 | — | ○no-op | ○no-op | ○no-op | **GP-2 #3/loop2 #2/loop3 #1 정정**: `SlackNotificationClient` 는 `@ConditionalOnProperty(name="slack.webhook.url")`(**presence-based·`havingValue` 없음**, `SlackNotificationClient.java:23`). **notification/product/order/payment 4서비스가 SlackPort 를 생성자 주입** → 빈 부재 시 부팅 실패. **⚠️ 근본 원인(loop3 #1)**: 4서비스 base `application.yml` 이 `slack.webhook.url: ${SLACK_WEBHOOK_URL:…placeholder}` **기본값을 둬**(notification:54-56·product:62-64·order:70-72·payment:67-69) → env 누락에도 property 가 항상 존재 → real bean 이 **항상** 등록 → notification fail-fast 불성립·product/order/payment no-op 미선택. **처분(option-b, real↔no-op 상호배타)**: ① 4서비스 base yml 의 `slack.webhook.url` **placeholder 기본값 제거**(Toss 와 동형). ② **notification** k8s 프로파일 `slack.webhook.url: ${SLACK_WEBHOOK_URL}`(no-default) → 설정 시 real bean, **누락 시 placeholder 미해석으로 fail-fast**. ③ **product/order/payment** k8s 프로파일은 `slack.webhook.url` 미설정(property 부재) + `slack.noop-fallback.enabled=true` → real 조건 false → :common no-op `SlackPort`(`@ConditionalOnMissingBean`+`@ConditionalOnProperty("slack.noop-fallback.enabled")`) 등록. `@ConditionalOnMissingBean` 단독 의존 금지(명시 property 로 real/no-op 동시 true 불가 보장). **⚠️ local/test 프로파일 영향**: base 기본값 제거 시 local/test 도 webhook 명시 또는 noop-fallback 켜야 부팅(Toss 와 동일 처분). 검증: notification k8s real client 선택 + webhook 누락 시 부팅 실패 / product·order·payment no-op 부팅 성공 테스트. |
  | `TOSS_SECRET_KEY`/`TOSS_WEBHOOK_SECRET` | — | — | — | — | ✅ 필수 | `TossPaymentClient`/`WebhookService` `@Value` 필수. **GP-2 #3**: base `application.yml:73-74` 에 `${TOSS_SECRET_KEY:test_sk_placeholder}`·`${TOSS_WEBHOOK_SECRET:test_webhook_secret_placeholder}` 기본값 존재 → "stub 금지" 위배. **k8s 프로파일(`payment-service/application-k8s.yml`)에서 기본값 제거**(`${TOSS_SECRET_KEY}`·`${TOSS_WEBHOOK_SECRET}` no-default)로 누락 시 즉시 실패. |

  → **product 전용 캐시 토글 유지(GP-2 #7)**: 현 단일 ConfigMap 의 `PEEKCART_CACHE_ENABLED`(`k8s/base/services/peekcart/configmap.yml:7-10`, product `CacheConfig` 가 `peekcart.cache.enabled` 로 ON/OFF — D-002 격리 재측정 토글)는 **product-service ConfigMap 에만** 이관(타 서비스 제외). D-002(🔄 추적, Order Service 분리 후 재측정)가 살아있어 폐기 아님.
  → secret 미사용 서비스는 해당 키를 secret 에서 제외(매니페스트 주석에 근거 명시).
- [ ] **P6.** `overlays/minikube` **per-service patch 확정(GP-2 #5·B4 — components 아님)**: 기존 단일 `patches/peekcart-{deployment,service}.yml` 구조와 일관되게 **서비스별 strategic-merge patch** 파일 생성 — `overlays/minikube/patches/<svc>-{deployment,service}.yml` ×5(총 10 파일). `kustomization.yml` `patches[]` 를 10 타깃으로 갱신(`name: <svc>`). components 방식은 기각(현 overlay 가 명시적 patch 구조·B4 정확한 파일경로 명시 요구).
- [ ] **P7.** `overlays/gke` **per-service**: 5서비스 deployment/service strategic-merge patch(`patches/<svc>-{deployment,service}.yml` ×10, resources 상향), `images[]` 5 entry(`ghcr.io/<owner>/peekcart-<svc>` → AR `…/peekcart/<svc>`), **HPA order-service만 1개(GP-2 #4 확정·로드맵 §16 정합)** — `hpa.yml` 을 `order-service` 타깃으로(기존 `peekcart` HPA→order-service rename). 타 4서비스 HPA 없음(필요 시 후속). `README.md` AR 경로·image·hpa cmd 갱신(order-service HPA 단수).
- [ ] **P8.** **base/overlays lint 그린 + 전환기 플래그 해제(GP-2 #2)**:
  - **image-contract-lint full 5/5**: PR3a 가 남긴 `.github/workflows/ci.yml` 의 `IMAGE_CONTRACT_TRANSITION=1`(suspended 통과)을 **제거** → PR3b 가 5서비스 매니페스트를 모두 산출하므로 `bash scripts/image-contract-lint.sh` 를 env 없이(full) 실행해 ci==base==gke 5/5 그린. PR3b 그린 조건은 suspended 가 아니라 full 5/5(`scripts/image-contract-lint.sh:163-175` 가 5 매니페스트 완성 시 플래그 제거 강제).
  - `kustomize-namespace-lint`(NS=peekcart 유지, 5서비스).
  - `servicemonitor-selector-lint` **실질 검증** — ⚠️ **P9(5 ServiceMonitor 생성) 를 본 검증보다 먼저 구현**(P8 이 P-id 상 뒤이나 실행 선후 명시, GP-2 loop2 #1). 현 스크립트는 ServiceMonitor 0개여도 vacuous-green(`servicemonitor-selector-lint.sh:79-129` 가 존재하는 monitor 만 순회) → **스크립트에 count==5 + canonical 서비스명 집합 강제 추가**(0/N 이면 위반). 그 후 5 ServiceMonitor↔Service `app=<svc>` 매칭 실질 검증.
  - `kubectl kustomize` 양 overlay 렌더 성공(5 Deployment/Service/ConfigMap/Secret/ServiceMonitor).

- [ ] **P9.** **ServiceMonitor per-service (5) — PR3b 편입(GP-2 #1)**: `services/<svc>/servicemonitor.yml` — selector `app=<svc>`, endpoints port `http` path `/actuator/prometheus`, namespaceSelector peekcart. base `services/peekcart/servicemonitor.yml` 제거. **실행 선후: P9(매니페스트 5) → P8 의 servicemonitor-selector-lint 검증**(GP-2 loop2 #1 — lint 가 vacuous-green 이 아니려면 5 ServiceMonitor 가 먼저 존재해야 함). `servicemonitor-selector-lint`(P8, count==5+name-set 보강) 가 5서비스 ServiceMonitor↔Service 매칭 실질 검증. **매니페스트(소속)만 PR3b** — ADR-0006 ServiceMonitor=`services/<svc>/` 소속 불변식 충족. 이를 소비하는 alert/dashboard/observability-lint 재설계는 P10~P13(PR3c) 잔류.

### PR3c — 관측성 per-service 재설계 + observability lint 재활성 + ADR-0009 개정
- [ ] **P10.** **grafana-alerts.yml per-service**: error-rate/slow-response 를 `sum by(application)(…)` + alert 라벨 `{{ $labels.application }}`(서비스 식별), target-down/scrape-absent 를 5서비스 ServiceMonitor 기준(`namespace="peekcart"` 유지, `service` per-service 또는 `by(service)`). rule uid 체계 per-service 정합. (트레이드오프: by-clause 채택 — rule 폭증 회피.)
- [ ] **P11.** **dashboards per-service**: `api-jvm`·`kafka-lag` 에 Grafana 템플릿 변수 `$application`(datasource label_values) 도입, `application="peekcart"`→`application="$application"`. `pod-resources`(`namespace="peekcart"`)는 5서비스 동일 NS 라 무변(verify). uid/title 정합.
- [ ] **P12.** **신규 ADR-0015(observability per-service contract) 작성 + ADR-0009 Partially Superseded**(GP-1 편입·Codex GP-2 #2): ADR 본문 immutable 원칙(README §8/§14)에 따라 ADR-0009 **본문 직접 수정 금지** — 대신 `docs/adr/0015-observability-per-service-contract.md` 신규 작성: §Decision 의 `application=peekcart` 단일값 불변식 → **per-service `application=<service-name>`** 으로 supersede(무효화 범위 명시 — S2 application 값·S5/S6 ground-truth 소스). SSOT 위치(S2): root `application.yml`(삭제됨) → 서비스별 `<svc>/src/main/resources/application.yml`. alert ground-truth(S5/S6): `services/<svc>/servicemonitor.yml`. + ADR-0009 Status 헤더에 `Partially Superseded by ADR-0015` 표기(상태줄만, 본문 불변) + `docs/adr/README.md` 인덱스 갱신. CLAUDE.md "관측성 계약 SSOT" 줄을 ADR-0015 참조로 보강(또는 ADR-0009 see-also 유지) 확인.
- [ ] **P13.** **observability lint 2종 재설계 + ci.yml 재활성**: `observability-ssot-lint.sh` — SSOT 를 서비스별 app.yml 로(각 모듈 `application` 값 == 자기 service 명, D5-V2 `value=="peekcart"` → per-service 검증). `observability-promql-lint.sh` — ground truth 를 per-service app.yml + `services/<svc>/servicemonitor.yml`. **alert lint 계약은 P10 의 by-clause 채택과 정합(Codex GP-2 #3 — "per-service UID 매트릭스" 가 아님, rule 복제 금지)**: rule UID 별로 (a) PromQL 이 `by(application)`(error-rate/slow-response) 또는 `by(service)`(target-down/scrape-absent) 그룹핑을 갖는지, (b) alert label/annotation 에 `$labels.application`/`$labels.service` 가 존재해 발화 서비스를 식별하는지, (c) 라벨 값이 ground truth 집합(5 서비스명)에 속하는지를 검증. 5×UID 복제 매트릭스로 해석 금지. ci.yml 주석 처리된 2 lint 호출 **복원**. 둘 다 그린.

### 교차 (D-016) — **PR3b 배치(loop3 #2)**

- [ ] **P14.** **D-016 image promotion — PR3b**: gke overlay `images[]` AR rewrite(P7)와 **같은 PR 에서 닫는다**. CI 가 GHCR 푸시 후 digest 산출(PR3a 완료) + **GHCR→AR 승격을 스크립트/문서로 형식화**(`scripts/` 승격 스크립트 + `overlays/gke/README.md` 절차, 수동 트리거 가능). gke `images[]` 가 승격된 AR digest 참조(L-016a digest 고정 합류). 완전 자동 트리거(태그/릴리스 훅)는 **후속 non-blocking**(범위 판단). **PR3b 머지 조건 = 스크립트 dry-run/help 통과 + GHCR digest 입력→AR ref 산출 + README 절차 링크**(§5 검증).

## 4. 영향 파일

**신규**: `Dockerfile`(루트, ARG SERVICE·digest 고정 — **PR3a ✅**) · `k8s/base/services/<svc>/{deployment,service,configmap,secret,servicemonitor}.yml` ×5(servicemonitor 포함 전부 **PR3b** — GP-2 #1; 비-order deployment 는 order-service readiness initContainer 보유) · `k8s/overlays/{minikube,gke}/patches/<svc>-{deployment,service}.yml` ×10씩(per-service patch 확정·GP-2 #5) · **`order-service` 단일 HPA**(`overlays/gke/hpa.yml` 을 order-service 로 rename·GP-2 #4, 5서비스 HPA 아님) · (option b·GP-2 #3/loop3 #1) `common/.../SlackPortNoop` no-op 빈(`@ConditionalOnMissingBean`+`@ConditionalOnProperty("slack.noop-fallback.enabled")` — real bean 과 상호배타) · **`docs/adr/0015-observability-per-service-contract.md`(신규 ADR, ADR-0009 supersede — PR3c)** · (D-016) 승격 스크립트.
**수정**: `.github/workflows/ci.yml`(매트릭스 빌드/스모크/푸시 — **PR3a ✅** / **PR3b: `IMAGE_CONTRACT_TRANSITION` env 제거 → full 5/5**(GP-2 #2) / PR3c: observability lint 2 복원) · ~~`scripts/docker-health-smoke.sh`(flywayMigrateShared 훅)~~ → **PR3a 완료: 공식 flyway 이미지(`flyway:11.7.2@digest`)가 마이그레이션 정본**(root `flywayMigrateShared` 깨짐, GP-2 #6 — k8s init/Job 재사용 금지) · `scripts/image-contract-lint.sh`(per-service — PR3a) · `scripts/observability-ssot-lint.sh`·`scripts/observability-promql-lint.sh`(per-service ground truth·by-clause 계약 — PR3c) · `scripts/servicemonitor-selector-lint.sh`(count==5+name-set 강제 보강·GP-2 loop2 #1) · `k8s/base/kustomization.yml`(5서비스 resources·servicemonitor 포함) · `k8s/overlays/{minikube,gke}/kustomization.yml`(per-service patches·images[]) · `payment-service/src/main/resources/application-k8s.yml`(Toss `${TOSS_*}` 기본값 제거 fail-fast·GP-2 #3) · **4서비스(notification/product/order/payment) `application.yml` 의 `slack.webhook.url` placeholder 기본값 제거 + `application-k8s.yml` slack 설정**(notification=no-default fail-fast / product·order·payment=`slack.noop-fallback.enabled=true`·webhook 미설정 — loop3 #1; local/test 프로파일도 webhook 또는 noop-fallback 명시 필요) · `product-service` ConfigMap(`PEEKCART_CACHE_ENABLED` product 전용 이관·GP-2 #7) · `k8s/overlays/gke/README.md`(order-service HPA 단수) · `k8s/monitoring/shared/grafana-alerts.yml`(by-clause per-service — PR3c) · `k8s/monitoring/shared/{api-jvm,kafka-lag}-dashboard.json`(`$application` 변수 — PR3c) · **`docs/adr/0009-observability-contract-ssot.md`(Status 헤더만 `Partially Superseded by ADR-0015`, 본문 불변 — PR3c)** · `docs/adr/README.md`(인덱스) · CLAUDE.md(관측성 SSOT 줄 ADR-0015 참조 보강).
**삭제**: `k8s/base/services/peekcart/*`(5세트로 분할 후) · ci.yml 단일 `IMAGE_NAME` env.
**불변(verify)**: `scripts/{kustomize-namespace,docker-health-smoke}.sh`(제네릭) · `docker-compose.yml` · 서비스 모듈 `application*.yml`(이미 per-service 태그) · `k8s/monitoring/shared/pod-resources-dashboard.json`(NS 기준) · ADR-0006 NS 불변식.

## 5. 검증 방법

- **PR3a ✅(#66)**: `docker build --build-arg SERVICE=<svc> -t peekcart-<svc>:local .` 5개 성공. 각 이미지 `bash scripts/docker-health-smoke.sh peekcart-<svc>:local` → `/actuator/health` 200(profile k8s, compose 인프라). **smoke 가 앱 실행 전 compose MySQL 에 공유 스키마(V1~V12)를 공식 flyway 이미지(`flyway:11.7.2@digest`)로 선마이그레이션 → 비-order 서비스(validate)도 200**(GP-2 #6 정정: root `flywayMigrateShared` gradle 태스크가 깨져 정본은 flyway Docker 이미지. 런타임 migrator(order-service Spring Boot Flyway)는 정상·별개 경로). ci.yml 매트릭스 5 job 빌드/스모크/푸시. `image-contract-lint`(전환기 SUSPENDED) 그린.
- **PR3b**: `kubectl kustomize k8s/overlays/minikube`·`…/gke` 렌더 성공(5 Deployment/Service/ConfigMap/Secret/**ServiceMonitor**). `bash scripts/kustomize-namespace-lint.sh`(5서비스 전부 peekcart NS) 그린. **`bash scripts/servicemonitor-selector-lint.sh` 실질검증**(count==5+canonical name-set 보강 후 5 ServiceMonitor↔Service `app=<svc>` 매칭 — ServiceMonitor PR3b 편입·0개 vacuous-green 차단, GP-2 #1/loop2 #1). **`IMAGE_CONTRACT_TRANSITION` env 제거 후 `bash scripts/image-contract-lint.sh`(full, 5서비스 ci==base==gke) 그린**(GP-2 #2 — suspended 아닌 full 5/5). **cold-start initContainer 검증**: 빈 DB 클러스터(kind/minikube 1회)에 5서비스 배포 → 비-order 4 서비스 initContainer 가 order-service readiness 200 까지 대기 후 앱 시작, validate-부팅 실패 0(order-service Spring Boot Flyway 선행 보장; ⚠️ 깨진 `flywayMigrateShared` 재사용 금지). 렌더 산출에 비-order deployment `initContainers` 존재 확인. **HPA: `images[]` 5 entry AR rewrite + `hpa.yml` order-service 단일 타깃**(GP-2 #4, 5서비스 HPA 부재 확인). Slack 검증(GP-2 #3/loop2 #2/loop3 #1): base yml placeholder 기본값 제거 후 — notification k8s 는 실 `SlackNotificationClient` 선택 + webhook 누락 시 **fail-fast**(silent 알림 유실 방지), product/order/payment 는 `slack.noop-fallback.enabled=true` no-op 으로 부팅 성공(real bean 미선택 = 상호배타). payment k8s 프로파일 Toss 기본값 부재 시 fail-fast 확인. **D-016(P14)**: 승격 스크립트 dry-run/help 통과 + GHCR digest 입력→AR ref 산출 + `overlays/gke/README.md` 절차 링크.
- **PR3c**: `bash scripts/servicemonitor-selector-lint.sh`(5 ServiceMonitor↔Service 매칭) 그린. `bash scripts/observability-ssot-lint.sh`·`bash scripts/observability-promql-lint.sh` **재활성 후** 그린(per-service ground truth — 각 서비스 app.yml `application=<svc>`, alert by-clause 매트릭스). `kubectl kustomize` 렌더에 5 ServiceMonitor 포함. `grep -rn 'application="peekcart"\|application: peekcart' k8s scripts` → 0. Grafana 대시보드 `$application` 드롭다운 5서비스. ADR-0009 개정 엔트리가 per-service SSOT 명문화 + D5-V2 lint 정합.
- **전체**: ci.yml 그린(빌드/테스트 + lint 5종 전부 활성 + 이미지 매트릭스). 5개 서비스 이미지 GHCR push. `./gradlew build test` 8모듈 그린(무회귀).

## 6. 완료 조건

- 5개 서비스 독립 컨테이너 이미지(단일 Dockerfile+ARG·base digest 고정 L-016a) 빌드/스모크/GHCR 푸시 — CI 매트릭스.
- k8s base/overlays(minikube/gke) 가 서비스별 Deployment/Service/ConfigMap/Secret/ServiceMonitor(5세트), 단일 `peekcart` 매니페스트 소멸. NS=peekcart 불변식 유지(ADR-0006).
- 관측성 per-service: alert(by-clause `application`)·dashboard(`$application` 변수)·ServiceMonitor(5) 재설계, `application=peekcart` 하드코딩 0.
- observability-ssot·promql lint **재활성**(per-service ground truth) + image-contract-lint per-service **full 5/5**(전환기 `IMAGE_CONTRACT_TRANSITION` 제거, GP-2 #2) + servicemonitor-selector-lint 실질검증(5 SM, PR3b) — lint 5종 전부 활성·그린.
- **HPA: order-service 단일**(GP-2 #4·로드맵 §16). 타 4서비스 HPA 부재. ServiceMonitor 매니페스트(5)는 PR3b 편입(ADR-0006 소속 불변식, GP-2 #1).
- 신규 ADR-0015(per-service 관측성 SSOT 명문화) + ADR-0009 `Partially Superseded by ADR-0015`(본문 불변), D5-V2 lint 새 불변식 정합. D-015 이미지 계약 per-service 유지.
- cold-start ordering 확정(비-order 서비스 initContainer order-service readiness gate, 정본=order-service Spring Boot Flyway) + smoke 공유 스키마 선마이그레이션(공식 flyway 이미지·root `flywayMigrateShared` 깨짐·GP-2 #6) — 빈 DB validate-부팅 실패 0.
- D-016 image promotion 형식화(digest 산출 + 승격 절차·스크립트 dry-run 검증) — **PR3b 머지 조건**(gke images[] AR rewrite 동반, loop3 #2). 완전 자동 트리거는 후속 non-blocking. L-016a digest 고정.
- **범위 외 명시**: DB 물리분리(②)·Spring Cloud Gateway(③)·KMS/Vault secret(L-002, ③). cold-start ordering 은 전환기 처분(② DB 분리 시 자연 정리).
