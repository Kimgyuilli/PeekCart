# PeekCart — Task 관리

> 현행 작업을 **PR 단위**로 추적한다. PR 1개 = 부채/기능 1묶음.
> 상태: `🔲 대기` / `🔄 진행 중` / `✅ 완료` / `⏸ 보류`

## 문서 맵

| 대상 | 경로 |
|---|---|
| Phase 1~3 task 이력 (아카이브) | `docs/progress/TASKS-archive-phase1-3.md` |
| Phase 4 task 이력 (아카이브) | `docs/progress/TASKS-archive-phase4.md` |
| 완료된 D- 행 | `docs/progress/TASKS-done.md` |
| Phase 4 설계·실행 로드맵 (ADR 시퀀싱·구현 순서) | `docs/progress/phase4-design-roadmap.md` |
| 진입 전 부채 해소 로드맵 (버킷 1 완결, 버킷 2/3 이관·게이트) | `docs/progress/phase4-prep-debt-roadmap.md` |
| 부채 후보 분류·승격 매핑 (L-001~L-022) | `docs/progress/phase4-prep-debt-roadmap.md §2~5` |
| Phase별 작업 이력 | `docs/progress/PHASE1.md` · `PHASE2.md` · `PHASE3.md` · `PHASE4.md` · `PHASE5.md` |

---

## 현재 단계: Phase 5 — 수요 기반 (로드맵 없음, 2026-09-22~, [#134](https://github.com/Kimgyuilli/PeakCart/pull/134))

> **Phase 1~4 로 사전 로드맵은 끝났다.** Phase 5 는 순서표가 없다. 필요하다고 느낀
> 시점에 항목을 추가하고 그때 착수한다.
>
> **추적 축은 아래 §개발 부채 / 작업 표 하나다.** 기존 D- 번호를 그대로 이어 쓴다
> (다음 번호 = 이 표와 `docs/progress/TASKS-done.md` 의 최대 번호 + 1). "부채 해소"와 "새로 하고 싶은 것"을 표에서 구분하지 않는 것은
> 의도다 — 수요 기반에서는 둘 다 *지금 필요하다고 판단한 작업* 이라는 같은 성격이고,
> 표를 나누면 어느 쪽에 적을지부터 정해야 해서 추가 비용이 생긴다.
>
> **항목 추가 규칙**: `영역` · `요약`(왜 필요한지가 요약에 들어간다) · `묶음`(발견 맥락 —
> 세션/PR/문서) · `상태`. 로드맵이 없으므로 **동기를 요약에 적어 두지 않으면 나중에
> 왜 적었는지 복원할 수 없다.** 착수 시 `/plan`, 머지 시 `/done`.
>
> **단계 종결 조건 없음.** Phase 4 처럼 순서표 소진으로 닫히지 않는다. 열린 표가 곧 현황이다.
>
> 열린 항목은 아래 표에 있다. 완료된 행은 `docs/progress/TASKS-done.md` 로 옮긴다.

지난 단계: Phase 4 는 `docs/progress/TASKS-archive-phase4.md` ·
Phase 1~3 은 `docs/progress/TASKS-archive-phase1-3.md`.

---

## 개발 부채 / 작업 (Tech Debt & Backlog)

> 여기서는 **열린 행**만 추적한다. 완료된 행은 `docs/progress/TASKS-done.md`(D-001~D-012 상세는 `TASKS-archive-phase1-3.md §개발 부채`)에 있다.

### Live / 신규

| ID | 영역 | 요약 | 묶음 | 상태 |
|---|---|---|---|---|
| D-064 | Payment / PG | **Toss 실호출 경로(승인 · 취소 · 조회)가 실제 PG 에서 검증되지 않는다** — E2E 는 PG stub(`scripts/e2e/pg-stub/`)으로 계약 형태만 흉내내고, JVM 테스트는 `MockRestServiceServer` 로 모사한다. 실 API 스펙 변경 · 실호출 reconciliation · 웹훅 서명의 실 PG 관통은 미검증. D-020(완료)의 게이트로 "외부 PG 장애 주입 환경"이 언급됐으나 별도 추적 행이 없음. D-059(409 처리)의 실제 응답 형태 확인과도 묶인다 | 블로그 25편 작성 중 재확인(2026-10-11) · 출처 PHASE4 ④-d-2b 미충족 5 · 계획 §9-4 | ◐ 무료 계약 보정 부분 병합 [#193](https://github.com/Kimgyuilli/PeekCart/pull/193), main a7ffa11 CI39483390397/6publish 성공. 일반 PAYMENT_STATUS_CHANGED는 비서명 hint로 처리하고 서버 조회만 신뢰(ADR-0041); 주문ID 생성·저장·checkout 계약 [#196](https://github.com/Kimgyuilli/PeekCart/pull/196)도 main a98ae7d CI39568336863/6publish 성공. 실 PG 승인·취소·조회/외부 수신·외부 checkout 통합 미검증 |
| D-065 | DLQ / Replay | **DLQ replay 가 개방된 적이 없고 개방 전제 검증 다섯 가지가 남아 있다** — kill-switch `APP_DEAD_LETTER_REPLAY_ENABLED` 는 4개 ConfigMap 모두 `"false"` 이며 계획 §9 4단계(닫힌 채 배포 → P22 증적 → 게이트웨이 관리자 라우트 → 개방 → 리허설)는 미실행. 미검증·미수행: ① 게이트웨이 경유 진입점 실제 스택 관통(ADMIN 토큰 → `/api/v1/admin/deadletter/*` → `ROLE_ADMIN`, ROLE_USER 403 — V-40, 라우트 계약과 엔드포인트 가드를 각각만 관측) ② `scripts/replay-drain-preflight.sh` 의 수집 계층(`kubectl exec` 기반 DB·Kafka 조회, 판정 계층만 fixture 관통) ③ `record_kind`·`root_record_id` `NOT NULL` contract 마이그레이션(계획 §10 R1, 그때까지 N16 은 코드 경로로만 강제) ④ 원장 `original_timestamp` NULL 비율(P22, 운영 표본 0 → 미측정, 처분은 ADR-0022 §D6 분기표) ⑤ GKE 실적용(계획 §10 R2 — `ALTER_CONFIGS` 권한·실제 좌표 읽기). 개방 세션에서 함께 처리될 묶음 | 블로그 25편 작성 중 재확인(2026-10-12) · 출처 PHASE4 ④-c-2b-4a 미충족 2·4 · ④-c-2b-4b 미충족 · 계획 §9·§10 R1/R2 | ◐ writer #201·권한 예외 #211·signed gateway [#210](https://github.com/Kimgyuilli/PeekCart/pull/210)·수집 판정 [#189](https://github.com/Kimgyuilli/PeekCart/pull/189) 무료 범위 부분 병합. 최신 #189 main7915f26 CI39619377710 성공/6publish 성공(한 payment image job API 상태 지연, 내부 Complete job 성공). 실제 MySQL/Kafka adapter [#212](https://github.com/Kimgyuilli/PeekCart/pull/212) 무료 범위 병합(main e45d321 CI39636215009/6publish 성공); 제약 #202·Flyway #207 최신 병합 검증, 운영 표본/GKE/NOT NULL 실제적용 미완. replay 비개방 유지 |
| D-075 | Saga / Outbox | **outbox 발행 처리율 천장이 추적되지 않는다** — D-002 측정(#120)이 비동기 예약 사가의 천장을 자원이 아니라 **폴링 설정**으로 지목했다: `app.outbox.polling.delay` 기본 5s × `batch-size: 100` = 이론 상한 20건/초(실측 14.3~16.3, MySQL CPU 4배에 +14%). 완화(500ms/500건) 시 77.3건/초로 오르나 그때도 어느 Pod 도 CPU 한도에 닿지 않는 **새 천장**이 남았고, 이벤트당 ~13ms 가 순차 동기 발행(블로킹 `.get()`, `publish-timeout 6s`)과 정합적이라는 관찰만 있다(미분리). 현재 order · product · payment 3서비스 모두 `batch-size: 100` · delay 기본 5s 그대로이고, 2분 부하가 남긴 사가를 비우는 데 9.2분(552초)이 걸린 기록이 있다. 처분(설정 상향 / 비동기 배치 발행 / 수용) 미결 | 블로그 28편(로드맵 27편) 작성 중 재확인(2026-10-15) · 출처 `evidence/d002bc-gke-20260917.md` §② · PHASE4 "D-002 종결" | 🔄 부분 완료 ([#200](https://github.com/Kimgyuilli/PeekCart/pull/200)): 무료 병목 분석·독립 리뷰·main CI/6publish 완료. 목표 TPS·최대 backlog·초과 대응 및 정책 선택·검증은 미결; 사용자 요청으로 후속 티켓 보류(재개·기준 제시 대기) |
| D-078 | Performance / Measurement | **D-002 계열 측정 잔여가 추적되지 않는다** — ADR-0027 이 "모르는 채 결정"으로 적고 TASKS 에 행이 없는 항목: ① 읽기 경로 list 의 MySQL CPU 배속 미확정(되돌림 +11.5% 게이트 실패, 블록 B 산포 44.5%, 블록 워밍업에 list 경로 미포함) ② MySQL 2000m 에서 남은 새 천장(detail OFF 904 rps, MySQL 스로틀 0.1s) 정체 미분리 ③ `product`(단수) 캐시 미측정(하네스가 `productStock`·`products` 만 분해) ④ `requests.cpu` 처분 근거인 노드 레벨 CPU 경합 미측정(ADR-0027 Alternative B 재개 조건 — 캐시 ON 경로가 MySQL CPU 에 반응한 원인은 D-072 가 유력 후보) ⑤ jitter 도입 후 부하 하 낙관락 충돌률·DLQ 유입 재측정 없음(ADR-0025 비관적 락 승격 게이트의 입력) ⑥ 스케줄러 풀 4 의 부하 하 적정성 미측정(D-024 미충족) ⑦ #129 세션 익일 Billing 콘솔 확인 미확인 ⑧ base `limits.cpu` 2000m 을 base 경로로 렌더한 매니페스트를 실제 클러스터에 적용한 적 없음(D-029 검증은 렌더 수준, PR #131 미충족 1) | 블로그 28편(로드맵 27편) 작성 중 재확인(2026-10-15) · 출처 ADR-0027 Consequences · PHASE4 D-024/D-025/D-029 미충족 | 🔲 |
| D-084 | Payment / Operations | **보호 승인 장기 미결의 증빙 기반 복구 경로가 없다** — PG 호출 전 중단 후 영구 404는 UNRESOLVED/APPROVAL_HELD를 유지한다. 기존 코드가 수동 FAILED로 닫은 보호 원장도 자동 복구하지 않는다. ADR-0035의 read-only 진단 후 PG·예약·환불 대사를 수행하고, 권한·증빙·감사 및 재고 1회 복구를 검증하는 운영 복구 절차/진입점이 필요하다. 시간 경과나 단순 운영자 확인만으로 해제하지 않는다 | D-060 후속 보강 [#176](https://github.com/Kimgyuilli/PeekCart/pull/176) · ADR-0035 Limits/Rollout diagnostics | ◐ 부분 병합 [#181](https://github.com/Kimgyuilli/PeekCart/pull/181) — 서버 직접 PG 확정 결과 복구 구현. 영구 404의 신뢰 가능한 PG 발급 증빙 원천·형식 미확보로 전체 미완 |
| D-089 | CI / Release | **main publish 부분 실패의 근본 원인과 부분 승격 상태가 추적되지 않는다** — main run `38973369532`(2026-10-16)에서 `gate` 와 모든 선행이 성공한 뒤 `publish (payment-service)` 만 docker push `unknown blob` 으로 실패했다. ADR-0030 이 인정한 비원자성대로 다른 5개 이미지의 `latest` 만 새 digest 로 승격된 창이 생겼고, 다음 main run `38981041166` 성공으로 해소됐다. #179 는 진단 출력 보존만 했고 "근본 원인 미확정" 이다(`task-debt-orchestration.audit.md`, `task-publish-diagnostics.md`). TASKS 행이 없어 재발 시 판단 기준(재실행 여부, 부분 승격 감지 · 복구 절차, 6개 `latest` digest 일치 확인)이 없다 | 블로그 30편(로드맵 29편) 작성 중 후속 PR 확인(2026-10-17) · ADR-0030 Consequences | 🔲 |
| D-092 | Observability / Ops | **D-030의 운영 전달·복구 미충족이 열린 작업으로 추적되지 않는다** — 현재 `grafana-alerts.yml`·GKE values는 contact point/notification policy 미provisioning이며, Outbox FAILED 수동 재발행 runbook과 notification-service 사용자/운영 웹훅 혼재의 처분도 없다. D-017은 L-004로 이관 완료, D-030도 완료로만 남아 실제 외부 전달과 복구 완료를 오인할 수 있다. D-063/#180은 로컬 규칙 발화·복귀를 해소했지만 이 세 항목은 범위 밖이다. 처리: 각 항목의 수용/구현·재개 조건 명시, 전달은 실제 채널별 검증, FAILED 재발행은 중복/멱등성 조건 포함 운영 절차 | 블로그 31편 미충족 재추적, 2026-10-18 · D-030 계획 §4 · origin/main `8e5f6ce` | ◐ 무료 로컬 운영경보 경로 [#217](https://github.com/Kimgyuilli/PeekCart/pull/217) 부분 병합, main 7320ffd CI39629072400/6publish 성공. 사용자/운영 웹훅 분리 [#219](https://github.com/Kimgyuilli/PeekCart/pull/219) 부분 병합, main b2e8620 CI39632767287/6publish 성공; 실제 Slack/GKE/운영 DB·Secret 소유/전환·FAILED 운영 복구 미검증 |
