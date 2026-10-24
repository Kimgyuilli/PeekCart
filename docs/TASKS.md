# PeekCart — Task 관리

> 현행 작업을 **PR 단위**로 추적한다. PR 1개 = 부채/기능 1묶음.
> 상태: `🔲 대기` / `🔄 진행 중` / `◐ 부분 완료` / `✅ 완료` / `⏸ 보류`

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
| D-064 | Payment / PG | **Toss 실호출 경로(승인 · 취소 · 조회)가 실제 PG 에서 검증되지 않는다** — E2E 는 PG stub(`scripts/e2e/pg-stub/`)으로 계약 형태만 흉내내고, JVM 테스트는 `MockRestServiceServer` 로 모사한다. 실 API 스펙 변경 · 실호출 reconciliation · 웹훅 서명의 실 PG 관통은 미검증. D-020(완료)의 게이트로 "외부 PG 장애 주입 환경"이 언급됐으나 별도 추적 행이 없음. D-059(409 처리)의 실제 응답 형태 확인과도 묶인다 | 블로그 25편 작성 중 재확인(2026-10-11) · 출처 PHASE4 ④-d-2b 미충족 5 · 계획 §9-4 | ⏸ 보류(2026-10-25 사용자 결정: PG 연동이 엮인 항목은 이번 범위에서 뺀다. 재개 조건: Toss 테스트 가맹점 키 확보). 이전 진행: 무료 계약 보정 부분 병합 [#193](https://github.com/Kimgyuilli/PeekCart/pull/193), main a7ffa11 CI39483390397/6publish 성공. 일반 PAYMENT_STATUS_CHANGED는 비서명 hint로 처리하고 서버 조회만 신뢰(ADR-0041); 주문ID 생성·저장·checkout 계약 [#196](https://github.com/Kimgyuilli/PeekCart/pull/196)도 main a98ae7d CI39568336863/6publish 성공. 실 PG 승인·취소·조회/외부 수신·외부 checkout 통합 미검증. 차단: Toss 테스트 가맹점 키(GCP 과금과 무관, 키가 있으면 로컬 검증 가능) |
| D-065 | DLQ / Replay | **DLQ replay 가 개방된 적이 없고 개방 전제 검증 다섯 가지가 남아 있다** — kill-switch `APP_DEAD_LETTER_REPLAY_ENABLED` 는 4개 ConfigMap 모두 `"false"` 이며 계획 §9 4단계(닫힌 채 배포 → P22 증적 → 게이트웨이 관리자 라우트 → 개방 → 리허설)는 미실행. 미검증·미수행: ① 게이트웨이 경유 진입점 실제 스택 관통(ADMIN 토큰 → `/api/v1/admin/deadletter/*` → `ROLE_ADMIN`, ROLE_USER 403 — V-40, 라우트 계약과 엔드포인트 가드를 각각만 관측) ② `scripts/replay-drain-preflight.sh` 의 수집 계층(`kubectl exec` 기반 DB·Kafka 조회, 판정 계층만 fixture 관통) ③ `record_kind`·`root_record_id` `NOT NULL` contract 마이그레이션(계획 §10 R1, 그때까지 N16 은 코드 경로로만 강제) ④ 원장 `original_timestamp` NULL 비율(P22, 운영 표본 0 → 미측정, 처분은 ADR-0022 §D6 분기표) ⑤ GKE 실적용(계획 §10 R2 — `ALTER_CONFIGS` 권한·실제 좌표 읽기). 개방 세션에서 함께 처리될 묶음 | 블로그 25편 작성 중 재확인(2026-10-12) · 출처 PHASE4 ④-c-2b-4a 미충족 2·4 · ④-c-2b-4b 미충족 · 계획 §9·§10 R1/R2 | ⏸ 보류(2026-10-25 사용자 결정: 운영 환경이 엮인 ④ 운영 표본과 ③ 의 운영 writer 정지·전환 증빙·운영 DDL 적용을 범위에서 뺀다). ③ 코드 몫 [#244](https://github.com/Kimgyuilli/PeekCart/pull/244): 4서비스 record_kind·root_record_id NOT NULL contract(DEFAULT 없음)와 DDL 전 가드, 위반 행은 history 없이 거부하고 자동 보정하지 않음, main `a1f831a` CI40132854886 성공. ②⑤ 재확인은 D-078 재측정 세션(S4). 이전 진행: writer #201·권한 예외 #211·signed gateway [#210](https://github.com/Kimgyuilli/PeekCart/pull/210)·수집 판정 [#189](https://github.com/Kimgyuilli/PeekCart/pull/189) 무료 범위 부분 병합. 최신 #189 main7915f26 CI39619377710 성공/6publish 성공(한 payment image job API 상태 지연, 내부 Complete job 성공). 실제 MySQL/Kafka adapter [#212](https://github.com/Kimgyuilli/PeekCart/pull/212) 무료 범위 병합(main e45d321 CI39636215009/6publish 성공); 제약 #202·Flyway #207 최신 병합 검증, 운영 표본/GKE/NOT NULL 실제적용 미완. replay 비개방 유지. GKE 확인(D-078 세션 2026-10-24, [#230](https://github.com/Kimgyuilli/PeekCart/pull/230)): ② 실제 스택 실행 **실패**(`replay-drain-preflight.sh` Kafka `localhost:9092` 고정, k8s 29092라 DLQ group lag 조회 전부 실패·exit 1) · ⑤ effective 토픽 30개 중 24개 선언값 일치, payment 토픽 6개 브로커 기본값. 두 결함은 D-106 ⑨⑩ 으로 추적. ① 은 [#210](https://github.com/Kimgyuilli/PeekCart/pull/210) 에서 로컬 signed stack 으로 실제 HTTP 관통을 확인해 끝났다(`docs/plans/done/D-065-signed-gateway.md` 완료 기록, 2026-10-24 정정). 남음: ③ NOT NULL contract 적용 · ④ 운영 표본(운영 환경 부재로 차단) · ②⑤ 수정 후 재확인(과금 세션, ⑤ 의 원인은 D-106 ⑩ [#237](https://github.com/Kimgyuilli/PeekCart/pull/237) 로 보완했고, 재확인은 파티션 수와 group 할당까지 보는 측정 전 토픽 상태 가드 [#239](https://github.com/Kimgyuilli/PeekCart/pull/239) 로 한다) |
| D-078 | Performance / Measurement | **D-002 계열 측정 잔여가 추적되지 않는다** — ADR-0027 이 "모르는 채 결정"으로 적고 TASKS 에 행이 없는 항목: ① 읽기 경로 list 의 MySQL CPU 배속 미확정(되돌림 +11.5% 게이트 실패, 블록 B 산포 44.5%, 블록 워밍업에 list 경로 미포함) ② MySQL 2000m 에서 남은 새 천장(detail OFF 904 rps, MySQL 스로틀 0.1s) 정체 미분리 ③ `product`(단수) 캐시 미측정(하네스가 `productStock`·`products` 만 분해) ④ `requests.cpu` 처분 근거인 노드 레벨 CPU 경합 미측정(ADR-0027 Alternative B 재개 조건 — 캐시 ON 경로가 MySQL CPU 에 반응한 원인은 D-072 가 유력 후보) ⑤ jitter 도입 후 부하 하 낙관락 충돌률·DLQ 유입 재측정 없음(ADR-0025 비관적 락 승격 게이트의 입력) ⑥ 스케줄러 풀 4 의 부하 하 적정성 미측정(D-024 미충족) ⑦ #129 세션 익일 Billing 콘솔 확인 미확인 ⑧ base `limits.cpu` 2000m 을 base 경로로 렌더한 매니페스트를 실제 클러스터에 적용한 적 없음(D-029 검증은 렌더 수준, PR #131 미충족 1) ⑨ README 근거 캡처 기준이 없다 — 세션마다 지정 Grafana 패널 PNG 를 남기지 않으면 수치만 있고 README 에 실을 근거 이미지가 없다 | 블로그 28편(로드맵 27편) 작성 중 재확인(2026-10-15) · 출처 ADR-0027 Consequences · PHASE4 D-024/D-025/D-029 미충족 · ⑨ README 개편 측정 수요(2026-10-22). 같은 GKE 클러스터를 띄울 때 선택 병행 가능: D-065 ⑤, D-092 (각자 완료 기준은 원래 행) | 🔄 재측정 대기: 계획 `D-078-remeasure-plan`([#243](https://github.com/Kimgyuilli/PeekCart/pull/243))의 S0~S5 를 과금 세션 1회로 실행한다(추정 약 3.8시간, $2.1~2.9, 세션 승인 필요). 선행 수정은 D-106([#232](https://github.com/Kimgyuilli/PeekCart/pull/232)·[#237](https://github.com/Kimgyuilli/PeekCart/pull/237)·[#239](https://github.com/Kimgyuilli/PeekCart/pull/239)·[#242](https://github.com/Kimgyuilli/PeekCart/pull/242)·[#243](https://github.com/Kimgyuilli/PeekCart/pull/243)), D-107([#241](https://github.com/Kimgyuilli/PeekCart/pull/241)), D-075([#242](https://github.com/Kimgyuilli/PeekCart/pull/242), ADR-0045), D-065 ③ 코드([#244](https://github.com/Kimgyuilli/PeekCart/pull/244))로 끝났다. 이 세션에서 D-065 ②⑤ 재확인, ADR-0045 부하 검증, 측정 전 토픽 가드·배포 래퍼의 첫 실사용을 함께 한다. 이전 진행: 무료 로컬 계측 부분 병합 [#199](https://github.com/Kimgyuilli/PeekCart/pull/199) main 51683a4 CI39687494364 · [#220](https://github.com/Kimgyuilli/PeekCart/pull/220) main 8fbd658 CI39679620260 · [#221](https://github.com/Kimgyuilli/PeekCart/pull/221) main b3a7c60 CI39674425123 · [#218](https://github.com/Kimgyuilli/PeekCart/pull/218) main 985f550 CI39699130969, 모두 6publish 성공. 과금 세션 사전 계측 준비(재시도 메트릭·executor 메트릭 고정·MySQL 2000m 측정 overlay) [#225](https://github.com/Kimgyuilli/PeekCart/pull/225) 병합(main b0c5e8b). 측정 대시보드·캡처 기준 [#226](https://github.com/Kimgyuilli/PeekCart/pull/226) 병합(main 4bed313). GKE 측정 세션 계획 [#227](https://github.com/Kimgyuilli/PeekCart/pull/227) 병합(main 577afbd). 캡처 도구·D-065 ②⑤·D-092 세션 편입 [#228](https://github.com/Kimgyuilli/PeekCart/pull/228) 병합(main 4785396). README 보강 지표 A~E 와 지연 원자료 도구 [#229](https://github.com/Kimgyuilli/PeekCart/pull/229) 병합(main 312ff16). GKE 세션 실행(2026-10-24, `evidence/d078-gke-20261024.md`, [#230](https://github.com/Kimgyuilli/PeekCart/pull/230) PR 열림): ⑤ DLQ/주문 0.040~0.068%(before 0.072%) · ⑥ 풀 4 중 active 최대 3, 발행 정지 지속 구간 없음 · ⑧ 통과 · ⑨ 캡처 36·export 40 · A~E 수집. ①②③④ 미측정 — ADR-0037 조회 bulkhead 로 S2 재현 게이트 구조적 실패(사용자 결정으로 접음). 세션 발견 11건(`loadtest/reports/2026-10-24/session-issues.md`, D-106) 수정 후 재측정. 재측정은 과금 세션 1회(시크릿·GSA 보존, S0 는 결제 재연결과 이미지 재승격만) |
| D-084 | Payment / Operations | **보호 승인 장기 미결의 증빙 기반 복구 경로가 없다** — PG 호출 전 중단 후 영구 404는 UNRESOLVED/APPROVAL_HELD를 유지한다. 기존 코드가 수동 FAILED로 닫은 보호 원장도 자동 복구하지 않는다. ADR-0035의 read-only 진단 후 PG·예약·환불 대사를 수행하고, 권한·증빙·감사 및 재고 1회 복구를 검증하는 운영 복구 절차/진입점이 필요하다. 시간 경과나 단순 운영자 확인만으로 해제하지 않는다 | D-060 후속 보강 [#176](https://github.com/Kimgyuilli/PeekCart/pull/176) · ADR-0035 Limits/Rollout diagnostics | ⏸ 보류(2026-10-25 사용자 결정: PG·운영이 엮인 항목은 이번 범위에서 뺀다. 재개 조건: 영구 404 의 PG 발급 무과금 증빙 원천·형식 확보). 이전 진행: 부분 병합 [#181](https://github.com/Kimgyuilli/PeekCart/pull/181) — 서버 직접 PG 확정 결과 복구 구현. 영구 404의 신뢰 가능한 PG 발급 증빙 원천·형식 미확보로 전체 미완 |
