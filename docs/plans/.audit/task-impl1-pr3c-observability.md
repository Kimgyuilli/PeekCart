## 2026-06-21 21:08 — GP-2 (loop 1)
- 리뷰 항목: 5건 (P0:0, P1:3, P2:2)
- 사용자 선택: [2] 전체 반영
- P1 #1 scrape-absent: 단일 absent() rule → 5서비스 equality matcher rule(by-clause 불가)
- P1 #2 ADR-0015 범위: "§Decision 뒤집기"(오류) → "현-위치 컬럼/D5-V1·V2 전제/S5 경로 정정 + per-service 의도 비준". §Decision per-service owner 컬럼은 이미 결정제·유효(코드 재검증 확인)
- P1 #3 PromQL syntax: D5-V6 요구(syntax+series 가정) 충족 위해 P5/P6 에 promtool/parser 추가
- P2 #4 lint coverage: by (application) 강제·단일 equality 실패 처리(false-green 차단)
- P2 #5 sweep: (application|service) 양쪽 + escaped quote 매치(service=\"peekcart\" 잔존 차단)
- 신규 blindspot: PLAN-BLINDSPOTS B11(escaped-quote/형제라벨 sweep false-green)
- raw: .cache/codex-reviews/plan-task-impl1-pr3c-observability-1781944643.json
- run_id: plan:20260620T083653Z:e963ea01-8a2a-412d-a2b0-5262d983e9c1:1

## 2026-06-21 21:17 — GP-2 (loop 2)
- 리뷰 항목: 3건 (P0:0, P1:1, P2:2) — 수렴 5→3
- 사용자 선택: [2] 전체 반영 후 종료
- P1 #1: by(application) 단독 허용 → 5서비스 정확일치 regex application=~"..." + by(application) 둘 다 강제(무필터/단일 equality 실패). ADR-0009:50 정합
- P2 #2: scrape-absent ground truth = SM selector app → 매칭 K8s Service metadata.name 집합(up{service=} 의미 정정)
- P2 #3: PromQL syntax fallback(balance) 약화 → promtool 필수·parser 실패 시 중단·balance 는 보조진단만
- 1차 5건은 잘 닫힘 확인(재나열 없음)
- raw: .cache/codex-reviews/plan-task-impl1-pr3c-observability-1782043849.json
- run_id: plan:20260621T121022Z:e963ea01-8a2a-412d-a2b0-5262d983e9c1:2

## 2026-06-21 21:42 — GW-2 (loop 1)
- 리뷰 run: work:20260621T123441Z:73cc3be4-45ed-4718-a58c-fb44bf9cae24:1
- 항목: 4건 (P0:0, P1:3, P2:1)
- 사용자 선택: [2] 전체 반영
- P1 #1: dashboard $application 변수 source=label_values(up{},application) 런타임 버그(up 에 application 라벨 없음) → custom 5서비스 고정
- P1 #2: promql-lint 필수 alert 존재 미검증(rule 삭제 시 false-green) → seen_uids vs REQUIRED_UIDS(8) 비교
- P1 #3: scrape-absent lint namespace 미검사 → namespace="peekcart" matcher 검사 추가
- P2 #4: lint ground truth=glob 결과(정본 아님) → EXPECTED_SERVICES(5) 정본 고정 + 발견집합 일치 선검증(ssot+promql)
- negative test 4종 통과(단일 equality/집합 불일치/syntax/필수 uid/namespace 부재 전부 exit≠0)
- diff: .cache/diffs/diff-task-impl1-pr3c-observability-1782045199.patch
- raw: .cache/codex-reviews/diff-task-impl1-pr3c-observability-1782045306.json

## 2026-06-21 21:50 — /done applied (PR https://github.com/Kimgyuilli/PeakCart/pull/68)
- TASKS.md: 구현 ① PR3c ✅ + 구현 ① 전체 종료(이미지#66·k8s#67·관측성#68)
- PHASE4.md: PR3c 엔트리(P1~P6·핵심결정·negative test 6종·프로세스)
- ADR-0015 Accepted(신규) · ADR-0009 Partially Superseded(p3 커밋에 반영)
- 커밋: 5개(feat observability·chore observability·docs adr·docs plan·docs progress)
- 부산물: PLAN-BLINDSPOTS B11

