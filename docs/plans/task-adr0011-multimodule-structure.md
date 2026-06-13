# task-adr0011-multimodule-structure — Phase 4 멀티모듈 구조 ADR-0011 작성

> 작성: 2026-06-14
> 관련 Phase: Phase 4 (MSA 분리) — 설계 단계 A2
> 로드맵: `docs/progress/phase4-design-roadmap.md §1 A2`
> 선행: ADR-0010(서비스 분해, 5개 서비스 경계 = SSOT). 본 ADR 은 그 5개를 Gradle 모듈로 어떻게 나누는지 결정
> 후속: 본 ADR 비준 후 구현 ①(Gradle 멀티모듈 전환)에서 실제 디렉토리/빌드 재구성. A3(DB/이벤트 계약)·A4(Gateway)와 병렬
> 관련 ADR: 신규 = **ADR-0011** (Proposed → Accepted). ADR-0010(서비스 경계) 구체화, ADR-0001(4-Layered+DDD) 모듈 내부 구조 유지

## 1. 목표

ADR-0010 이 확정한 5개 서비스(User/Product/Order/Payment/Notification)를 **Gradle 멀티모듈로 어떻게 분리하는지 구조·의존 규칙을 결정**하는 것이 본 task 의 primary 산출물(**ADR-0011**)이다. 현재는 단일 모듈(`settings.gradle: rootProject.name='peekcart'`, 단일 `build.gradle`/`Dockerfile`/이미지)이라, Phase 4 구현 ①(멀티모듈 전환) 착수 전에 모듈 경계·의존 규칙·빌드 산출 계약을 못 박아야 전환이 흔들리지 않는다.

세부 목표:

- **(D1) 모듈 레이아웃 결정** — `common` + **`peekcart-common-observability`(ADR-0009 선결정 — 본 ADR 은 위치·의존 반영만, 내용 재결정 안 함)** + 5개 서비스 모듈의 Gradle 프로젝트 구조(`settings.gradle` include 트리), 루트 build.gradle ↔ 서브모듈 build.gradle 책임 분담
- **(D2) `common` 모듈 경계** — 공유 대상(이벤트 DTO·공통 예외·응답 포맷·global 유틸 중 어디까지) vs 공유 금지(도메인 엔티티·서비스 로직). 현 `com.peekcart.global.*` 의 어느 패키지가 common 으로 가고 어느 것이 각 서비스에 남는지 분류
- **(D3) 의존 규칙** — 서비스 모듈 간 직접 의존 금지(이벤트 기반 통신만), 서비스는 `:common` + `:peekcart-common-observability` 만 공통 의존. ADR-0001 의 "도메인 간 직접 호출 금지"를 모듈 경계로 물리적 강제
- **(D4) 빌드/이미지 산출 계약** — 모듈별 `bootJar`/이미지(서비스 모듈만 bootJar, common 은 plain jar), CI 가 N개 이미지를 빌드/푸시하는 방식. L-016a/D-016 편입
- **(D5) 전환 영향 범위 명시** — 구현 ① 이 다룰 작업의 골격(Dockerfile N개화 또는 파라미터화, CI matrix, k8s deployment N개)을 ADR §Consequences 에 행동 지침으로

본 task 는 **문서만 변경**한다. 실제 `settings.gradle`/`build.gradle` 분리, 패키지 이동, Dockerfile/CI/k8s 재구성은 구현 ①(별도 task).

## 2. 배경 / 제약

### 현 빌드 구조 (감사 — 실제 파일 인용)

- `settings.gradle`: `rootProject.name = 'peekcart'` (단일 모듈, include 없음)
- `build.gradle`: 단일 모듈, `org.springframework.boot 3.5.12`, `group = 'com'`, 단일 `bootJar`(L17) → `app.jar`
- `Dockerfile`: 단일 이미지 — `COPY src/ src/` 전체 복사 → `./gradlew bootJar` → `build/libs/app.jar` (L5-16)
- `.github/workflows/ci.yml`: 단일 이미지 `ghcr.io/${owner}/peekcart`(L14), PR Docker build + `docker-health-smoke.sh`(L76-80), main push 시 `:latest`/`:sha` 푸시(L93-95)
- `k8s/base/services/peekcart/deployment.yml`: 단일 Deployment `ghcr.io/kimgyuilli/peekcart:latest`(L25)
- `k8s/overlays/gke/kustomization.yml`: `images.newName=...peekcart`, **`newTag: latest`**(L35-37) — L-016a digest 고정 부채

### 현 `com.peekcart.global.*` 패키지 (common 후보 감사)

`auth, cache, config, entity, exception, filter, idempotency, jwt, kafka, lock, outbox, port, response, security` — 이 중 무엇이 진짜 공유 가능(common)이고 무엇이 서비스별로 분기/복제되어야 하는지 D2 에서 분류. 특히 `outbox`(Order/Payment 만)·`idempotency`(소비 서비스별)·`security`/`jwt`(User/Gateway)는 단순 common 이 아님.

### 편입 부채

- **L-016a** — gke `images.newTag: latest` → digest(`@sha256:...`) 고정. CI 이미지 운반 자동화(D-016)와 맞물림
- **D-016** — GHCR → Artifact Registry 복사(`crane copy`) + `kustomize edit set image` + `git restore` 수동 절차. 서비스 N개화 시 재현성·감사성 급락 → image promotion 자동화

### ADR 관계

- ADR-0010(서비스 경계) = "무엇을 5개로 나누나"(논리). ADR-0011 = "그 5개를 Gradle 모듈/빌드로 어떻게 물리 분리하나"(구조). ADR-0010 §D1 표가 입력
- ADR-0001(4-Layered+DDD) = 각 서비스 모듈 **내부**는 기존 4레이어 유지. ADR-0011 은 모듈 **간** 경계만 결정 (supersede 아님, 모듈 단위로 확장)
- **ADR-0009(관측성 계약 SSOT) = `peekcart-common-observability` 모듈을 Phase 4용으로 사전 결정**(ADR-0009 L131: "Phase 4 plan 은 이 모듈을 자기 결정 항목에서 제외하고 본 ADR 인용"). S1(`MetricsConfig`)·S3(exposure base yml)·S4(`ActuatorSecurityConfig`)가 이 모듈 소유. **ADR-0011 은 이 모듈을 자기 결정에서 제외하고 ADR-0009 를 인용**하며, 모듈 레이아웃·common 분류·의존 규칙에 반영(refine, supersede 아님)
- ADR-0004/0005(GKE/Kustomize) = 이미지 운반·overlay 패턴. D-016/L-016a 가 여기 맞물림

### 비대상

- 실제 모듈 분리/패키지 이동/빌드 파일 재작성 — 구현 ①
- A3(이벤트 스키마·DB 분리)·A4(Gateway) 의 결정
- 멀티레포 전환 — 모노레포(Gradle 멀티모듈) 채택은 `docs/02-architecture.md §4-4`(Layer 1) 확정, 본 ADR 은 그 전제 유지

## 3. 작업 항목

### Part A — 사전 감사

- [ ] **P1.** 현 빌드 구조 재확인 — `settings.gradle`/`build.gradle`(bootJar/jar 블록)/`Dockerfile`/`ci.yml`(image job)/`k8s` image 참조를 실제 라인 인용으로 ADR §Context 에 기록
- [ ] **P2.** `com.peekcart.global.*` 분류 감사 — **패키지 단위가 아니라 class/role 단위**로 (a) common 공유 (b) 서비스 전속 (c) 서비스별 복제 (d) A3/A4 위임 분류. 패키지 안에 서비스 전속과 순수 공유가 섞인 케이스를 class-level 로 쪼갬: `config.KafkaConfig`(토픽 빈 직접 정의 — `KafkaConfig.java:31`, 발행 서비스 전속/복제), `config.SecurityConfig`(공개 URL+JWT 필터 결합 — `SecurityConfig.java:37`; **actuator 허용 부분(S4)은 `peekcart-common-observability` 의 `ActuatorSecurityConfig` 로 분리 — ADR-0009 L48**, 비즈니스 인증은 User/A4), `config.MetricsConfig`·exposure base yml(S1/S3 → **`peekcart-common-observability` — ADR-0009 L45/47**), `auth`/`jwt`/`security`(User/A4), `outbox`(Order/Payment), `idempotency`(소비 서비스별), `port`(연동별), `response`/`exception`(순수 공유 후보). 최소 `config·auth·kafka·outbox·idempotency·security·port` 는 class-level 확정. **ADR-0009 가 owner 를 이미 정한 관측성 surface(S1~S4,S7,S8)는 그 결정을 인용만 하고 재결정하지 않음**
- [ ] **P3.** 이벤트 DTO 공유 경계 확인 — 현 `global.outbox.dto`(OrderCreatedPayload 등, `OrderCreatedPayload.java:5` 필드 구체화됨)가 발행/소비 양쪽에서 쓰이므로 common 후보. "이벤트 계약 = common" vs "각 서비스 자기 발행 DTO 소유 + 소비측 복제"의 트레이드오프 정리. **⚠️ 금지선**: A2 는 이벤트 DTO 의 **모듈 소유/공유 방식만** 결정하고, 클래스명·필드·필수/선택·버전·파티션 키·retention 은 **A3 전까지 non-authoritative**(권위 없음)로 둔다 — ADR-0010 §D2 가 스키마를 A3 로 위임했으므로 A2 가 스키마를 비준하지 않는다

### Part B — Decision 후보 비교 + 확정

- [ ] **P4.** Alternatives 비교 — 모듈 분할 단위 후보를 동일축(빌드 경계 명확성, common 비대화 위험, 서비스 독립성, 전환 비용)으로 비교:
  - Alt A: `common` + 5개 서비스 모듈 (서비스=배포 단위=모듈)
  - Alt B: `common` + `domain-common`(공유 VO) + 5개 서비스 — common 2계층
  - Alt C: 기능별 라이브러리 모듈 다수 (event-contract, security-lib, web-common ...) + 서비스
  - **채택 권고 = Alt A** (단순·배포 단위 일치). 기각 사유 대칭 기술
- [ ] **P5.** 모듈 레이아웃 표(D1) — `settings.gradle` include 트리 + 각 모듈의 build.gradle 책임(루트=공통 plugin/version 관리, 서비스=bootJar, common/observability=plain jar). **`peekcart-common-observability` 를 별도 모듈로 포함**(ADR-0009 선결정, A2 는 위치만 확인·인용하고 내용 재결정 안 함). 모듈 = `common` + `peekcart-common-observability` + 5개 서비스. 디렉토리 구조 예시
- [ ] **P6.** `common` 경계 표(D2) — P2 의 class/role 분류 결과를 **"common 포함 / `peekcart-common-observability` / 서비스 전속 / 서비스별 복제 / A3·A4 위임" 5열**로 확정. 패키지 통째가 아니라 혼재 패키지(config/kafka/security 등)는 class-level 행으로. 관측성 surface(S1~S4,S7,S8)는 ADR-0009 owner 컬럼을 인용. "TBD" 금지. 이벤트 DTO 는 "소유/공유 방식"만(스키마는 A3, P3 금지선)
- [ ] **P7.** 의존 규칙(D3) — 서비스 모듈 → **`common` + `peekcart-common-observability` 만** `implementation`(후자는 ADR-0009 선결정 모듈), 서비스 ↔ 서비스 직접 의존 **금지**(컴파일 차원 강제). 이벤트 계약 공유 방식(P3 결론)을 규칙으로. **위반 검출은 필수 계약**: ① 서비스 모듈 build.gradle 에 `project(':common')`·`project(':peekcart-common-observability')` 외 project 의존 금지 ② CI 에서 검증 task(예: `assertNoServiceProjectDeps` Gradle task)로 위반 시 **빌드 실패** ③ (선택) ArchUnit 패키지 의존 테스트. ADR §Decision 에 검출 방식을 명문화 (실제 task 구현은 ①)
- [ ] **P8.** 빌드/**테스트**/이미지 산출 계약(D4) — (빌드) 서비스 모듈별 `bootJar` → 이미지 N개, common 은 plain jar. (테스트) 공용 test support/fixture 를 `common` 의 `java-test-fixtures` 로 둘지 + 서비스별 `testImplementation(testFixtures(project(':common')))` 허용 여부, Testcontainers 통합 테스트를 어느 모듈에서 실행할지, CI artifact path 를 `**/build/reports/` 로 일반화. (이미지) CI image job matrix 화 골격 + L-016a(digest 고정)·D-016(image promotion 자동화)를 §Consequences 행동 지침으로. **Docker health smoke 계약 유지**: 현 CI `Smoke Docker image (PR)`(`ci.yml:78-80`, `scripts/docker-health-smoke.sh` — `/actuator/health` 200 대기)를 서비스별 이미지 matrix 에서 유지할 대상, 필요한 infra(MySQL/Redis/Kafka) 조합, 포트 충돌 회피, 실패 시 빌드 실패를 명시 (실제 구현은 ①)

### Part C — ADR-0011 작성

- [ ] **P9.** `docs/adr/0011-phase4-multimodule-structure.md` 신규 — `docs/adr/template.md` / ADR-0010 형식 준수
  - **Status**: 초안 `Proposed`
  - **Decided**: 2026-06-14 / **관련 Phase**: Phase 4
  - **Context**: 현 단일 모듈 구조(P1) + global 패키지 분류(P2) + 이벤트 DTO 경계(P3) + ADR-0010(서비스 경계)·ADR-0009(관측성 모듈 선결정) 입력
  - **Decision**: 모듈 레이아웃(P5, `common`+`peekcart-common-observability`+5서비스) + common 경계 class-level(P6) + 의존 규칙·**위반 검출 필수**(P7) + 빌드/테스트/이미지 계약(P8). **이벤트 DTO 는 모듈 소유/공유만 결정, 스키마는 A3 위임(non-authoritative)** 명시. **`peekcart-common-observability` 는 ADR-0009 선결정으로 본 ADR 결정에서 제외(인용만)**
  - **Alternatives Considered**: P4 의 Alt A/B/C
  - **Consequences**: 긍정(구현 ① SSOT, 모듈 경계로 직접 호출 물리 차단) / 부정(common 비대화 위험, 빌드 복잡도, 이미지 N개 운영) / 후속(구현 ①, A3 이벤트 계약, L-016a/D-016)
  - **References**: ADR-0001, ADR-0002(Phase 4 에 Gradle 멀티모듈 포함), `docs/02-architecture.md §4-4`(모노레포 — Layer 1), ADR-0009(`peekcart-common-observability` 모듈 선결정), ADR-0010, 로드맵, 빌드 파일 인용
- [ ] **P10.** ADR Status 전환 — 본문 작성 후 `Proposed` → `Accepted` (ADR-0010 패턴)

### Part D — 인덱스/참조 동기화

- [ ] **P11.** `docs/adr/README.md` INDEX 표에 ADR-0011 행 추가
- [ ] **P12.** `docs/02-architecture.md` §4-4(모노레포)·§12(패키지 구조) 영향 검토 — 멀티모듈 구조를 §12 에 반영할지, 아니면 `(see ADR-0011)` 참조로 둘지 결정 후 최소 반영 (구조 상세는 ADR 본문, Layer 1 은 참조)
- [ ] **P13.** `bash docs/consistency-hints.sh` exit 0 확인

### Part E — 문서 동기화

- [ ] **P14.** `docs/progress/phase4-design-roadmap.md §1 A2` 상태 갱신(비준 완료) + `docs/TASKS.md` A2 행 ✅ + `docs/progress/PHASE4.md` A2 엔트리 추가

## 4. 영향 파일

| 파일 | 변경 유형 | Part |
|------|-----------|------|
| `docs/adr/0011-phase4-multimodule-structure.md` | 신규 (Proposed → Accepted) | C (P9, P10) |
| `docs/adr/README.md` | INDEX 행 추가 | D (P11) |
| `docs/02-architecture.md` | §4-4/§12 최소 반영 + `(see ADR-0011)` | D (P12) |
| `docs/progress/phase4-design-roadmap.md` | A2 상태 갱신 | E (P14) |
| `docs/TASKS.md` | A2 행 ✅ | E (P14) |
| `docs/progress/PHASE4.md` | A2 엔트리 | E (P14) |

코드/빌드 파일 변경: **0건** (`settings.gradle`/`build.gradle`/`Dockerfile`/`ci.yml`/`k8s` 모두 구현 ① 범위).

## 5. 검증 방법

### 자동
- `bash docs/consistency-hints.sh` exit 0
- `./gradlew test` 불필요 (코드 변경 0건)

### 수동 (ADR 본문 품질 — 모두 통과해야 Accepted)

**§Context:**
- [ ] **C1**: 현 빌드 구조가 실제 파일:라인 인용으로 기록됨 (P1)
- [ ] **C2**: global 14개 패키지 분류가 근거와 함께 표로 (P2)

**§Decision:**
- [ ] **D1**: 모듈 레이아웃 표 — settings include 트리(`common`+`peekcart-common-observability`+5서비스) + 모듈별 build.gradle 책임 명시
- [ ] **D2**: common 경계 표 **5열(포함/`peekcart-common-observability`/서비스 전속/복제/A3·A4 위임)** 전부 채워짐, 혼재 패키지(config/kafka/security)는 class-level 행, "TBD" 금지
- [ ] **D3**: 의존 규칙 — 서비스↔서비스 직접 의존 금지 명문화, 서비스는 `:common`+`:peekcart-common-observability` 만 허용 + **위반 검출 필수 계약**(build.gradle project 의존 제한 + CI 검증 task → 빌드 실패)
- [ ] **D4**: 빌드/테스트/이미지 계약 — 이미지 N개화 + Docker health smoke 서비스별 유지 + testFixtures/Testcontainers 모듈 위치 + CI artifact path + L-016a/D-016 행동 지침이 구현 ① 으로 1:1 도출 가능. **이벤트 DTO 스키마는 A3 위임(non-authoritative) 금지선 명시**
- [ ] **D5**: ADR-0001(모듈 내부 4레이어 유지)·ADR-0002(Phase 4 Gradle 멀티모듈)·**ADR-0009(`peekcart-common-observability` 선결정 — 본 ADR 이 인용·제외)**·ADR-0010(5개 경계)와 모순 없음. 모노레포 참조는 `02-architecture §4-4`

**§Alternatives:**
- [ ] **A1**: Alt A/B/C 동일 비교축, 채택(Alt A)/기각 사유 대칭

**§Consequences:**
- [ ] **CQ1**: 구현 ①(멀티모듈 전환)의 작업이 본 ADR 만 보고 도출 가능 (settings/build 분리, 패키지 이동, Dockerfile/CI/k8s N개화)
- [ ] **CQ2**: common 비대화·이미지 N개 운영 등 부정 영향이 구체적

## 6. 완료 조건

- [ ] P1 ~ P14 전부 체크
- [ ] ADR-0011 파일 존재 + Status: Accepted
- [ ] `bash docs/consistency-hints.sh` exit 0
- [ ] §5 수동 체크리스트 (C1~C2, D1~D5, A1, CQ1~CQ2) 전부 통과
- [ ] 의존 규칙 위반 검출이 "필수 계약"으로 명문화 (구현 ① 에서 위반 샘플이 빌드 실패로 검출되도록 ADR 가 요구)
- [ ] 이벤트 DTO 스키마 A3 위임(non-authoritative) 금지선이 ADR 에 명시
- [ ] `peekcart-common-observability`(ADR-0009 선결정)가 모듈 레이아웃·의존 규칙에 반영되고 ADR-0009 인용 (본 ADR 재결정 아님)
- [ ] Docker health smoke 계약이 이미지 N개화에 반영
- [ ] `02-architecture.md` §4-4/§12 에 ADR-0011 참조 존재
- [ ] roadmap·TASKS·PHASE4 동기화
- [ ] PR 생성 + 머지

## 7. 트레이드오프 및 결정 근거

| 결정 | 채택 (계획 시점) | 기각 대안 | 근거 |
|------|------|-----------|------|
| 모듈 분할 단위 | **Alt A — common + 5개 서비스 모듈** (+ ADR-0009 선결정 `peekcart-common-observability`) | Alt B(common 2계층), Alt C(기능별 라이브러리 다수) | 모듈=배포 단위=서비스 로 단순. common 비대화는 D2 경계로 통제. 관측성 모듈은 ADR-0009 가 이미 결정(본 ADR 인용·제외). Alt C 는 Phase 4 초기 과설계 |
| common 범위 | 이벤트 계약·응답·예외 등 **순수 공유만** | global 전체를 common 으로 | outbox/idempotency/security 는 서비스 분기 대상 → 통째 이동 시 결합도↑ |
| 산출물 범위 | 구조·의존·빌드 계약 골격만 (A2) | 실제 전환까지 한 task | 전환은 대규모 코드 이동 → 구현 ① 분리, 리스크↓ |
| ADR 관계 | "ADR-0010 구체화 / ADR-0001 모듈 확장" | Supersede | ADR-0010/0001 유효, 본 ADR 은 물리 모듈 경계 추가 |

## 8. 후속 (Out-of-Scope)

- 구현 ① — Gradle 멀티모듈 전환(settings/build 분리, 패키지 이동, Dockerfile/CI matrix/k8s N개). L-016a/D-016 실제 적용
- A3 — DB-per-service + 이벤트/Saga 계약 (이벤트 DTO 스키마 확정)
- A4 — Gateway 보안
