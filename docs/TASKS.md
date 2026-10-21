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
| D-086 | Docs / CI | **CI 시간 이력 서술이 원본 run 과 다르다** — ① D-032 "그런데 CI 전체는 줄지 않았다"(PHASE5 · `task-d032` §V-7)의 비교 기준 run `35753591828`(19분 03초)은 임계경로가 `test(order) 1116s → gate` 였다(e2e 경로는 6초 먼저 끝남). "test 는 이미 임계경로 밖" 은 이 run 에서 사실이 아니며, 두 경로가 거의 동률이었다 ② 같은 절의 `images` 1.9분에서 3.4분 증가 원인을 "새 브랜치라 `type=gha` 캐시 콜드(ref 격리)" 로 적었으나 PR run `35976903613` 의 images 로그는 `importing cache manifest` 후 `CACHED` 11스텝이고, #138 이 `order-service/build.gradle` 에 27줄을 더해 Dockerfile 의 `COPY order-service/build.gradle` 이후 레이어(의존성 받기 28.8초 · bootJar 34.2초)가 무효화된 것이다. GitHub 문서상 PR run 은 base 브랜치 캐시를 읽을 수 있다(ref 격리는 머지 후 첫 main push 에는 해당) ③ D-031 progress · 계획서 "임계경로 `lint 51s -> images 206s -> e2e 949s`" 의 images 는 order 206초이고 실제 e2e 직전에 끝난 잡은 notification 242초다 ④ D-037 행 "#135~#137 도 같은 상태로 머지된 것으로 보인다" — `build` 잡은 #97(2026-09-01T09:53Z)에서 사라졌고 #141 까지 41개 PR 이 존재하지 않는 필수 체크 아래 병합됐다 ⑤ "36분 중 34.5분"(ADR-0028 · D-031 · PR #135) — 네 잡 합은 2095초(34분 55초), 사슬 구간은 35분 34초 ⑥ `ci.yml` images matrix 주석 "주석을 끼우면 ... 조용히 어긋난다" — 실험 결과 `image-contract-lint` 가 canonical 불일치로 exit 1(큰 소리로 실패). 게시된 블로그 29편 본문 "테스트 잡이 이미 CI 의 가장 긴 경로가 아니었기 때문" 도 ① 과 같은 문제(블로그 저장소 소관) | 블로그 30편(로드맵 29편) 작성 중 재확인(2026-10-17) · jobs API · 잡 로그 | 🔲 |
| D-088 | CI / Scripts | **검사기가 CI 에 배선됐는지와 검사기 자체가 살아 있는지를 강제하는 장치가 없다** — D-056 은 전수조사로 미배선 lint 2개를 찾았을 뿐 재발 방지 장치를 두지 않았다(scheduler lint 는 2026-09-18 생성 후 11일간 미배선). 현재 CI 에서 도는 lint 중 `--self-test` 가 없는 것이 4개다: `scheduler-lock-contract-lint.sh` · `image-contract-lint.sh` · `kustomize-namespace-lint.sh` · `observability-ssot-lint.sh`. 실험 E3: scheduler lint 첫 줄에 `echo "[SCHED-LOCK] OK"; exit 0` 을 넣으면 `@SchedulerLock` 을 지운 위반이 있어도 rc=0 이고 CI 가 이를 잡지 못한다(D-056 progress 미충족에 적혔으나 TASKS 행 없음). 처분 후보: `scripts/*-lint.sh` 각각이 `ci.yml` 에서 호출되는지(또는 사유 있는 예외 목록) 검사하는 메타 lint, self-test 없는 lint 의 목록화와 재검토 조건 | 블로그 30편(로드맵 29편) 작성 중 실험 E3(2026-10-17) · D-056 미충족 | 🔲 |
| D-089 | CI / Release | **main publish 부분 실패의 근본 원인과 부분 승격 상태가 추적되지 않는다** — main run `38973369532`(2026-10-16)에서 `gate` 와 모든 선행이 성공한 뒤 `publish (payment-service)` 만 docker push `unknown blob` 으로 실패했다. ADR-0030 이 인정한 비원자성대로 다른 5개 이미지의 `latest` 만 새 digest 로 승격된 창이 생겼고, 다음 main run `38981041166` 성공으로 해소됐다. #179 는 진단 출력 보존만 했고 "근본 원인 미확정" 이다(`task-debt-orchestration.audit.md`, `task-publish-diagnostics.md`). TASKS 행이 없어 재발 시 판단 기준(재실행 여부, 부분 승격 감지 · 복구 절차, 6개 `latest` digest 일치 확인)이 없다 | 블로그 30편(로드맵 29편) 작성 중 후속 PR 확인(2026-10-17) · ADR-0030 Consequences | 🔲 |
| D-091 | Docs / Testing | **e2e 책임 경계 ADR의 일부 설명이 구현과 모순되거나 후속 코드에서 낡았다** — ADR-0031 D2-3은 poller 복원을 “ShedLock 행 삭제 후”라고 적지만 같은 ADR Context·하단과 실제 스크립트는 UPDATE 해제를 요구한다. “시간 경과 결함은 스택 바깥 제어 수단이 없다”는 D3 재검토 근거도 현재 scenario_e의 expires_at DB 조작과 구분해야 한다(시계 전체 제어와 영속 만료 입력 변경은 다름). ADR-0032의 시간 82s/157s는 특정 당시 run의 분해이며 현재 6시나리오 잡 714s로 일반화할 수 없다. immutable 본문 덮어쓰기 대신 Update Log와 현재 전략에 적용 범위·당시/현재 구분 보강 | 블로그 31편 원문/현재 코드 대조, 2026-10-18 · ADR-0031/0032 · `saga_e2e.py` | 🔲 |
| D-092 | Observability / Ops | **D-030의 운영 전달·복구 미충족이 열린 작업으로 추적되지 않는다** — 현재 `grafana-alerts.yml`·GKE values는 contact point/notification policy 미provisioning이며, Outbox FAILED 수동 재발행 runbook과 notification-service 사용자/운영 웹훅 혼재의 처분도 없다. D-017은 L-004로 이관 완료, D-030도 완료로만 남아 실제 외부 전달과 복구 완료를 오인할 수 있다. D-063/#180은 로컬 규칙 발화·복귀를 해소했지만 이 세 항목은 범위 밖이다. 처리: 각 항목의 수용/구현·재개 조건 명시, 전달은 실제 채널별 검증, FAILED 재발행은 중복/멱등성 조건 포함 운영 절차 | 블로그 31편 미충족 재추적, 2026-10-18 · D-030 계획 §4 · origin/main `8e5f6ce` | ◐ 무료 로컬 운영경보 경로 [#217](https://github.com/Kimgyuilli/PeekCart/pull/217) 부분 병합, main 7320ffd CI39629072400/6publish 성공. 사용자/운영 웹훅 분리 [#219](https://github.com/Kimgyuilli/PeekCart/pull/219) 부분 병합, main b2e8620 CI39632767287/6publish 성공; 실제 Slack/GKE/운영 DB·Secret 소유/전환·FAILED 운영 복구 미검증 |
| D-095 | Architecture / Docs | **공유 DTO가 같은 커밋의 JSON 필드 호환성을 컴파일로 보장한다는 ADR 전제가 과도하다** — ADR-0032 Context2/AlternativeB는 common payload 공유 때문에 필드 불일치를 컴파일이 막는다고 한다. 실제 NotificationConsumer는 JsonNode.get("userId")처럼 문자열로 읽는다. 공유 PaymentFailedPayload의 userId Java API를 유지하고 `@JsonProperty("recipientId")`만 추가한 변이에서 payment/notification compileJava는 성공하고, 실제 ObjectMapper 직렬화 결과에서 소비자가 읽는 userId를 확인하는 탐침은 null로 실패했다(양성 통과). 해당 DTO는 당시9e27e0e와 현재origin/main8e5f6ce가 동일. Java 타입 공유와 wire schema 호환성의 보장 범위를 분리해 ADR Update Log 및 계약 테스트 대안 근거를 정정할 필요가 있다. Pact 도입 필요성 자체를 입증한 것은 아님 | 블로그 31편 실험 E7, 2026-10-18 · `tools/blog31/payload/` · ADR-0032 | 🔲 |
| D-096 | Docs / Architecture | **공유 메시징 승격 근거의 동일성 수치와 파일 분류가 실제 이력과 다르다** — ADR-0033·D-049 계획·PHASE5는 공유 대상 32개가 네 벌 byte 동일하다고 하지만 #168 부모 7e83ee3에서 31개만 동일하고 OutboxEventStatus 1개는 order/product와 payment/notification 두 집합이다. 서비스별 대상은 35/35/35/34개, 서비스에 남은 것은 consumer·quarantine consumer·LedgerOwnerConfig이고 초기 TASKS 요약의 차이 목록과도 다르다. BACKFILL 마이그레이션은 order V5, product V4이며 ADR의 양쪽 V5 표기는 틀리다. parity Java 목록은 deadletter16+outbox8과 enum 두 집합으로 idempotency6/ShedLock1까지 전부 강제한 것은 아니다. immutable ADR은 Update Log로 수치·검사 범위를 정정하고 progress/계획의 사실 정정 연결 필요 | 블로그 32편(로드맵 31편) 근거 대조·격리 실험, 2026-10-20 · `docs/learning/drafts/tools/blog32/` | 🔲 |
| D-100 | Docs / Migration | **Flyway 주석 정리 판단의 분석 버전이 실제 프로젝트 의존 버전과 다르다** — D-050 계획·PHASE5는 flyway-core12.4.0 javap을 근거로 들지만 #169 직전42030ff 및 현재8e5f6ce의 payment runtimeClasspath는11.7.2다. 당시 작업 기록은 Gradle 해석 대신 로컬 캐시12.4.0 jar를 골랐다. 실제11.7.2와12.4.0 ChecksumCalculator 실행에서 주석 텍스트 변경은 checksum 변경, 빈 줄/LF→CRLF/마지막 개행 제거는 동일, 공백만 있는 줄은 변경임을 확인했다. 적용된 SQL 주석을 제외한 결론은 유지되나 버전 근거와 줄 처리 범위를 정정해야 한다. DB validate 재실행을 했다는 뜻은 아님 | 블로그 32편(로드맵 31편) 근거 대조·격리 실험, 2026-10-20 · `docs/learning/drafts/tools/blog32/` | 🔲 |
| D-101 | Docs / Architecture | **과거 설계 분리 뒤 현재 아키텍처 문서의 트리와 오래된 줄번호 참조가 남아 있다** — D-053 미충족인 docs/02-architecture.md §12 Phase3 트리가 없는 루트 src와 과거 단일 서비스 위치를 그린다. 배포 절은 현재 참조를 받으므로 통째로 과거 문서로 옮길 수 없다. origin/main에서도 잔존하며 #175 후속은 다른2줄만 수정했다. ADR-0010/0012의 과거 L95-164/L174-382 참조도 현행 위치 안내 없이 어긋나 있다. 현행 배포 구조와 역사 트리를 구분하고 immutable ADR은 Update Log/안정 절 링크로 추적 경로 보강 필요 | 블로그 32편(로드맵 31편) 근거 대조·격리 실험, 2026-10-20 · `docs/learning/drafts/tools/blog32/` | 🔲 |
| D-102 | Code / Comments | **계획 내부 식별자 주석 정리의 제외 범위와 재발 감시가 열린 작업으로 추적되지 않는다** — D-050은 src/test를 제외하고 검사기를 scratch에만 뒀다. 현재 공유 모듈 DeadLetterEndpointReplayAuthTest에 구현④-c-2b-4a/P24 등 외부 문맥 없이는 복원하기 어려운 참조가 남아 있고 검사 스크립트는 상시 CI 규약이 아니다. 테스트 주석을 의미·출처 기준으로 정리할지 수용할지 확정하고, 재발 시 CI lint 도입이라는 조건을 추적해야 한다. ADR/전역 부채 ID와 유용한 근거 주석까지 일괄 삭제하지 않으며 기존 D-088의 일반 lint 생존/배선 문제와 구분 | 블로그 32편(로드맵 31편) 근거 대조·격리 실험, 2026-10-20 · `docs/learning/drafts/tools/blog32/` | 🔲 |
| D-104 | Docs / Harness | **하네스 실측 근거에 추출 모집단과 정정이 반영되지 않았다** — 6b6dd69 부모의 docs/plans 직하 audit 18개에서 특정 표기 정규식으로 56요약/442항목/P0 9와 별도 41처분 문구/반영306·기각1을 재현했다. 전체 검토·처분 모집단이 아니며 중복 제거도 없다. 라운드3 20은 문자열 출현으로 재현되고 실패 시도·본문 언급을 포함한다. 렌더러 주석은 정정 전 45실패/null1/전부usage를 유지하고 공통 규칙도 대개usage라고 적지만 계획은 44빈파일/null0/usage9다. PR3d 분할 기록도 총10 기각과 열거된11 ID가 불일치한다. 원자료·시점·추출법을 명시하고 PHASE4, harness-rationale, 관련 주석의 전체집계/인과 과장을 정정해야 한다. 기존 raw corpus 부재는 별도 증거 한계로 남긴다 | 블로그 33편 원문·이력·계산 대조, 2026-10-20 · tools/blog33/statistics-recalculation.json 및 renderer-work-records.json | 🔲 |
