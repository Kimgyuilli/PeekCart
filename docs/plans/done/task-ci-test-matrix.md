# CI `build` job 분해 — lint / test 매트릭스 / guards / gate

> 동기: 구현 ⑥([#96]) 에서 `build` job 이 **32분 23초** 걸렸다. 단일 러너에서 `./gradlew build` 를 직렬 실행한다.
> 상위 논의: 레포 분리 검토 → **분리는 원인이 아니라고 판정**. 비용 동인은 컨테이너 수명 × 병렬성 0 이다.
> 상태: 계획 수립

---

## 1. 명제 (부정형)

아래 중 **하나라도 성립하면 이 task 는 미완**이다.

- N1. PR 의 벽시계 임계 경로가 여전히 **단일 job 직렬**이다 (모듈 테스트가 병렬로 돌지 않는다).
- N2. **루트 가드 5종**(`assertNoServiceProjectDeps` · `assertNoDuplicateGlobalFqcn` · `assertNoOrderProductSourceCoupling` · `assertNoOrderPaymentSourceCoupling` · `assertGatewayHasNoServletDeps`)이 CI 에서 **실행되지 않는다**.
- N3. **saga 계약 매트릭스 jvm 증적**(22행 / 4모듈)이 분해 후 **대조 대상을 잃는다** — 증적 없음인데 통과하거나, 게이트 자체가 사라진다.
- N4. 루트 `./gradlew build` 의 **태스크 집합(154개)** 중 분해 후 **아무 job 도 수행하지 않는 것**이 생긴다.
- N5. 어느 모듈이 **어느 job 에서도 테스트되지 않는다** (매트릭스 항목 누락이 조용히 통과).
- N6. lint / images / e2e / publish 의 **게이트 순서가 느슨해진다** — 테스트가 깨져도 이미지가 만들어지거나 publish 된다.
- N7. 분해가 **합산 runner-minutes(중복 컴파일 비용)를 근거 없이 늘린다** — 임계값과 그 도출 근거가 기록되지 않는다.
  - *정정(1R #7)*: 이 레포는 **PUBLIC** 이라 GitHub-hosted 러너에 **billable minutes 가 없다**(확인). 합산 시간은 과금이 아니라 **자원 효율·중복 컴파일** 지표다.

---

## 2. 배경 — 착수 전 코드 검증

| # | 검증한 전제 | 결과 | 근거 |
|---|---|---|---|
| V1 | `build` job 이 단일 러너 직렬이다 | ✅ — 정책 lint(**고유 스크립트 11개 / bash 호출 18회** + `peekcart` 라벨 grep sweep) → `./gradlew build --no-daemon` → saga gate → artifact 업로드. **실측 32m23s**. *정정(1R #9)*: 초안의 "lint 14종" 은 `scripts/*lint*.sh` **파일 수**이지 CI 가 실행하는 수가 아니다 | `ci.yml:14-136`, run 33314020601 |
| V2 | `images` 가 `build` 산출물을 소비한다 | ❌ **반증** — `docker build --build-arg SERVICE=...` 로 **자체 재빌드**한다. `needs: build` 는 **순서 게이트일 뿐** | `ci.yml:138-161` |
| V3 | saga `--jvm-evidence` 가 워크스페이스 glob 에 고정이다 | ❌ **반증(유리한 쪽)** — `ROOTS="${2:-$(ls -d ./*/build/test-results/test ...)}"` 로 **명시 roots 인자(콤마 구분)를 받는다**. 스크립트 수정 없이 artifact 수집 경로를 넘길 수 있다 | `saga-contract-matrix-lint.sh:404-405` |
| V4 | jvm 증적이 필요한 모듈 | **4개** — `order-service`·`product-service`·`payment-service`·`notification-service`, **22행** | `docs/plans/fixtures/saga-contract-matrix.tsv` |
| V5 | 루트 가드가 `build` 에 묶여 있다 | ✅ 그리고 **이게 분해의 최대 함정** — 가드 5종이 **루트 프로젝트의 `check`** 에 `dependsOn` 으로 걸려 있다. 매트릭스로만 바꾸면 **루트 `check` 가 안 돌아 가드가 조용히 사라진다.** 코드 주석이 이 위험을 정확히 적어뒀다: *"가드를 여기 연결하지 않으면 CI 가 실행하지 않아 false-green 이 된다"*. **비용 정정(1R #4)**: 가드는 `:classes` + 전 서비스 `:classes` + `:gateway:classes` 를 `dependsOn` 하므로(`:109-110`, `:192`) 이 job 은 **전 배포 모듈의 main 컴파일을 수행**한다. `test`/`testClasses` 는 포함하지 않는다 — "루트 한정" 은 **태스크 소유권**이지 작업량이 아니다 | `build.gradle:109-110, 192, 270-274` |
| V6 | Gradle 병렬/포크 설정이 있다 | ❌ **없음** — `gradle.properties` **파일 자체가 없다**. `maxParallelForks`·`org.gradle.parallel` 0건 | 전수 grep |
| V7 | 컨테이너 재사용 설정 | ❌ 없음 — `testcontainers.reuse`/`withReuse` 0건. **57 클래스 / `@Container` 165 선언**이 매번 새로 뜬다 | 전수 grep |
| V8 | 모듈 수 | **10** (`common` · `peekcart-common-observability` · `peekcart-common-auth` · `internal-token-contract` · `gateway` · 5서비스) | `settings.gradle` |
| V9 | jvm 증적이 commit 에 묶인다 | ❌ **아니다** — `check_jvm_evidence` 는 `commit_sha` 를 인자로 받지만 **본문에서 쓰지 않는다**(stale 검사는 `check_e2e_evidence:321` 에만 있다). artifact 로 옮겨도 이 성질은 **변하지 않는다**(악화도 개선도 아님) | `saga-contract-matrix-lint.sh:275-303` |
| V10 | 테스트 없는 모듈은 매트릭스에서 빼도 된다 | ❌ **반증(1R #1)** — `--dry-run` 실측: 루트 `build` 는 **154 태스크**이고 `:internal-token-contract:{assemble,build,check,jar,**testFixturesJar**,...}` · `:peekcart-common-observability:{...}` · **루트 자신의 `:assemble/:build/:check/:jar/:test`** 를 포함한다. 특히 `internal-token-contract` 의 **`testFixturesJar` 는 다른 모듈 테스트가 의존**한다. 빼면 jar 산출물 검증이 사라진다 → **매트릭스는 10모듈 전부를 덮어야 한다** | `./gradlew build --dry-run` |
| V11 | reports artifact 를 test-results 로 대체해도 된다 | ❌ **반증(1R #3)** — **ADR-0011 §D4 가 "CI test-report artifact path 를 `**/build/reports/` 로 일반화" 를 결정**했다. 현 `ci.yml:128-131` 도 `*/build/reports/**` 를 보존한다. test-results 만 올리면 **ADR 결정 위반**이자 JaCoCo 산출물 소실 | `docs/adr/0011-...md:87`, `ci.yml:128-131` |
| V13 | 태스크 집합 동등성에 별도 lint 가 필요하다 | ❌ **반증(2R #7)** — 실측: `:build` + 10모듈 `:module:build` 의 dry-run 태스크 집합이 루트 `build` 와 **154 == 154, 차이 0**. Gradle 의 비한정 `build` 는 전 프로젝트 `build` 를 선택하므로, **모듈 집합만 같으면 태스크 집합은 자동으로 같다** → 별도 parity lint 는 항등식이라 폐기하고 **P6 에 흡수** | `comm -23/-13` 비교 |
| V14 | 무테스트 모듈도 산출물 디렉터리를 만든다 | ❌ **반증(2R #2)** — `peekcart-common-observability`·`internal-token-contract` 는 `build/test-results`·`build/reports` **둘 다 부재**. staging 에 빈 디렉터리를 만들어도 `upload-artifact` 는 **파일**을 수집하므로 복원 후 남지 않는다 → 게이트가 "디렉터리 실재" 를 요구하면 **정상 platform shard 가 실패**한다(D7 에서 2모듈만 정적으로 예외 처리) | `ls */build/` |
| V15 | 루트에는 보존할 report 가 없다 | ❌ **반증(2R #9)** — 루트 `build/reports/problems/` 가 생성되고 현 `ci.yml:128-131` 이 이를 보존한다. shard/gate 만 통합하면 **1R 의 reports 수정이 루트 report 를 새로 누락**시킨다 | `ls build/reports/` |
| V12 | 합산 러너 시간이 과금 지표다 | ❌ **반증(1R #7)** — 레포가 **PUBLIC**(`gh repo view`) 이라 GitHub-hosted 러너에 billable minutes 가 없다. 자원 효율 지표로만 다룬다 | `gh repo view --json visibility` |

**구조 변경 아님** — 모듈/코드 이동 없음. `PLAN-BLINDSPOTS` B1 대상 아님. 변경은 `.github/workflows/ci.yml` 단일 파일(+ 필요 시 `gradle.properties` 신설).

**V5 가 이 task 의 핵심 위험이다.** 분해의 이득은 명확하지만, 순진하게 나누면 **가드 5종이 사라진 채로 그린이 된다.** 명제 N2 를 넣은 이유다.

**V3 이 범위를 줄였다.** 초안은 "게이트가 워크스페이스 glob 에 고정이라 스크립트를 고쳐야 한다"고 봤으나, 명시 roots 인자가 이미 있어 **스크립트 변경이 불필요**하다.

### 2.1 확정한 결정

> **3R 후 단순화**: 1~2라운드에서 "테스트 실패 vs artifact 유실" 을 구분하려고 manifest·rc 배관을 쌓았는데, 3라운드 지적 9건 중 **4건이 그 기구에서만** 나왔다(rc 스텝 간 전달 부재 · 모듈별 rc 산출 불가 · `tests:0` 모호성 · 중복검출 미검증). **방어 장치가 결함 생산원이 됐다** → 걷어낸다.

| 결정 | 선택 | 근거 |
|---|---|---|
| **D1. job 그래프** | `lint` · `test`(shard 매트릭스) · `guards` 병렬 → `gate`(**`needs: [test, guards]`**) → `images`(`needs: [lint, gate]`) | V2. **gate 가 guards 의 루트 report 를 통합**하므로 guards 를 `needs` 에 넣어야 한다(3R #5 — 2R 안은 `needs: test` 뿐이라 artifact 가 아직 없을 수 있었다). `images` 는 gate 를 거치므로 test·guards 를 전이로 기다린다 |
| **D2. shard 구성** | **6 shard** — `platform`(`common`+`peekcart-common-auth`+`peekcart-common-observability`+`internal-token-contract`+`gateway`) + 서비스 5개. **10모듈 전부**(V10) | 1R #1·#5. `@Container` 선언이 서비스에 몰려 있다(platform 0 · user 8 · notification 18 · payment 36 · product 48 · order 55) |
| **D3. shard→모듈 매핑의 단일 정본** | `strategy.matrix.include[]` 의 **`modules`(공백 구분 문자열)** 하나. Gradle 명령과 **P6 lint** 가 같은 필드를 소비한다 | 3R #6 — 정본을 안 정하면 lint 가 자기 복사본만 검증하는 false-green 이 된다. 실행식과 검증식이 갈리면 안 된다 |
| **D4. 태스크** | shard 는 `modules` 를 `:M:build` 로 펼쳐 **한 번의 Gradle 호출**. `guards` 는 루트 **`:build`** | `build = assemble + check`. 루트 `:build` 가 루트 `:check` → **가드 5종**(V5) |
| **D5. 실패 신호 — rc 배관 없음** | Gradle 스텝을 **그대로 실패시킨다**. staging·upload 는 `if: ${{ !cancelled() }}`. job 의 red 자체가 "이 shard 가 깨졌다" 는 신호다 | **3R #2·#3 을 설계로 소멸**시킨다. 셸 지역변수 `rc` 를 스텝 간에 넘길 수단(`$GITHUB_OUTPUT`)도, 단일 Gradle 호출에서 **존재하지도 않는 모듈별 rc** 도 필요 없어진다 |
| **D6. 증적 전달** | staging 디렉터리에 `<module>/build/{test-results,reports}` 배치를 만들어 **고정 이름** `shard-results-<shard>` 로 업로드(**`overwrite: true`**). gate 는 **`merge-multiple` 을 쓰지 않고** artifact 별로 받아(`_artifacts/<name>/`) **충돌을 먼저 검출한 뒤 직접 병합**한다(`--merge-artifacts`) | 1R #2 는 유효(LCA 동작 때문에 staging 필요). **`run_attempt` 를 이름에 넣지 않는다**(3R #1) — "Re-run failed jobs" 는 실패 job 만 재실행하므로 성공 shard 는 옛 이름으로 남아 attempt 한정 pattern 이 **반드시 1개만 찾는다**. `overwrite` 가 v4 의 immutable 충돌을 푼다 |
| **D7. gate 의 배치 검증** | 기대 모듈 목록을 **관측이 아니라 D3 매핑(정적)** 에서 얻는다. `peekcart-common-observability`·`internal-token-contract` **2개만** 산출물 부재 허용, **나머지 8개는 XML ≥ 1 요구** | 3R #4 — manifest 가 관측값 0을 기록하면 "정상 무테스트" 와 "테스트 소실" 이 같아진다. **기대값은 정적이어야** 그 둘이 갈린다. V14 로 2개 모듈의 부재가 정상임은 확인됐다 |
| **D8. reports 보존** | shard artifact 에 `build/reports/**` 포함 · `guards` 가 루트 `build/reports/**` 를 `root-reports` 로 업로드 · gate 가 **세 트리**를 `test-reports` 로 통합 | V11(ADR-0011 §D4) · V15 |
| **D9. 병렬성 설정** | `gradle.properties` 신설은 **범위 밖** | V6/V7 — `maxParallelForks` 는 컨테이너 동시 기동을 늘려 러너 OOM 위험. 컨테이너 재사용(§5-1)과 함께 설계 |

## 3. 작업 항목

### P1. `lint` job 분리
- 현 `build` job 의 lint 스텝을 통째로 이동 — **고유 스크립트 11개 / bash 호출 18회 + `peekcart` 라벨 grep sweep**(V1). 숫자가 아니라 **step 이름과 스크립트 목록**을 정본으로 옮긴다
- 의존성 설치(pyyaml · kubectl · promtool) 동반. **Java/Gradle 셋업 불필요** — P6 은 `settings.gradle` 과 워크플로 YAML 만 읽는다
- `needs` 없음

### P2. `test` job — 6 shard 매트릭스
- `strategy.matrix.include[]` 에 `{shard, modules}` (D3 정본). `fail-fast: false`
- Gradle: `./gradlew $(for m in ${{ matrix.modules }}; do printf ':%s:build ' "$m"; done) --no-daemon --console=plain` (`SPRING_PROFILES_ACTIVE: test`)
  - **실패하면 그대로 실패시킨다**(D5) — rc 캡처·전달 없음
- **staging 배치** — `if: ${{ !cancelled() }}`: 각 모듈의 `build/test-results` · `build/reports` 를 `staging/<module>/build/` 로 복사 (없으면 건너뛴다 — V14 의 2모듈)
- **upload** — `if: ${{ !cancelled() }}`: `staging/**` → `shard-results-${{ matrix.shard }}`, **`overwrite: true`**, `if-no-files-found: error`

### P3. `guards` job — 루트 가드 5종
- Gradle: `set -o pipefail; ./gradlew :build --no-daemon --console=plain 2>&1 | tee "$RUNNER_TEMP/guards.log"`
  - **`tee` 가 필수다**(3R #9) — 이전 스텝의 콘솔 출력은 다음 셸에서 파일로 읽을 수 없다. 확인 스텝이 읽을 대상을 만들어야 한다
- **가드 실행 확인 스텝** — `if: ${{ !cancelled() }}`: `guards.log` 에서 `assertNoServiceProjectDeps: OK` 등 **5개 성공 표식이 전부** 나오는지. 로그 파일 부재도 실패
  - 태스크가 UP-TO-DATE 로 건너뛰면 "도는 것처럼 보이지만 안 도는" 상태가 된다
- **루트 report 업로드** — `if: ${{ !cancelled() }}`: `build/reports/**` → `root-reports` (`overwrite: true`)
- **비용 명시**(V5): 이 job 은 전 배포 모듈의 main 컴파일을 수행한다(test 제외). shard 들과 중복이므로 P7 에서 계측한다

### P4. `gate` job — saga 계약 매트릭스
- `needs: [test, guards]`(D1), `if: ${{ !cancelled() }}`. **모든 스텝에도 `if: ${{ !cancelled() }}` 를 명시**(2R #4 — 기본 `success()` 면 4-a 실패 시 후속이 전부 skip 되어 현행 `if: always()` 보다 진단이 퇴행한다)
- **4-a 병합·배치 검증**: `download-artifact`(`pattern: shard-results-*` · `path: _artifacts`, **merge 없음**) → `--merge-artifacts` 가 **상대 경로 충돌 0** 을 확인하고 루트로 병합 → 그 다음 **D7 의 정적 기대**와 대조 — 산출물 필수 8모듈에 `build/test-results/test/*.xml` 이 **1개 이상** 있는가. 어긋나면 **"배치/증적 유실"**(증적 없음과 **다른 메시지**)
- **4-b jvm 증적 대조**: `--jvm-evidence`. **4-a 통과 AND `needs.test.result == 'success'`** 일 때만 전체 대조하고, 실패했으면 **결측 22행을 대량 출력하지 않고 4-a 의 결론만** 보고한다
- `--structure` · `--self-test` · `saga-e2e-smoke --self-test` · `e2e-network-contract-lint --self-test` · `kafka-subscription-contract-lint --self-test` 는 shard 결과와 무관하므로 **항상** 실행
- **통합 report**: `root-reports`(guards 의 `guards.log` + 있으면 루트 `reports`) 도 내려받아 모듈 두 트리와 함께 `test-reports` 로 재업로드(D8, `overwrite: true`)

### P5. 게이트 순서 재배선
- `images: needs: [lint, gate]` (gate 가 test·guards 를 전이로 기다린다)
- `e2e: needs: images` · `publish: needs: images` + 기존 `if` (변경 없음)

### P6. 자기검증 — shard 커버리지 lint (`scripts/ci-test-matrix-lint.sh`)
- `settings.gradle` 의 `include` ↔ **`matrix.include[].modules`**(D3 정본) 를 대조해 **각 모듈이 정확히 한 shard 에 1회** 나타나는지 강제
  - 누락 → 테스트가 조용히 사라진다(N5) · **중복 → `merge-multiple` last-writer-wins 로 증적이 덮인다**(2R #3) · 유령 → 없는 모듈 빌드 시도
- **P9(태스크 집합 동등성 lint)는 폐기**(V13) — `:build` + 10모듈 `:module:build` 의 dry-run 태스크 집합이 루트 `build` 와 **154 == 154, 차이 0** 임이 실측됐다. 모듈 집합이 같으면 태스크 집합은 자동으로 같다. 등식은 계획서에 1회 기록하고 CI 에서 재계산하지 않는다
- `--self-test`: YAML 타입 오류 · 빈 `modules` · shard 이름 중복 · 모듈 중복 배치 · 누락 · 유령
- `lint` job 에서 실행

### P7. 실측 기록
- 분해 전(32m23s, run 33314020601) ↔ 후의 **벽시계 임계 경로**, **job/shard 별 소요**, **합산 runner-minutes**, **중복 컴파일 비용**을 `docs/progress/evidence/` 에. 과금이 아니라 자원 지표임을 명시(V12)
- shard 별 소요를 표로 남겨 §5-7 재분할 판단의 입력으로 삼는다

## 4. 검증 방법 (실패 주입 기준)

| id | 대상 | 실패 주입 | 기대 |
|---|---|---|---|
| **T1** (N2) | 루트 가드가 실제로 도는가 | ① 서비스 간 의존 금지 위반(`order-service` → `payment-service`) ② **가드 성공 출력 한 줄을 지운다** ③ **`guards.log` 파일을 없앤다** | ①은 `guards` **FAILED**. ②③은 **확인 스텝이 FAILED** — 체커 자신이 red 가 되는지 본다(3R #9). 이게 없으면 "가드가 도는 것처럼 보이는" 상태를 못 잡는다 |
| **T2** (N3) | jvm 증적 대조가 살아있는가 | `saga-contract-matrix.tsv` 의 jvm 행 하나의 `evidence_key` 를 없는 FQCN 으로 변조 | `gate` **4-b** 가 "증적 없음" FAILED |
| **T3** (N3, D7) | 배치 검증이 증적 유실을 구분하는가 | ① 한 shard 의 업로드를 막는다 ② 산출물 필수 모듈의 XML 을 지운다 ③ **무테스트 2모듈이 든 정상 platform shard** ④ **서로 다른 두 shard 가 같은 상대 경로를 올린다**(P6 입력은 정상인 채로) | ①②④ 는 **4-a "배치/증적 유실"** — **T2 와 다른 메시지**. ③ 은 **통과**(V14). ④ 는 `--merge-artifacts` 가 잡는다 — P6 의 정적 검사와 **다른 층**이다(3R #7 · diff 3R #2: **P6 은 설정 중복**을, **병합 단계는 업로드 이후의 충돌·변조**를 잡는다) |
| **T3b** (D5, 2R #1/#4) | 실패 경로에서 진단 증적이 남는가 | ① shard 의 Gradle 을 강제 실패 ② gate 의 4-a 를 강제 실패 | ①에서 shard 가 FAILED 되면서도 **staging artifact 가 업로드**된다. ②에서도 **self-test 와 통합 `test-reports` 업로드가 실행**된다. 하나라도 skip 되면 현행 `if: always()` 대비 퇴행이므로 FAILED |
| **T4** (N5, P6) | shard 커버리지 | ① 더미 모듈 include 후 매핑 미추가 ② 유령 모듈 ③ 한 모듈을 두 shard 에 배치 ④ 빈 `modules` ⑤ shard 이름 중복 | 전부 `ci-test-matrix-lint` **FAILED** |
| **T5** (N4) | 분해로 사라진 태스크 | **1회성 실측으로 확정**(V13) — `./gradlew build --dry-run` vs `:build` + 10모듈 `:module:build`. 결과 **154 == 154, 차이 0** | 계획서에 기록. CI 상시 검사는 두지 않는다 — 모듈 집합이 바뀌는 순간은 **T4 가** 잡는다. 새 태스크가 `check`/`build` 에 붙으면 Gradle 의존 그래프가 양쪽 모두에 자동 반영한다(3R 확인) |
| **T6** (N6) | 게이트 순서 | 한 shard 를 강제 실패시킨 PR | `images`·`e2e`·`publish` **미실행(skipped)**. `gate` 는 `!cancelled()` 라 돌되 4-b 를 건너뛰고 4-a 결론만 보고 |
| **T7** (N1, N7) | 성능 | 분해 후 실제 PR 의 job/shard 별 소요를 **최소 2회** 수집 | **① 벽시계 임계 경로 < 32m23s**(baseline) ② **shard 별 소요표를 evidence 에 기록** ③ 서비스 5 shard 의 **최대/최소 비를 계산**해 기록하고, **2.0 초과면 §5-7 재분할 판단을 문서로 남긴다**. **사전 임계값(20분·2.0)을 완료 조건으로 못박지 않는다**(3R #8 — 모듈별 시간 자료가 없는 상태의 숫자는 근거가 없다. platform(컨테이너 0)과 order(55)를 같은 비율로 묶는 것도 부당하다). 합산 runner-minutes 는 절대 증가분과 비율을 기록 |
| **T8** | 기존 CI 계약 무회귀 | 분해 후 PR | `e2e` 가 이미지 4종을 받고 시나리오 4종을 돈다 · `publish` 는 main push 에서만 · `test-reports` 에 **모듈 `test-results` · 모듈 `reports` · 루트 `build/reports`(V15) 세 트리**가 모두 있다 |
| **T9** (D6, 3R #1) | 재실행 안전성 | shard 하나를 실패시킨 뒤 **"Re-run failed jobs"** | 성공 shard 는 attempt 1 artifact 를 유지하고 실패 shard 만 **`overwrite: true`** 로 덮인다. gate 가 **6개 shard artifact 를 모두** 찾아 4-a 통과. **이름에 `run_attempt` 를 넣으면 이 시나리오가 반드시 깨진다** — 그것이 이 테스트의 존재 이유다 |

## 5. 범위 밖 (처분 명시)

1. **Testcontainers 컨테이너 재사용** — 단일 최대 효과지만 **격리 설계 변경**이다. `AbstractIntegrationTest` 가 의도적으로 컨테이너를 소유하지 않고, `cleanDatabase()` 가 그 전제 위에 있다. Kafka consumer group offset·Redis 키 누수 표면이 새로 생긴다. **처분: 별도 task (항목 1).**
2. **`gradle.properties` 병렬 설정** — D7. 컨테이너 동시 기동을 늘려 러너 OOM 위험. 1번과 함께 설계.
3. **path filter / affected-module 빌드** — 이 task 로 매트릭스가 생기면 그 위에 얹는 게 자연스럽다. **처분: 후속(항목 3).**
4. **테스트 태깅(fast/slow 분리)** — 항목 4. 매트릭스와 독립이므로 순서 무관.
5. **레포 분리** — 상위 논의에서 **기각**. 공유 모듈 4개를 6~7 모듈이 참조하고, lint 14종 중 다수가 크로스 서비스 계약이며, e2e 가 4서비스를 한 스택에 세운다. 조직·릴리즈 주기가 갈릴 때 재검토.
6. **jvm 증적의 commit 바인딩 부재** — V9. 분해가 만든 문제가 아니라 **선재 성질**이고 이번 변경이 악화시키지 않는다. **처분: 인지·기록만.**
7. **shard 입도 최종 확정** — D2 의 6 shard 는 `@Container` 분포에 근거한 **초기값**이다. T7 이 shard 별 wall time·분산을 2회 이상 측정하므로, 편차가 크면(예: 한 shard 가 나머지의 2배) 재분할한다. **처분: T7 실측 후 결정, 이 PR 에서 고정하지 않는다.**

---

## 6. 완료 조건

### 구현 상태 (P1~P7)

- [x] P1 `lint` job 분리 (정책 lint 전량 + Java 셋업 없음)
- [x] P2 `test` job — 6 shard 매트릭스 (`matrix.include[].modules` 단일 정본 · staging · `overwrite`)
- [x] P3 `guards` job — 루트 `:build` + `tee` 로그 + 가드 5표식 확인 + 루트 report 업로드
- [x] P4 `gate` job — `needs: [test, guards]` · 배치검증 → 증적대조 2단 · 스텝별 `!cancelled()`
- [x] P5 게이트 순서 재배선 (`images: needs: [lint, gate]`)
- [x] P6 `ci-test-matrix-lint.sh` — `--merge-artifacts` · `--verify-layout` · `--verify-guards` · `--self-test` **22종**(coverage 8 + layout 2 + zero-drift 3 + guards 3 + merge 3 + guard-list 3)
- [x] P7 실측 기록 — `docs/progress/evidence/ci-test-matrix-20260901.md`. **전체 run 51m44s → 30m23s(−41%)**, 테스트 단계 **33m20s → 11m23s(−66%)**



- [~] **N1~N5·N7 불성립 확인** — N1(병렬 실행 실측) · N2(가드 5종 실행) · N3(gate 20s 증적 대조 성공) · N4(154==154) · N5(커버리지 lint) · N7(합산 +18%, 근거 기록). **N6 미검증** — shard 실패 시 게이트 차단은 이번 run 이 전량 통과라 경로를 안 탔다
- [~] **T1·T4·T5 실패주입 확인 · T3 병합 실측 · T7 실측 완료 · T8 무회귀 확인**(e2e 이미지 4종 수신·시나리오 4종 통과, publish 정상 skip). **T2·T3b·T6·T9 미검증** — 전부 실패 경로라 전량 통과 run 에서는 안 탄다
- [x] P7 실측 표가 `docs/progress/evidence/ci-test-matrix-20260901.md` 에 존재 — 벽시계·shard별·합산 runner-minutes(과금 아닌 자원 지표로 명시)·중복 컴파일 비용
- [x] 임계 경로 **30m23s < baseline 51m44s**. 서비스 shard 비 **3.39 (2.0 초과)** → §5-7 판단 문서화 완료: **재분할하지 않는다** — 임계 경로를 order(11m22s)가 아니라 **e2e(15m28s)가 쥐고 있어** order 를 쪼개도 전체 run 이 줄지 않는다
- [x] `ci-test-matrix-lint` 신설 + `--self-test` **22종** + `lint` job 배선 (`ci-task-parity-lint` 는 V13 으로 **폐기**)
- [~] `test-reports` artifact 업로드 성공(gate 20s 통과). **세 트리 내용 확인은 미실시** — artifact 를 내려받아 검사하지 않았다
- [~] Codex diff 리뷰 — 3라운드(4건 P1:2 → 3건 P1:1 → 3건 P1:2), **상한 도달로 수렴 미달**. 3R 지적 3건은 전량 반영했고 재리뷰는 돌리지 않았다. 남은 검증은 CI 실행

---

## 7. 정정 이력

### 계획 리뷰 1라운드 (Codex, 9건 / P0:0 P1:4 P2:5) — **전량 반영**

리뷰가 **확인해준 것**: `:check` 에서 가드 5종이 실제 실행됨(로그 확인) · `images → e2e` artifact 흐름은 재배선으로 깨지지 않음 · 모노레포 유지가 ADR-0011 과 일치 · P1~P8 id 규약 준수.

초안이 틀렸던 것:

1. **"테스트 없는 2모듈은 매트릭스에서 빼도 된다"** (#1). `--dry-run` 실측으로 반증 — 루트 `build` 는 **154 태스크**이고 그 안에 `:internal-token-contract:{assemble,build,check,jar,**testFixturesJar**}` · `:peekcart-common-observability:{...}` · **루트 자신의 `:assemble/:build/:check/:jar/:test`** 가 있다. 특히 `testFixturesJar` 는 **다른 모듈 테스트가 의존**한다. → shard 가 **10모듈 전부**를 덮고, 루트 `:build` 는 `guards` job 이 맡는다.
2. **artifact 배치가 저절로 복원된다고 가정**(#2). `upload-artifact` 는 최소 공통 조상 기준 상대 경로로 저장하고 `download-artifact` 는 artifact 이름 디렉터리 아래 푼다 → `test-results-<module>/...` 가 되어 게이트 기본 glob 과 어긋난다. → **staging 배치 + `merge-multiple`** 로 확정.
3. **reports artifact 소실**(#3). **ADR-0011 §D4 가 `**/build/reports/` 일반화를 결정**했고 현 CI 도 보존한다. 초안대로면 검사는 돌지만 JaCoCo 산출물이 사라진다 — **ADR 위반**. → D5 신설.
4. **"루트 한정 = 가볍다" 는 오해**(#4). 가드가 `:classes` + 전 서비스 `:classes` + `:gateway:classes` 를 `dependsOn` 하므로 `guards` job 은 **전 배포 모듈 main 컴파일**을 한다. "루트 한정" 은 태스크 소유권이지 작업량이 아니다. → V5·D3·P3 에 비용 명시.
5. **shard 입도를 실측 없이 8개로 확정**(#5). light 3모듈은 `@Container` 0개인데 각각 러너 기동·공통 의존 컴파일을 반복한다. → **`platform` 1 + 서비스 5 = 6 shard** 로 시작하고, 최종 입도는 **T7 실측 후 결정**(§5-7).
6. **T5 의 `--dry-run` 파싱이 false-green 가능**(#6). 사람이 읽는 출력이라 파서가 빈 집합을 만들어도 포함 관계가 통과한다. → `--console=plain` 강제 + **0행/기준 태스크 부재 시 실패** + `--self-test`.
7. **"과금" 프레이밍이 틀림**(#7). 레포가 **PUBLIC** 이라 GitHub-hosted 러너에 billable minutes 가 없다(확인). → N7 을 자원 효율 지표로 재작성하고, **1.5배 임계값도 근거가 없어 철회** — baseline 대비 증가분을 기록해 판단 근거로 삼는다.
8. **`gate: if: always()` 의 두 부작용**(#8). 취소된 run 에서도 돌고, 테스트 실패와 artifact 유실이 같은 "증적 없음" 으로 섞인다. → `!cancelled()` + **manifest 기반 2단 게이트**(배치 검증 → 증적 대조).
9. **lint 숫자 드리프트**(#9). "14종" 은 `scripts/*lint*.sh` 파일 수이고 CI 는 **고유 11 스크립트 / 18 호출**을 돈다. → 숫자 대신 step·스크립트 목록을 정본으로.

**범위가 늘었다**: P9(태스크 집합 동등성 lint) 가 #1·#6 으로 신설됐고, shard 가 8 → 6 으로 줄되 덮는 모듈은 8 → **10** 으로 늘었다.

### 계획 리뷰 2라운드 (Codex, 10건 / P0:0 P1:6 P2:4) — **전량 반영**

**1라운드 수정이 만든 새 결함 7건.** 직전 task(구현 ⑥)와 같은 패턴이 반복됐다.

리뷰가 **확인해준 것**: 루트 `:test` 는 `NO-SOURCE` · 루트 `build --dry-run` 은 계획대로 154 태스크 · `shard-results-*` 이름이 최초 실행에서는 고유하고 pattern 과 일치.

1R 수정이 만든 새 결함:

1. **staging·manifest 스텝에 조건이 없다**(#1). GitHub Actions 후속 스텝 기본 조건은 **`success()`** 다. 1R 은 upload 에만 `!cancelled()` 를 달아서, **Gradle 실패 시 staging·manifest 가 건너뛰어져 manifest 자체가 생기지 않는다** — D6 이 약속한 "원인 shard 요약" 이 원리적으로 불가능했다. → rc 보존 패턴 + 전 스텝 `!cancelled()` + **마지막에 rc 로 실패**(순서가 중요).
2. **무테스트 모듈이 정상 shard 를 죽인다**(#2). `peekcart-common-observability`·`internal-token-contract` 는 `build/test-results`·`build/reports` 가 **둘 다 없다**(확인). 1R 이 "manifest 가 선언한 모듈 디렉터리 실재" 를 4-a 조건으로 넣어서 **정상 platform shard 가 실패**한다. → `tests: 0` 계약 + 디렉터리 부재 허용, T3 ③ 신설.
3. **모듈 중복 배치를 아무도 안 막는다**(#3). `merge-multiple: true` 는 같은 상대 경로에서 **last-writer-wins** 라 한 모듈이 두 shard 에 들어가면 증적이 조용히 덮인다. 1R 의 P8 은 "양방향 집합 대조" 라 **중복을 통과**시킨다. → "정확히 1회" 강제 + manifest 파일 목록 중복 검출 + T4 ③.
4. **gate 스텝들이 4-a 실패 시 전부 skip**(#4). job 에만 `!cancelled()` 를 달아서 self-test 와 통합 report 업로드가 기본 `success()` 에 걸린다 — 현행 `if: always()` 보다 **진단 증적이 퇴행**한다. → 스텝별 조건 명시 + T3b 신설.
5. **재실행이 409 로 깨진다**(#5). v4 artifact 는 run 내 immutable 이라 "Re-run failed jobs" 시 같은 이름 업로드가 충돌한다. → 이름에 `run_attempt` + T9 신설.
6. **P9 가 lint job 의 "Java 불필요" 전제와 충돌**(#6). P9 는 Gradle wrapper 가 필요한데 P1 은 Java 셋업을 뺐다. → **P9 폐기로 함께 소멸**.
7. **P9 자체가 항등식**(#7). 실측: `:build` + 10모듈 `:module:build` 의 dry-run 태스크 집합이 루트 `build` 와 **154 == 154, 차이 0**. Gradle 의 비한정 `build` 가 전 프로젝트를 선택하므로 **모듈 집합만 같으면 태스크 집합은 자동으로 같다**. 1R 이 #1 을 고치며 신설한 lint 가 비용만 있는 중복이었다. → **폐기하고 P8 에 흡수**, 등식은 계획서에 1회 기록.

그 외:
8. **T7 이 주관적 통과를 허용**(#8). "유의 감소" 는 판정이 아니다. → 임계경로 ≤20분 · shard 편차 비 ≤2.0 으로 정량화.
9. **루트 `build/reports` 를 새로 누락**(#9). 1R 의 reports 수정이 모듈만 다뤄서, 현 CI 가 보존하던 루트 `build/reports/problems/` 가 빠졌다. → `guards` 가 별도 artifact 로 올리고 gate 가 통합.
10. **D5/D7 참조 드리프트**(#10). 1R 에서 D5(reports)가 신설되며 §5-2 의 "D5" 가 병렬 설정을 가리키게 됐다. → D7 로 정정.

**범위가 줄었다**: P9 폐기로 작업 항목이 9 → **8**, 신설 lint 가 2 → **1**.

### 계획 리뷰 3라운드 (Codex, 9건 / P0:0 P1:6 P2:3) — **전량 반영 + 설계 단순화**

**2라운드 수정이 만든 새 결함 6건.** 그런데 이번 라운드는 **개별 결함보다 패턴이 신호였다** — 9건 중 4건(#2·#3·#4·#7)이 전부 **1~2라운드에서 내가 쌓은 manifest·rc 기구**에서 나왔다. "테스트 실패 vs artifact 유실" 을 구분하려던 방어 장치가 **그 자체로 결함 생산원**이 됐다.

그래서 이번엔 지적을 하나씩 메우는 대신 **기구를 걷어냈다**(CLAUDE.md §2).

- **rc 배관 폐기** → Gradle 스텝을 그냥 실패시키고 후속에 `!cancelled()` 만 둔다. job 의 red 가 곧 "이 shard 가 깨졌다" 다. **#2(스텝 간 전달 수단 부재)·#3(단일 호출에서 모듈별 rc 산출 불가)이 설계로 소멸**했다.
- **manifest 폐기** → 기대 모듈 목록을 관측값이 아니라 **D3 정적 매핑**에서 얻는다. **#4(관측 `tests:0` 이 "정상 무테스트" 와 "테스트 소실" 을 같게 만든다)가 소멸**했다.

나머지:

1. **`run_attempt` 이름 격리가 T9 를 구조적으로 깨뜨린다**(#1). "Re-run failed jobs" 는 **실패 job 과 그 dependent 만** 재실행하므로, 성공한 5 shard 는 attempt 1 이름으로 남고 attempt 2 gate 는 pattern 에서 **1개만** 찾아 반드시 실패한다. 2R 이 409 를 피하려다 더 흔한 경로를 깬 것이다. → **고정 이름 + `overwrite: true`**.
5. **gate 가 guards artifact 를 소비하면서 `needs` 에 없었다**(#5). 병렬 실행이라 다운로드 시점에 artifact 가 없을 수 있다. → `needs: [test, guards]`.
6. **P6 이 파싱할 YAML 스키마 미정**(#6). "워크플로 안에 명시" 는 계약이 아니다. 실행식과 검증식이 갈리면 lint 가 자기 복사본만 보는 false-green 이 된다. → **`matrix.include[].modules` 단일 정본**(D3), Gradle 명령과 lint 가 같은 필드를 읽는다.
7. **4-a 의 중복 검출이 미검증**(#7). T4 ③은 P6 의 **정적** 층만 시험한다. → T3 ④ 신설, 두 층의 역할을 문서로 분리.
8. **T7 의 20분·2.0 이 근거 없음**(#8). 모듈별 시간 자료가 없고, platform(컨테이너 0)과 order(55)를 같은 비율로 묶는 것도 부당하다. → **사전 임계값 철회**, "baseline 미만 + 소요표 기록 + 비 2.0 초과 시 판단 문서화" 로 대체.
9. **가드 로그 확인 스텝이 읽을 파일이 없다**(#9). 이전 스텝의 콘솔 출력은 다음 셸에서 못 읽는다. → `tee "$RUNNER_TEMP/guards.log"` + T1 ②③으로 **체커 자신이 red 가 되는지** 확인.

**범위가 또 줄었다**: 작업 항목 8 → **7**, 신설 계약(manifest 스키마·rc 프로토콜) 2개 소멸.
