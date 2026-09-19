# task-impl3-pr3d-internal-token — audit log

> `/plan` · `/work` 게이트 이력. 결정과 그 근거만 남긴다(상세 산출물은 계획서 §9 / progress 문서).

## 2026-08-12 — GW-1 (branch)

- 자동 통과: `feat/impl3-pr3d-a-internal-token` (사용자 선호 — 계획 승인 생략)
- 분할 확정: 계획서 §9 (PR3d-a 코드 / PR3d-b 키배포·클러스터). 근거는 §9.1 코드 검증 3건.

## 2026-08-12 — GW-2 (work loop 1, PR3d-a diff · split 3 chunk)

- 리뷰 run:
  - `work:20260811T192347Z:217f571c-74f4-4811-b3e0-1e801aa372a6:1:c1` (실행코드 31파일/1848줄) — 10건 (P0:4, P1:4, P2:2)
  - `work:20260811T192347Z:217f571c-74f4-4811-b3e0-1e801aa372a6:1:c2` (테스트 21파일/1888줄) — 5건 (P1:4, P2:1)
  - `work:20260811T192347Z:217f571c-74f4-4811-b3e0-1e801aa372a6:1:c3` (설정·스크립트·문서 16파일/1830줄) — 9건 (P1:4, P2:5)
- 총 24건. **분할 아티팩트 10건 기각 · 실제 결함 7건(중복 제거) 전량 반영.**

### 기각 — chunk 분할 아티팩트 (10건)

diff 를 3 chunk 로 나눠 각각 독립 리뷰했더니, **다른 chunk 에 있는 파일을 "패치에 없다"** 고 판정한 지적이
10건 나왔다(c1 P0 1~4·P1 5·P1 6, c2 P1 4, c3 P1 3·P1 4·P2 5·P2 9). 예: c1 은 `InternalTokenVerifier` 부재로
"컴파일 불가 P0" 를 냈지만 그 파일은 c2 에 있다. **full build 그린(567 테스트·가드 5종)이 반증**이며,
파일별 chunk 소속을 대조해 확인했다. → split 리뷰에서 "누락" 계열 지적은 저장소 상태로 교차검증한 뒤 판정한다.

### 반영 — 실제 결함 (7건)

| # | 출처 | 결함 | 반영 |
|---|---|---|---|
| 1 | c1:7 · c2:2 · c3:1 (P1) | 직접경로 Bearer 거부 테스트가 `"any.user.access.token"` (깨진 문자열) — 사용자 토큰 verifier 가 되살아나도 원래 401 이라 **vacuous-negative** | `TestRsaKeys.mintUserAccessToken()` 신설(유효 RS256·미만료) + 4서비스에 `TestRsaKeys.register()` 로 **검증키를 일부러 등록** → verifier 가 재배선되면 테스트가 실제로 깨진다 |
| 2 | c2:1 (P1) | `InternalTokenModeInvariant` 가 SecurityFilterChain 0개일 때 조기 return → **fail-open** (보안설정 미배선 컨텍스트가 조용히 기동) | 0개도 위반으로 처리. 슬라이스는 `@Component` 미스캔이라 영향 없음 |
| 3 | c2:3 (P1) | `keyDomainsAreSeparated` 가 고정 fixture 키 1개만 대조 → 내부 레지스트리에 *다른* 키가 바인딩되고 그 키가 User 쪽에도 있으면 통과 | 두 레지스트리 **fingerprint 집합 전체의 서로소** 검사로 교체(5서비스) |
| 4 | c3:2 (P1) | lint 가 내부키를 **전체 합산**으로 봐서, 한 서비스만 키를 가져도 통과 | 서비스 단위 검사(ITKO-006) + self-test 케이스 추가(6→7종) |
| 5 | c1:8 · c2:5 · c3:7 (P2) | 양성 대조군이 "401 이 아님" 만 확인 → 403·405·5xx 도 그린 | 구체 상태로 고정: product `405`(admin 은 GET 없음 = 인증통과 증거) · order `200` · payment `404` |
| 6 | c3:6 (P2) | alg 음성이 HS512/unsecured 뿐 — 승인 키의 **RS384/RS512** 를 수용해도 통과 | RS384/RS512 거부 + RS256 양성 대조군 · `exp==iat` · maxTTL 경계(±1s) · skew 경계(±1s) · active/previous kid 회전 추가 (42→48건) |
| 7 | c1:10 · c3:8 (P2) | WebFlux 서명 예산 미측정 | `InternalTokenSigningBudgetTest` 신설 — 예산 **선확정**(RSA-2048 p95 &lt;10ms · 3072 &lt;25ms), 측정 p95 = 1.80ms / 3.00ms. **부하 하 event-loop lag 는 PR3d-b 이연**을 계획서 §9.2 에 명시 |

- 검증: 10모듈 `./gradlew build` 그린 · 가드 5종 그린 · `internal-key-ownership-lint` self-test 7/7
- diff: `.cache/diffs/diff-task-impl3-pr3d-internal-token-1786473047.patch`
- raw: `.cache/codex-reviews/diff-task-impl3-pr3d-internal-token-17864762{57,584}-c{1,2}.json` · `-1786477192-c3.json`

## 2026-08-12 — /ship --execute (PR #80)

- GS-1 consistency precheck `ok` (자동 통과) · GS-2 partition 승인 · GS-3 PR 본문 승인
- 5 커밋: `feat(gateway)` / `feat(auth)` / `test(auth)` / `chore(ci)` / `docs(plan)`
- **커밋 재분할 1회**: 최초 p1 이 `internal-token-contract/src/testFixtures/**` 7개를 흡수해 src↔test 혼합 → push 전 `git reset --soft main` 후 계약 모듈 testFixtures 를 p3 으로 이동해 재커밋(분류 순도 확인: p1 src 10 / p2 src 27 / p3 test 29+build 1 / p4 chore 3 / p5 docs 2)
- push `origin/feat/impl3-pr3d-a-internal-token` · PR [#80](https://github.com/Kimgyuilli/PeakCart/pull/80) (base main)
- `/done`: TASKS ③ PR3d-a ✅[#80] 반영(③ 는 PR3d-b/PR4 남아 `🔄` 유지) · PHASE4 이력 추가
- **Layer 1(02 / 04 §10-2) 동기화는 PR3d-b 일괄 반영으로 이연** — 사용자 결정

## 2026-08-12 — CI 실패 수정 (PR #80, images 6/6)

- **증상**: `build` 잡(gradle+lint 9종) 통과, **`images` 6개 전부 실패** — `Could not resolve project :internal-token-contract`
- **원인**: `settings.gradle` 에 모듈을 추가했지만 `Dockerfile` COPY 목록을 갱신하지 않았다. Dockerfile 설정 단계가 전 모듈을 평가하므로 소스가 없으면 project 해석이 실패한다. **로컬 `./gradlew build` 그린으로는 잡히지 않는 부류**(이미지 빌드 컨텍스트 전용).
- **수정**: `COPY internal-token-contract/build.gradle ...` + `COPY internal-token-contract/ ...` 2줄 추가.
- **재발 방지(주석 → 검사)**: Dockerfile 은 이미 "settings.gradle 에 모듈 추가 시 COPY 목록도 동기화하라" 는 **주석**을 갖고 있었고, 그럼에도 같은 실수가 났다. → `scripts/dockerfile-module-sync-lint.sh` 신설로 CI 강제:
  - `settings.gradle` include ↔ Dockerfile COPY **양방향** 정합(build.gradle 줄 + 소스 디렉토리 줄 각각)
  - 역방향 검사(DMS-004)로 모듈 *제거* 시 잔존 COPY 도 잡는다
  - self-test 5종(정상 저장소·정상 픽스처·DMS-002·**DMS-003(#80 회귀 재현)**·DMS-004), 진단 ID+횟수 대조
  - CI `build` 잡의 policy lint 단계에 배선 → 이미지 잡보다 먼저·싸게 실패한다
- **검증**: lint OK(모듈 10개) · self-test 5/5 · **로컬 `docker build` 2종 성공**(gateway·user-service) — 로컬 gradle 그린이 Docker 그린을 보장하지 않으므로 실제 이미지 빌드로 확인했다.

## 2026-08-13 — GW-2 (loop 1) · PR3d-b-1

- 리뷰 run: `work:20260812T143408Z:2031929f-7ee5-483f-b239-ce7c0691c4bd:1` (single, 1,569줄/20파일)
- 항목: 11건 (P0:0, P1:9, P2:2) — **분할 아티팩트 0건**(single 모드 선택 효과, PR3d-a 의 10건과 대비)
- 사용자 선택: [2] 전체 반영
- diff: `.cache/diffs/diff-task-impl3-pr3d-internal-token-1786544743.patch`
- raw: `.cache/codex-reviews/diff-task-impl3-pr3d-internal-token-1786545618.json`

**전량 실제 결함으로 확인**. 그중 3건이 내가 만든 검증 도구 자체의 false-green이고, 1건은 내 논증 오류다.

| # | 반영 |
|---|---|
| 1 | SPC **내용** exact allow-list(WKO-008) — 이름만 승인하면 user SPC 의 `resourceName` 을 gateway secret 으로 바꿔 개인키를 가져가도 통과했다 |
| 2 | 소유자 판정을 이름 → **(ns, kind, name)** — `Job/gateway`·`Pod/gateway` 우회 차단 |
| 3 | 개인키 탐지를 이름 정규식 → **base64 decode 후 PEM marker** — `bundle.pem` 에 PKCS#8 담는 우회 차단 |
| 4 | `csi.nodePublishSecretRef`(**inline volume**) + SPC namespace 검사 — SPC 쪽만 봐서 놓쳤다 |
| 5 | 서비스별 배선 검사(WKO-010) + JWT 도메인 env 오염(WKO-011: `APP_JWT_RS256_PUBLICKEYS_*`·`SPRING_APPLICATION_JSON`·미승인 ConfigMap) |
| 6 | **내 논증 오류** — "Pod 200 = 서명 주입" 은 다운스트림이 SIGNED_ONLY 일 때만 참인데 §7 ③ 은 DUAL_ACCEPT 다. 전제를 관측으로 확인(`assert_downstream_signed_only`)하고 아니면 거부하도록 수정 |
| 7 | 구 RS 조회 0건·실패를 수렴으로 흘리던 `-le 1` → ownerReference 기반 식별 + 판정불가 분리 |
| 8 | image digest 대조를 실행 중 Pod 의 `containerStatuses.imageID` 로 구현 + `--expect-image` 필수화(`--allow-any-image` 만 예외) |
| 9 | P10 ② "직접경로 Bearer 거부" 추가 — 밖에서는 NetworkPolicy 로 불가하지만 **gateway Pod 안에서는 가능**(허용된 유일 peer). 내 "불가능" 판단이 틀렸다 |
| 10 | self-test 판정을 `grep -qF`(존재) → **ID×기대 횟수 multiset**. StatefulSet/ReplicaSet/ephemeral/동명 workload 변이 추가 (workload 12→24, exposure 23→25) |
| 11 | `APP_INTERNALTOKEN_MODE: SIGNED_ONLY` 를 ConfigMap 에서 제거 — ADR-0007 위반(동작 규약의 프로파일 유출)이고 base 기본값과 중복이라 이득도 없었다 |

**#10 의 부분 적용(명시)**: exposure-lint 의 **레거시 13종**은 substring 대조를 유지했다. 그 기대 문구들은 서로 겹치지 않는 고유 문구라 ID 공유로 인한 false-green 위험이 없고, 전면 ID 부여는 이 PR 범위 밖의 리팩터가 된다. **CSI 계열 12종은 같은 ID 를 공유하므로 전부 횟수 대조**로 전환했다.

**검증**: lint 10종 그린 · self-test 69종(exposure 25 · workload 24 · np 8 · itko 7 · dockerfile 5) · 3 렌더 그린 · 10모듈 빌드+테스트 그린 · 임베디드 python 4블록 compile + 양/음성 케이스.

## 2026-08-12 15:21 — /done applied (PR https://github.com/Kimgyuilli/PeekCart/pull/83)

- TASKS.md: 구현 ③ PR3d-b-1 을 🔲 → ✅ (#83). 구현 ③ 자체는 🔄 유지(b-2 잔여).
- PHASE4.md: PR3d-b-1 작업 이력 추가(핵심 결정·리뷰 11건·미충족 4건).
- ADR: 신규/상태 변경 없음 — ADR-0017/0013/0007 의 기존 결정 범위 안에서 구현했다.
- Layer 1: 영향 없음 — 02/04 는 #81 에서 이미 서명 assertion·키 도메인 분리로 동기화됨.
