# task-impl1-gradle-multimodule — Codex 리뷰 audit

## 2026-06-15 — GP-2 (loop 3, PR2a 추가 리뷰 — 사용자 요청)
- attempt 3/3 (plan 예산 상한). 검증 중심 프롬프트(2차 반영본 정확성 + 잔여 P0/P1). 리뷰 항목: 3건 (P0:0, P1:3, P2:0) — 전부 신규 이슈.
- 사용자 선택: [2] 전체 반영
- 반영 요약:
  - P1 #1 T2/T4 + 게이트(h) — JWT sign/verify 단일 설정 계약(`JwtAuthProperties`, 동일 jwt.secret/algorithm HS256) + root signer↔common-auth verifier cross-module 회귀 (ADR-0014 D1-b/D2-a)
  - P1 #2 T2 + 게이트(i) — `JwtProvider`→`JwtTokenSigner`(root)/`JwtTokenVerifier`(common-auth) 대체+기존 FQCN 제거, global.* 중복 FQCN 부재 검사 (split-package 오로딩 방지)
  - P1 #3 T7 + 게이트(j) — SecurityFilterChain 소유 단일화(common-auth=filter/configurer 제공, 각 모듈 1 chain), 모듈별 chain 수·JwtFilter 1회 등록 + root 미인증 거부 회귀
- raw: .cache/codex-reviews/plan-task-impl1-gradle-multimodule-1781459901.json
- run_id: plan:20260614T175821Z:6491c029-f6c0-4f05-9011-25708812d1d9:3

## 2026-06-15 — GP-2 (loop 2, PR2a 분해)
- 컨텍스트: PR1 완료·아카이브 후 PR2a(Notification peel + peekcart-common-auth 최초 생성) 분해 위해 state 재초기화. 계획서에 "PR2a 실행 세부"(T1~T11 + 완료 게이트 a~e) 서브섹션 추가.
- GP-1: ADR 경계 변경이나 ADR-0014(Accepted) 구현이므로 auto-pass.
- attempt 1: codex 180s 타임아웃(global/ 전체 탐색, 출력 0 bytes) → GP-2b → 사용자 [재시도]
- attempt 2: 탐색 축소 프롬프트(읽을 파일 2개 한정)로 성공. 리뷰 항목: 5건 (P0:0, P1:3, P2:2)
- 사용자 선택: [2] 전체 반영
- 반영 요약:
  - P1 #1 T1/T2 — `TokenBlacklistLookupPort`(read-only)+Redis adapter, `miss=pass`/`실패=fail-closed`/jti·TTL 검증을 T1 완료 조건으로 명시 (ADR-0014 D1-c)
  - P1 #2 T7 — common-auth WebMvcConfig/OpenApiConfig 는 LoginUser/resolver+공통 schema 만, 서비스 스캔/PUBLIC_URLS 의존 금지 경계 명시 (ADR-0011 D3)
  - P1 #3 T10 — 보안 negative 에 blacklist hit 거부·Redis 실패 fail-closed·miss 통과 회귀 추가 (ADR-0014 D1-c/D2-b)
  - P2 #4 게이트 (f) 신설 — `flywayMigrateShared` 실행성 + Testcontainers 단일 마이그레이션 확인
  - P2 #5 게이트 (e) — 산출 이미지 범위 명시(root app + notification-service)
- raw: .cache/codex-reviews/plan-task-impl1-gradle-multimodule-1781454326.json
- run_id: plan:20260614T162526Z:5e02aa3a-19a8-47f7-bbc5-bba574d0b4c2:2
- (참고 attempt1 timeout raw: plan-task-impl1-gradle-multimodule-1781454021.json)


## 2026-06-14 18:13 — GP-2 (loop 1)
- 리뷰 항목: 9건 (P0:0, P1:5, P2:4)
- 사용자 선택: [2] 전체 반영
- 반영 요약:
  - P1 #1 P11 Flyway 단일 전략 확정(전 서비스 flyway.enabled=false + 단일 migration-runner 1회 적용)
  - P1 #2 Flyway 범위 문서 정합(영향파일=공유 V1~V4 실행위치 배선, 서비스별 재배치 ② 제외)
  - P1 #3 P12 ProjectDependency 모든 configuration 평가 + testFixtures allowlist + 재현 테스트
  - P1 #4 P18 신설 — ADR-0009 S5/S6 ServiceMonitor + alert application 정규식
  - P1 #5 P13 서비스별 ObservabilityMetricsIntegrationTest 복제
  - P2 #6 P7 모듈명 ADR-0011 D1 정확화
  - P2 #7 §8 Exit Criteria coverage 표 + 구현④/⑤ Out-of-Scope
  - P2 #8 P19 신설 — 문서 동기화 작업 항목 승격
  - P2 #9 §7 트레이드오프 3건 보강(common 비대화·CI matrix·processed_events 복제)
- raw: .cache/codex-reviews/plan-task-impl1-gradle-multimodule-1781428231.json
- run_id: plan:20260614T091008Z:c3f2d785-2d6a-4ccf-b56d-3587b19d467b:1

## 2026-06-14 20:14 — GP-2 (loop 2)
- 리뷰 항목: 4건 (P0:0, P1:2, P2:2) — 1차 반복 아닌 신규 잔여 공백
- 사용자 선택: [전체 반영 후 종료]
- 반영 요약:
  - P1 #1 P11 Flyway 실행지점을 root task flywayMigrateShared 로 고정(별도 모듈 금지, 7모듈 불변)
  - P1 #2 P16 k8s 4종 세트(configmap/secret/probe/profile/port/envFrom) 확장 + P14 smoke 매트릭스 연결
  - P2 #3 P13 보안 negative 회귀(미인증 비즈니스 endpoint 401/403, S4)
  - P2 #4 P19 02-architecture Phase 4 트리/전환표 직접 갱신 필수화(ADR-0011 D1~D2 상충 해소)
- raw: .cache/codex-reviews/plan-task-impl1-gradle-multimodule-1781435509.json
- run_id: plan:20260614T111123Z:369405dd-1913-42ca-a865-78a75ac927c6:2

## 2026-06-14 20:39 — GW-2 (loop 1, PR1)
- 리뷰 run: work:20260614T113656Z:1ff7c82c-a841-4936-bf2f-019938562911:1
- 항목: 1건 (P0:0, P1:0, P2:1) → P0/P1 0건 자동 통과
- 사용자 선택: P2 적용(stray *.state.json.tmp.* 제거 + .gitignore 규칙 추가)
- 검증: ./gradlew build BUILD SUCCESSFUL (common/observability/app + 51 test 그린), 이동 파일 R100(순수 이동)
- diff: .cache/diffs/diff-task-impl1-gradle-multimodule-1781436981.patch
- raw: .cache/codex-reviews/diff-task-impl1-gradle-multimodule-1781437039.json

## 2026-06-14 21:00 — /ship --execute → /done applied (PR https://github.com/Kimgyuilli/PeakCart/pull/48)
- Commits: p1 refactor(global) 멀티모듈+이동(35파일), p2 docs(plans) 계획서+audit, +done docs(tasks)
- Push: origin/feat/task-impl1-gradle-multimodule-pr1
- PR: https://github.com/Kimgyuilli/PeakCart/pull/48
- /done: TASKS.md 구현 ① 🔲→🔄 (PR1 ✅ #48), PHASE4.md PR1 이력 추가

## 2026-06-15 — GW-2 (work loop 1, PR2a-1 common-auth 추출)
- 컨텍스트: PR2a 가 커서 PR2a-1(common-auth 추출 + JWT verify/sign 분리)로 체크포인트 분할. notification peel 은 PR2a-2 이연(SlackPort 경계 발견).
- 구현(P7/P10 부분): common-auth 모듈 + 검증 9종 이동 + JwtTokenVerifier/JwtTokenSigner 분리(JwtProvider 제거) + JwtAuthProperties 단일 계약 + TokenBlacklistLookupPort/RedisTokenBlacklistLookupAdapter(fail-closed) + JwtSecurityConfigurer + root SecurityConfig/AuthService 재배선.
- 구현 중 발견 수정(2건, systemic): (a) 라이브러리 모듈 -parameters 미적용 → Spring by-name DI 깨짐(RedisTemplate 동명빈) → subprojects 에 -parameters. (b) 라이브러리 모듈 junit-platform-launcher 부재 → 테스트 실행 불가 → subprojects testRuntimeOnly 추가.
- diff 리뷰: 1건 (P0:0, P1:1, P2:0). 사용자 선택: [반영]. → common-auth 단위 회귀 추가(RedisTokenBlacklistLookupAdapterTest 3케이스 + JwtFilterTest 4케이스).
- 검증: ./gradlew build 그린 (272 root tests + 7 common-auth tests).
- diff: .cache/diffs/diff-task-impl1-gradle-multimodule-1781462191.patch
- raw: .cache/codex-reviews/diff-task-impl1-gradle-multimodule-1781462214.json
- run_id: work:20260614T183654Z:b5c1431b-b916-4f8c-a1ad-800f9fb36a2b:1

## 2026-06-14 19:05 — /ship --execute → /done applied (PR https://github.com/Kimgyuilli/PeakCart/pull/51)
- Commits: p1 refactor(auth) 26f, p2 test(auth) 3f, p3 docs(plans), +done docs(tasks/PHASE4)
- Push: origin/feat/task-impl1-gradle-multimodule-pr2a
- PR: https://github.com/Kimgyuilli/PeakCart/pull/51 (PR2a-1 — common-auth 추출 + JWT verify/sign 분리)
- /done: TASKS.md 구현 ① 행에 PR2a-1 ✅ #51, PHASE4.md PR2a-1 이력 추가. ADR status 변경 없음(ADR-0014 이미 Accepted).
- drift note: ship 진입 시 partially_live(rename/untracked-dir collapse 아티팩트) → git add -N 로 all_live 해소(흡수 0, 커밋 0건 확인).

## 2026-06-15 — GP-2 (plan, PR2a-2 SlackPort 경계)
- 컨텍스트: PR2a-1(#51) 후 PR2a-2(notification peel) 선결 — SlackPort 경계. state 재초기화, 계획서에 "PR2a-2 실행 세부"(N1~N9) 추가.
- GP-1: SlackPort→:common 은 ADR-0011 §D2 사실정정(Update Log, 신규 ADR 아님) → auto-pass.
- 결정: N1 SlackPort+SlackNotificationClient(도메인 의존 0)→:common 횡단 인프라, N2 ADR-0011 §D2 Update Log 정정.
- attempt 1: 리뷰 2건 (P0:0, P1:1, P2:1). 사용자 선택: [전체 반영].
- 반영: P1 — N5 KafkaConfig 경계 §D2 정합(producer/consumer factory=:common 유지, notification=listener/error-handler 서비스별 배선만). P2 — N5 'P20'→'N1' 참조 정정.
- raw: .cache/codex-reviews/plan-task-impl1-gradle-multimodule-1781464320.json
- run_id: plan:20260614T191200Z:672c6dd3-e1ad-4082-96c2-070cd840b38c:1

## 2026-06-15 — GP-2 (plan loop 2, PR2a-2 SlackPort 추가 리뷰 — 사용자 요청)
- attempt 2/3. 검증 중심(1차 반영 정확성 + 잔여 P0/P1). 리뷰 2건 (P0:0, P1:2, P2:0) — 전부 신규.
- 사용자 선택: [전체 반영]
- 반영:
  - P1 #1 N2 — ADR-0011 §D2 표의 SlackPort 행 직접 정정 명시(common 횡단 인프라). §D3 allowlist 는 ADR-0014 가 Partially Supersede 소유 → ADR-0011 §D3 재편집 회피 명시.
  - P1 #2 N8 — root Kafka error handler/DLQ→SlackPort mock 호출 테스트 + root·notification 테스트 프로필 slack.webhook.url 더미/SlackPort mock 빈(부팅 실패 방지+네트워크 없는 회귀).
- raw: .cache/codex-reviews/plan-task-impl1-gradle-multimodule-1781465532.json
- run_id: plan:20260614T193212Z:a9366f4b-e2c2-4f15-af62-3aac70b77d88:2

## 2026-06-15 — GP-2 (plan loop 3, PR2a-2 SlackPort 추가 리뷰 — 사용자 요청)
- attempt 3/3 (plan 예산 상한). 잔여 공백 탐색. 리뷰 2건 (P0:0, P1:2, P2:0) — 전부 신규.
- 사용자 선택: [전체 반영]
- 반영:
  - P1 #1 N1 — SlackNotificationClient :common 이동 시 패키지 경로 결정(global.* 재배치, notification 잔류 금지) + RestClient 의존은 :common 의 spring-boot-starter-web api 로 이미 충족(추가 불필요) 명시.
  - P1 #2 N8 — root @SpringBootTest 부팅 스모크(SlackPort :common 이동 + root webhook + KafkaConfig 주입 조합 부팅 검증, 게이트 b 직결).
- raw: .cache/codex-reviews/plan-task-impl1-gradle-multimodule-1781465799.json
- run_id: plan:20260614T193639Z:7320a759-76fd-4014-9957-0ec272e1705d:3

## 2026-06-15 — GW-2 (work loop 1, PR2a-2a SlackPort→common)
- 컨텍스트: PR2a-2 가 커서 PR2a-2a(SlackPort 경계 이동 + ADR 정정)로 체크포인트. notification peel(N3~N9)은 PR2a-2b 이연.
- 구현(N1/N2): SlackPort(global.port, 경로 유지) + SlackNotificationClient(notification.infrastructure.slack→com.peekcart.global.slack) → :common git mv. ADR-0011 §D2 표 SlackPort 행 정정(서비스전속→common) + Update Log 추가.
- 검증: ./gradlew build 그린 (272 tests, SlackPort 경로 유지로 8개 사용처 무변경).
- diff 리뷰: 1건 (P0:0, P1:0, P2:1) → 자동 통과. P2(계획 본문 옛 SlackPort→Notification 전속 서술 모순) 반영: line 33/66/99/136 정정.
- diff: .cache/diffs/diff-task-impl1-gradle-multimodule-1781506173.patch
- raw: .cache/codex-reviews/diff-task-impl1-gradle-multimodule-1781506186.json
- run_id: work:20260615T064946Z:f529ab1a-9469-4669-880b-f0bffa29e63a:1
