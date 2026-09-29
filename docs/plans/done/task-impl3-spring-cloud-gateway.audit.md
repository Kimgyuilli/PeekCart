# task-impl3-spring-cloud-gateway — plan audit

## 2026-07-04 02:45 — GP-2 (loop 1)
- 리뷰 항목: 6건 (P0:0, P1:4, P2:2)
- 사용자 선택: [2] 전체 반영 (60s 무응답 → best-judgment: 6건 전부 코드/ADR 검증된 정당 지적, 전량 반영)
- 반영 내역:
  - #1(P1) JWKS 운영조건(cache TTL·last-known-good+alert·Gateway dual-validation) → P12/P18/P21 보강
  - #2(P1) refresh_tokens 기존 데이터 처분(전량 무효화+재로그인, backfill 불가 명시) → P6/P10 보강
  - #3(P1) NetworkPolicy 음성·양성 검증 + GKE overlay 필수 exit → P17/P18/§5 보강
  - #4(P1) ADR immutable 충돌 정정 — ADR-0009:58 에 S9 행 **기존재**(검증으로 확인) → P20 "행 추가"→"존재/owner 검증+코드구현+Layer1 동기화", §4 "ADR 본문 수정 안 함"
  - #5(P2) B11 sweep(application/service 형제라벨+escaped-quote) → P20/P22 보강
  - #6(P2) RS256 서명 latency 측정(D2 KMS 격상 후속조건) → P5 병합(재번호 회피)
- P0 무시 사유: 해당 없음(P0 0건)
- raw: .cache/codex-reviews/plan-task-impl3-spring-cloud-gateway-1783132724.json
- run_id: plan:20260704T023818Z:5f91b2fb-0cec-4d3d-b9e6-1feb34715bac:1
- lint: OK (P1..P22 연속, 필수 섹션 6/6)

## 2026-07-04 02:53 — GP-2 (loop 2, 수렴 확인)
- 리뷰 항목: 2건 (P0:0, P1:0, P2:2) — 자동 통과(P1 전멸, 6→2 수렴)
- 사용자 선택: 사용자 요청으로 2차 리뷰 실행 → 2 P2 전량 반영
- 반영 내역:
  - #1(P2) P6 근거 자가당착 정정 — "평문 token→해시 원문 소실 backfill 불가" 모순 제거, "backfill 기술 가능하나 보존 요구 없어 전량 만료"로 수정
  - #2(P2) B5 미처분 — 테스트 키쌍 단일 소유 위치 = `:common` testFixtures 명시(P1 + 영향파일)
- raw: (2nd) plan-task-impl3-spring-cloud-gateway 2차 run
- run_id: plan:20260704T025243Z:5f91b2fb-0cec-4d3d-b9e6-1feb34715bac:2
- 종료: 잔여 P1/P0 0건, 3차 불필요 — 수렴

---

# /work PR1 (RS256/JWKS dual-validation, P1~P5)

## 2026-07-04 09:01 — GW-2 (loop 1)
- 리뷰 run: work:20260704T053356Z:6485a2c5-3e5f-436b-b850-de6e69480ca6:1 (single, diff 1048L)
- 항목: 4건 (P0:0, P1:3, P2:1)
- 사용자 선택: [2] 전체 반영 (모두 정당한 보안 posture 지적)
- 반영 내역:
  - #1(P1) fallback allow-list 과확장(`startsWith("HS")` → HS256/384/512 전부) → 레거시 정확 alg **HS512 단일**로 축소 + HS256-when-on 거부 테스트
  - #2(P1) fallback unbounded(전 서비스 base=true) → base 기본값 **false**(RS256 단일), 전환 배포·전환 테스트만 명시 활성화(bounded, PR4 제거)
  - #3(P1) 개인키 산출물 포함(user main resources) → **제거**. private=`:common` testFixtures(test-scope), public=common main(비밀 아님) 단일화. user @SpringBootTest 3종 + signer 단위테스트가 testFixtures 키 참조. base yml=gitignored 파일 마운트 기본값(k8s CSI=PR3). local-keys/ gitignore
  - #4(P2) 테스트 보강 → alg=none·RS256 kid부재·HS256(비레거시) 거부 + JWKS modulus 선행0 트리밍(256B) 검증
- diff: .cache/diffs/diff-task-impl3-spring-cloud-gateway-1783143202.patch
- raw: .cache/codex-reviews/diff-task-impl3-spring-cloud-gateway-1783143259.json
- 검증: 반영분 targeted 테스트 그린(verifier 단위 + user/notification 통합), 전체 build test BUILD SUCCESSFUL(8모듈, 회귀 0)

## 2026-07-04 — /ship (PR #73)
- drift: `partially_live`(신규/삭제 파일 다수 오탐, blindspot 백로그) — main..HEAD 0 커밋 확인 후 진행
- precheck: ok(warnings 0)
- 커밋: 3 partition(docs/feat/test) + /done 1(docs progress) = 4 커밋, untracked 잔여 0
- PR: https://github.com/Kimgyuilli/PeakCart/pull/73
- /done applied: TASKS ③ 🔲→🔄(PR1 인라인) · PHASE4 PR1 이력 추가. ADR-0013 Accepted 유지(D1/D2 부분 구현). Layer1 미변경(RS256 full 상태는 gateway 완료 후)

## 2026-07-06 09:15 — GP-2 (loop 1, PR2 스코프)
- 리뷰 run: plan:20260706T000419Z:aebee036-ef1f-43d5-915d-0fa3b13651c0:1
- 항목: 4건 (P0:0, P1:2, P2:2)
- 사용자 선택: [2] 전체 반영
- 반영 내역:
  - #1(P1) grace 1회성 원자 소비 불명확(Redis GETDEL→DB 대체 시 이중 발급) → P7 `consumeGraceOnce(tokenHash, now)` 조건부 UPDATE(affected rows=1) 명시 + P8 조회-후-판단 금지 + P10 병렬 2요청 중 1건만 성공 동시성 테스트
  - #2(P1) family deny Redis 키 계약 부재(ADR-0014 D1-c) → P9 키 스펙 명시: `auth:deny:family:<familyId>`(blacklist 신키 동일 네임스페이스 계열)·원문 금지·TTL ≥ access 최대 잔여·User write/Gateway read·miss=통과·조회실패=fail-closed(PR3) + P10 deny 어댑터 키 계약 테스트
  - #3(P2) §5 PR2 검증 범위 부족(:user-service 만) → `:peekcart-common-auth:test :user-service:test` 확장 + P10 verifier/JwtFilter family_id 회귀 명시
  - #4(P2) uk_refresh_tokens_token 드롭 후 대체 unique 부재 → P6 `token_hash CHAR(64) NOT NULL`+`uk_refresh_tokens_token_hash` UNIQUE·해시=TokenHasher.sha256Hex 재사용(실존 확인) + P10 unique index 존재 검증
- 착수 보강(사전, code-verified): P6 마이그레이션 V2·평문 token/uk 드롭·fk 유지 / P7 repository 재정의 / P8 Redis grace 경로 제거·login/logout deleteByUserId→REVOKED 전환 / P9 TokenIssuer.issue 시그니처 seam·TokenClaims 전파
- raw: .cache/codex-reviews/plan-task-impl3-spring-cloud-gateway-1783296259.json
- tokens: 159,843

## 2026-07-06 09:20 — GP-2 (loop 2, PR2 스코프)
- 리뷰 run: plan:20260706T001229Z:aebee036-ef1f-43d5-915d-0fa3b13651c0:2
- 항목: 2건 (P0:0, P1:1, P2:1) — loop1 4건 반영분(deny 키 계약·unique) 닫힘 확인
- 사용자 선택: [2] 전체 반영, P1 은 (a)안
- 반영 내역:
  - #1(P1) 전환기 deny read 경로 부재(PR3 전까지 "즉시 차단" 미동작, ADR-0013 D4) → **(a)안**: P9 common-auth `TokenBlacklistLookupPort`/adapter family deny 확장 + `JwtFilter` family_id 전달(hit=401·miss=통과·조회실패=fail-closed). B1 표 확장(PR2)→이동(PR3) 정정, §4·완료조건 동기화 — PR3 이관 대상과 동일 행이라 버려지는 작업 아님
  - #2(P2) grace 성공 후 상태 불변식 미결정(family 내 ACTIVE 2개 가능) → P7 consumeGraceOnce 성공 시 같은 트랜잭션에서 기존 replacement ROTATED 처리 → ACTIVE 정확히 1개 + P10 테스트(replacement 재제시 거부 포함)
- raw: .cache/codex-reviews/plan-task-impl3-spring-cloud-gateway-1783296749.json
- tokens: 94,672 (누적 254,515)

## 2026-07-06 09:28 — GP-2 (loop 3, PR2 스코프 — 사용자 요청 추가 루프)
- 리뷰 run: plan:20260706T002238Z:aebee036-ef1f-43d5-915d-0fa3b13651c0:3
- 항목: 2건 (P0:0, P1:2, P2:0) — loop 2 반영분(전환기 enforcement·grace 불변식)의 신규 계약 표면 검증. fail-closed 는 현행 어댑터가 이미 fail-closed 라 posture 변화 아님 확인
- 사용자 선택: [2] 전체 반영
- 반영 내역:
  - #1(P1) family_id 부재 레거시 토큰 계약 없음(NPE·auth:deny:family:null·레거시 전면 401 위험) → P9: absent/null/blank 면 blacklist 만 검사·family deny=miss 취급(claim 부재 ≠ 조회 실패), 신규 발급은 family_id 필수 + P10 레거시(RS/HS fallback) 회귀 테스트
  - #2(P1) grace-success force-rotation 순환 위험 + supersede 된 replacement 의 access token 처분 미정의 → P7: ROTATED-without-grace(grace_until 미부여/≤now, consumeGraceOnce 재성공 순환 금지)·replaced_by_token_id 단방향(자기참조 금지)·access token 은 TTL 까지 bounded overlap 수용(jti blacklist 과설계 미채택) + P10 비순환 확증 테스트
- raw: .cache/codex-reviews/plan-task-impl3-spring-cloud-gateway-1783297358.json
- tokens: 71,450 (누적 325,965)
- attempts 3/3 소진 — 추가 루프는 사용자 명시 확인 필요

## 2026-07-06 11:30 — GW-2 (work loop 1, PR2 구현 diff)
- 리뷰 run: work:20260706T020953Z:a0de8369-43ea-438e-8cc0-d9e676f7e355:1 (single, diff 1671L)
- 항목: 3건 (P0:0, P1:3, P2:0)
- 사용자 선택: [2] 전체 반영 (3건 모두 plan P7/P8 불변식과 정합하는 실제 갭)
- 반영 내역:
  - #1(P1) forceRotate affected-rows 무시 → raw0 grace ↔ raw1 정상 refresh 동시 시 ACTIVE 2개 가능(plan "ACTIVE 1개" 위반) → forceRotate!=1 이면 보수적으로 detectReuse(family revoke+deny) 후 USR-004. INSERT 한 새 토큰도 revoke 로 함께 REVOKED(noRollbackFor 커밋). 통합테스트 graceAndReplacementRefreshConcurrent_neverTwoActive(activeCount<=1) 추가
  - #2(P1) denyFamily(Redis) 실패 시 예외가 RefreshTokenReuseException 아님 → noRollbackFor 미적용 → DB revoke 롤백 → detectReuse 내 denyFamily try/catch 로깅(예외 격리). deny 미기록은 access TTL bounded + blacklist read fail-closed 로 최종 안전. 단위테스트 refresh_reuseWithRedisFailure_stillRevokesFamily 추가
  - #3(P1) REVOKED 재제시가 deny 미기록(plan P8 "이미 revoked family 재제시"=reuse 정의 이탈) → REVOKED status 도 detectReuse 경로로 합류(revoke idempotent + deny 재기록). 테스트 refresh_revokedToken → deny 검증으로 보강
- 리팩터: revokeFamilyAndDeny → detectReuse(예외 반환 헬퍼)로 통합, 3개 reuse 진입점(ROTATED 초과·forceRotate miss·REVOKED) 단일화. @Slf4j 추가
- 검증: :peekcart-common-auth:test :user-service:test BUILD SUCCESSFUL(회귀 0)
- diff: .cache/diffs/diff-task-impl3-spring-cloud-gateway-1783303116.patch
- raw: .cache/codex-reviews/diff-task-impl3-spring-cloud-gateway-1783303825.json
- tokens: 76,429

## 2026-07-06 11:38 — GW-2 (work loop 2, 재리뷰)
- 리뷰 run: work:20260706T023349Z:a0de8369-43ea-438e-8cc0-d9e676f7e355:2 (single, diff 1780L)
- 항목: 1건 (P0:0, P1:0, P2:1) — loop1 반영분 트랜잭션/롤백 semantics 정합 확인, 테스트 갭 1건
- 사용자 선택: [2] 전체 반영
- 반영 내역:
  - #1(P2) graceAndReplacementRefreshConcurrent 는 swallow+activeCount<=1 이라 "forceRotate miss → 새 토큰까지 family 전체 REVOKED 커밋" 불변식을 직접 고정 못함 → 결정적 통합테스트 graceSuccessButReplacementAlreadyRotated_revokesWholeFamily 추가(raw0 grace 유효 + raw1 사전 ROTATED → forceRotate=0 결정적 진입 → activeCount=0·non-REVOKED=0·deny 기록 검증)
- 검증: :user-service:test RefreshTokenReuseIntegrationTest BUILD SUCCESSFUL
- raw: .cache/codex-reviews/diff-task-impl3-spring-cloud-gateway-1783305262.json
- tokens: 59,514 (work 누적 135,943)

## 2026-07-06 — /ship (PR #74)
- precheck: ok(warnings 0)
- 커밋: 3 partition(feat/test/docs) + /done 1(docs progress) = 4 커밋
- PR: https://github.com/Kimgyuilli/PeakCart/pull/74
- /done applied: TASKS ③ PR2 인라인(🔄 유지, PR3/PR4 대기) · PHASE4 PR2 이력 추가. ADR-0013 Accepted 유지(D4 구현). REQUIRES_NEW→noRollbackFor 전환은 구현 디테일(progress 기록, 신규 ADR 불요). Layer1 미변경(header-trust 전환은 PR3).

## 2026-07-23 — GP-2 (plan, PR3 loop 1)

- 대상: PR3 (Gateway 모듈 + header-trust). P11~P18 + 착수 보강 블록. PR1/PR2(P1~P10) 완료분 리뷰 제외
- 사전 code-verify(계획은 ADR 아닌 현재 코드로 검증): PR2 산출물 반영 보정 4건을 계획서에 선반영
  - (a) PR2 가 심은 표면(`isBlacklistedOrFamilyDenied`·`TokenClaims.familyId`·`RsaPublicKeyRegistry`) → Gateway reactive 재구현 대상
  - (b) `LoginUser.accessToken` seam 파손 — 유일 소비자 `AuthController.logout`
  - (c) 슬라이스 8개는 `@WithMockLoginUser`(SecurityContext 직접)라 header-trust 무영향 → 재작성 실범위 = 통합 2개 + JwtFilterTest
  - (d) canonical 고정 목록이 image-contract-lint / promote-images 2곳
- attempt 1: **timeout**(300s, stdout 공백). stderr 에 items:[] 응답만 — 미채택
- attempt 2: ok — **13건 (P0:0 / P1:10 / P2:3)**, tokens 미파싱
- 사용자 선택: **[2] 전체 반영 (13/13)**

### 반영 내역

| # | sev | 지적 | 반영 |
|---|---|---|---|
| 1 | P1 | `common/build.gradle:12-14` 가 servlet/JPA/kafka 를 `api` 전이 노출 → WebFlux 경계 불성립 | P11 `:common` 의존 금지·gateway-local DTO, P19 REACTIVE 부팅+servlet 부재 검증, 가드 추가 |
| 2 | P1 | 라우트 정본 부재(`/api/v1/{domain}` placeholder) — 실경로는 auth/users·admin/products·cart(단수) 분기, JWKS 는 `/api/v1` 밖 | P11 명시 라우트 표 + JWKS 외부 미노출·api-docs 미노출, P19 양성/오라우팅 음성 |
| 3 | P1 | 공개 경로 무헤더 401 충돌(signup/login/refresh·상품 GET·webhook permitAll) | P14 "헤더 부재 시 체인 계속, SecurityConfig 가 판정", P12 공개 경로 JWT 미요구(strip 은 유지), P19 무헤더 양성/보호 401 |
| 4 | P1 | family_id 부재 토큰·레거시 `bl:` dual-read 가 PR3 이관 계약에 미연결 | P12 null family→blacklist-only·`bl:` 유지, P19 회귀, P22 제거 게이트 |
| 5 | P1 | 알고리즘 오기 — 계획 "HS256" vs 실제 `JwtTokenVerifier:90-95` **HS512 정확 한정** | P12/P19/P22/P23 및 §1·§6 를 HS512 로 정정 |
| 6 | P1 | ADR-0014 D2-c 는 **PR3** 에서 servlet 검증 제거 요구인데 P21/P22 가 PR4 존속 전제 → ADR 충돌 | P14 에 삭제 목록(JwtFilter/verifier/lookup/configurer + JJWT·Redis 의존) 승격, PR4 범위 축소 주석 |
| 7 | P1 | 공개키 소스 미결정(JWKS vs registry 미러), CSI 마운트 대상 모호, 드리프트 방지 부재 | 보강(a) JWKS 정본 확정·미러 폐기, P17 개인키 user-service 전용/Gateway 미마운트, P19 conformance 테스트 |
| 8 | P2 | logout seam 방향은 정합하나 `LoginUser` nullable 대안이 열려 있음 | 보강(b)·P14 `LoginUser(userId,role,familyId)`·`logout(userId,familyId)` 로 계약 확정, nullable 폐기 |
| 9 | P1 | minikube NodePort×5 / GKE Internal LB×5 잔존 + Prometheus 직접 scrape 가 Gateway-only 정책과 충돌 | P17 ClusterIP 환원(patch 10개 제거) + NetworkPolicy monitoring scrape 예외, P19 음성/양성 |
| 10 | P1 | GKE 필수 exit 가 실행 절차 없이 선언만 | P19 보안 smoke 스크립트/수동 승인 job(enforcement→배포→probe→증적→cleanup), 미실행 시 PR3 미완료 |
| 11 | P1 | 무중단 롤아웃 순서 부재(Gateway 선배포=401 / 서비스 선전환=헤더 위조) | **신규 P18** 5단계 롤아웃 + rollback 조건, 기존 P18(테스트)→P19, PR4 P19~P22→P20~P23 |
| 12 | P2 | fail-closed 결정은 기존재(지적 해소). 단 401/503 병기·blast radius 미기록 | P12 응답 계약 401/429/503 분리, P13 blast radius 운영 문서화, §2 트레이드오프 보강 |
| 13 | P2 | `servicemonitor-selector-lint.sh:93` canonical 5 **동등 비교** → 6번째 monitor 가 lint 실패 | P17 Gateway ServiceMonitor + 도메인5/인프라1 분리 정본, P23 negative test |

- 구조 변경 신호(신규 모듈·경계 이동) → PLAN-BLINDSPOTS B1 역의존 스윕 §2 기존재 + 보강 블록으로 PR2 반영분 보정
- GP-1: auto-pass (ADR-0013 Accepted 선행 — 신규 ADR 불요)
- raw: `.cache/codex-reviews/plan-task-impl3-spring-cloud-gateway-1784798208.json`
- run_id: `plan:20260723T091555Z:c5ed8b64-2b89-4355-b519-67c89d96d1ee:2`
- 13/13 반영 후 lint OK(P1~P23 연속). **단 반영이 새 계약 표면을 대량 추가**(P18 신설·응답계약·삭제목록·PR4 재번호·공개경로 bypass 시맨틱) → 수렴 조건(새 표면 무추가 + P1=0) 미충족이라 3회차 속행

## 2026-07-23 — GP-2 (plan, PR3 loop 2)

- 목적: 전체 재검토가 아니라 **2회차 반영으로 신규 생성된 계약 표면 검증**(프롬프트를 변경분에 한정)
- attempt 3: ok — **8건 (P0:0 / P1:5 / P2:3)**, tokens 미파싱
- 사용자 선택: **[2] 전체 반영 (8/8)**
- **해소 확인**(재지적 아님): 헤더 완전 부재 시 보호 경로는 `JwtSecurityConfigurer:41-44` `anyRequest().authenticated()` 로 **fail-safe** — 2회차에서 우려한 조용한 익명 통과 없음. P1~P23 연속·유일, PR4 참조 P20~P23 정상 갱신 확인

### 반영 내역

| # | sev | 지적 | 반영 |
|---|---|---|---|
| 1 | P1 | family-less 토큰 계약 모순 — P12 수용 vs P14 familyId 고정 → `auth:deny:family:null` 오기록 또는 혼재 401 | P12 **시한부 수용**(PR3a~c 한정) + PR3d 진입 게이트 "마지막 family-less 발급 + access TTL 경과" 증명 → 수용 제거·`LoginUser.familyId` non-null 불변식. P19 보호API/logout/혼재Pod 회귀 |
| 2 | P1 | P18 단계별 배포 산출물 경계 없음 — 단일 이미지로 ④/⑤ 구분 불가, ⑤ 이후 롤백 실패 | **PR3a~d 실행 분할 표 신설**(단계·주항목·진입조건·롤백) + P18 에 실제 진입조건 값·`maxUnavailable=0`·단계별 태그·역순 rollback 강제 기입 |
| 3 | P1 | "common-auth identity-only" 미성립 — `JwtTokenSigner`가 `JwtAuthProperties`/`JwtKeyProperties`/`PemKeyLoader`, `JwkController:3`이 `RsaPublicKeyRegistry` 의존 → 일괄 삭제 시 컴파일 불가 | P14 에 **클래스별 move/delete/retain 표** + 과잉삭제 방지(User JJWT·Redis write / Product Redis 캐시 유지) |
| 4 | P1 | 부분·중복·형식오류 헤더 미정의 → 권한 위조 또는 500 | P14 **3-state 계약**(전부 없음=anonymous 통과 / 정확히 하나씩=인증 / 그 외=401) + 값 검증 규칙, P19 음성 매트릭스 |
| 6 | P1 | conformance 대상(`JwtTokenVerifier`)이 같은 PR 에서 삭제 → 최종 head 에서 완료조건 동시 만족 불가 | **golden vector 방식**: PR3a differential → fixture 동결 → PR3d 이후 Gateway 단독 conformance. §5 동기화 |
| 5 | P2 | allowlist method+path SSOT 부재(Product 는 method 무관 permitAll), JWKS 기대값 모순(404 vs 200) | **공개 경로 SSOT 표 신설**(method+path+Gateway/서비스/기대), Product GET 한정, JWKS 외부404↔내부200 분리 |
| 7 | P2 | JWKS 503 ↔ last-known-good 분기점 없음 | P12 **응답 행렬 확정**(known kid+LKG 정상+alert / unknown+refresh성공후미존재 401 / unknown+refresh실패 503 / cold start usable 0 → readiness=false), P19 parameterized |
| 8 | P2 | P3/P5 에 HS256 잔존 — "전반 정정" 설명과 모순 | P3/P5 → HS512 정정, §2 배경의 "HS256 대칭키" 도 사실 정정, **P22 에 ADR-0013 Update Log(`fix(adr):`) 작업 추가**(결정변경 아닌 사실오류 → 새 ADR 불요, `:65` 대안비교는 원문 유지) |

- raw: `.cache/codex-reviews/plan-task-impl3-spring-cloud-gateway-1784799939.json`
- run_id: `plan:20260723T094453Z:93de8826-c20d-4ed2-9882-d361655c41df:3`
- tokens: 별도 파싱
- 종료: 8/8 반영 후 lint OK(P1~P23 연속) → plan.done. **attempt 3 = 권장 상한 도달** — 추가 루프는 상한 초과 확인 필요
- 다음: `/work` (실행은 PR3a 부터)

## 2026-07-23 — GW-2 (work, PR3a loop 1)

- 브랜치: `feat/impl3-pr3a-gateway-shadow` (main sync 후 분기, PR2 #74 머지분 반영)
- 구현: **PR3a = P11(모듈+라우트 정본)·P12(reactive 검증 필터)·P13(RateLimiter)·P16(Dockerfile/CI/canonical)**. P14/P15(header-trust)·P17(k8s)는 PR3c/PR3b 소관이라 미착수.
- diff 2536L > 2000 → 사용자 승인 하에 **3-chunk split 전량 리뷰**(누락 scope 0). 예산 초과(work 3/3, cycle 6) 명시 승인.
- 리뷰 run: c1 `work:...:1:c1`(2건) · c2 `work:...:2:c2`(7건) · c3 `work:...:3:c3`(6건) — aggregate ok
- 항목: 15건(중복 제거 **12건**) — P0:0 / P1:10 / P2:5. tokens 124,455 + 140,898 + 143,009
- 사용자 선택: **[2] 전체 반영** (G 는 축소안)

### 반영 내역

| 키 | sev | 지적 | 반영 |
|---|---|---|---|
| A | P1 | 인증 필터가 `LOWEST_PRECEDENCE-100` 이라 route filter(RequestRateLimiter)보다 **나중** 실행 → 검증·strip 전 **위조 `X-User-Id` 가 rate-limit 키**로 사용 | order **-100**(route filter order 1 보다 앞) + 검증된 userId 를 `AUTHENTICATED_USER_ID_ATTR` 로 전달, `userKeyResolver` 는 헤더 대신 attribute 만 신뢰 |
| B | P1 | `exp` 없는 토큰이 무기한 유효(jjwt 는 exp 존재 시에만 만료 검사) | `getExpiration()==null` → `InvalidTokenException`(401) + 회귀 테스트 |
| C | P1 | JWKS 갱신이 `cache.put` 만 해서 **폐기/침해 kid 가 재시작까지 잔존** | `AtomicReference<Map>` **snapshot 통째 교체**(성공·비어있지 않은 응답만), 실패/빈 응답에만 LKG 유지 + 제거 kid 무효화 테스트 |
| D | P1 | SCG 기본 `RedisRateLimiter` 는 Redis 오류를 `allowed=true` 로 삼켜 **fail-OPEN**(ADR-0013 D3 위반). `deny-empty-key` 로는 못 덮음 | `FailClosedRedisRateLimiter` 자체 구현(고정 윈도우 INCR, 오류 전파) + `@Primary` 로 기본값도 fail-closed → 필터가 **503** 매핑 |
| E | P1 | `onErrorResume` 가 `chain.filter` 이후 업스트림 오류까지 삼켜 **401 오분류**, 공개 경로 체인 **이중 호출** | 오류 처리를 **인증 Mono 로 한정**, 다운스트림은 전파(RateLimiter 장애만 503 변환) + 회귀 2종 |
| F | P1 | Boot 가 ApplicationReady 에 ACCEPTING 게시 → 내 REFUSING 을 덮어써 키 0개인데 ready | `@EventListener(ApplicationReadyEvent) @Order(LOWEST_PRECEDENCE)` 로 **Boot 이후 확정** + 기동 시 1회 적재 대기 |
| G | P1 | preAuth 키가 `?email` 쿼리 — 실제 login/signup 은 email 이 **JSON body** → 쿼리만 바꿔 버킷 회피 | **축소 반영**: 조작 가능한 쿼리 성분 제거 → **IP 단독**. 계정 차원은 body-caching 필요라 계획 P13 에 **후속 항목**으로 명시 |
| H | P1 | gateway 는 외부 진입점인데 8080 동일 포트에 actuator 노출(`/actuator/prometheus` 직접 접근) | **management.server.port=8081 분리** + smoke/PR3b scrape 경로 조정 |
| c2:4 | P2 | 공개 경로에서 만료·위조·**deny hit** 토큰까지 익명 통과 → 로그아웃/reuse 무효화 우회 | 토큰이 *제시되면* 공개 경로여도 검증 → 401. 무토큰만 익명 통과 |
| c2:5 | P2 | `/api/v1/auth/**` 전체가 pre-auth 키 → 인증 API 인 logout 포함 | signup/login/refresh 전용 라우트 분리(POST 한정), 나머지는 userId 키 |
| c2:6 | P2 | `assertGatewayHasNoServletDeps` 가 `check` 미연결 → CI `./gradlew build` 가 가드를 안 돌림(false-green) | `check.dependsOn` 에 추가. **clean build 로 실제 실행 확인** |
| c3:6 | P2 | `inFlight` 캐시 미해제 → 회전 직후 새 kid 가 cooldown 동안 401 | `doFinally` 로 해제 + "완료 fetch 재사용 금지" 테스트 |

### 리뷰 전 자체 발견 (CI 파손)

- **`/actuator/health` 503 → gateway 이미지 smoke 타임아웃**: JWKS 미도달(smoke 망에 user-service 없음) → readiness DOWN → 루트 health 집계 DOWN. 컨테이너 실행으로 재현 확인.
  처분: readiness 는 **트래픽 게이팅** 의미이므로 유지하고, smoke 는 gateway 한정 **관리 포트(8081) liveness** 로 분기(도메인 5서비스는 루트 health 유지 — 실제 MySQL/Redis/Kafka 연결 검증이 유효).
- 부트스트랩 테스트가 `NoUniqueBeanDefinitionException`(KeyResolver 3개 / RateLimiter 2개)을 2회 포착 → `@Primary` 로 해소. 테스트가 없었으면 런타임 부팅 실패.

### 검증

- `./gradlew clean build` **BUILD SUCCESSFUL (14m6s)** — 전 모듈 그린, `assertGatewayHasNoServletDeps`·`assertNoServiceProjectDeps` 실행 확인
- gateway 테스트 **42 → 56건**, 0 실패
- `docker build --build-arg SERVICE=gateway` **OK** (539MB) · `docker-health-smoke.sh gateway:ci` **passed**(`:18081/actuator/health/liveness`)
- `image-contract-lint` matrix 6/6 인식(gateway 매니페스트는 PR3b → `IMAGE_CONTRACT_TRANSITION=1` 게이트)
- diff: `.cache/diffs/diff-task-impl3-spring-cloud-gateway-1784805374.patch`
- 종료: work.done. 재리뷰 없음(work 3/3 소진) — 검증은 빌드·테스트·스모크로 대체. 다음: `/ship`

## 2026-07-23 — /ship (PR #75)

- dry-run → execute. precheck **ok**(warnings 0) → GS-1 자동 통과
- **GS-0 drift 오탐 확인**: `hpx_diff_absorption_status` 가 `git status --porcelain` 과 diff 파일 경로를 대조하는데, untracked 는 디렉토리로 접혀(`?? gateway/`) 신규 19파일이 unmatched → `partially_live`. 실제로는 브랜치 커밋 0개로 흡수분 없음(양성 확인 후 진행). **신규 디렉토리를 만드는 모든 PR 에서 재발** — `-uall` 사용으로 고쳐야 함(harness 후속, 본 PR 범위 밖).
- 커밋: 4 partition(feat/test/chore/docs) + /done 1(docs progress) = **5 커밋**
- PR: https://github.com/Kimgyuilli/PeekCart/pull/75
- /done applied: TASKS ③ 행에 PR3a 인라인(**🔄 유지** — PR3b/c/d·PR4 대기) · PHASE4 PR3a 이력 추가(핵심 결정 6·후속 3 명시).
  ADR-0013 **Accepted 유지**(D1/D3 구현이지 결정 변경 아님) · ADR-0014 D2-c exit 은 PR3c 소관 · Layer1(02/04) 미변경(header-trust 완료 후 = 계획 P21)
- 후속 필수: **PR3b 에서 gateway k8s 매니페스트 추가 후 ci.yml 의 `IMAGE_CONTRACT_TRANSITION=1` 제거**(full 6/6 강제)

## 2026-07-23 — GP-1 / GP-2 (PR3b 계획, loop 1)

**GP-1(노출, ADR 선행=아니오)**: gateway ServiceMonitor 추가가 ADR-0015 S5(canonical 5 정확일치)·S6.d(absent set) 계약을 깬다는 신호 → 사용자 결정 = **SM·관측성 lint 6 확장을 PR4 로 이연**(PR3b 는 ADR 무변경). 범위 정본 = §PR3 실행 분할표(ClusterIP 환원/NetworkPolicy 는 PR3c) — TASKS.md:43 축약 서술과 불일치했던 것을 분할표로 확정.

**Codex plan 리뷰**
- attempt 1: **timeout**(exit 124, 300s) — 탐색 과다. raw: .cache/codex-reviews/plan-task-impl3-spring-cloud-gateway-1784817351.json (빈 파일)
- attempt 2: ok, **8건(P0:0 / P1:4 / P2:4)**. run_id: plan:20260723T144125Z:cc2e0b2c-4132-4918-8f51-d9bd68e2967c:2, raw: .cache/codex-reviews/plan-task-impl3-spring-cloud-gateway-1784817717.json
- 사용자 선택: **[2] 전체 반영**

**반영 내역**
- #1(P1) P24 에 `envFrom.configMapRef=gateway-config` 필수 + P30 구조 assert — 미배선 시 프로파일 미적용→Redis localhost false-green
- #2(P1) P25 소유 범위 확대 — k8s 연결값(업스트림 URI 5·JWKS·Redis)을 application-k8s.yml 이 단독 소유(ADR-0007), 라우트 정의는 base 유지
- #3(P1) P28 rollback 에서 `kubectl delete -k` **금지** — overlay 전체(5서비스+MySQL/Redis/Kafka PVC) 삭제 위험 → 진입점 복귀 → rollout undo → 이름 단위 삭제
- #4(P1) P27(b) `scripts/gateway-exposure-lint.sh` 신설 — 렌더 음성(8081/Secret/SM 부재·configMap 배선·maxUnavailable=0)을 non-zero exit 로 실행화 + 조작 입력 자기검증
- #5(P2) §5 명령 교정(brace expansion → for loop, `docker build --build-arg SERVICE=gateway`) + CI policy lint **4종** 전체 재현
- #6(P2) P24/P26 식별자 계약 고정(metadata.name/container name/ConfigMap 이름/scaleTargetRef)
- #7(P2) 결정(나)에 PR4 `gateway-metrics` 고유 label 계약 기록 — 공용 `app=gateway` 면 SM 이 public Service 까지 매칭해 lint 실패
- #8(P2) P24 `maxUnavailable=0` 필드화 + P28 canary 파라미터(cohort·임계·관찰시간) + **digest 고정**(latest 금지)

## 2026-07-24 — GP-2 (PR3b 계획, loop 2)

**Codex plan 리뷰 attempt 3**: ok, **신규 7건(P0:0 / P1:4 / P2:3)**. run_id: plan:20260723T145424Z:cc2e0b2c-4132-4918-8f51-d9bd68e2967c:3, raw: .cache/codex-reviews/plan-task-impl3-spring-cloud-gateway-1784818502.json. 사용자 선택 **[2] 전체 반영**.
> loop1 반영으로 *새로 생긴* 계약 표면(신규 lint 스펙·연결값 소유 규칙·rollback 절차·식별자 계약)만 재검토하도록 프롬프트를 좁힘.

- #1(P2→반영) placeholder 키 정규화: `${USER_SERVICE_URI:..}` → `${app.gateway.upstream.user-uri:..}` 5종. 환경변수 표기법을 프로퍼티 이름으로 고착시키던 문제 제거(env override 는 완화 매핑으로 유지). 기존 테스트 참조 0 확인.
- #2(P1) **lint 스펙의 실제 구멍**: `port==8080` 만 보면 `targetPort: 8081` 이 통과해 관리 엔드포인트가 LB 8080 으로 공개 → targetPort 고정 + 개수 1 + selector 3자 일치 + probe 포트/경로 + 조작 입력 5종.
- #3(P1) Secret 부재 판정을 이름 추측 → **참조 기반**(secretRef/secretKeyRef/volume 전무). SM 집합 검사는 selector-lint 소유라 **중복 제거**. PR4 가 뒤집는 것은 SM 기대값 5→6 뿐이고 Secret 부재 계약은 유지로 문구 정정(결정 라 모순 해소).
- #4(P1) `:gateway:test` 는 application-k8s.yml 을 로드하지 않음(CI 는 profile=test) → k8s 프로파일 명시 활성 설정 테스트 신설, **property 존재/origin** 까지 assert(값 비교는 base 기본값과 같아 무의미).
- #5(P1) rollback 재현성: known-good digest·revision **사전 기록** → 진입점 복귀 → `--to-revision`/`set image @sha256` → status+5서비스 정상성 → **kustomization 을 known-good 으로 되돌린 뒤에만 재apply**(undo 후 재apply 로 실패 버전 복귀 차단).
- #6(P2) gateway-metrics label 계약 확정: Service `{app: gateway, monitoring-role: metrics}` ↔ SM matchLabels 동일 두 키(논리곱), PR4 P21 인수조건.
- #7(P2) application.yml:176 주석이 SM PR4 이연과 모순 → 영향 파일에 주석 정정 추가(§4 "미수정" → "부분 수정").

**수렴 판정**: loop2 도 P1=4 + 새 계약 표면 추가(정규 placeholder 키·lint 조건 확장·k8s 프로파일 설정 테스트) → 종료 조건("직전 루프가 새 계약 표면 무추가 + P1=0") **미달**. attempts=3/3 소진 — 4회차는 §7-6 상한 초과라 사용자 확인 필요.

## 2026-07-24 — GP-2 (PR3b 계획, loop 3 · 상한 초과 승인)

**budget**: attempts 4 > 권장 상한 3 — 사용자 명시 승인(gate-events `GP-cap`).
**Codex plan 리뷰 attempt 4**: ok, **신규 4건(P0:0 / P1:3 / P2:1)**. run_id: plan:20260723T150421Z:...:4. 사용자 선택 **[2] 전체 반영**.
> 프롬프트로 2차 반영분 delta(A~E)만 재검토 + "새 문제 없으면 items 를 비우라" 명시.

**확인된 것(무결)**: (A) `${app.gateway.upstream.<svc>-uri:...}` 는 Binder 가 URI 변환 **전에** 해석하므로 RouteDefinition.uri 에서 유효하고 `app.gateway.jwt.*` 관례와 정합 · (C) property 존재/origin assert 는 `ConfigurationPropertySources`/`ConfigurationProperty.getOrigin()` + `RouteDefinitionLocator` 로 외부 호출 없이 가능 · **과잉·중복 검사 없음**.

- #1(P1) lint 를 **이름 카운트 → 실제 매칭 판정**으로: 다른 이름 Service 가 gateway Pod 선택 / 다른 Deployment 가 `app: gateway` Pod 생성 우회 차단 + `hostNetwork=false`·`hostPort` 부재(hostPort 8081 은 Service 검사를 통째로 우회). PR4 는 `gateway-metrics` allow-list 로 확장.
- #2(P1) Secret 검사 범위를 **PodSpec 전체**로: `initContainers` 경유 주입(native sidecar 는 `restartPolicy: Always` 로 initContainers 에 위치해 "컨테이너 1개" 검사 회피) + `volumes[].projected.sources[].secret` 포함. gateway 는 `initContainers` **0개 고정** + `automountServiceAccountToken: false`.
- #3(P1) rollback ②의 **제어면·barrier·기록 위치** 확정: 환경별 진입점 수단과 cohort 값, ② 직후 barrier(5서비스 도달성 + gateway 잔존 트래픽 임계), digest/revision/전환시각을 타임스탬프 증적 파일로, 완전 철거 시 **HPA 를 Deployment 보다 먼저 삭제**.
- #4(P2) **문서 SSOT 붕괴 정정**: §5②·§6 이 P27(b) 조건 목록을 복제하다 어긋남(§5 는 SM 부재 검사를 적었으나 P27 은 SM 검사 제외) → 두 곳을 **P27(b) 참조로 단일화**, 조작 입력도 P27 목록(9종) 정본화.

**수렴 판정**: 여전히 P1=3 이나 **성격이 바뀜** — loop1 = 빠진 산출물, loop2 = 새 검사의 정확도, loop3 = 검사의 우회 경로 + 내가 만든 문서 중복. #4 를 SSOT 단일화로 닫아 "목록 재복제" 결함 **클래스**를 제거함. 잔여 위험은 lint 스크립트 구현 세부에 집중 → 실제 코드를 보는 `/work` diff 리뷰가 더 정확한 게이트. **여기서 계획 루프 종료.**

## 2026-07-24 — GW-1 / GW-2 (PR3b 구현, loop 1)

**GW-1**(자동 통과): 브랜치 `feat/impl3-pr3b-gateway-k8s` — PR3a 명명 관례 승계.

**구현**: P24~P30. base gateway 매니페스트(Deployment+Service+ConfigMap) · `application-k8s.yml` 신설 + 라우트 placeholder 정규화 · overlay patch 4 + gke images[] 6 + gateway HPA(minReplicas 2) · `IMAGE_CONTRACT_TRANSITION` 제거 → **full 6/6** · `scripts/gateway-exposure-lint.sh` 신설 · 계획서 §7 롤아웃 runbook · gke README · `K8sProfileConnectionPropertiesTest` 신설.

**Codex diff 리뷰**(코드 723줄만 — 계획 문서 218줄은 /plan 에서 3회 리뷰돼 제외, cycle 예산 5/5): ok, **7건(P0:0 / P1:3 / P2:4)**. 사용자 선택 **[2] 전체 반영**.

- #1(P1) **CI red 발견** — `observability-promql-lint` 가 exit 2. 내가 로컬에서 lint 4종만 돌리고 관측성 2종을 빠뜨렸고, Codex 가 policy step 전체를 실행해 잡음. 원인은 그 lint 가 S6.d 의 "**SM 이 매칭하는** Service 집합" 을 `k8s/base/services/*/deployment.yml` **전체 glob** 으로 근사한 것 — SM 없는 gateway Service 가 extra 로 검출. **ADR-0015 S6.d 문언대로 SM matchLabels 매칭 Service 로 svc_set 산출**하도록 정정(ADR 변경 없음, 결정 (가) 유지).
- #2(P1) gateway-exposure-lint 가 Deployment/StatefulSet/DaemonSet/ReplicaSet 만 훑어 **Job/CronJob/직접 Pod 우회**가 통째로 무검사(Codex 가 CronJob+hostPort 8081 로 exit 0 재현). → `pod_templates()` 로 PodSpec 공통 추출(CronJob jobTemplate 포함) + ephemeralContainers 검사 추가.
- #3(P2) selector "3자 일치" 가 부분집합 판정뿐이었음 → matchLabels/Service selector **정확 일치** + matchExpressions 금지.
- #4(P2) self-test 가 non-zero 여부만 확인 → **mutation 별 진단 문자열 대조** + 무변조 baseline 통과 확인. 조작 입력 9 → **13종**(cronjob_host_port·bare_pod·label_drift·container_secret 추가, container_secret 은 initContainers 0 계약에 가려져 있던 secretKeyRef 검사를 직접 때린다).
- #5(P2) 라우트 테스트가 값 비교라 placeholder 이름 회귀에 false-green(프로파일 값 == base 기본값) → `@Nested UpstreamPlaceholderWiringTest` 로 **sentinel 값 override 후 9개 라우트 전부** 추적. 5/9 → 9/9.
- #6(P2) `spring.data.redis.port` 누락 → CsvSource 추가.
- #7(P1) gke README 치환 루프가 5개만 순회 → gateway 포함 6개 + digest 고정 안내 + `PROJECT_ID_PLACEHOLDER` 잔존 확인 명령. 승격 설명도 6으로 동기화.

**검증**: CI policy lint **7종 전부 그린**(namespace·image-contract **full 6/6**·gateway-exposure·self-test **13/13**·servicemonitor 5 유지·observability-ssot·observability-promql) · `kubectl kustomize` 양 overlay 렌더 · `./gradlew build` BUILD SUCCESSFUL(gateway 66 테스트 0 실패, 가드 5종) · `docker build SERVICE=gateway` + `docker-health-smoke.sh gateway:ci` passed.

**미확보(명시)**: 실 클러스터 canary 증적 — PR3c GKE 보안 smoke 세션에 합류(계획 §7 · P30 정직성 게이트). 렌더 성공을 canary 통과로 기록하지 않음.

## 2026-07-24 — /ship (PR #76)

- drift: `all_live` (GS-0 미발동) · precheck: **ok**(warnings 0, GS-1 자동 통과)
- 선행 조치: `/work` 중 상태 확인용 `git add -A` 로 19파일이 staged 상태였음 → **`git reset` 후 partition 별 명시 add** (그대로 커밋했으면 첫 커밋이 staged 전체를 삼켜 분할이 무너짐)
- 커밋: **6 partition** — feat(k8s) 10파일 / feat(gateway) 2 / test(gateway) 1 / chore(ci) 2 / **fix(ci) 1**(promql lint 정정은 신규 기능이 아니라 기존 구현 근사의 버그 수정이라 분리) / docs(plan) 4. 잔여 untracked 0
- PR: https://github.com/Kimgyuilli/PeakCart/pull/76
- /done applied: TASKS ③ 행에 PR3b 인라인(**🔄 유지** — PR3c/3d·PR4 대기) · PHASE4 PR3b 이력 추가(핵심 결정 6·미확보 1 명시)
- **ADR 무변경**: ADR-0013 Accepted 유지(D3 구현이지 결정 변경 아님) · **ADR-0015 무변경**(SM/관측성 6 확장은 PR4 소관 — 결정 (가)) · promql lint 정정은 S6.d **문언대로의 구현 교정**이라 계약 변경 아님 · Layer1(02/04) 미변경(외부 노출 단일화 완료는 PR3c 이후)


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
