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

---

## /ship 기록 — 2026-09-15

- **PR**: https://github.com/Kimgyuilli/PeakCart/pull/110
- **브랜치**: `docs/impl3-pr3d-b2-gke-session-evidence` (origin/main 기준 신규)
- **precheck**: `ok` (warnings 0)
- **커밋**: 1개 (`docs` 단일 분류, 3 files +621/-1)

### 브랜치를 새로 판 이유

Step 1 에서 전제 위반을 잡았다 — 직전 브랜치 `feat/impl3-pr3d-b2-workload-identity` 는
PR #109 가 2026-09-14 에 **이미 머지**돼 `origin/main` 대비 **0 ahead / 1 behind** 인 죽은
브랜치였다. 그대로 ship 했으면 머지된 내용 위에 새 PR 을 여는 꼴이 된다.

### `🔄 → ✅` 를 적용하지 않았다

`/ship` Step 8-1 은 TASKS 행을 `✅` 로 바꾸라고 규정하지만 **적용하지 않았다**.
계획서 P1~P16 중 P11~P14 와 P2 음성 대조군이 미수행이라 구현 ③ 은 종결되지 않았다.
TASKS 행에는 PR 링크만 달고 상태는 `🔄` 로 뒀다.

체크박스 자동 판정은 이 계획서에서 무의미하다 — P2 하나만 `- [x]` 형식이고 나머지
(P1·P3~P16)는 애초에 체크박스가 아닌 평문 불릿이라, 형식상으로는 "전부 체크됨"으로
통과해버린다.

### 갱신 항목

- `docs/TASKS.md` — ③ 행 b-2 잔여를 세션 결과로 갱신 + PR #110 링크 (상태 `🔄` 유지)
- `docs/progress/PHASE4.md` — 세션 기록 + PR 링크 + 미종결 명시
- `docs/progress/evidence/pr3d-b2-gke-20260914-1320.md` — 신규 556줄

### 미충족 (PR 본문 §미충족과 동일)

P11 회전 overlap · P12 순서 역전 재현 · P13 scrape 실증 · P14 부하 측정 ·
P2 음성 대조군. P14 는 CPU 쿼터 자동 거부로 노드 2대 구성이라 재개 전 쿼터 확보 또는
범위 축소가 선행되어야 한다. Codex 리뷰 미호출(사용자 지시) — "P0/P1 = 0" 주장 없음.

---

## /ship 기록 — 세션 2 (2026-09-15)

- **PR**: https://github.com/Kimgyuilli/PeakCart/pull/115
- **브랜치**: `docs/impl3-pr3d-b2-session2-evidence`
- **precheck**: `ok` (warnings 0)
- **커밋 2개**: `docs`(증적·동기화) / `fix(runbook)`(오류 정정) — 성격이 달라 분리

### 계획서 P1~P16 전부 수행 — 구현 ③ `🔄` → `✅`

세션 1(#110)이 이월한 P11·P12·P13·P14·P2 음성 대조군을 전부 끝냈다. 이월 사유였던 CPU 쿼터는
이번에도 풀리지 않았으나(증설 2회 자동 거부 · 콘솔 `조정 가능 여부: 아니요`), **loadgen 을 VM 에서
클러스터 내 k6 Job 으로 바꿔** 쿼터 12 안에서 3노드를 확보해 축소 없이 수행했다.

### Step 1 에서 조정한 것

호출 시점 브랜치가 `main` 이었다. main 직접 ship 금지 규칙에 따라 브랜치를 만들어 변경을 옮겼다.
main 에는 커밋이 남지 않았다.

### 체크박스 자동 판정은 이 계획서에서 여전히 무의미하다

16항목 중 `- [x]` 형식은 P2 하나뿐이고 나머지는 평문 불릿이다. 세션 1 때는 이 때문에 "형식상 통과"
상태로 종결 판정이 났을 수 있었고, 이번엔 실제로도 전부 수행돼 결과가 일치했을 뿐이다.
다음 계획서는 작업 항목을 전부 체크박스로 통일해야 한다.

### 갱신 항목

- `docs/TASKS.md` — b-2 ✅ + PR #115 링크 · **③ 행 상태 `🔄` → `✅`**
- `docs/progress/PHASE4.md` — 세션 2 기록 + PR 링크 + 종결 명시
- `docs/runbooks/user-jwt-key-rotation.md` — §4.1 롤백 절차 정정 · §6 갱신
- `docs/progress/evidence/pr3d-b2-gke-20260915-0854.md` — 신규 374줄

### 미충족 (PR #115 §미충족과 동일)

오버셀링 정합성 미검증 · event-loop lag 직접 계측 없음 · auth alert 임계 미확정 ·
측정에 co-location 경합 포함 · **User 도메인 회전 미실증**(P11/P12 는 내부 토큰 도메인) ·
발견 2·3·4 미수정 · Codex 리뷰 미호출(사용자 지시).
