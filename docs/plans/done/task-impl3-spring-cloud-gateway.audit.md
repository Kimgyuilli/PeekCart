
## 2026-07-23 18:25 — GP-2 (plan loop 1, PR3c 세부)
- 리뷰 항목: 10건 (P0:0, P1:8, P2:2) — attempt 2 (attempt 1 timeout/exit124, degraded finalize)
- 사용자 선택: [2] 전체 반영 (planner acting, 10건 전량 code-grounded 수정)
- 반영 요약:
  - #1 barrier 에 enforcement hard-fail + non-gateway 차단 + 직접경로 도달불가를 rollout 前으로 이동(§8-3)
  - #2 정책단계 apply 가 이미지 흔들지 않도록 digest 고정 + revision assert, maxUnavailable:0 매니페스트화(P34/§8-2)
  - #3/#4 NetworkPolicy = ingress-only podSelector(component:backend), SA peer 폐기(vanilla NP 미지원)·Gateway SA PR3c 미도입(P35)
  - #5 egress 격리 폐기(ingress-only), DNS 규칙 삭제(P35)
  - #6 HeaderTrustSecurityConfigurer 가 csrf/STATELESS/entryPoint/accessDeniedHandler/MdcFilter 전부 보존 계약 + 401/403/MDC 회귀(P31/P33)
  - #7 networkpolicy-contract-lint 신설 + self-test 5종(P35/§4/§5)
  - #8 family-less logout TTL 계약 완료조건 고정(access 유효/refresh 차단, §6)
  - #9 혼재구간 refresh/logout revision-aware 반복 probe + 증적(§8-4)
  - #10 Gateway ServiceMonitor 를 PR3 전체 신설목록에서 제거 → PR4 귀속(SM 5 유지, L257)
- raw: .cache/codex-reviews/plan-task-impl3-spring-cloud-gateway-1784830520.json
- run_id: plan:20260723T181433Z:66a51143-8b4c-42a6-a344-059fec3a6552:2
- 다음: 수렴 확인 re-review(attempt 3) — P1=8 + 새 계약표면 추가라 종료조건 미충족

## 2026-07-23 18:31 — GP-2 (plan loop 2, PR3c 수렴 재리뷰)
- 리뷰 항목: 4건 (P0:0, P1:2, P2:2) — attempt 3, 전부 직전 반영의 내부 정합/실행화 갭(새 설계결함 아님)
- 사용자 선택: [2] 전체 반영
- 반영: #1 §8-3 barrier 를 exit-1 실행 assertion 으로(비-gateway 차단·gateway 200·Prometheus up·직접경로 도달불가) · #2 Gateway SA 잔존 모순 제거(L252 미도입 확정·L158/L269 검증토큰 podSelector 로) · #3 §8-2 revision hard assert 기계화 · #4 enforcement 판정 단일화(Dataplane V2 OR networkPolicy.enabled)
- raw: .cache/codex-reviews/plan-task-impl3-spring-cloud-gateway-1784831171.json
- run_id: plan:20260723T182530Z:66a51143-8b4c-42a6-a344-059fec3a6552:3
- 추세: 10 → 4 (설계결함→정합폴리시). attempts=3(권장상한). 4차는 over-cap → 사용자 확인 필요(§7-6)

## 2026-07-24 04:54 — GW-2 (work loop 1, PR3c diff)
- 리뷰 run: work:20260724T044015Z:e8aaf50b-93eb-4f27-bd4c-472d6aaf84b2:1
- 항목: 6건 (P0:0, P1:4, P2:2)
- 사용자 선택: [2] 전체 반영 (보안 false-green 수정)
- 반영:
  - #1 gke-smoke non-gateway barrier: kubectl-run 실패↔curl 결과 분리(marker), 000000 버그 제거 → RBAC/image 실패를 차단성공으로 오판 차단
  - #2 direct-path barrier: DIRECT_ENDPOINTS 필수+개수검증, HTTP 응답 오면 실패(연결실패 000 만 도달불가) → vacuous-green 제거
  - #3 np-lint: 보호대상을 고정 5 Deployment 이름으로 식별+각각 component:backend·선택 독립검증 → 라벨 드리프트 false-green 차단(self-test backend_label_drift)
  - #4 gke-smoke default: 성공 증적 위조 금지 — 실제 결과 기록·실패 시 exit1·미수행 항목 명시
  - #5 np-lint peer: rule 단위 gateway/monitoring peer+TCP8080 결합 검사(self-test gateway_wrong_port·monitoring_no_prom_selector)
  - #6 test: filter 3-state에 blank ID/role·중복 role 추가(13종), 통합테스트 MdcFilter 등록 parity assert
- 검증: np-lint self-test 8/8, gw-exposure 13/13, 7 lint 그린, common-auth test·user 통합테스트 그린
- diff: .cache/diffs/diff-task-impl3-pr3c-review-1784868001.patch
- raw: .cache/codex-reviews/diff-task-impl3-spring-cloud-gateway-1784868040.json

## 2026-07-24 05:10 — /ship --execute (PR #77)
- 4 커밋: feat(auth)/test(auth)/feat(k8s)/docs(plan) + docs(progress) done 반영
- **커밋 재작성**: 최초 p1 이 /work 의 pre-staged git rm 삭제 10개를 흡수(git commit 은 전체 index 커밋) → soft-reset 후 clean index 재커밋(카테고리 무혼합 확인)
- push origin/feat/impl3-pr3c-header-trust-networkpolicy · PR #77 (base main)
- /done: TASKS ③ PR3c ✅[#77] 반영(③ 는 PR3d/PR4 남아 🔄 유지) · PHASE4 PR3c 이력 추가
- consistency precheck ok
- **미확보 명시**: GKE 실 클러스터 smoke 증적(PR3d 진입 전 필수)
