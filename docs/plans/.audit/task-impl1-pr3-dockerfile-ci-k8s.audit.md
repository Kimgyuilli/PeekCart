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
