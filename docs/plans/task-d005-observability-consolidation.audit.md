# task-d005-observability-consolidation — /plan audit log

## 2026-05-05 14:53 — GP-2 (loop 1)
- 리뷰 항목: 4건 (P0:0, P1:2, P2:2)
- 사용자 선택: "동의하는 부분에 대해서 반영" — 4건 모두 substantive · 적용 비용 plan 본문 한정 → 전체 반영 (option [2])
- 수용 (전체 반영):
  - **#1 (P1)** D5-V6 가 ADR-0009 §Decision S6 ("PromQL syntax + 입력 series 가정 lint") 와 1:1 매핑 위반. plan 의 "1:1 구현" 표현 약화 → "1:1 매핑 (D5-V6 는 라벨 invariant 만 부분 격상)". §1 세부 목표 D5-V6 항목에 "ADR-0009 §Decision S6 검증 수단 중 PromQL syntax 절반은 본 task 비대상" 명시. §6 완료 조건 9 (D5-V6 부분 격상 잔여) 신규. §8 후속 항목의 "PromQL syntax check 도입" 단락이 ADR-0009 미이행 잔여임을 명시 + 재검토 트리거 구체화 (`promtool` expression subcommand maturity / Phase 4 OpenTelemetry alert 수 증가)
  - **#2 (P1)** P4 의 `probes.enabled` 활성화 방식 미결정으로 ADR-0007/0009 회색지대 재결정 위험. P4 상세를 `@TestPropertySource(properties = "management.endpoint.health.probes.enabled=true")` 로 고정. `application-test.yml` 옵션 제거 (ADR-0007 test profile 예외 범위 확장 회피). §4 영향 파일의 신규 후보에서 `application-test.yml` 삭제. 고정 사유 P4 상세 본문에 인용 (ADR-0007 L97 / ADR-0009 §회색지대 분류)
  - **#3 (P2)** negative 매트릭스 부족. §5 검증 방법 (수동) 의 negative test 를 **detector branch 단위 9 case** 로 확장: D5-V1 (2 — 두 키 각각) + D5-V2 (2 — Java 중복 + yaml 값 불일치) + D5-V5 (2 — selector + port) + D5-V6 (3 — application + namespace + service). D5-V3/V4 는 통합 테스트 assertion 자동 회귀로 강제 — PR 본문 §검증 부록 1줄 명시. §6 완료 조건 4 (lint negative) 의 수치도 "총 9건" 으로 갱신
  - **#4 (P2)** CI 실패 전파 설명 불일치. lint step 위치를 `chmod +x gradlew` 다음, **`./gradlew build` 이전** 으로 이동 (정책 위반을 build 비용 부담 전 빠르게 fail). §2 "CI 단일 job 통합" + §3 P7 상세 모두 갱신. `upload-artifact` 의 `if: always()` 사실 인정 (lint 실패 시에도 빈 artifact 업로드 — 디버깅 가치 유지) — 잘못된 "산출물 업로드되지 않음" 문구 제거
- 거부/보류: 없음
- raw: `.cache/codex-reviews/plan-task-d005-observability-consolidation-1777963876.json`
- run_id: `plan:20260505T052712Z:55b52af4-b007-44d8-abe7-58f7a163a35f:1`
- tokens used: 61,561

## 2026-05-05 16:54 — GP-2 (loop 2, 재리뷰)
- 리뷰 항목: 2건 (P0:0, P1:1, P2:1) — loop 1 수정 정합성 우선 점검 결과
- 사용자 선택: "동의하는 부분에 대해서 반영" — 2건 모두 substantive · 적용 비용 plan 본문 한정 → 전체 반영
- 수용 (전체 반영):
  - **#1 (P1)** D5-V6 lint 가 "라벨 값 불일치" 만 negative 로 증빙, 필수 라벨 *부재* 회귀 미검출. ADR-0009 §Context L23-26 의 의존 surface (S6.a/b → S2+S5, S6.c/d → S5) 가 라벨 부재 시 손실. P6 상세에 **alert uid 별 required-label matrix** 신규 추가 (S6.a/b: `application` 필수, S6.c/d: `namespace` + `service` 필수). 알고리즘에 presence 검증 step 추가 (필수 라벨 부재 → `required label absent` violation). §5 negative 매트릭스 D5-V6 를 5 branch 로 확장 (value mismatch 3 + label absence 2 family 대표 — S6.a/b 의 application 부재 1건 + S6.c/d 의 namespace/service 부재 1건). §6 완료 조건 4 의 case 합계 9 → 11 갱신
  - **#2 (P2)** D5-V2(b) negative case 가 "다른 yaml 에 추가" 변형을 허용 → D5-V1 location violation 동시 유발로 detector branch 1:1 증빙 실패. D5-V2(b) 를 **base `application.yml` 의 `application: peekcart` → `other` 변경** 으로 고정. "다른 yaml 추가" 변형은 D5-V1 case 로만 잔존하도록 본문 명시
- 거부/보류: 없음
- raw: `.cache/codex-reviews/plan-task-d005-observability-consolidation-1777967534.json`
- run_id: `plan:20260505T075135Z:55b52af4-b007-44d8-abe7-58f7a163a35f:2`
- tokens used: 104,694

## 2026-05-06 13:12 — GP-2 (loop 3, 최종 재리뷰)
- 리뷰 항목: 1건 (P0:0, P1:1, P2:0) — 수렴 점검 결과 신규 P1 1건
- 사용자 선택: "동의하는 부분에 대해서 반영" — 옵션 [A] (사실 정정만, scope 유지) 채택
- 수용 (전체 반영):
  - **#1 (P1)** §7 R1 의 PromQL syntax check 제외 사유가 "promtool 의 PromQL standalone parse subcommand 부재" 로 stale. 실제 `promtool promql format <expr>` 은 공식 문서에 존재 (experimental 기능). 동일 stale 근거가 §1 D5-V6 설명 / §2 비대상 / §6 완료 조건 / §8 후속 재검토 트리거 까지 4번 반복. ADR-0009 §Decision S6 미이행 잔여의 기각 사유가 잘못된 사실로 방어되어 audit trail 손상 위험. **사실 기반 트레이드오프로 4곳 정정**: (a) §1 D5-V6 → "도입 비용 트레이드오프 (experimental 기능, CI 설치, expr 추출 비용)", (b) §2 도구 선택 → "promql format 은 experimental, check rules 는 schema 불일치, ROI 낮음", (c) §7 R1 → "도구 부재가 아닌 도입 비용 트레이드오프" 로 표제 변경 + experimental flag 의존성/CI step 추가/expr 추출 비용 명시, (d) §8 후속 → 재검토 트리거 (i)~(iii) 구체화 (Phase 4 OT alert 증가 / promtool stable 승격 / 운영 인시던트). 옵션 [B] (P6 에 promtool 추가하여 D5-V6 1:1 정합) 는 experimental flag 의존 위험 + 본 task 결정 부동성 (ADR-0009 §Decision 표 4번째 컬럼 = "없음") 보호 위해 비채택. ADR-0009 미이행 잔여는 §8 그대로 유지
- 거부/보류: 옵션 [B] (scope 확장) — experimental 기능 의존 위험 회피
- raw: `.cache/codex-reviews/plan-task-d005-observability-consolidation-1778040609.json`
- run_id: `plan:20260506T040938Z:55b52af4-b007-44d8-abe7-58f7a163a35f:3`
- tokens used: 82,932

---

**누적 요약 (loop 1+2+3)**: P0:0 / P1:4 / P2:3 → 총 7건, 7건 전부 반영. attempts=3 (권장 상한 도달). plan 수렴 — 추가 라운드 권장 안 함.

## 2026-05-06 16:35 — GW-2 (loop 1, /work split review)

- 리뷰 항목: 9건 (P0:1, P1:4, P2:4) — chunk 3 split (c1/c2/c3)
- aggregate_result: ok
- 사용자 선택: "동의하는 부분에 대해서 반영" (plan loop 패턴 동일) — 권고 적용
- 수용 (P1 4건 + P2 3건 + 부분 1건):
  - **c1:2 (P1)** D5-V6 lint 가 rule 단위 라벨 합산 → 한 expr 누락 false negative. ADR-0009 §Context S6 의존 surface 는 모든 prometheus expr 단위로 적용되어야 함. 알고리즘을 `prometheus` datasource entry 단위 (refId + expr) 로 변경. negative test 신규 (S6.a refId B 의 application 만 제거 → exit 1 검출).
  - **c2:2 (P1)** D5-V2 Java regex 좁음 (`MeterRegistryCustomizer<|new MeterFilter\(\)`). `@Bean MeterFilter foo()` factory 방식 미검출 false negative. 광범위 `\b(MeterFilter|MeterRegistryCustomizer)\b` grep + import-only 라인 후속 필터로 변경. negative test (factory 방식 추가 + import-only 가 false positive 안 일으키는지) 통과.
  - **c2:3 (P2)** `set -euo pipefail` 하 inline python3 비-0 종료 시 후속 Java side 검사 누락. `python3 - <<'PY' ... PY || PY_RC=$?` 로 exit code 보존. (초기 시도 `if ! cmd; then PY_RC=$?` 는 exit code 가 negation 으로 inverted 되는 함정 — 두 번째 시도에서 정정.) negative test (yaml + java 동시 위반 → 두 보고 모두 출력) 통과.
  - **c3:1 (P1)** D5-V5 ServiceMonitor `endpoints[]` 비거나 `port` 없으면 위반 없이 통과. selector 매칭 검사 이전에 endpoint_ports presence 검증 추가. negative test (endpoints=[] / endpoint port 제거) 모두 exit 1 검출.
  - **c1:3 (P2)** Java class 선언 줄바꿈 정정 (`class\nObservabilityMetricsIntegrationTest extends ...` → 한 줄).
  - **c3:2 (P2)** servicemonitor lint + promql lint 양쪽에 pyyaml preflight 추가 (`python3 -c 'import yaml'` 실패 시 명시 메시지 + exit 2). ssot lint 도 동일 패턴 (이미 적용).
  - **c1:1 (P1, 부분 적용)** D5-V3 test 가 `NOT_FOUND` 대신 `isNotEqualTo(OK)` 사용 — 계획서 P3 가 명시한 404 계약보다 약하다는 지적. **empirical reality: SecurityConfig filter 가 actuator 이전에 동작 → `/actuator/info` 는 PUBLIC_URLS 미포함 → 401 반환** (plan §3 P3 의 "exposure include 밖 endpoint 는 actuator 자체에서 404" 단정은 보안 레이어 작동 순서 미고려). `NOT_FOUND` 로 강제 시 테스트 fail. 현 `isNotEqualTo(OK)` 가 양쪽 케이스 (401 security / 404 actuator) 모두 커버 — 화이트리스트 회귀 신호 보존. plan 본문은 frozen, PHASE3.md 본 task 엔트리에 401 ground truth + plan claim 정정 명시.
- 거부:
  - **c2:1 (P0)** TASKS.md "해결됨" 표기가 split chunk 단독 view 에선 P3~P7 누락처럼 보임 — split aggregation artifact. 전체 diff (9 files) 에는 P3~P7 모두 포함되어 있음. reviewer 가 c2 chunk만 본 정황상 false positive. TASKS.md 변경 유지.
  - **c3:3 (P2)** `namespaceSelector.matchNames` 가 ServiceMonitor.namespace 외 ns 포함해도 통과 — strict equality 검증 권고. plan §3 P5 가 명시하지 않은 scope creep. 현재 모놀리스 컨텍스트에서 false positive 위험 미감지 + Phase 4 확장 시점에 재평가가 적절.
- raw: `.cache/codex-reviews/diff-task-d005-observability-consolidation-1778049858-c1.json` / `-c2.json` / `-c3.json`
- run_id: `work:20260506T064339Z:55b52af4-b007-44d8-abe7-58f7a163a35f:1:c1` / `:c2` / `:c3`
- tokens used: c1=66,408 / c2=107,181 / c3=82,754 (총 256,343)
