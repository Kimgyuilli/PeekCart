## 2026-09-14 — 계획 수립 (Codex 리뷰 미호출)

- Codex 리뷰: **사용자 지시로 미호출**. 따라서 본 계획서는 수렴 판정(P1=0)을 주장하지 않는다.
- 착수 전 코드 검증: 8건 (확인 6 / 뒤집힘 2)
- 뒤집힌 전제:
  - **W7** gateway 는 평문 `X-User-*` 를 더 이상 주입하지 않는다(`GatewayAuthenticationFilter:59-63`, PR3d-a 가 삭제) → §7 ②/③ 리허설의 "before" 상태는 PR3c 시점 이미지(`github.sha` 태그)를 배포해야 재현된다. 불가 시 범위 축소를 §7 U1 에 고정.
  - **W8** `k8s/` 에 `ServiceAccount`·`serviceAccountName` 0건 → SPC 가 규정한 Workload Identity 의 주체가 없다. KSA+IAM 바인딩 없이는 CSI 투영 자체가 실패 → **P2 신설**(b-2 의 "증적만" 경계가 깨지는 지점, 사유 명시).
- 범위 변화: +P2(KSA/WI 매니페스트) · §7 ②/③ 일부를 W7 조건부로 강등
- raw: 없음 (리뷰 미수행)

## 2026-09-14 — P2 구현 (diff 리뷰 미수행)

- Codex diff 리뷰: **사용자 지시로 미호출**. 따라서 "P0/P1 = 0" 주장 없음.
- 범위: P2(클러스터 비의존)만. P1·P3~P16 은 GKE 세션 몫.
- 계획 대비 확장 1건 — `workload-key-ownership-lint` 에 WKO-012/013 추가. 사유: Secret Manager 권한은
  볼륨이 아니라 **신원**에 붙으므로 KSA 를 빌려 쓰는 워크로드는 CSI 마운트 전수 검사를 통째로 우회한다.
  매니페스트만 넣으면 그 축이 클러스터에서만 검증 가능한 상태로 남는다. 계획서 P2 에 반영 후 구현.
- 검증: lint 8종 통과 · `workload-key-ownership-lint --self-test` **29/29**(신규 5종 포함) ·
  `gateway-exposure-lint --self-test` 29/29 · 양 overlay 렌더 OK.
- **미수행**: `./gradlew test` — diff 에 JVM 산출물이 0건(k8s 매니페스트·bash·docs). grep 으로
  자바/gradle 이 k8s 렌더를 참조하지 않음을 확인.
- raw: 없음 (리뷰 미수행)

## 2026-09-14 — /ship (P2 분리 ship)

- PR: https://github.com/Kimgyuilli/PeakCart/pull/109 (base main, 머지 안 함)
- consistency precheck: ok (warnings 0)
- 커밋 3개 (feat(k8s) 매니페스트 / test(k8s) lint / docs(plan))
- 계획서 체크박스 1/16 — 미완이 아니라 §6 이 정한 분할(P2 만 클러스터 비의존). ship 시 명시 보고.
- 갱신: TASKS.md ③ 행(b-2 🔲 → 🔄 + P2 ✅ #109, ③ 자체는 🔄 유지) · PHASE4.md 세션 기록
- 부채 로드맵 미갱신 — L-002 는 P3(실 키 주입)에서 종결 예정이라 이 PR 로 닫지 않았다.
