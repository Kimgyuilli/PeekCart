# task-d031-ci-graph-parallelization — audit

## 2026-09-22 — 계획 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 등급: M (계획 리뷰는 M 에서 원래 `해당 없음`. 게이트도 `blocked`/file 로 이중 차단)
- 코드 검증(§2): V1~V9 수행, 계획서 §배경 표에 기록
- 범위 변화: **늘어남.** V5 에서 `publish: needs: images` 확인 —
  `images` 의 needs 만 완화하면 테스트 실패 상태에서 GHCR 푸시가 가능해진다.
  `publish` 에 `gate` 직접 연결(P2)과 그 성질을 고정하는 lint(P3/P4)를 편입.
  이 발견으로 등급이 S 에서 M 으로 상향.

## 2026-09-23 — /ship
- PR: https://github.com/Kimgyuilli/PeakCart/pull/135
- preflight: ok · consistency precheck: ok (warnings 0) · review_health: ok
- diff 리뷰: 의도적 생략(file: 사용자 지시, .cache/codex-off)
- writing-lint: 커밋 6건 통과. 본문은 볼드 밀도 초과 3건을 잡아 `## How` 의 볼드
  소제목 4개를 `###` 제목으로 승격. 제목은 `<type>(<scope>):` 형식 위반을 잡아 수정
- 갱신: docs/TASKS.md (D-031 완료 + 범위 변화 기록) · docs/progress/PHASE5.md (작업 이력)
- 미충족 이월: V-7(gha scope 실효) · V-9(실측 벽시계) — 머지 후 main push run 에서 확인

## 2026-09-22 — 머지 후 실측 (V-7 / V-9)
- PR #135 머지(b617973). PR run 35744585147 과 main push run 35747847666 측정
- V-9: 조건1 충족(images 가 test 완료보다 15분39초 앞서 시작) · 조건2 미달(21분51초, 목표 20분)
- 효과: PR run 36분51초에서 21분51초(-40.7%), main push 39분18초에서 21분20초(-45.7%)
- 릴리스 게이트 실작동 확인 — gate 종료 후 publish 6개 시작. 머지 전에는 불가능했던 검증
- **V-7 미달** — CACHED 0스텝. cache-to export 비용만 내고 images 가 느려졌다
  (104~173초에서 166~257초). 원인 후보 둘이 갈리지 않아 D-034 로 승격
- 예측 실패 기록: 로컬 --load 재빌드 6.25초/CACHED 29스텝을 캐시 효과의 방증으로 적었으나
  BuildKit 로컬 캐시였고 type=gha 거동을 예측하지 못했다. 계획서 §V-7 재판정에 명시

## 2026-09-22 — D-034 판정 (V-7 확정)
- 판정: 유지. main push run 35751377973 에서 CACHED 30스텝, images 86~130초
- 원인은 ①(GH Actions 캐시 ref 격리). PR 브랜치 캐시를 main push 가 못 읽어 첫 run 이 콜드
- 베이스라인(104~173초) 대비로도 이득이라 cache-from/cache-to 를 되돌리지 않는다
- **측정 실패 2건 기록**:
  1. 대기 루프 조건이 틀렸다 — conclusion 이 null 이 아니라 빈 문자열이라
     select(.!=null) 이 미완료 잡을 완료로 셌고 루프가 조기 종료했다.
     status=="completed" 로 고쳤다
  2. 그 상태에서 gh run view --log 를 받아 CACHED 0 으로 보고했다. 실제로는 로그가 아니라
     "run is still in progress" 안내문 한 줄이었다. 잡 단위 API
     (actions/jobs/<id>/logs)로 재측정했다. "0건"과 "측정 안 됨"을 구분하지 못한 실패다
