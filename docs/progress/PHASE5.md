# Phase 5 진행 보고서 — 수요 기반

> Phase 5 작업 이력, 주요 결정 사항, 이슈 기록
> 작업 상태 추적은 `docs/TASKS.md §개발 부채 / 작업`

---

## Phase 5 는 무엇이 다른가

Phase 1~4 는 **착수 전에 순서표가 있었다.** 무엇을 언제 할지 로드맵
(`docs/07-roadmap-portfolio.md §16`)에 미리 적어 두고 그 순서를 소진하는 방식이었고,
순서가 비면 단계가 닫혔다.

Phase 5 에는 그 순서표가 없다. **필요하다고 판단한 시점에 항목을 추가하고 그때 착수한다.**

그래서 세 가지가 바뀐다:

1. **Exit Criteria 가 없다.** 소진할 목록이 없으므로 단계가 종결되지 않는다.
   `docs/TASKS.md` 의 열린 표가 곧 현황이다
2. **추적 축이 하나다.** D- 번호 단일 표. "부채"와 "신규 작업"을 나누지 않는다 —
   수요 기반에서는 둘 다 *지금 필요하다고 판단한 작업* 이라는 같은 성격이다
3. **동기를 기록하지 않으면 복원되지 않는다.** 로드맵이 근거를 대신 붙들어 주던 것이
   사라졌으므로, 항목을 추가할 때 **왜 필요한지**를 `요약` 에, **어디서 발견했는지**를
   `묶음` 에 적는다. 이것이 Phase 5 에서 유일하게 늘어난 규율이다

## 이월 항목 (Phase 4 → 5)

| ID | 영역 | 상태 |
|---|---|---|
| D-027 | Harness / Cost | ✅ ② 완료([#128](https://github.com/Kimgyuilli/PeakCart/pull/128)) · ① 완료([#158](https://github.com/Kimgyuilli/PeekCart/pull/158)) · ③ 완료([#159](https://github.com/Kimgyuilli/PeekCart/pull/159)) |
| D-030 | Observability / Ops | ✅ 완료([#155](https://github.com/Kimgyuilli/PeekCart/pull/155)) — Outbox `FAILED` ↔ DLQ Slack 채널 분리 + DLQ 적재량 메트릭 (L-004 승격, [#133](https://github.com/Kimgyuilli/PeakCart/pull/133)) |

---

## 작업 이력

> 엔트리 형식은 PHASE4.md 와 동일: `## <제목> ([PR](...), YYYY-MM-DD)`

## outbox·멱등성·DLQ 원장 실행 세트의 공유 모듈 승격 (D-049, ADR-0033, [#168](https://github.com/Kimgyuilli/PeekCart/pull/168), 2026-09-29)

4서비스에 byte 동일하게 복제된 `global.{outbox,idempotency,deadletter}` 와 `ShedLockConfig` 32개를 새 모듈
`peekcart-common-messaging` 으로 옮기고 사본 96개를 지웠다. 서비스마다 다른 `DeadLetterConsumer`·
`DeadLetterQuarantineConsumer`·`LedgerOwnerConfig` 와 Flyway 스키마는 서비스에 남겼다. `:common` 편입은 user-service 가
엔티티·스케줄러를 스캔하게 돼 기각했다(ADR-0033 Alt A). `OutboxEventStatus` 는 `BACKFILL` 을 포함한 한 벌로 합쳤다.
재복제 방지는 parity lint 의 java byte 검사에서 `assertNoDuplicateGlobalFqcn` 으로 옮겼다. 코드 검증에서 CI test 매트릭스와
`integration-test-container-lint` 모듈 목록도 갱신 대상으로 드러나 범위에 넣었다. 같은 PR 에 머지된 계획서 5건의
아카이브가 함께 들어 있다. 등급 L, 계획·diff 리뷰는 `.cache/codex-off` 로 의도적 생략.

미충족: 서비스별 `global.*` 통합 테스트(`DeadLetterLedger`·`DeadLetterMetrics`·`LedgerOwnerWiring`)는 4벌 그대로다.
공유 로직을 바꿀 때 4벌을 함께 고치는 일이 반복되면 다시 본다.

## Kafka 리스너 통합 테스트의 할당 대기와 발행 동기화 (D-058, [#167](https://github.com/Kimgyuilli/PeekCart/pull/167), 2026-09-29)

#164 CI 에서 payment `DlqIntegrationTest` 가 20초 동안 DLT 레코드 0건으로 실패한 건의 후속이다. 같은 구조(리스너를
켜고 곧바로 발행)가 4모듈 12개 클래스에 있어 범위를 넓혔다. `AbstractIntegrationTest#awaitListenerAssignment` 로 발행 전
파티션 할당을 기다리고, future 를 버리던 send 22곳을 `orTimeout(10초).join()` 으로 동기화했다. 누락은
`integration-test-container-lint.sh` ITC-006 이 막는다. 계측해 보니 로컬에서는 테스트 시작 시점에 할당이 이미 거의 끝나
있어(대기 약 0.1초) 초안 전제인 "할당 지연이 첫 테스트를 느리게 한다" 가 반증됐다. 등급 L, 계획·diff 리뷰는
`.cache/codex-off` 로 의도적 생략.

미충족: CI 실패의 근본 원인은 미확정이다. 재발하면 할당 대기 timeout 이나 발행 지점 예외로 좁혀진다. 그래도 await
timeout 으로 실패하면 CI 에 앱 로그를 보존하는 task 를 연다.

## 미배선 스크립트 2개의 CI 편입 (D-056, [#166](https://github.com/Kimgyuilli/PeekCart/pull/166), 2026-09-29)

`scheduler-lock-contract-lint.sh` 와 `codex-review-render-selftest.sh` 를 lint 잡의 policy lints 단계에 넣었다. 삭제도
후보였으나 scheduler lint 는 D-024 풀 확대 뒤 cron 잡 자기 중첩을 막는 유일한 강제 수단이고, render selftest 는 리뷰
렌더러의 false-green 방지 장치라 배선을 택했다. 각각 실패를 주입해(`@SchedulerLock` 제거, 빈 응답 판정 exit 0) exit 1
을 확인했다. render selftest 의 V9 는 `.cache` 를 읽으므로 CI 에서는 skip 된다. 등급 M, diff 리뷰는 `.cache/codex-off` 로
의도적 생략.

미충족: scheduler lint 에는 `--self-test` 가 없다. 위반 탐지는 이번 실패 주입으로만 확인했고, lint 자체를 무력화하는
수정은 CI 가 잡지 못한다. 재검토 조건은 이 lint 의 검사 분기를 고치는 PR 이 생길 때다.

## 잔존 audit 디렉터리의 done 병합 (D-055, [#165](https://github.com/Kimgyuilli/PeekCart/pull/165), 2026-09-29)

이전 하네스가 쓰던 `docs/plans/.audit/` 의 파일 2개를 `done/` 의 같은 이름 audit 에 병합하고 디렉터리를 없앴다. 두 쪽
기록은 겹치지 않았다. gateway 는 잔존본이 PR1~PR3b(#73~#76), `done/` 본이 PR3c(#77)였고, pr3d 는 잔존본이 2026-07-24
계획 리뷰 3라운드, `done/` 본이 구현 이후 기록이었다. 병합 전 두 쪽의 모든 줄이 병합본에 남은 것을 `grep -vxFf` 로
대조했다. 같은 PR 에서 #164 CI 실패 분석의 부채 D-058(Kafka 통합 테스트 토픽 부재 경합)을 등록했다. 등급 S.

미충족: 없음.

## Notion 설계 export 원본 삭제 (D-054, [#164](https://github.com/Kimgyuilli/PeekCart/pull/164), 2026-09-29)

`docs/00-lagacy.md` (68KB) 를 삭제했다. 01~07 분리 때 내용이 전부 이관됐고 원본은 git 이력이 보존한다. archive
이동도 후보였으나 파일이 남으면 grep 중복이 계속돼 삭제를 택했다. PHASE1·PHASE4·TASKS-archive·done 계획서의 언급은
당시 사실이라 고치지 않았다. 등급 S.

미충족: 없음.

## Layer 1 문서와 CLAUDE.md 의 코드 불일치 정정 (D-051, [#163](https://github.com/Kimgyuilli/PeekCart/pull/163), 2026-09-29)

`02-architecture.md` Phase 4 트리와 전환표가 적던 `common/event`·`common/outbox` 공유 모듈과 `*EventProducer` 를 실제
배치로 바꿨다. 착수 전 검증에서 TASKS 원문과 달리 `common` 모듈은 존재하고 이벤트 DTO 는 `global/outbox/dto/` 에서
공유되며, 서비스별 복제는 Outbox·멱등성·DLQ 원장뿐임을 확인했다. `04-design-deep-dive.md` §8-3 의 "DLQ 에서 Slack 알림"
은 §9-3 과 같게 원장 적재와 `peekcart-dlq-backlog` alert 를 주 신호로 고쳤다(see ADR-0018 D6). CLAUDE.md 명명 규칙은
실제 consumer 9개의 패턴인 `{구독 대상}Consumer (infrastructure/kafka)` 로 바꿨다. 등급 S.

미충족: 없음. §8-3 에 남은 계획서 식별자(`④-c-2a` 등)는 D-050 범위라 두었다.

## 등급 판정 기준의 사후 검증 (D-027 ③, [#159](https://github.com/Kimgyuilli/PeekCart/pull/159), 2026-09-29)

`grade:` 가 적힌 계획서 25개(S 8 · M 11 · L 6)를 head 브랜치명으로 PR 에 매칭하고, `docs/` 를 뺀 diff 에 `/plan` §1 의
세 기준(모듈 2개 이상 · 계약 표면 · 작업 밖 되돌림)을 다시 적용했다. 계획서 안 첫 PR 링크는 6건이 다른 PR 을 인용해
매칭 근거로 쓰지 않았다. 25개 전부 일치했고, 재판정 2건(D-031 S에서 M, codex-review-render M에서 L)은 모두 범위
증가로 인한 상향이다. 판정 기준은 유지하고 사후 검산은 자동화하지 않았다. 크기는 M 0~617줄, L 115~1228줄로 겹쳐
크기 임계값 검사기는 올바른 판정을 오탐한다. 결과 표는 `docs/plans/done/task-d027-grade-audit.md` §4.

미충족: 판정자가 등급을 매긴 쪽과 같은 에이전트라 "전부 일치" 는 불일치 부재의 증명이 아니다. 리뷰 강도 배분의
타당성은 `.cache/codex-off` 로 재료가 없어 검증하지 못했다. 재검토 조건은 Codex 재개 후 M 등급 diff 리뷰의 P0 발생이다.

## 커맨드 근거 서술의 별도 문서 분리 (D-027 ①, [#158](https://github.com/Kimgyuilli/PeekCart/pull/158), 2026-09-29)

`/plan`·`/work`·`/ship` 은 호출마다 본문 전체가 컨텍스트에 실린다. 재개 조건은 H1~H6 규칙의 안정이었고, 커맨드가
2026-09-22 이후 바뀌지 않은 채 커밋 93개를 거쳐 충족으로 판정했다. 근거 서술 18곳(축소 이력, 실측 수치, 사건 이력)을
`docs/conventions/harness-rationale.md` 로 줄 단위 원문 그대로 옮기고 원래 자리에는 규칙과 `(근거 R-...)` 포인터만 남겼다.
절 번호는 `.agents/skills`·`writing.md`·D-040 이 참조하므로 제목 줄을 고정했다. 삭제 줄이 모두 근거 문서에 있는지
검사했고, 한 줄을 지우는 주입에 실패를 확인했다.

효과는 46,556B→43,219B(7%)로 작다. 남은 크기의 대부분은 `plan.md`·`work.md` 의 §5-0 게이트, §6 결과 처리,
리뷰 상태 4값이 거의 같은 문장으로 중복된 것이다. 같은 PR 에 D-038(Redpanda 재평가) 폐기 기록을 실었다.

미충족: 중복 규칙 통합은 근거 분리 범위가 아니라 이월했다. 재검토 조건은 커맨드 크기를 더 줄일 필요가 생길 때다.

## Gradle 구간 실측과 setup-gradle 미착수 판정 (D-039, [#157](https://github.com/Kimgyuilli/PeekCart/pull/157), 2026-09-29)

ADR-0028 §후속 ③ 의 "컴파일 60초" 는 D-032 이전 추정이라 착수 전에 다시 쟀다. PR run 36422217146(#156)과
36393965323(#154)에서 test 잡은 임계경로(e2e negative-control)보다 5.7~7.2분 먼저 끝난다. `setup-gradle` 이 닿는
곳이 여기라 줄여도 PR 시간은 줄지 않는다. 임계경로 위의 Gradle 은 Dockerfile 안 `bootJar` 이고 35.6초 중
컴파일이 약 8초, `--no-daemon` 기동·설정이 약 28초다. 이 캐시는 docker build 안에 닿지 않는다. 구현하지 않고 닫았다.

재개 조건: test 잡이 임계경로에 올라오거나 러너 시간 비용이 제기될 때.
미충족: 이미지 빌드의 Gradle 기동·설정 약 28초는 다른 수단(BuildKit cache mount 등)의 문제라 범위 밖이다.

## e2e 스택 기동의 토픽 생성 통합과 앱 동시 기동 (D-048, [#156](https://github.com/Kimgyuilli/PeekCart/pull/156), 2026-09-28)

D-042 시간 분해의 후속이다. main CI 3회에서 토픽 사전 생성이 65~68초, 앱 순차 기동이 18~25초씩 4회였다.
토픽 20종마다 생성과 대조를 따로 exec 해서 exec 와 Kafka CLI JVM 이 40회 떴다. 이를 exec 각 1회로 묶었고
생성 수·파티션 대조 가드는 유지했다. 결함 주입 3건(파티션 불일치, 생성 거부, 파서 고장)이 모두 rc=1 이다.
컨테이너 안 병렬 생성은 브로커 `-Xmx1G` 가 `mem_limit: 1g` 를 채우고 있어 채택하지 않았다.

앱 4개는 서로 `depends_on` 이 없어 compose up 1회로 동시 기동한다. 순차 기동의 사유는 2026-08-28 CPU 경합
실측 하나였다. 로컬(8 CPU) 실측은 토픽 50→23초, 앱 74→51초다. 두 잡의 기동 중복은 D-043 병렬 계약 때문에 범위 밖이다.

미충족: 앱 동시 기동의 채택 판정. CI 는 PR 에서만 돌아 PR 의 CI 2회 이상으로 판정하고, 창 초과가 한 번이라도
나면 순차로 되돌린다.

판정(2026-09-29, [#157](https://github.com/Kimgyuilli/PeekCart/pull/157)): 채택. 동시 기동 PR CI 2회가 녹색이고 창 초과가 없다.
기동 합계는 순차 기준선(run 36393965323) 156/178초에서 133/132초(#156) · 131/121초(#157)가 됐다(scenarios/negative-control).
#156 이 판정 전에 머지돼 두 번째 run 은 #157 에서 얻었다.

## Outbox 발행 소진 alert 신설과 DLQ 신호 분리 (D-030, [#155](https://github.com/Kimgyuilli/PeekCart/pull/155), 2026-09-28)

L-004(Phase 4 운영 관측성) 이월분이다. 착수 전 코드 검증에서 D-030 의 두 전제가 뒤집혀 범위를 다시 정했다.
DLQ 적재량 메트릭은 이미 있었고, 앱의 Slack 직접 발송은 notification-service 외 3서비스에서 no-op 이라
"한 채널에 섞인다" 는 notification-service 에만 해당했다. 실제 공백은 Outbox FAILED 에 alert 가 없는 것이었다.

`grafana-alerts.yml` 에 `peekcart-outbox-failed`(4서비스 `outbox_backlog{status="failed"} > 0`, for 5m)를 더하고
`signal` 라벨(`outbox-publish`/`dlq`)로 두 규칙을 갈랐다. FAILED 는 자동 재시도도 retention 삭제도 없는 종착
상태라 gauge 가 저절로 0 이 되지 않는다. `observability-promql-lint` 에 식 계약을 등록하고 self-test 를 17종에서
19종으로 늘렸다. 계약 추가 전에는 새 규칙이 검사 없이 통과했다. ADR-0009 S8 의 "alert 미도입" 서술은
작성 시점의 사실이라 고치지 않는다.

미충족: contact point / notification policy provisioning(ADR 판단 대상), Outbox FAILED 재발행 runbook,
notification-service 웹훅의 사용자·운영 알림 혼재, 실제 스택 alert 발화 시험(GKE 세션 필요).

## e2e 만 잡던 결함 3건의 JVM 단언 (D-047, [#154](https://github.com/Kimgyuilli/PeekCart/pull/154), 2026-09-28)

ADR-0032 D3 의 후속이다. D-042 결함 주입에서 모듈 테스트 전체가 통과한 3건에 JVM 단언을 더했다.
`PaymentControllerTest` 는 FAILED 응답의 코드 `PAY-005` 를 보고, `NotificationConsumerTest` 는
`payment.failed` 의 `PAYMENT_FAILED` 알림과 `reason=RESERVATION_FAILED` 취소 알림을 본다. `src/main` 변경은 없다.

M12·M13·M14 를 다시 주입하고 모듈 전체를 돌리면 각각 새 테스트 1건만 실패한다(payment 239건, notification
49건). `./gradlew test` 1255건 실패 0. e2e 단언 A4b·A12·B3 은 배선 때문에 그대로 둔다.

미충족: 없음.

## 실제 스택 e2e 와 JVM 테스트의 검증 책임 판정 (D-042, [#153](https://github.com/Kimgyuilli/PeekCart/pull/153), 2026-09-28)

ADR-0032 로 경계를 정했다. e2e 단언 하나는 서비스 간 배선과 도착 후 로직을 함께 싣는다. 배선은 실제 스택이,
로직은 JVM 이 맡는다. e2e 단언은 배선을 싣는 한 남기고, 로직이 e2e 에만 있으면 JVM 테스트를 더한다.
"JVM 이 이미 덮는다" 는 결함 주입으로만 인정한다. 이번 재평가로 제거한 e2e 단언과 대조군은 없다.

범위는 판정까지로 줄였다(사용자 결정, 등급 L 에서 M). 착수 전 코드 확인에서 TASKS 행의 "HTTP 생산자·소비자
호환성" 은 앱 서비스끼리 HTTP 호출이 없어 gateway 라우팅과 Toss 로 좁혀졌다. 이벤트 payload 타입은 `common`
공유라 같은 커밋의 필드 불일치는 컴파일이 막는다.

결함 주입 23건 중 20건을 지목한 JVM 테스트가 잡았고 3건은 공백이었다(PAY-005 코드, `payment.failed` 알림,
예약 실패 취소 알림). 배선이 없는 단언 7건은 모두 조회 1회라 빼도 시간이 줄지 않는다. 시간은 스택 기동에
있다(scenarios 잡에서 시나리오 82초, 기동 157초). 후속으로 D-047(JVM 공백 3건)과 D-048(스택 기동 시간)을 열었다.

착수 중 main 복구를 먼저 했다([#152](https://github.com/Kimgyuilli/PeekCart/pull/152)). `PaymentSecurityIntegrationTest`
가 공유 DB 를 비우지 않아 앞 클래스가 남긴 결제 행에 404 대신 200 을 받았고, #150·#151 머지 run 의 gate 를
연달아 떨어뜨렸다.

앞선 미충족 둘을 여기서 닫는다. D-041 의 V7: #151 머지 run
[36338959413](https://github.com/Kimgyuilli/PeekCart/actions/runs/36338959413) 의 음성 대조군 단계는 429초로,
직전 main run 의 658초에서 줄었다. D-045·D-046 의 전체 통과: 이 작업의 검증 실행에서 `./gradlew test` 가
한 번에 통과했다(1253 테스트, 실패 0).

미충족: 배포 버전 스큐는 e2e 와 JVM 모두 보지 않는다(ADR-0032 에 공백으로 기록). A0(단가 캐시 전제)와
D3(DLQ 식별자 non-null)는 주입하지 않아 JVM 중복으로 판정하지 않았다.

## e2e 음성 대조군의 실패 지점 특정과 결함 주입 경계 (D-041, [#151](https://github.com/Kimgyuilli/PeekCart/pull/151), 2026-09-28)

ADR-0031 로 결함 주입 경계를 정했다. 주입은 스택 바깥의 기존 수단(ShedLock 행, 컨테이너 정지, pg-stub
script, 입력 데이터)으로만 하고 서비스에 테스트 제어 표면을 두지 않는다. 대조군은 runner 안에서
`Timeout.stage` 로 실패 지점을 특정하고, 부재는 원인 관측 + 시스템 주기에서 유도한 창 + 주입 제거 후 복원
확인으로 증명한다. 대조군 목록은 `docs/06-testing-strategy.md` §13-1-b 이고 D-042 의 입력이다.

착수 전 D-033 CI 로그로 대조군 461초를 나눴다. 405초가 고정 대기였고, ② 는 시나리오 A 첫 줄에서 죽어
예약 단계에 닿지 않는 공허한 대조군이었다. 구현 중 복원 확인이 한 건을 더 잡았다. ① 이 ShedLock 행을
DELETE 로 해제해 poller 가 GC 전까지 락을 못 잡았고(ShedLock 6.3.1 `LockRecordRegistry`), 그 상태에서 ③ 이
예약도 취소도 없이 통과할 수 있었다. 해제를 `lock_until` UPDATE 로 바꾸고 ③ 에 예약 성공 관측을 더했다.

로컬 검증: 시나리오 4종 통과, 대조군 전량 통과(대조군 구간 305초), 무주입·엉뚱한 지점 주입 4종에서 대조군
FAIL, `--self-test` 14종. 대조군 6종은 유지한다.

미충족: CI 대조군 소요를 D-033 기준선 461초와 대조하는 것(V7)은 PR CI 실행 후 기록한다.

## 무응답 Redis 상한을 부하 잡음과 분리 (D-045·D-046, [#150](https://github.com/Kimgyuilli/PeekCart/pull/150), 2026-09-28)

D-044 의 미충족을 이어받아 product-service 공유 컨테이너 기동 대기 초과(D-045)를 재현하려 했다.
단독 `--rerun` 4회와 전체 `--rerun` 1회 모두 기동 시간이 정상 범위(MySQL 12~23초, Kafka 5~11초)라
재현 불가, 호스트 일시 지연 추정으로 닫았다. 코드 조치는 하지 않았다.

그 전체 실행에서 `ProductCacheFallbackIntegrationTest` V3 가 2.764s 로 상한 2.6s 를 넘었다(D-046).
로그상 타임아웃은 정확히 4회였고 초과분은 DB 조회 구간의 부하 잡음이다. 시간 상한은 timeout 소실·증가만
보도록 3.5s 로 두고, 캐시 경로 계약은 재고 캐시 get·put 증가분 단언으로 옮겼다. 곁에서 `FallbackSnapshot`
baseline 에 `productStock` 이 빠져 V1 재고 단언이 누적값이던 것을 고쳤다. 새 ADR 은 필요하지 않다.

미충족: 전체 `./gradlew test` 가 한 번에 통과한 실행이 여전히 없다. 수정 후에는 product-service 만 다시 돌렸다.

## 서비스 test 태스크의 무작위 시드를 입력에서 제외 (D-044, [#149](https://github.com/Kimgyuilli/PeekCart/pull/149), 2026-09-27)

서비스 5모듈의 클래스 순서 시드를 명시한 경우에만 test 태스크 입력으로 둔다. 무작위 시드는 `doFirst`
에서 넣어 입력 스냅샷에 잡히지 않는다. user-service 에서 변경 없는 재실행이 1m5s 에서 `UP-TO-DATE`
4s 가 됐다. `-PtestSeed`·`TEST_SEED` 재현 실행은 입력이 달라 반드시 돌고, 원 실행과 같은 클래스 순서를
낸다. 포기한 것은 변경 없는 재실행의 새 순서 1회뿐이고 필요하면 `--rerun` 을 쓴다.

착수 전 범위가 줄었다. CI 는 빌드 캐시를 쓰지 않아 매번 test 를 돌리므로 CI 동작 변화가 없고, TASKS 행이
요구한 CI 벽시계 측정은 필요 없었다. 새 ADR 은 필요하지 않다. Codex 리뷰는 호출하지 않았다(`.cache/codex-off`).

미충족: 전체 `./gradlew test` 가 한 번에 통과한 실행이 없다. 1회차에서 product-service 공유 컨테이너 하나가
기동 대기를 넘겨(`ContainerLaunchException`) 89건이 연쇄 실패했고 2회차는 통과했다. 시드와 무관하다고
판단했으나 재현하지 못했다.

## 로컬 검증 범위 재평가 — 전량 유지 (D-040, [#148](https://github.com/Kimgyuilli/PeekCart/pull/148), 2026-09-27)

ADR-0028 §후속 ④ 를 D-035 이후 실측으로 닫았다. 로컬 `./gradlew test --rerun` 이 706초, 8모듈
1253 테스트 0 실패다. 싱글톤 전환 전 서비스 5모듈 합계가 약 4510초였으므로 `work.md` §8 의 전량
실행을 좁힐 압력이 사라졌다. 규칙은 그대로 두었다.

착수 전 전제 하나가 틀렸다. 변경 없는 `./gradlew test` 는 입력이 같은 모듈을 건너뛸 것으로 봤으나
568초가 걸렸고 서비스 5모듈이 전부 돌았다. 클래스 순서 시드 기본값이 `System.currentTimeMillis()`
이고 test 태스크의 `systemProperty` 로 들어가 입력이 매 실행 바뀐다. 건너뛰는 것은 common · gateway ·
peekcart-common-auth 뿐이다. 시드를 입력에서 빼는 안은 D-035 의 매 실행 셔플 의도와 맞바꾸는 부분이
있어 D-044 로 등록했다. 새 ADR 은 필요하지 않다.

미충족: 서비스 test 태스크의 UP-TO-DATE 불가는 D-044 로 넘겼다. PHASE1·2 progress 는 엔트리가 `###`
헤딩이라 다이제스트가 읽지 못한다.

## product-service 통합 테스트 컨테이너 싱글톤 전환 (D-035 4/4, [#147](https://github.com/Kimgyuilli/PeekCart/pull/147), 2026-09-27)

product-service 통합 테스트 20클래스를 `@Import(SharedContainers.class)` 로 전환해 D-035 를 닫았다.
진입점의 무조건 `@EnableScheduling` 을 제거해 `SchedulingConfig` 게이트로 넘겼다. 브로커 왕복으로
리스너 소비를 관측하는 StockCompensationRefund · DeadLetterLedger 와 타이머 발화를 기다리는
StockSchedulerWiring 만 opt-in 하고 `@DirtiesContext(AFTER_CLASS)` 로 가뒀다(ADR-0029).

Redis 를 Toxiproxy 뒤에 두는 ProductCacheFallback 은 그대로 옮기면 깨진다. Boot 의 프로퍼티 기반
`RedisConnectionDetails` 는 `@ConditionalOnMissingBean` 이라, 공유 Redis 빈이 있으면 `spring.data.redis.*`
가 무시된다. 예외로 두지 않고 프록시를 가리키는 `@Primary` 빈으로 덮었다. Lettuce 와 Redisson 이
같은 빈을 받는다. 빼면 7개 중 5개가 red 다. EXPLAIN 테스트는 공유 DB 의 잔여 행 30개에서 풀스캔으로
뒤집히는 것을 확인하고 매 테스트 전에 DB 를 비우게 했다.

`:product-service:test --rerun` 이 1098초에서 156초가 됐다. 시드 3개 셔플 통과. D-035 ④ 토픽 누적은
최종 19개 전부 고정 이름, 메타데이터 타임아웃 0건으로 4모듈 모두 악화가 없었다. lint 이월 두 건도
닫았다. 대상을 test 소스가 있는 8모듈 전부로 넓히고, settings.gradle 과 대조한 누락 가드(ITC-005)와
FQN 매칭을 넣었다. `./gradlew test` 전량 통과. Codex 리뷰는 호출하지 않았다(`.cache/codex-off`).

미충족: `cleanDatabase` 는 `shedlock` 을 지우지 않으므로, 같은 락 메서드를 두 클래스가 직접 부르게
되면 뒤 클래스가 조용히 skip 된다. 지금은 호출자가 각 한 곳이다. Redis keyspace 전체 정리 헬퍼는
만들지 않았다. 새 ADR 은 필요하지 않다.

## payment-service 통합 테스트 컨테이너 싱글톤 전환 (D-035 3/4, [#146](https://github.com/Kimgyuilli/PeekCart/pull/146), 2026-09-26)

payment-service 통합 테스트 13클래스를 `@Import(SharedContainers.class)` 로 전환했다. 진입점의
무조건 `@EnableScheduling` 을 제거해 `SchedulingConfig` 게이트로 넘겼다. 브로커 왕복으로 리스너
소비를 관측하는 5클래스와 타이머 발화를 기다리는 ShedLock 만 opt-in 하고 `@DirtiesContext(AFTER_CLASS)`
로 가뒀다(ADR-0029).

payment 는 테스트 클래스끼리 고정 토픽을 주고받는 첫 모듈이었다. DeadLetterLedger 가 `*.dlq` 에
보낸 레코드가 Dlq 테스트 리스너(earliest)에 `@BeforeEach` 의 `clear()` 뒤에 도착하는 것을 관측했다.
리스너는 context 기동 시점부터 읽으므로 `cleanKafkaTopics` 로는 순서를 보장할 수 없다. 그래서 같은
클래스 세 번째 케이스가 쓰던 고유 key 필터를 앞 두 케이스에 맞췄다. 수정 전 클래스와 메서드 순서를
강제하면 red, 수정 후 green 이다. 클래스 순서만 강제한 첫 재현은 green 이었다. 메서드 순서까지
고정해야 누수가 드러난다는 점은 계획서 정정 이력에 남겼다.

`:payment-service:test --rerun` 이 1919초에서 55초가 됐다. 시드 3개 셔플 통과. opt-in 6개를 끄면
전부 red 가 된다. D-035 ④ 토픽 누적은 최종 20개 전부 고정 이름이고 메타데이터 타임아웃 0건이었다.
`./gradlew test` 전량 통과. Codex 리뷰는 호출하지 않았다(`.cache/codex-off`).

미충족: lint 가 FQN 표기(`@org.testcontainers.junit.jupiter.Testcontainers`)를 잡지 못하는 기존
사각지대를 검증 중 발견했다. 목록 누락 가드와 함께 product PR 에서 판단한다. 새 ADR 은 필요하지 않다.

## notification-service 통합 테스트 컨테이너 싱글톤 전환 (D-035 2/4, [#145](https://github.com/Kimgyuilli/PeekCart/pull/145), 2026-09-26)

notification-service 통합 테스트 7클래스를 `@Import(SharedContainers.class)` 로 전환했다. 자율
writer 를 가진 첫 확산 대상이라 진입점의 무조건 `@EnableScheduling` 을 제거해 `SchedulingConfig`
게이트로 넘겼고, 리스너 소비를 브로커 왕복으로 관측하는 Consumer · DeadLetterLedger 두 클래스만
리스너를 켜고 `@DirtiesContext(AFTER_CLASS)` 로 가뒀다(ADR-0029). 스케줄러 opt-in 은 0건이다.

`:notification-service:test --rerun` 이 342초에서 69초가 됐다. 시드 3개 셔플 통과. opt-in 을
끄면 두 클래스가 red 가 되는 것으로 조용한 green 이 아님을 확인했다. D-035 ④ 토픽 누적은 최종
12개 전부 고정 이름이고 메타데이터 타임아웃 0건이었다. `./gradlew test` 전량 통과. Codex 리뷰는
호출하지 않았다(`.cache/codex-off`).

lint self-test 픽스처가 `MODULES` 를 순회하도록 바꿔 대상 확대마다 픽스처를 고치던 문제를 없앴다.

미충족: 그 대가로 목록에서 빠진 모듈을 self-test 가 잡지 않는다(product PR 에서 판단). 토픽
누적은 notification 단독 관측이라 payment · product 에서 다시 본다. 새 ADR 은 필요하지 않다.

## user-service 통합 테스트 컨테이너 싱글톤 전환 (D-035 1/4, [#144](https://github.com/Kimgyuilli/PeekCart/pull/144), 2026-09-26)

D-032 가 order-service 에서 검증한 컨테이너 모듈 싱글톤을 user-service 로 넓혔다. 통합
테스트 4클래스를 `@Import(SharedContainers.class)` 로 전환하고, test 태스크에 스케줄러·리스너
기본 off(ADR-0029)와 클래스 순서 랜덤·시드 출력을 넣었으며, `integration-test-container-lint.sh`
검사 대상에 user-service 를 추가했다(self-test 7/7).

`:user-service:test --rerun` 이 176초에서 58초가 됐다. 시드 3개 셔플 통과, cleanup 을 자기
행만 지우도록 바꾼 뮤테이션에서 해당 클래스가 앞선 시드만 통과해 셔플의 검출력을 확인했다.
`./gradlew test` 8모듈 1253 테스트 통과. Codex 리뷰는 호출하지 않았다(`.cache/codex-off`).

미충족: user 는 writer 0 이라 ③ opt-in 조사는 0건이고, Kafka 미사용이라 ④ 토픽 누적 확인은
notification·payment·product PR 로 이월했다. user 에서도 Kafka 컨테이너가 한 번 뜨며 58초에
포함된다. 새 ADR 은 필요하지 않다.

## CI 최종 게이트와 이미지 승격 경계 (D-043, [#142](https://github.com/Kimgyuilli/PeekCart/pull/142), 2026-09-26)

ADR-0030 에 따라 `gate` 가 lint/test/guards/images/e2e 의 실패·skip 을 최종 집계하고,
main push 의 `publish` 는 성공한 gate 뒤에만 시작하도록 연결했다. 이미지 artifact 는
checksum·이미지 ID 를 기록하며 e2e 와 publish 가 로드 후 ID 를 확인한다. 게시 단계는
SHA 태그의 원격 config digest 를 검증하고 같은 manifest digest 로 `latest` 를 승격한 뒤
원격 태그를 재조회한다. 브랜치 보호의 `enforce_admins=true` 를 적용·재조회했다.

로컬 `./gradlew test --no-daemon` 통과(30분 8초, 49 tasks executed), 게이트 배선
변형 검사 24/24와 관련 lint 가 통과했다. PR CI [run 36169884411](https://github.com/Kimgyuilli/PeekCart/actions/runs/36169884411)에서
두 e2e 모드와 모든 선행 job 이 성공했고, 대조군 완료 뒤 gate 가 시작해 성공했다.
PR 의 publish 는 건너뛰었다. 이후 [main push run 36228604631](https://github.com/Kimgyuilli/PeekCart/actions/runs/36228604631)에서
시나리오 08:11:26 UTC·음성 대조군 08:17:28 UTC 완료 뒤 gate 가 08:17:31 UTC 시작해
08:17:45 UTC 성공했고, 6개 publish 는 모두 그 뒤에 시작해 성공했다. GHCR 원격에서
커밋 SHA 태그와 `latest` 의 manifest digest 가 6개 이미지 모두 일치함을 재조회했다.
브랜치 보호의 필수 체크는 `gate`(GitHub Actions 앱 ID `15368`), `strict=true`,
`enforce_admins=true` 로 재확인했다. D-043 완료(2026-09-26, see ADR-0030).

## main 필수 체크 복구 (D-037, [#141](https://github.com/Kimgyuilli/PeekCart/pull/141), 2026-09-25)

브랜치 보호가 존재하지 않는 `build` 체크를 요구하던 상태를 확인하고, 실제 CI 의 `gate`
체크로 교체했다. PR #140 head 에서 `gate` 를 발행한 GitHub Actions 앱 ID `15368` 을 확인한
뒤 같은 앱으로 고정했다. `strict=true` 와 다른 보호 설정은 유지했다.

GitHub API 재조회, 게이트 실패 전파 lint 자체 검사 6/6, `./gradlew test` 가 통과했다.
별도 Codex 리뷰는 호출하지 않았다. 실제 실패 PR 의 병합 UI 는 재현하지 않았다.
관리자 우회(`enforce_admins=false`)와 `gate` 밖의 lint·이미지·e2e 검증 범위는
D-043 에서 다룬다. 새 ADR 은 필요하지 않다.

## e2e 시나리오와 음성 대조군 병렬 실행 (D-033, [#140](https://github.com/Kimgyuilli/PeekCart/pull/140), 2026-09-25)

D-032 CI 실측에서 e2e 16.1분이 단독 병목으로 확인돼 ADR-0028 §후속 ①을 재판정했다.
PR에서 음성 대조군을 계속 실행하면서 직렬 대기를 없애기 위해 `e2e`를 `scenarios`와
`negative-control` 매트릭스로 분리했다. 두 실행은 별도 러너와 cold start 스택을 쓰고,
각자 run ID·증적 artifact 이름을 가진다. 시나리오 증적 게이트는 시나리오 잡에 남겼다.

워크플로 배선 lint는 모드 누락, 이미지 의존·증적 게이트 삭제, 중복 artifact 이름 등을
변이 7종으로 검출한다. 로컬 `./gradlew test` 전량 통과(27분 54초). 별도 Codex 리뷰는
호출하지 않았다.

PR CI [run 36075940872](https://github.com/Kimgyuilli/PeekCart/actions/runs/36075940872)은
전체 성공했다. 두 e2e 잡이 00:08:58 UTC에 동시 시작해 시나리오 5분 13초,
음성 대조군 10분 54초에 성공했다. 전체 14분 10초로 D-032 PR 실측 20분 49초보다
6분 39초 짧았다(단일 run 비교). product-service 테스트 13분 44초와 대조군이
거의 함께 끝나 새 임계경로를 이룬다.
별도 러너의 이미지 로드와 스택 기동이 중복되므로 러너 사용 시간은 늘 수 있다.
`publish`가 e2e를 기다리지 않는 기존 계약은 이번 변경에서 유지했다.

## 통합 테스트 컨테이너 모듈 싱글톤 전환 (D-032, [#138](https://github.com/Kimgyuilli/PeakCart/pull/138), 2026-09-24)

ADR-0028 의 결정을 order-service 에 구현했다. `:order-service:test` 975초 중 844초(87%)가
컨테이너 부팅·Flyway·context 기동이었고, 그것을 모듈 싱글톤으로 합쳤다. 로컬 221~277초.

**착수 후 범위가 늘었다.** 계획서의 "Kafka 위험 없음" 판정이 오판이었고(테스트 헬퍼의 UUID
토픽만 보고 애플리케이션 고정 토픽을 놓쳤다), 그 아래에 **자율 writer 가 테스트에서 기본
on** 이라는 근본 원인이 있었다. 스케줄러와 `@KafkaListener` 둘 다였고, 프로덕션 코드를
건드리는 결정이라 ADR-0029 로 먼저 고정한 뒤 적용했다 (see ADR-0029).

**셔플(V-4)이 결함 4건을 드러냈다.** 순차 실행 420건 전건 통과는 순서가 운 좋았던 것이다.
그중 리스너 결함은 기존 워크어라운드가 무효라는 것까지 파고들어야 했다 — `groupId` 가
상수라 캐시된 다른 context 의 consumer 가 파티션을 넘겨받는다. 컨테이너를 빈으로 노출하면
context 파괴 시 새 포트로 재기동돼 캐시된 다른 context 가 전멸하는 것도 여기서 나왔고,
그 때문에 **ADR-0028 §Decision 의 메커니즘 서술이 사실과 달라져** `fix(adr):` 로 정정했다.

**V-7 CI 실측**: `:order-service:test` 가 975초에서 **224초**로 줄었다(4.35배). 잡 벽시계는
18.6분에서 5.2분이다. run5 이상치(7018초/27건)는 CI 에서 재현되지 않아 로컬 자원 고갈
가설이 남는다.

**그런데 CI 전체 벽시계는 줄지 않았다** — 19분 03초에서 20분 49초다. D-031 이 test 를
임계경로에서 뺀 뒤라 임계경로는 `images → e2e` 이고, test 에서 13.4분을 걷어내도 전체는
그만큼 줄지 않는다. **ADR-0028 이 적은 "19분 → 약 16분" 예측이 빗나갔다.** 이번 run 의
`images` 가 1.9분에서 3.4분으로 늘어난 것은 새 브랜치라 `type=gha` 캐시가 콜드였던
것이다(D-034 가 기록한 ref 격리와 같은 현상). 이 전환의 값어치는 CI 벽시계가 아니라
**로컬 반복 비용과 확장성**이고, 그 판단은 계획서 §명제가 처음부터 적어둔 것이다.

부수로 `e2e` 16.1분이 단독 병목이라는 **D-033 의 전제가 실측으로 확증**됐다.

V-6 은 기대에 못 미쳤다. 25개 context 가 15회 기동하는데, `@DirtiesContext` 를 붙인
4클래스가 재생성을 강제하기 때문이다. **격리를 사서 캐시 적중을 일부 내준 것**이고
ADR-0029 §Consequences 의 트레이드오프가 수치로 나타났다. 확산(D-035)에서 opt-in 이 늘면
이 수가 이득을 깎는다.

## 테스트 자율 writer 정책 확정 (D-036, ADR-0029, [#138](https://github.com/Kimgyuilli/PeakCart/pull/138), 2026-09-24)

D-032 의 V-4 blocker 를 풀기 위한 선행 결정이다. 구현(`SchedulingConfig` 신설,
`OrderApplication` 의 `@EnableScheduling` 제거)이 결정보다 앞서 있던 상태를 되돌렸다 —
ADR-0028 은 *컨테이너 수명* 결정이지 *자율 writer 정책* 이 아니다.

**진단이 계획서보다 한 단계 깊었다.** 계획서 §2-3c 는 결함 4를 "`@KafkaListener` 가 자율
writer 다" 까지 적었으나, 그 테스트에는 **이미 워크어라운드가 있었다**(`@BeforeEach` 에서
자기 context 의 리스너 컨테이너를 `stop()`, 2026-09-11 `89955c1`). 그런데도 실패하는 이유를
실측으로 갈랐다 — `@KafkaListener` 의 `groupId` 가 하드코딩 상수라 **모든 캐시된 context 가
같은 그룹으로 같은 브로커에 붙는다.** 세 클래스만 돌린 런에서 `order-svc-stock-result-group`
에 서로 다른 context 의 consumer 2개(`-36`, `-44`)가 공존하고 `generation 4` 까지 리밸런스하며
파티션이 넘어가는 것을 확인했다. **per-context 수단으로는 구조적으로 막을 수 없다** 는 것이
이 ADR 이 필요했던 이유다.

`spring.kafka.listener.auto-startup` 이 듣지 않는 이유도 함께 확인했다 — 5개 서비스 전부
`ConcurrentKafkaListenerContainerFactory` 를 손수 `@Bean` 으로 만들어 Boot 의 auto-configured
factory 를 쓰지 않는다.

**결정(ADR-0029)**: 테스트에서 자율 writer(스케줄러 + Kafka 리스너)는 기본 off, 그 동작을
검증하는 테스트만 opt-in, 켠 테스트는 `@DirtiesContext(AFTER_CLASS)` 로 수명을 자기 클래스에
가둔다. 리스너 게이트는 Boot 속성이 아니라 factory 의 `autoStartup` 에 건다. 대안 5종
(현행 per-context stop · Boot 속성 · `groupId` 랜덤화 · 테스트 전용 프로파일 · `@MockBean`)의
기각 사유를 함께 남겼다.

**적용은 이 항목 밖이다** — order-service 는 D-032, 나머지 4모듈은 D-035 에서 한다.

## CI 그래프 직렬화 해소 + 컨테이너 수명 결정 ([#135](https://github.com/Kimgyuilli/PeakCart/pull/135), 2026-09-23)

CI 36분의 구조를 측정으로 분해하고, 그 결과를 ADR-0028 로 고정한 뒤 1단계를 구현했다.

측정이 먼저였다. 실측 [run 35731821466](https://github.com/Kimgyuilli/PeakCart/actions/runs/35731821466)
에서 `test(order) 1051s` · `gate 15s` · `images 173s` · `e2e 856s` 가 직렬로 이어져 36분 중
34.5분을 차지했다. 샤드 내부는 더 극단적이었다 — `:order-service:test` 975초 중 JUnit XML 이
보고하는 클래스 시간은 **131초뿐**이고 나머지 **844초(87%)** 가 컨테이너 부팅·Flyway·context
기동이다. JUnit 이 static initializer 를 testcase 시간에 넣지 않아 그동안 보이지 않던 구간이다.

**결정(ADR-0028)**: 컨테이너 수명을 per-class 에서 모듈 싱글톤으로 바꾼다. 싱글톤 전환 ·
`@Container` 금지 lint · 순서 셔플 검증을 한 묶음으로 간다. 대안 6종(JUnit 병렬 · Redpanda ·
withReuse · 러너 코어 재배분 · e2e PR 제외 · 모듈 분할)의 기각 사유를 함께 남겼다.
워크플로 그래프 변경은 YAML 한 줄이고 되돌림이 즉시라 ADR 보호 대상에서 제외했다.

**구현(D-031, 1단계)**: `images` 의 needs 를 `[lint]` 로 완화해 test 와 병렬화했다.

착수 후 범위가 늘었다. `publish` 가 `needs: images` 하나만 걸고 있어서 그동안 `images` 를 거쳐
**간접적으로만** 게이트되고 있었고, 그 경로를 끊으면 테스트가 실패한 main push 에서도 GHCR 에
`:latest` 가 올라간다. `publish: needs: [images, gate]` 로 직결하고, 그 연결이 다시 끊기지
않도록 `scripts/ci-release-gate-lint.sh` 를 신설했다. 도달성만 보지 않고 `gate` 의 실패 전파
스텝 존재까지 본다 — `gate` 는 `if: !cancelled()` 라 선행이 실패해도 초록으로 끝날 수 있어서다.
이 발견으로 등급이 S 에서 M 으로 올라갔다.

`images` 빌드를 buildx + `type=gha` 캐시로 전환했다. scope 를 서비스별로 나눈다(매트릭스 6개가
단일 scope 를 공유하면 서로의 캐시를 덮어쓴다). `load: true` 가 필수인데 `docker-container`
드라이버가 결과를 로컬 daemon 에 남기지 않아 뒤따르는 health smoke 와 `docker save` 가 이미지를
찾지 못하기 때문이고, 로컬에서 양방향으로 확인했다.

**실측 (머지 후)**:

| | 베이스라인 | 변경 후 | 차이 |
|---|---|---|---|
| PR run | 36분51초 | **21분51초** | -15분 (-40.7%) |
| main push run | 39분18초 | **21분20초** | -18분 (-45.7%) |

`images` 가 15:04:50 에 시작하고 `test(order)` 가 15:20:29 에 끝났다. **15분39초 앞서 시작**해
직렬 사슬이 실제로 끊겼다. 다만 목표였던 20분에는 1분51초 미달이다.

**릴리스 게이트가 실작동했다.** main push run 에서 `gate` 종료(15:49:38) 후 `publish` 6개가
15:49:42 에 시작했다. PR 에서는 `publish` 가 skip 되므로 이 확인은 머지 후에만 가능했다.
`ci-release-gate-lint` 도 CI 에서 `self-test OK (6/6)` 로 조작 입력 4종을 전부 탐지했다.

**임계경로가 이동했다.** `test(order) 1051s -> gate -> images -> e2e 856s` 였던 것이
`lint 51s -> images 206s -> e2e 949s` 가 됐다. 20분 미달의 원인은 **e2e 단독**이고
(949초 = 전체의 72%), 이것은 ADR-0028 §후속 ① 이 예측한 상태이며 D-033 의 표적이다.

**미충족**:

- ~~buildx `type=gha` 캐시가 순손실이다~~ → **D-034 에서 유지로 판정(2026-09-22).** 다음
  main push run [35751377973](https://github.com/Kimgyuilli/PeakCart/actions/runs/35751377973)
  에서 `CACHED` **30스텝**, `images` **86~130초**로 베이스라인(104~173초)보다도 빨랐다.
  첫 run 이 느렸던 원인은 GitHub Actions 캐시의 ref 격리였다 — PR 브랜치가 채운 캐시를
  main push 가 읽지 못해 콜드였고 `cache-to mode=max` 의 export 비용만 냈다
- 로컬 `--load` 재빌드가 6.25초에 CACHED 29스텝이었던 것은 BuildKit 로컬 캐시였고,
  `type=gha` 의 거동을 예측하지 못했다
- `publish` 가 `e2e` 를 기다리지 않는 것은 **기존 상태**이고 범위 밖으로 두었다. D-033 에서
  e2e 실행 정책을 정할 때 함께 본다

**후속**: D-032(싱글톤 본체, 844초가 표적) · D-033(e2e 음성 대조군 605초 정책 재판정).
D-034 는 같은 날 판정 완료.
Codex 리뷰는 계획·diff 양쪽 모두 `.cache/codex-off` 로 차단된 상태에서 진행했다(의도적 생략).

## Phase 5 기반 세팅 — 로드맵 축 제거 ([#134](https://github.com/Kimgyuilli/PeakCart/pull/134), 2026-09-22)

Phase 4 가 종결([#133](https://github.com/Kimgyuilli/PeakCart/pull/133))되면서 사전 로드맵이
소진됐다. Phase 5 는 순서표를 다시 만들지 않기로 했으므로, **문서가 순서표를 전제하던
자리들을 먼저 걷어냈다.** 코드 변경 0.

무엇을 했나:

- `docs/TASKS.md` — `## 현재 단계` 를 Phase 5 로 교체. 그 자리를 차지하던 Phase 4
  설계(A1~A4.5)·구현(①~⑥) 표는 **아래 `## Phase 4 — MSA 분리` 섹션으로 합쳤다**
  (두 곳에 나뉘어 있던 Phase 4 기술이 한 곳이 됐다)
- 부채표 제목을 `개발 부채 / 작업 (Tech Debt & Backlog)` 로. **표를 나누지 않는 것이
  결정이다** — 수요 기반에서 "부채 해소"와 "신규 작업"은 같은 성격이고, 표가 둘이면
  항목 추가 때마다 분류부터 해야 한다. 다음 번호는 D-031
- `docs/progress/PHASE5.md` 신설 — `harness-context.sh` 가 `현재 단계` 줄에서 Phase
  번호를 추론해 `PHASE{N}.md` 를 읽으므로, 이 파일이 없으면 다이제스트가 파서 고장으로 떨어진다
- `docs/07-roadmap-portfolio.md §16` — Phase 5 항목을 추가하되 **작업 목록도 Exit
  Criteria 도 적지 않았다.** 적으면 그게 로드맵이 되기 때문이다. 현황은 TASKS.md 를 가리킨다

**검증**: `scripts/harness-context.sh --check` = `ok` (단계가 Phase 5 로 읽히고 PHASE5.md 를
찾는다) · `scripts/plans-index.sh --check` exit 0.

**부수 정리**: `task-phase4-closure` 계획서가 PR 링크 부재로 `보류` 였다. #133 이 근거임을
확인해 계획서에 링크를 적고 `done/` 으로 아카이브했다(인덱스 51행).

**미충족**: 없다. Phase 5 는 종결 조건이 없는 단계이므로 "남은 항목" 개념이 이 작업에 없다.

**다음**: D-030 (Slack 채널 분리 + DLQ 적재량 메트릭) — 5서비스 분리로 per-service
태그(ADR-0015)가 갖춰져 라우팅 기준이 이미 있다.
