# task-impl1-pr3-dockerfile-ci-k8s — audit

## 2026-06-19 13:54 — GP-1 (ADR 선행 판단)
- 신호: 인프라 재구성(k8s 매니페스트·이미지) — 대부분 ADR-0004/0005/0006/0011 기결정 → 신규 ADR 불필요.
- 예외: ADR-0009 §Decision `application=peekcart` 단일값 불변식(D5-V2 lint 강제)이 per-service 태그로 깨짐.
- 사용자 게이트: [PR3 내 ADR 개정으로 편입] (Recommended) — proceed, 신규 ADR-0015 + ADR-0009 Partially Superseded 로 처리(P12).
- gate event: `.cache/.../_gate_events.tsv` GP-1 proceed/amend-adr0009-in-pr3 (risk=low)

## 2026-06-19 13:54 — GP-2 (loop 1)
- 리뷰 항목: 5건 (P0:0, P1:2, P2:3)
- 사용자 선택: [2] 전체 반영 (5건 모두 파일·라인 인용으로 코드/ADR 확증)
  - #1(P1, test) PR3a smoke 가 비-order 서비스에서 실패 위험 — `docker-health-smoke.sh:40` 가 인프라(빈 MySQL)만 올리고 앱 즉시 실행하나, 비-order 서비스는 Flyway disabled+JPA validate(런타임 마이그레이터 order-service 단독, `order-service/.../application.yml:10`) → 빈 DB validate 실패. → P2 에 smoke 전 `flywayMigrateShared`(compose MySQL) 선행 훅, `docker-health-smoke.sh` 마이그레이션 훅(제네릭 on/off), §5 PR3a 검증에 비-order 200 확인.
  - #2(P1, architecture) P12 "ADR-0009 개정"이 ADR 운영 규칙(README §8/§14 — 본문 immutable·결정 변경 시 신규 ADR·부분 무효화 `Partially Superseded by`)과 충돌. → P12 를 "신규 ADR-0015(observability per-service contract) 작성 + ADR-0009 Status=Partially Superseded(본문 불변) + README 인덱스 갱신"으로 교체. §2 B2·헤더·§4 신규/수정(0015 신규·0009 상태줄만)·§6 갱신.
  - #3(P2, test) P13 "alert uid 매트릭스 per-service" 가 P10 by-clause(rule 복제 금지, ADR-0009:50) 와 모순 — 구현자 5×UID 복제 해석 여지. → P13 을 "rule UID 별 by(application)/by(service) 그룹핑 + alert label `$labels.application`/`$labels.service` 존재 + 값 ∈ 5서비스명 검증, 복제 매트릭스 금지"로 수정.
  - #4(P2, security) secret 분리 누락 — 현 `secret.yml:13` 에 `SLACK_WEBHOOK_URL` 존재·order 실사용(`order-service/.../application.yml:70`), product/payment/notification 도 SlackPort 경로. P5 가 Toss+DB/JWT 만 언급. → P5 에 서비스별 secret 키 표(DB/JWT/SLACK/TOSS 필수·기본값·불필요 + `@ConditionalOnProperty` 근거) 추가.
  - #5(P2, architecture) cold-start ordering "택1 /work 확정"이 B4(구체 메커니즘 강제, PLAN-BLINDSPOTS:36) 미충족. Phase Exit "모든 서비스 정상 배포"가 직접 의존(`07-roadmap:90`). → P4 에 확정 메커니즘: 비-order 4 서비스 deployment `initContainers` 가 `order-service` `/actuator/health/readiness` 200 폴링 후 앱 시작(order-service readiness ⟹ 마이그레이션 완료). order-service 는 gate 없음. §5 PR3b·트레이드오프·§6 갱신.
- 검증: hpx_plan_lint OK (반영 후 재확인, P1~P14 연속)
- raw: .cache/codex-reviews/plan-task-impl1-pr3-dockerfile-ci-k8s-1781877270.json
- run_id: plan:20260619T135405Z:d9805759-35b4-4103-b7b6-69776ce35998:1
- tokens: 255,032

## 2026-06-20 08:59 — GW-2 (work loop 1, PR3a)
- 리뷰 run: work:20260619T235129Z:113b83d3-6f8c-4fee-a59f-0e6b182552d1:1 (single, diff 524줄 — 코드 ~250 + plan/audit 문서)
- 항목: 3건 (P0:0, P1:1, P2:2) — 전체 반영(3건 모두 파일·라인 확증)
  - #1(P1 bug) image-contract-lint 가 전환기에 D-015 를 false-green 으로 보고 — k8s 는 여전히 peekcart:latest pull, CI 는 peekcart-<svc> 빌드, 5서비스 전부 skip → manifest-checked 0/5 인데 OK. → checked==0 을 명시적 실패로, IMAGE_CONTRACT_TRANSITION=1 일 때만 "SUSPENDED" 통과(ci.yml lint step env set, PR3b 에서 제거). 두 모드 검증.
  - #2(P2 security) packages:write 과다 — build job 은 패키지 작업 없는데 write 보유. → build job 권한 contents:read 만. images job 은 push 스텝(main 한정) 전용임을 주석 명시(job 분리는 docker build 중복이라 회피).
  - #3(P2 bug) flyway/flyway:11 mutable tag + flywayMigrateShared 계약 드리프트. → flyway 이미지 11.7.2@sha256:8ace7d9... digest 고정(repo flyway 11.7.2 정합·L-016a). flywayMigrateShared 깨짐(gradle flyway 플러그인 mysql DB 플러그인 미해석)·smoke 가 Docker flyway 정본 임을 smoke 스크립트 주석에 명시.
- 구현 이탈(계획 P2): 계획은 smoke 전 flywayMigrateShared 훅을 적었으나 그 gradle 태스크가 현재 깨짐 → 공식 flyway Docker 이미지로 공유 스키마 적용(더 견고·런타임 Boot Flyway 와 동일 산출). **후속 부채: flywayMigrateShared 수복(또는 폐기)**.
- 검증: 5개 서비스 docker build 전부 성공 · notification-service smoke(profile k8s·flyway 이미지 V1~V12 적용·/actuator/health 200) 통과 · image-contract-lint 두 모드 · kustomize-namespace·servicemonitor-selector lint 그린.
- diff: .cache/diffs/diff-task-impl1-pr3-dockerfile-ci-k8s-1781913045.patch
- raw: .cache/codex-reviews/diff-task-impl1-pr3-dockerfile-ci-k8s-1781913120.json
- run_id: work:20260619T235129Z:113b83d3-6f8c-4fee-a59f-0e6b182552d1:1
- tokens: 122,603

## 2026-06-20 09:08 — GW-2 (work loop 2, PR3a · 사용자 재리뷰 요청)
- 리뷰 run: work:20260620T000119Z:113b83d3-6f8c-4fee-a59f-0e6b182552d1:2 (single) — 1차 반영분 재검토
- 항목: 2건 (P0:0, P1:1, P2:1) — 전체 반영(둘 다 1차 반영의 잔여 구멍)
  - #1(P1 bug) image-contract-lint 부분-매니페스트 false-green 잔존 — 1차는 checked==0 만 게이트 → PR3b 에서 1서비스만 생기면 1/5 인데 exit 0(나머지 미검증), gke entry 누락도 위반 미집계. → checked < 전체면 flag 있을 때만 PARTIAL/SUSPENDED, 없으면 실패. 매니페스트 존재 서비스는 base+gke **둘 다** 필수(한쪽 누락=위반). 두 모드 검증.
  - #2(P2 security) packages:write 가 images job 전체(PR build/smoke 포함)에 부여 — 권한은 job 단위라 주석으로 못 좁힘. → ci.yml 을 images(build/smoke·contents:read)/publish(main 한정·packages:write) **job 분리**. smoke 통과 이미지를 docker save→artifact→load 로 publish 에 전달(재빌드/divergence 0). smoke 가 flyway Docker 이미지로 전환돼 gradle/Java 불요 → images job 에서 setup-java 제거.
- 1차 #3(flyway digest) 해소 확인됨(Codex).
- 검증: image-contract-lint 두 모드(0/5 차단·flag SUSPENDED) · ci.yml YAML valid(3 job: images read / publish main·write) · smoke gradle 비의존.
- diff: .cache/diffs/diff-task-impl1-pr3-dockerfile-ci-k8s-1781913667.patch
- raw: .cache/codex-reviews/diff-task-impl1-pr3-dockerfile-ci-k8s-1781913707.json
- run_id: work:20260620T000119Z:113b83d3-6f8c-4fee-a59f-0e6b182552d1:2
- tokens: 177,101

## 2026-06-20 09:16 — GW-2 (work loop 3, PR3a · 사용자 재리뷰 요청)
- 리뷰 run: work:20260620T000846Z:113b83d3-6f8c-4fee-a59f-0e6b182552d1:3 (single) — 2차 반영분 재검토. (첫 호출은 프롬프트 heredoc 의 \${matrix.service} bad substitution 으로 실패→프롬프트 수정 재실행, codex 미실행이라 attempt 재증가 없음.)
- 항목: 2건 (P0:0, P1:1, P2:1) — 전체 반영(둘 다 새 결함, 2차 반영의 정확성은 Codex 확인)
  - #1(P1 bug) image-contract-lint 의 "전체"가 canonical 5 가 아니라 CI matrix 추출 길이 → images matrix 에서 서비스가 빠지면 4/4 full-green(서비스집합 축소 false-green), publish matrix 드리프트도 미감지. → lint 에 CANONICAL_SERVICES 5 고정(ground-truth), images·publish matrix 를 각각 파싱해 canonical 과 정확히 일치 검증(불일치=위반). checked 게이트도 canonical 5 기준.
  - #2(P2 bug) digest 산출 미흡 — publish push 후 docker inspect RepoDigests || n/a 로 실패해도 green. L-016a/D-016 은 push 후 digest 산출 요구. → docker push 출력에서 sha256 digest 캡처, 비면 exit 1(::error::). 중복 echo 제거.
- 검증: lint(canonical matrix 일치·0/5 flag SUSPENDED·flag 없이 차단) · ci.yml YAML valid(digest-fail 가드 존재·images contents:read) · 3 job 구조.
- diff: .cache/diffs/diff-task-impl1-pr3-dockerfile-ci-k8s-1781914126.patch
- raw: .cache/codex-reviews/diff-task-impl1-pr3-dockerfile-ci-k8s-1781914220.json
- run_id: work:20260620T000846Z:113b83d3-6f8c-4fee-a59f-0e6b182552d1:3
- tokens: 171,184
- 비고: attempts_by_command.work=3 (권장 cap 도달). 4차 리뷰는 명시 확인 필요(§7-6).

## 2026-06-20 09:23 — /done applied (PR https://github.com/Kimgyuilli/PeekCart/pull/66)
- TASKS.md 구현 ① 행에 PR3a ✅ [#66] 추가 (구현 ① 은 PR3b/3c 미완으로 🔄 유지)
- PHASE4.md: PR3a 이력 엔트리(P1·P2·P3·핵심결정·검증·후속부채·다음 PR3b/3c)
- GS-1: [MISS] ADR-0015 ignore-with-reason (PR3c 작성 예정 forward reference)
- ship: 2 커밋(chore(ci) / docs(plan)) + docs(progress) 1 커밋, push, PR #66

## 2026-06-20 14:30 — GP-2 (plan loop 1, PR3b 착수 전 재리뷰)
- 리뷰 run: plan:20260620T052322Z:b3eae52b-7cfd-4541-a160-b67acec16b86:1 — PR3b(P4~P9) 준비 상태. order-service 런타임 Flyway 마이그레이터 전제는 코드 일치 확인됨(Codex).
- 리뷰 항목: 7건 (P0:0, P1:3, P2:4) — 전부 코드 grep 근거, 노이즈 0.
- 사용자 선택: [전체 반영] (권고대로) + HPA 결정 게이트 → **order-service만**(로드맵 §16 정합).
  - #1(P1) ServiceMonitor PR3c→**PR3b 당김**(ADR-0006 소속 불변식·P9 이동·selector-lint 실질검증 P8).
  - #2(P1) `IMAGE_CONTRACT_TRANSITION` 제거→**full 5/5** P8 명시.
  - #3(P1) Secret 표 코드정정 — SlackPort 를 notification/product/order/payment **4서비스 생성자 주입**(no-op fallback 부재) → webhook 미설정 시 4서비스 부팅 실패. 처분: (b·권고) :common `@ConditionalOnMissingBean` no-op SlackPort. Toss k8s 프로파일 기본값 제거 fail-fast.
  - #4(P2) HPA **order-service 단일**(5서비스 균일 기각·로드맵 §16 What 정합·문서 무수정).
  - #5(P2) minikube/gke overlay **per-service patch 확정**(components 기각·B4).
  - #6(P2) flywayMigrateShared 후속부채 명문화 — k8s cold-start=order-service Boot Flyway 정본·smoke=공식 flyway 이미지·root task 재사용 금지.
  - #7(P2) `PEEKCART_CACHE_ENABLED` **product 전용 ConfigMap 유지**(D-002 🔄 살아있음).
- 반영 위치: 헤더 PR분할/B1표 row4/§2 HPA 트레이드오프/P4·P5·P6·P7·P8·P9/§4 영향파일/§5 검증/§6 완료조건.
- 부산물: PLAN-BLINDSPOTS **B6 확장**(역방향 함정 — `@ConditionalOnProperty` 빈을 생성자 주입하는 서비스 다수면 secret 미공급 시 부팅 실패; no-op fallback vs 전 서비스 주입).
- raw: .cache/codex-reviews/plan-task-impl1-pr3-dockerfile-ci-k8s-1781933041.json
- run_id: plan:20260620T052322Z:b3eae52b-7cfd-4541-a160-b67acec16b86:1
- tokens: 134,998

## 2026-06-20 14:46 — GP-2 (plan loop 2, loop1 반영 정확성 재리뷰·사용자 재리뷰 요청)
- 리뷰 run: plan:20260620T054349Z:b3eae52b-7cfd-4541-a160-b67acec16b86:2
- 리뷰 항목: 4건 (P0:0, P1:2, P2:2) — 전부 loop1 반영분의 정확성 결함, 노이즈 0. 전체 반영.
  - #1(P1) `servicemonitor-selector-lint` 가 ServiceMonitor 0개면 vacuous-green → P8 "실질검증" 거짓. 처분: lint 에 count==5+canonical name-set 강제 보강 + P9(매니페스트)→P8(검증) 실행순서 명시. §4 에서 해당 lint 를 불변→수정 이동.
  - #2(P1) loop1 의 no-op SlackPort fallback 권고에 구멍 — 전역 @ConditionalOnMissingBean 은 notification webhook 누락 시에도 주입돼 알림 silent 유실. 처분: fallback 을 property-gate(`slack.noop-fallback.enabled`)로 product/order/payment 한정, notification 은 off→fail-fast. 검증 테스트 2종 추가.
  - #3(P2) P2·트레이드오프 줄이 아직 flywayMigrateShared 선행 훅으로 적힘(§4/§5 와 모순) → 공식 flyway 이미지로 통일, root task 후속부채로만.
  - #4(P2) §1 성공기준(5)가 "ADR-0009 §Decision 명문화"로 읽혀 본문 수정처럼 보임 → "ADR-0015 §Decision 명문화 + ADR-0009 Status=Partially Superseded"로 정정.
- 반영 위치: 헤더 loop2 줄/§1 성공기준/§2 트레이드오프·P2/P5 SLACK 행/P8·P9/§4 B1표 row12·불변·수정/§5 PR3b.
- raw: .cache/codex-reviews/plan-task-impl1-pr3-dockerfile-ci-k8s-1781934259.json
- run_id: plan:20260620T054349Z:b3eae52b-7cfd-4541-a160-b67acec16b86:2
- tokens: 127,849
- 비고: attempts_by_command.plan=2. 사용자가 loop3 재리뷰 요청 → attempt 3(권장 cap 도달).

## 2026-06-20 15:06 — GP-2 (plan loop 3, 수렴 확인·사용자 재리뷰 요청)
- 리뷰 run: plan:20260620T060324Z:b3eae52b-7cfd-4541-a160-b67acec16b86:3
- 리뷰 항목: 2건 (P0:0, P1:1, P2:1) — loop2 반영분의 잔여 결함. 전체 반영.
  - #1(P1) loop2 의 no-op property-gate 가 여전히 안 닫힘 — 4서비스 base yml `slack.webhook.url: ${SLACK_WEBHOOK_URL:placeholder}` 기본값 + presence-based `@ConditionalOnProperty(name=...)`(havingValue 없음) → property 항상 존재 → real bean 무조건 등록 → notification fail-fast·product/order/payment no-op 둘 다 깨짐. 처분: base yml placeholder 기본값 제거 + notification(no-default fail-fast)/product·order·payment(`slack.noop-fallback.enabled=true` no-op)·real↔no-op 명시 property 상호배타. local/test 동반 처분. 코드 grep 확인(4 yml line 인용).
  - #2(P2) D-016 P14 PR 경계 미정(PR3b/3c/후속 모호) → PR3b 배치(gke images[] AR rewrite 동반)+§5 dry-run 검증+§6 머지조건 명시.
- 반영 위치: 헤더 loop3 줄/P5 SLACK 행/§4 신규·수정/P14·§5·§6/PLAN-BLINDSPOTS B6 함정².
- 부산물: PLAN-BLINDSPOTS **B6 함정²**(presence-based @ConditionalOnProperty + base 기본값 → 조건 항상 true → fail-fast/no-op 게이팅 붕괴; 기본값 제거 선결·명시 property 상호배타).
- raw: .cache/codex-reviews/plan-task-impl1-pr3-dockerfile-ci-k8s-1781935430.json
- run_id: plan:20260620T060324Z:b3eae52b-7cfd-4541-a160-b67acec16b86:3
- tokens: 111,933
- 비고: **attempts_by_command.plan=3 (권장 cap 도달)**. 추가 loop4 는 §7-6 명시 확인 필요. P1 추세 3→2→2→... 수렴 중이나 loop3 도 P1 1건(이전 반영의 잔여) — Slack 게이팅이 반복 핫스팟.

## 2026-06-20 15:48 — GW-2 (work loop 1, PR3b 구현 diff 리뷰)
- 리뷰 run: work:20260620T064507Z:f746e5a3-8d31-4f9d-a942-5f739d4b5ff9:1 (single, diff 1954줄) — kustomize 양 overlay·namespace-lint·image-contract full 5/5 통과 확인됨(Codex).
- 항목: 5건 (P0:1, P1:3, P2:1) — 전부 유효, 전체 반영.
  - #1(P0) **MySQL 이 삭제된 peekcart-secret 참조** → CreateContainerConfigError, 클러스터 부팅 불가(B1 스윕이 infra→secret 간선 누락). → `infra/mysql/secret.yml`(mysql-secret) 신설 + mysql.yml secretKeyRef + base kustomization.
  - #2(P1) notification base secret/yml 의 SLACK placeholder 가 k8s fail-fast 무력화 → committed secret 에서 `SLACK_WEBHOOK_URL` 제거(operator/external 주입). base application.yml 기본값은 테스트/local 유지.
  - #3(P1) payment base secret 의 TOSS placeholder 동일 → committed secret 에서 `TOSS_*` 제거.
  - #4(P1) promote-images digest 미결합·gke newTag latest mutable → 승격 후 `kustomize edit set image ...@digest` 고정 명령 출력 + README operator pin 절차. full lint-digest 강제는 후속 명시.
  - #5(P2) promote-images.sh --help sed 범위 오류로 코드 출력 → usage() heredoc 교체.
- **부수 수정(연쇄)**: #2/#3 로 smoke(profile k8s, SLACK/TOSS no-default fail-fast)가 깨지므로 `docker-health-smoke.sh` 가 SLACK_WEBHOOK_URL/TOSS_* dummy 를 **런타임 주입**(operator secret 시뮬레이션, 렌더 manifest 엔 안 샘).
- 검증: kustomize 양 overlay 렌더·peekcart-secret dangling 0·SLACK/TOSS placeholder 렌더 0·lint 3종·promote help/dry-run·**notification+payment 이미지 build+smoke green**(fail-fast+dummy 주입 e2e)·`./gradlew build` 전체 그린(구현 시점).
- diff: .cache/diffs/diff-task-impl1-pr3-dockerfile-ci-k8s-1781937809.patch
- raw: .cache/codex-reviews/diff-task-impl1-pr3-dockerfile-ci-k8s-1781937937.json
- run_id: work:20260620T064507Z:f746e5a3-8d31-4f9d-a942-5f739d4b5ff9:1
- tokens: 125,224
- 부산물: B1 스윕에 **infra→app-secret 간선**(인프라가 앱 secret 의 키를 참조) 누락 패턴 — 단일→per-service 분해 시 공유 secret 을 쪼개면 인프라(MySQL 등)의 secretKeyRef 가 dangling. → **PLAN-BLINDSPOTS B1b 에 반영**(인프라→공유리소스 이름 간선).

## 2026-06-20 16:00 — GW-2 (work loop 2, 1차 반영 수렴 확인) → work.done
- 리뷰 run: work:20260620T070019Z:f746e5a3-8d31-4f9d-a942-5f739d4b5ff9:2 (single, diff 2084줄)
- 항목: **0건 — 수렴**. Codex 확인: mysql-secret 분리 정합(redis/kafka 도 peekcart-secret 참조 없음·렌더 dangling 0)·SLACK/TOSS committed 제거가 fail-fast 실효+렌더 placeholder/stub 누출 0·smoke dummy 런타임 주입 ADR-0007 정합·promote/lint 통과.
- 검증: kustomize 양 overlay·lint 3종·promote help/dry-run·(1차에서 notification+payment build+smoke green·gradle build 전체 green).
- diff: .cache/diffs/diff-task-impl1-pr3-dockerfile-ci-k8s-1781938819.patch
- raw: .cache/codex-reviews/diff-task-impl1-pr3-dockerfile-ci-k8s-1781938851.json
- run_id: work:20260620T070019Z:f746e5a3-8d31-4f9d-a942-5f739d4b5ff9:2
- tokens: 168,469
- stage → **work.done** (work_attempts=2). 다음: /ship (커밋/PR/done).
