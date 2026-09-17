# audit — task-d002-bc-session

## 2026-09-16 — 계획 리뷰 라운드 1 (미완료)

- **Codex 리뷰 호출 실패** — usage limit. `ERROR: You've hit your usage limit ... try again at 5:01 AM`
- 항목: 0건 (리뷰 미수행) → **"P0/P1 = 0" 주장 없음**
- raw: `.cache/codex-reviews/plan-task-d002-bc-session-1789501628.json` (빈 파일) ·
  stderr: 동 경로 `.stderr`
- 대신 수행: 계획서 §2 의 V1~V9 는 **작성자가 직접 파일을 열어 확인**했다(반증 포함 — V3/V5/V6/V7/V8).
  외부 리뷰로 교차검증되지 않은 상태로 남는다.
- **사용자 지시**: Codex 리뷰 호출하지 않음(재시도 금지) → 이 계획은 **외부 리뷰 없이 확정**한다.

## 2026-09-16 — 구현 (P1~P3 준비 단계)

- **Codex diff 리뷰 미호출** (사용자 지시) → **"P0/P1 = 0" 주장 없음**
- 구현: P1~P3 완료. P4~P13 은 GKE 클러스터 기동이 필요해 별도 세션.
- **구현 중 뒤집힌 전제 3건** — 계획서 §2 에 V10/V11/V12 로 반영:
  - **V10** D-002a 가 배제한 CPU 는 product-service 의 것이었다. `gke-d002a` 는 infra 를 patch 하지
    않아(`patches` 1건) MySQL 은 내내 `limits.cpu: 500m` 이었고 그 축은 미분리로 남아 있었다.
    → P5 의 1순위가 InnoDB 내부에서 **MySQL 자신의 CPU 상한**으로 바뀜.
  - **V11** 상품 SQL 직접 시드는 `product.updated` 를 발행하지 않아 `product_price_cache` 가 비고
    주문이 전부 `ORD-007` 로 죽는다 → 시드를 **admin API 경유 + 캐시 전파 게이트**로 교체.
  - **V12** 로그인 라우트만 **IP 키 10 req/s** → k6 `setup()` 순차 + 150ms 간격.
- 초안 정정: P1 쿼터 **1.85 → 3.60 vCPU**(base 250m 로 계산했으나 gke 프로파일은 서비스당 500m).
- 검증:
  - lint **18/18 PASS** (`scripts/*-lint.sh` 전량)
  - overlay 렌더 성공 — Deployment 8 / Service 9 / CPU requests 3,600m 실측
  - SQL **실제 MySQL 8 + 실제 Flyway 스키마**(user 3 · product 8 · order 10 테이블)에 적용해 통과
  - `d002bc-verify.sql` **false-green 검사**: 정합 상태에서 위반 0건 → 오버셀링(-7)·음수재고 주입 후
    `diff=-7`, `negative_stock_rows=1` 로 **검출 확인**
  - `./gradlew test` **미실행** — JVM 소스/리소스/빌드스크립트 변경 0건(k8s yaml · sql · k6 js · md 만)

## 2026-09-16 — /ship

- PR: https://github.com/Kimgyuilli/PeakCart/pull/119 (머지하지 않음)
- precheck: **ok** (warnings 0)
- 커밋 2개: `chore(d002bc)` 하네스 18파일 / `docs(d002bc)` 계획서·audit·runbook
- 체크박스 게이트: P1~P3 만 `[x]`. **P4~P13 은 의도적 미완**(GKE 실측 세션) — 사용자 승인 후 진행
- 갱신: `docs/TASKS.md` 구현표 D-002 행 + 부채 D-002 행(V10 반영) · `docs/progress/PHASE4.md` 이력
- **D-002 는 ✅ 로 닫지 않았다** — 하네스 단계만 완료

## 2026-09-17 — GKE 측정 세션 (P4~P13)

- **Codex 리뷰 미호출**(사용자 지시) → **"P0/P1 = 0" 주장 없음**
- 수행: P4~P13. 명제 N1~N4 전부 거짓 → **D-002 종결**
- 결과: 천장 3겹 분리(① MySQL CPU ×2.94 · ② outbox 폴링 ×4.8 · ③ 스케줄러 스레드 1개 발행 정지) ·
  D-002c 는 replicas=1 구조적 불가 / replicas=3 에서 V5 실증(PRD-004=0 ↔ 낙관락 충돌 7) · P10 통과
- **뒤집힌 전제**(계획 대비): D-002c 의 관측 지표 설계가 전제한 "consumer 경합" 이 성립하지 않았다 —
  consumer LAG 이 0 이고 예약 소비 스레드가 시스템 전체에 1개였다. replicas 를 올려야 측정 가능했다.
- 미충족: spread 대조군 오염 · 300rps 미달성 · P6 되돌리기 미수행 · 외부 연동 미검증
- 파생: D-021~D-025 등록 · ADR-0004 본문 정정 · `cleanup.sh` 회수 로직 수정(실전 검증됨)
- 자원: `cleanup.sh` 잔여 0 검증 통과(exit 0), orphan PD 3개 실제 삭제
