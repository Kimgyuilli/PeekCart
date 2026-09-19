# task-ci-test-matrix — 계획 리뷰 audit

## 2026-08-31 — 계획 리뷰 라운드 1
- 항목: 9건 (P0:0, P1:4, P2:5) / 반영 9건 · 기각 0건
- 뒤집힌 전제:
  - "테스트 없는 2모듈은 매트릭스에서 빼도 된다" → 루트 `build` 는 **154 태스크**이고 `internal-token-contract:testFixturesJar`(다른 모듈 테스트가 의존)·루트 자신의 `:assemble/:build/:check/:jar` 포함
  - artifact 배치가 저절로 복원된다 → `upload-artifact` 는 LCA 상대경로, `download-artifact` 는 이름 디렉터리 아래 → staging 필요
  - reports 를 test-results 로 대체 가능 → **ADR-0011 §D4 가 `**/build/reports/` 일반화를 결정**. 위반
  - "루트 한정 = 가볍다" → 가드가 전 서비스 `:classes` 를 dependsOn 하므로 전 모듈 main 컴파일 수행
  - 합산 러너 시간이 과금 지표 → 레포가 **PUBLIC** 이라 billable minutes 없음
  - "lint 14종" → 파일 수였고 CI 는 **고유 11 스크립트 / 18 호출**
- 범위 변화: P9(태스크 parity lint) 신설, shard 8 → 6 이되 덮는 모듈 8 → 10
- raw: `.cache/codex-reviews/plan-task-ci-test-matrix-1788103056.json`

## 2026-08-31 — 계획 리뷰 라운드 2
- 항목: 10건 (P0:0, P1:6, P2:4) / 반영 10건 · 기각 0건
- **1R 수정이 만든 새 결함 7건**
- 뒤집힌 전제:
  - staging/manifest 스텝이 Gradle 실패 후에도 돈다 → GitHub Actions 후속 스텝 기본 조건은 **`success()`**
  - 무테스트 모듈도 산출물 디렉터리를 만든다 → **둘 다 부재**(실측). "디렉터리 실재" 요구 시 정상 shard 실패
  - 양방향 집합 대조가 중복 배치를 막는다 → 막지 못함. `merge-multiple` 은 **last-writer-wins**
  - P9 가 의미 있는 검사다 → **실측 154 == 154, 차이 0**. 모듈 집합이 같으면 태스크 집합은 자동으로 같음 → **P9 폐기**
  - 루트에는 보존할 report 가 없다 → `build/reports/problems/` 존재, 현 CI 가 보존 중
- 범위 변화: 작업 항목 9 → 8, 신설 lint 2 → 1
- raw: `.cache/codex-reviews/plan-task-ci-test-matrix-1788103663.json`

## 2026-08-31 — 계획 리뷰 라운드 3 (상한) — **설계 단순화**
- 항목: 9건 (P0:0, P1:6, P2:3) / 반영 9건 · 기각 0건
- **2R 수정이 만든 새 결함 6건**
- **패턴 판정**: 9건 중 **4건이 1~2R 에서 쌓은 manifest·rc 기구에서만** 나왔다. 방어 장치가 결함 생산원이 됐다고 보고, 지적을 하나씩 메우는 대신 **기구를 걷어냈다**(CLAUDE.md §2)
  - rc 배관 폐기 → Gradle 스텝을 그냥 실패시키고 후속에 `!cancelled()`. #2·#3 설계로 소멸
  - manifest 폐기 → 기대 목록을 정적 매핑(D3)에서. #4 소멸
- 뒤집힌 전제:
  - `run_attempt` 이름 격리가 재실행을 안전하게 한다 → **정반대**. "Re-run failed jobs" 는 실패 job 만 재실행하므로 성공 shard 가 옛 이름으로 남아 attempt 한정 pattern 이 **반드시 깨진다** → 고정 이름 + `overwrite: true`
  - gate 가 `needs: test` 만으로 guards artifact 를 쓸 수 있다 → 병렬이라 없을 수 있음
  - "워크플로 안에 명시" 가 lint 의 계약이 된다 → 스키마 미정이면 lint 가 자기 복사본만 보는 false-green
  - 후속 스텝이 이전 스텝 콘솔을 읽을 수 있다 → 불가. `tee` 필요
  - T7 의 20분·2.0 이 타당 → 모듈별 시간 자료 없음 + platform(컨테이너 0)과 order(55) 를 같은 비율로 묶는 것이 부당 → **사전 임계값 철회**
- 범위 변화: 작업 항목 8 → **7**, manifest 스키마·rc 프로토콜 2개 소멸
- **수렴 미달** — P1 = 6. 라운드 상한 3 도달
- raw: `.cache/codex-reviews/plan-task-ci-test-matrix-1788104199.json`

---

## 2026-09-01 — diff 리뷰 라운드 1
- 항목: 4건 (P0:0, P1:2, P2:2) / 반영 4건 · 기각 0건
- **치명 2건**:
  - gate 가 선행 실패를 결과에 반영하지 않아 **테스트가 red 인데 gate 가 green** → `images(needs:[lint,gate])` 가 실행되고 e2e·publish 까지 감. 계획 T6 과 정면 충돌 → "Propagate upstream failure" 스텝 신설
  - 가드 표식 grep `^${g}: OK` 가 `build.gradle:96` 의 `assertNoServiceProjectDeps: [모듈목록] OK (...)` 를 **절대 매치 못 함** → 정상 빌드에서도 guards 실패. lint 로 위임하고 self-test 에 실제 5종 포맷 고정
- 그 외: root-reports 의 `continue-on-error` 가 부재를 성공으로 바꿈 · `ZERO_TEST_MODULES` 드리프트 미검사
- raw: `.cache/codex-reviews/diff-task-ci-test-matrix-1788104843.json`

## 2026-09-01 — diff 리뷰 라운드 2
- 항목: 3건 (P0:0, P1:1, P2:2) / 반영 3건 · 기각 0건
- **라운드1 수정이 만든 새 결함 1건**: root-reports 필수화(`if-no-files-found: error`)가 **false-red** 위험을 만듦 — 루트는 소스 없는 aggregator 라 `build/reports` 생성이 보장되지 않는다 → 항상 생기는 `guards.log` 로 artifact 존재만 보장
- 리뷰가 확인해준 것: Propagate 스텝이 앞 스텝 실패 시에도 `!cancelled()` 로 평가되고 이중 안전 · `fail-fast:false` 매트릭스에서 일부 실패 시 `needs.test.result` 는 failure
- raw: `.cache/codex-reviews/diff-task-ci-test-matrix-r2.json`

## 2026-09-01 — diff 리뷰 라운드 3 (상한)
- 항목: 3건 (P0:0, P1:2, P2:1) / 반영 3건 · 기각 0건
- **라운드2 수정이 만든 새 결함 1건**: `root-staging/reports` 가 **`build/` 경로 성분을 제거**해 ADR-0011 §D4 의 `**/build/reports/` 계약 위반 → `root-staging/build/reports` 로 정정
- T3④(업로드 이후 충돌 검출) 이 구현에 없었음 → `--merge-artifacts` 신설(merge-multiple 폐기, artifact 별 수신 후 상대경로 충돌 검출 뒤 직접 병합)
- 가드 목록 하드코딩 드리프트 → `build.gradle` 의 `check` dependsOn 에서 읽도록 변경
- raw: `.cache/codex-reviews/diff-task-ci-test-matrix-r3.json`

## 로컬 검증 (2026-09-01)
- `ci-test-matrix-lint --self-test` **22종** 통과 (coverage 8 · layout 2 · zero-drift 3 · guards 3 · merge 3 · guard-list 3)
- 실패 주입 3종(빈 modules · 모듈 중복 배치 · 모듈 추가 미반영) 전부 red
- **T1 실측**: `./gradlew :build` → 가드 5종 실행 확인, `--verify-guards` 가 실제 출력 5종 매치
- **platform shard 실측**: `:common:build … :gateway:build` BUILD SUCCESSFUL, staging 에 3모듈만 생성(무테스트 2모듈 부재 = D7 계약대로)
- **gate 병합 실측**: 실제 산출물 2 artifact 206파일 → 충돌 0, `<module>/build/test-results/test` 배치 복원
- lint 15종 전부 PASS
- **미검증**: 벽시계 성능(P7·T7) · T6(게이트 순서) · T9(재실행) — **CI 실행이 곧 검증**이다

## 2026-09-01 — /ship
- PR: https://github.com/Kimgyuilli/PeakCart/pull/97
- precheck: `ok` (warnings 0)
- 커밋 4개: `ci:` / `docs(ci):` ×3
- 갱신: `docs/TASKS.md` 에 행 추가(🔄 — CI 실측 후 ✅)
- ADR 신설 없음 — 외부 의존성·아키텍처 경계·인프라 변경 없음. ADR-0011 §D4(report artifact path)는 **준수 대상**이지 변경 대상이 아니다
- **머지 전 필수**: 이 PR 의 CI 실행으로 P7(실측) · T6(게이트 순서) · T9(재실행)를 채운다. 그 전에는 ✅ 로 올리지 않는다

## 2026-09-01 — CI 실측 (P7 · T7)
- run [33470741832](https://github.com/Kimgyuilli/PeekCart/actions/runs/33470741832) **전량 통과**
- **전체 run 51m44s → 30m23s (−41%)** · 테스트 단계 **33m20s → 11m23s (−66%)**
- shard: platform 1m21s / user 3m21s / notification 4m01s / payment 8m28s / product 10m38s / order 11m22s
- 합산 test runner-minutes 39m11s (baseline build 33m20s 대비 ×1.18 — 중복 컴파일)
- **서비스 shard 비 3.39 > 2.0** → §5-7 판단: **재분할 안 함**. 임계 경로가 e2e(15m28s)로 넘어가
  order 를 쪼개도 전체 run 이 줄지 않는다. 재검토 조건 = e2e 가 빨라져 테스트가 다시 임계가 될 때
- gate 20s — 병합·배치검증·jvm 증적 대조·self-test 5종. 배치 복원이 실제로 동작
- **미검증**: T2·T3b·T6·T9 (전부 실패 경로) · `test-reports` 세 트리 **내용** 미확인
- 증적: `docs/progress/evidence/ci-test-matrix-20260901.md`
