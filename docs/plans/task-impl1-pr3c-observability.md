# task-impl1-pr3c-observability — PR3c 관측성 per-service 재설계 + ADR-0015

> 구현 ① PR3(배포 표면 per-service 재구성)의 **마지막 조각**. PR3a(이미지/CI #66)·PR3b(k8s 매니페스트 #67)에 이어, 단일 `peekcart` 식별자를 전제하던 **관측성 표면**(alert/dashboard/observability lint)을 per-service 로 재설계하고, 그 계약을 ADR-0015 로 명문화한다.
> 모(母)계획 `task-impl1-pr3-dockerfile-ci-k8s.md` 의 PR3c scope(P10~P13)를 현재 코드(PR3a/3b 머지 후 상태)로 재검증해 분리·승계한 포커스 계획서.
> 선행 ADR: ADR-0006(monitoring 분리)·ADR-0009(관측성 SSOT, 본 PR 에서 Partially Superseded)·ADR-0010/0011(5서비스). **신규 ADR-0015 작성**(ADR 본문 immutable 원칙, README §8/§14).

## 1. 목표

단일 `application=peekcart` 식별자를 전제하던 관측성 계약을 **per-service `application=<svc>-service`** 로 전환하고, 비활성화된 observability lint 2종을 per-service ground truth 로 재작성·재활성한다.

**성공 기준**:
1. **ADR-0015 §Decision** 이 per-service 관측성 SSOT 를 명문화하고, **ADR-0009 Status 가 `Partially Superseded by ADR-0015`(본문 불변, Status 헤더만)** 로 변경되며 `docs/adr/README.md` 인덱스·CLAUDE.md SSOT 줄이 정합.
2. `grafana-alerts.yml` 4 alert 가 `application="peekcart"`/`service="peekcart"` 고정값을 제거하고 per-service(`by (application)` 또는 per-service label)로 동작 — 5서비스 각각에 대해 alert 가 평가됨.
3. Grafana 대시보드(api-jvm·kafka-lag)가 `$application` 템플릿 변수로 5서비스 선택 가능.
4. `scripts/observability-ssot-lint.sh`·`scripts/observability-promql-lint.sh` 가 **per-service ground truth**(5서비스 yml + 5 servicemonitor)로 재작성되어 `ci.yml` 에서 **재활성**되고 그린.
5. 검증 sweep: 관측성 파일에 `application=peekcart`(escaped quote 포함) 고정 잔존 0 — 전부 per-service 값 / `by`-clause / 템플릿 변수로만 존재.

## 2. 배경 / 제약

### 현재 코드 (grep 검증 완료, 2026-06-20)

PR3a(#66)·PR3b(#67) 머지 후 상태. 모계획의 PR3a/3b 항목은 전부 완료됐고, 본 계획은 **잔존 PR3c scope 만** 다룬다.

- **per-service 메트릭 태그 이미 존재**: 5서비스 각 `<svc>-service/src/main/resources/application.yml` 에 `management.metrics.tags.application: <svc>-service` (예: `notification-service`, `order-service` — full name). root `src/main/resources/application.yml` 은 Payment peel(#65)로 **삭제됨**.
- **MetricsConfig 단일 owner 이동**: `peekcart-common-observability/src/main/java/com/peekcart/global/config/MetricsConfig.java` (이전 root `global/config` → 공유 모듈). 서비스별 복제 없음. (test 의 `*ObservabilityMetricsIntegrationTest.java` 3건은 owner 아님.)
- **5 ServiceMonitor 존재**(PR3b): `k8s/base/services/<svc>-service/servicemonitor.yml` — 각 `selector.matchLabels.app: <svc>-service`, `metadata.namespace: peekcart`, endpoint port `http` path `/actuator/prometheus`. ⚠️ 경로 접미사는 `<svc>-service`(예: `notification-service`), 단순 `<svc>` 아님.
- **alert 4개 모두 단일값 하드코딩**: `k8s/monitoring/shared/grafana-alerts.yml`
  - `peekcart-high-error-rate`(uid) → `http_server_requests_seconds_count{application="peekcart", status=~"5.."}` / 분모 `{application="peekcart"}`
  - `peekcart-slow-response` → `histogram_quantile(0.95, …{application="peekcart", uri!~"/actuator.*"}… by (le))`
  - `peekcart-target-down` → `count(up{namespace="peekcart", service="peekcart"} == 0) or on() vector(0)`
  - `peekcart-scrape-absent` → `absent(up{namespace="peekcart", service="peekcart"}) or on() vector(0)`
- **대시보드 단일값 하드코딩**: `api-jvm-dashboard.json`(`application="peekcart"` 12회·`$application` 변수 없음), `kafka-lag-dashboard.json`(`templating` 1회 — 빈 변수 블록 추정, application 변수 없음). `pod-resources-dashboard.json` 은 application 참조 확인 필요(pod/namespace 기반이면 대상 외).
- **observability lint 2종 비활성**: `.github/workflows/ci.yml:44-49` 에서 두 lint 가 주석 처리됨. 사유 주석: "root `src/main/resources/application.yml` 을 SSOT 로 가정하던 lint 가 root app 해체로 깨짐 → per-service 재설계는 PR3 에서". (활성 lint 3종: kustomize-namespace / image-contract / servicemonitor-selector.)
  - `observability-ssot-lint.sh`: `BASE="src/main/resources/application.yml"`(삭제됨)·`EXPECTED_APPLICATION_TAG="peekcart"`·Java owner `EXPECTED_OWNER="src/main/java/com/peekcart/global/config/MetricsConfig.java"`(이동됨) 하드코딩.
  - `observability-promql-lint.sh`: `APP_YAML="src/main/resources/application.yml"`(삭제됨)·`SM_YAML="k8s/base/services/peekcart/servicemonitor.yml"`(삭제됨, per-service 로 분할)·alert uid matrix `peekcart-*` 하드코딩.
- **ADR-0009 Status**: 현재 `Accepted` (헤더 line 3). CLAUDE.md line 140 `관측성 계약 SSOT … ADR-0009 §Decision 표 … (see ADR-0009)`.

### B1 — 역의존 스윕 (단일 `peekcart` 관측성 식별자의 인바운드 간선)

> PR3c 는 모듈/코드 이동(peel/rename)이 **아니다**(GP-1 구조변경 미발화 — alert/dashboard/lint/ADR = 설정·문서). 따라서 PLAN-BLINDSPOTS B1 전체 역의존 스윕은 강제 대상 아님. 단 "단일 `peekcart` 관측성 식별자"의 인바운드 소비자는 코드로 확인해 처분한다.

| # | 인바운드 참조(소비자) | 종류 | 처분 |
|---|---|---|---|
| 1 | `grafana-alerts.yml` 4 alert 의 `application/service/namespace="peekcart"` | 매니페스트 | **재작성**(P2) — application/service per-service, namespace="peekcart"는 유지(5서비스 공유 NS, ADR-0006) |
| 2 | `api-jvm/kafka-lag-dashboard.json` 의 `application="peekcart"` panel query | 대시보드 | **템플릿 변수화**(P3) — `$application` |
| 3 | `observability-ssot-lint.sh`(BASE·EXPECTED_TAG·EXPECTED_OWNER) | lint | **재작성**(P4) — 5서비스 yml ground truth, owner=common-observability |
| 4 | `observability-promql-lint.sh`(APP_YAML·SM_YAML·uid matrix) | lint | **재작성**(P5) — 5서비스 yml + 5 servicemonitor ground truth |
| 5 | `ci.yml` 주석 처리된 2 lint 호출 | CI | **주석 해제 재활성**(P6) |
| 6 | ADR-0009 §Decision `application=peekcart` 단일값 불변식 + D5-V2 | ADR/계약 | **ADR-0015 supersede**(P1) — per-service `application=<svc>-service` |

### B2 — escaped-quote false-green 함정 (신규 발견, PLAN-BLINDSPOTS 후보)

모계획의 PR3c 검증은 `grep -rn 'application="peekcart"\|application: peekcart' k8s scripts → 0` 을 제시한다. 그러나 `grafana-alerts.yml`·대시보드 JSON 은 `application=\"peekcart\"`(**escaped quote**)로 저장돼 있어 literal `application="peekcart"` 패턴은 **매치하지 않는다** → 미수정 상태에서도 grep 이 0(false-green). 실제로 grep A(`application="peekcart"`)는 이미 0 을 반환했으나 alert/dashboard 에는 12+회 잔존. **검증 sweep 은 escaped/unescaped 양쪽**(`application=\\?"peekcart"`)을 매치해야 한다(§5 P-검증에 반영).

### 트레이드오프

- **alert per-service 방식 — `by (application)` grouping vs per-service rule 복제**: `by (application)` 단일 rule(라벨 보존, 5서비스 자동 평가, 신규 서비스 자동 포함) 채택. 복제 5 rule 은 신규 서비스마다 rule 추가 필요 + lint 부담. 단 target-down/scrape-absent 는 `up{job=...}` 부재 시 평가이므로 `by`-clause 의미가 다름 → per-service 처리 방식을 Codex 리뷰 항목으로 노출(아래 R1).
- **R1 — target-down/scrape-absent 의 per-service 표현(Codex 1차 #1·2차 #2 반영 확정)**: target-down 은 `count by (service)(up{namespace="peekcart"} == 0)` 로 서비스별 평가. scrape-absent 는 `absent()` 에 by-clause 적용 불가하므로 **5서비스 equality matcher rule 분할**(`absent(up{...,service="<service-name>"})` ×5, expected-service set = **SM 가 매칭하는 K8s Service 의 `metadata.name` 집합** — selector app 값 아님). 정합 라벨(`service` vs `job`)은 ServiceMonitor 가 생성하는 실제 Prometheus 라벨에 의존 → 구현 시 렌더 확인 필요(kube-prometheus-stack 관례상 `service`=Service `metadata.name`). kube-state-metrics expected-service 벡터 join 은 클러스터 의존(정적 검증 불가)이라 기각.
- **단일값 불변식 폐기**: ADR-0009 D5-V2 의 `application='peekcart'` 단일값 불변식은 ADR-0015 로 **per-service 다중값**(각 서비스 = 자기 이름)으로 대체. lint 도 "단일값" → "서비스별 자기이름 일치"로 의미 전환.
- **DB/인프라 변경 없음**: monitoring 스택(ADR-0006)·NS(peekcart 공유)·ServiceMonitor 매니페스트(PR3b 완료)는 그대로. 본 PR 은 alert/dashboard 쿼리 + lint + ADR 만.

## 3. 작업 항목

> 실행 순서 권장: P1(계약 정의=ground truth) → P2/P3(소비자: alert/dashboard) → P4/P5(lint 재작성) → P6(재활성+sweep). lint 가 alert/yml 을 검증하므로 P2~P5 후 P6 에서 함께 그린.

- [ ] **P1.** **ADR-0015 작성 + ADR-0009 Partially Superseded**(Codex #2 — 범위 정정): ADR 본문 immutable 원칙(README §8/§14)에 따라 ADR-0009 본문 직접 수정 금지 — `docs/adr/0015-observability-per-service-contract.md` 신규(template 복사). **⚠️ 범위 주의(코드 재검증)**: ADR-0009 §Decision 표는 **이미 per-service 를 결정**해 둠 — S2="각 서비스 모듈 `application.yml`, 값=서비스 자기 이름", S5="각 서비스 own `services/<service-name>/servicemonitor.yml`", S6="shared/ 1파일, cross-service 는 `application=~"<svc1>|<svc2>"` 정규식", D5-V6="PromQL syntax + 입력 series 가정 lint". 따라서 ADR-0015 의 역할은 **"§Decision 결정을 뒤집기"가 아니라 "(a) 모놀리스 현-위치 컬럼(root `application.yml`·`base/services/peekcart`)을 5서비스 분리 완료 상태로 정정, (b) D5-V1/V2 의 단일 root yml 전제·`EXPECTED_APPLICATION_TAG="peekcart"` 회귀검증을 per-service ground truth 로 재정의, (c) D5-V6 alert lint 를 본 PR 에서 실제 구현(by-clause coverage + PromQL syntax)으로 격상"** 으로 좁힌다(원래 §Decision 의 per-service 의도를 실현·비준). §Decision 표(S1/S2/S5/S6): S1 owner=`peekcart-common-observability/.../MetricsConfig.java`(현 위치 확정), S2 값=`<svc>-service`(full name) ground truth=5서비스 `application.yml`, S5 ground truth=`services/<svc>-service/servicemonitor.yml`(접미사 정정), S6 cross-service alert=by-clause/regex 5서비스 coverage. + ADR-0009 **Status 헤더만** `Partially Superseded by ADR-0015` + Status 줄 아래 **무효화 범위 명시**(§Context 현-위치 서술의 root `application.yml`·`base/services/peekcart` 경로·테스트 `application=peekcart`, §Decision 표 "현 SSOT(파일:라인)" 컬럼의 모놀리스 경로, D5-V1/V2 의 단일 root yml 전제, S5 단일 servicemonitor 경로 — **§Decision 의 per-service owner 컬럼 자체는 유효, 무효화 아님**)(README §부분무효화 규약). + `docs/adr/README.md` INDEX Status 갱신 + ADR-0015 행 추가. + CLAUDE.md "관측성 계약 SSOT" 줄에 `(see ADR-0009, ADR-0015)` 보강.
- [ ] **P2.** **grafana-alerts.yml per-service 재작성**(Codex #1/#4 — by-clause + scrape-absent 5 rule): 4 alert expr 에서 단일값 제거 —
  - `peekcart-high-error-rate`/`peekcart-slow-response`: `application="peekcart"` 단일 필터 제거 + **5서비스 정확일치 regex `application=~"notification-service|order-service|payment-service|product-service|user-service"`** 필터 + `by (application)` grouping(분자/분모 동일 grouping 으로 ratio 보존, `slow-response` 는 `by (application, le)`). **무필터 + `by (application)` 단독 금지**(Codex 2차 #1 — PeakCart 외 application 라벨까지 alert 대상 됨, ground-truth 1:1 아님). **단일 서비스 equality(`application="order-service"`) 금지**. regex 값 집합은 5서비스 `application.yml` ground truth 와 정확히 일치(P5 lint 강제, ADR-0009:50 정합).
  - `peekcart-target-down`: `service="peekcart"` 제거(namespace="peekcart" 유지) + `count by (service)(up{namespace="peekcart"} == 0)` 로 서비스별 평가(R1 — `service` 라벨이 ServiceMonitor 가 만드는 실제 Prometheus 라벨인지 렌더 확인 후 `service` vs `job` 확정).
  - `peekcart-scrape-absent`: **`absent()` 는 by-clause 적용 불가**(Codex 1차 #1) → 단일 rule 폐기, **5서비스 equality matcher rule 로 분할** — 각 `absent(up{namespace="peekcart", service="<service-name>"})`(uid `scrape-absent-<svc>`). **expected-service set ground truth = SM 가 매칭하는 K8s Service 의 `metadata.name` 집합**(Codex 2차 #2 — `up{service=...}` 의 `service` 라벨은 SM selector app 값이 아니라 scrape 대상 Service 이름 의미). P5 lint 가 alert equality matcher 집합 == servicemonitor-selector-lint 가 산출한 매칭 Service-name 집합 1:1 강제. (대안 kube-state-metrics join 은 클러스터 의존 → 정적 검증 불가하므로 equality 5 rule 채택.)
  - uid 명명 규약은 P5 lint matrix 와 동기. alert summary/annotation 에 `{{ $labels.application }}`/`{{ $labels.service }}` 로 서비스 식별 추가.
- [ ] **P3.** **대시보드 `$application` 템플릿 변수**: `api-jvm-dashboard.json`·`kafka-lag-dashboard.json` 에 `templating.list` 로 `application` 변수 추가(`label_values(application)` 쿼리, datasource=prometheus, multi/includeAll 정책 결정). 모든 panel query 의 `application="peekcart"` → `application=~"$application"`(또는 `="$application"`). `pod-resources-dashboard.json` 은 application 참조 여부 확인 후 대상 시 동일 적용, 아니면 대상 외 명시.
- [ ] **P4.** **observability-ssot-lint.sh per-service 재작성**: `BASE` 단일 → 5서비스 yml loop(`<svc>-service/src/main/resources/application.yml`). D5-V1(SSOT key 재선언 검사)는 각 서비스 base yml vs 자기 프로파일(`application-*.yml`)로 per-service 적용. D5-V2 application 태그 값: 단일 `EXPECTED_APPLICATION_TAG="peekcart"` → **서비스별 기대값 = 디렉터리명**(`<svc>-service`, 자기 이름 일치 검증). Java owner: `EXPECTED_OWNER` → `peekcart-common-observability/src/main/java/com/peekcart/global/config/MetricsConfig.java`, 후보 grep 범위 `src/main/java` → 전체 모듈(`*/src/main/java`). 화이트리스트/주석 ADR 참조를 ADR-0015 로 보강.
- [ ] **P5.** **observability-promql-lint.sh per-service 재작성**(Codex #3/#4 — coverage 강제 + PromQL syntax): ground truth — `APP_YAML` 단일 → 5서비스 yml(application 태그 집합 `{notification-service, …, payment-service}`), `SM_YAML` 단일 → 5 servicemonitor(`services/<svc>-service/servicemonitor.yml`)에서 namespace/service 집합. MATRIX(alert uid → 필수 라벨·coverage):
  - high-error-rate/slow-response: **5서비스 정확일치 regex `application=~"notification-service|order-service|payment-service|product-service|user-service"` + `by (application)` 둘 다 강제**(Codex 2차 #1). regex 값 집합 = 5서비스 `application.yml` 의 `management.metrics.tags.application` 집합과 **정확히 일치**(부족/초과 시 실패, ground truth 에서 생성). **무필터 + `by (application)` 단독은 실패**(PeakCart 외 앱 라벨 유입 차단), **단일 서비스 equality(`application="order-service"`)도 실패**(Codex 1차 #4 — false-green).
  - target-down: `namespace` 필터 + `by (service)` coverage.
  - scrape-absent: **alert equality matcher 집합이 매칭 Service `metadata.name` 집합과 1:1**(각 `absent(up{...,service="<service-name>"})`, 5개 정확히 — 누락/초과 시 실패). ground truth = `servicemonitor-selector-lint` 가 산출하는 SM↔Service 매칭의 Service `metadata.name` 집합(Codex 2차 #2 — SM selector app 값 아님). uid 명명(P2 와 동기).
  - **PromQL syntax 검증 추가**(Codex 1차 #3 — D5-V6 "syntax + 입력 series 가정" 충족): `grafana-alerts.yml` 의 inner `alerts.yaml` 을 추출해 refId 별 expr 를 **실제 PromQL parser 로 파싱** — 정본 = `promtool check rules`(임시 rule 파일로 변환) 또는 동급 PromQL parser. **promtool 은 P6 CI 에서 필수 설치**(선택 아님, Codex 2차 #3). **parser 실패 시 lint exit ≠0 으로 중단**. 괄호/함수 balance 정적 검사는 **보조 진단으로만** 두고 syntax 통과로 인정하지 않는다(parser 부재를 balance 검사로 대체 금지 — false-green 재발 방지). **Grafana `__expr__`(threshold/reduce expression)까지 완전 평가 불가 한계는 검증 섹션에 분리 명시.**
  - expr 단위(refId) 검증 유지(rule-level 합산 false-negative 금지 — 기존 주석 보존).
- [ ] **P6.** **ci.yml lint 재활성 + sweep 가드**: `ci.yml:48-49` 두 lint 주석 해제(`observability-ssot-lint.sh`·`observability-promql-lint.sh` 호출 복원), 비활성 사유 주석 제거/갱신. pyyaml 설치 step 이 두 lint preflight(`import yaml`)를 충족하는지 확인(기존 활성 lint 가 이미 의존하면 OK). **promtool 설치 step 필수 추가**(P5 PromQL syntax 검증의 정본 — Codex 1차 #3/2차 #3, 미설치 시 lint 가 exit 2 로 명확히 실패하게). **escaped-quote sweep**(B2 + Codex #5): `application` 과 `service` **둘 다** 매치 — `grep -rnE '(application|service)=\\?"peekcart"|application: ?peekcart|service: ?peekcart' k8s/monitoring scripts` → 0(템플릿 변수/by-clause/per-service 값만 잔존). 현재 `grafana-alerts.yml:101,128` 에 `service=\"peekcart\"` 실재 → application-only sweep 은 이를 놓침. `tags`/`uid` 의 `peekcart` 브랜드 문자열(예: `peekcart-high-error-rate` uid)은 sweep 대상에서 제외(라벨 값만 검사). sweep 은 ci.yml step 또는 observability-ssot-lint 에 통합(§5 에서 위치 확정).

## 4. 영향 파일

**신규**:
- `docs/adr/0015-observability-per-service-contract.md` (ADR-0009 supersede — P1)

**수정**:
- `docs/adr/0009-observability-contract-ssot.md` (Status 헤더만 `Partially Superseded by ADR-0015` + 무효화 범위 줄, 본문 불변 — P1)
- `docs/adr/README.md` (INDEX: ADR-0009 Status 갱신 + ADR-0015 행 추가 — P1)
- `CLAUDE.md` (관측성 SSOT 줄 ADR-0015 참조 보강 — P1)
- `k8s/monitoring/shared/grafana-alerts.yml` (4 alert per-service by-clause — P2)
- `k8s/monitoring/shared/api-jvm-dashboard.json` (`$application` 템플릿 변수 + panel query — P3)
- `k8s/monitoring/shared/kafka-lag-dashboard.json` (`$application` 템플릿 변수 — P3)
- `k8s/monitoring/shared/pod-resources-dashboard.json` (application 참조 시만 — P3, 확인 필요)
- `scripts/observability-ssot-lint.sh` (per-service ground truth — P4)
- `scripts/observability-promql-lint.sh` (per-service ground truth — P5)
- `.github/workflows/ci.yml` (lint 2종 주석 해제 재활성 + promtool 설치 step(P5 syntax) + sweep step — P6)

**확인(미수정 가능)**: 5서비스 `<svc>-service/src/main/resources/application.yml`(이미 per-service 태그 보유 — ground truth source, 미수정), `peekcart-common-observability/.../MetricsConfig.java`(owner — 미수정), 5 `servicemonitor.yml`(PR3b 완료 — ground truth, 미수정).

## 5. 검증 방법

- **P1**: `docs/adr/0015-*.md` 존재 + §Decision 에 per-service SSOT 표(S1/S2/S5). `grep -n "Partially Superseded by ADR-0015" docs/adr/0009-*.md` 매치 + 본문 diff 가 Status 헤더 영역만(`git diff` 로 본문 불변 확인). README INDEX 에 ADR-0009 Status 변경 + ADR-0015 행. CLAUDE.md SSOT 줄 ADR-0015 참조.
- **P2/P3**: `python3 -c "import yaml; yaml.safe_load(open('k8s/monitoring/shared/grafana-alerts.yml'))"` 파싱 성공. 각 대시보드 JSON `python3 -m json.tool` 파싱 성공 + `templating.list` 에 `application` 변수 존재. (가능 시) Prometheus/Grafana 로컬 1회 렌더로 5서비스 드롭다운·alert per-service 평가 육안 확인(없으면 정적 검증으로 대체 명시).
- **P4/P5/P6**: `bash scripts/observability-ssot-lint.sh` exit 0(5서비스 각 자기이름 태그 일치, owner=common-observability). `bash scripts/observability-promql-lint.sh` exit 0(per-service ground truth, alert 라벨 invariant + **5서비스 coverage**: error-rate/slow-response 가 5서비스 정확일치 regex `application=~"..."` + `by (application)` 강제·무필터/단일 equality 실패 확인, scrape-absent equality 집합 == 매칭 Service `metadata.name` 집합 1:1, **promtool 로 PromQL syntax 통과**). `ci.yml` 에서 두 lint 호출 활성(주석 아님) — `grep -n "observability-ssot-lint\|observability-promql-lint" .github/workflows/ci.yml` 가 호출 라인(주석 `#` 없는) 반환. 활성 lint 5종(kustomize-namespace/image-contract/servicemonitor-selector/observability-ssot/observability-promql) 전부 그린. **negative 검증**(false-green 차단 확인): alert 에 의도적으로 단일 `application="order-service"` equality 를 넣었을 때 promql-lint 가 exit 1 로 실패하는지 1회 수동 확인.
- **sweep(B2 + Codex #5)**: `grep -rnE '(application|service)=\\?"peekcart"|application: ?peekcart|service: ?peekcart' k8s/monitoring scripts` → 단일 고정값 0(per-service 값/`$application`/by-clause 만). escaped quote + `service` 포함 패턴으로 false-green 차단(현 `grafana-alerts.yml:101,128` 의 `service=\"peekcart\"` 가 제거됐는지 확인). uid/tags 의 `peekcart-*` 브랜드 문자열은 매치 제외(라벨 값 `="peekcart"` 형태만 대상).
- **PromQL 평가 한계 명시**(Codex #3): 정적 lint 는 PromQL 문법 + 라벨 invariant 만 검증한다. Grafana `__expr__`(threshold/reduce expression 노드)의 의미 평가, 실제 series 존재(Prometheus 미실행), alert 발화 임계 동작은 정적 검증 대상 외 — Prometheus/Grafana 로컬 1회 육안 확인으로 보완(없으면 후속 환경 검증으로 이연 명시).
- **회귀**: `./gradlew build`(test 프로파일) 그린 — 관측성 메트릭 통합테스트(`*ObservabilityMetricsIntegrationTest`) 영향 없음 확인.

## 6. 완료 조건

- ADR-0015 신규(per-service 관측성 SSOT §Decision) + ADR-0009 `Partially Superseded by ADR-0015`(본문 불변, 무효화 범위 명시) + README INDEX + CLAUDE.md 정합.
- grafana-alerts per-service(error-rate/slow-response/target-down = by-clause 5서비스 coverage·scrape-absent = 5 equality rule) — 단일 `application/service="peekcart"` 고정값 0. 단일 서비스 equality 0.
- 대시보드 `$application` 템플릿 변수로 5서비스 선택 가능.
- observability-ssot-lint·observability-promql-lint per-service 재작성(coverage 강제 + PromQL syntax) + `ci.yml` 재활성 → 활성 lint 5종 전부 그린. promql-lint negative 테스트(단일 equality → exit 1) 확인.
- escaped-quote + `service` sweep 0(false-green 차단).
- ADR-0015 가 ADR-0009 §Decision per-service 의도를 비준·실현(뒤집기 아님), 무효화 범위는 현-위치/D5-V1·V2 전제/S5 경로로 정확히 한정.
- **본 PR 완료 시 구현 ① PR3(배포 표면 per-service) 전체 종료** → 다음은 구현 ②(서비스별 DB 물리 분리).

후속(범위 외): 구현 ② DB 물리 분리 시 각 서비스 자가 마이그레이션 → cold-start initContainer(PR3b) 자연 제거. Slack alert delivery(L-004). D-016 완전 자동 트리거(non-blocking).
