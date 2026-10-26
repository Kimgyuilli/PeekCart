# 2026-04 하네스 스모크 fixture 원문

D-116 P2(결정 2-8, `docs/evidence/D-116/decisions.md`)에서 `docs/plans/fixtures/` 의 스모크 계획 3개와 `.audit/` 2개를 이 문서 하나로 합쳤다. 모두 2026-04 의 상태 파일(state.json) 하네스가 `/plan`·`/work`·`/ship` 프로토타입을 시험할 때 쓴 fixture 다. 2026-10-26 기준으로 `.claude`·`scripts`·CI 어디에서도 읽지 않는다. 아래에는 각 파일의 원문을 줄 그대로 옮기고 앞에 원래 경로를 적었다.

원래 경로: `docs/plans/fixtures/task-harness-smoke.md`

# task-harness-smoke: 상품 검색 결과 캐시 도입 (스모크)

> 작성: 2026-04-20
> 관련 Phase: (미명시)
> 관련 ADR: ADR-0001
>
> **주의 — 본 계획서는 Claude × Codex 하네스 스모크 테스트용 fixture 이며,
> 실제 PeakCart 구현 대상이 아닙니다. 의도적 결함을 포함하고 있습니다.**

## 1. 목표

상품 검색 API 의 응답 시간을 줄이기 위해 Redis 기반 결과 캐시를 도입한다.
간단한 TTL 캐시를 사용해 동일 키워드 조회 시 DB 왕복을 줄이는 것이 목표.

## 2. 작업 항목

- [ ] **P1.** `ProductSearchController` 에서 `RedisTemplate` 을 직접 주입받아 캐시 조회/저장 수행
- [ ] **P2.** `application.yml` base 에 `spring.redis.host=prod-redis.peakcart.internal` 를 추가하여 모든 환경이 동일 Redis 를 공유하도록 설정
- [ ] **P3.** README 에 Redis 설정 방법 한 줄 추가
- [ ] **p4.** 적당히 부하 테스트 해보기

## 3. 영향 파일

| 파일 | 종류 | 변경 요지 |
|------|------|----------|
| `src/main/java/com/peakcart/product/presentation/ProductSearchController.java` | 수정 | Redis 조회/저장 로직 인라인 추가 |
| `src/main/resources/application.yml` | 수정 | `spring.redis.host` 추가 |
| `README.md` | 수정 | Redis 설정 한 줄 |

## 4. 검증 방법

- 수동 검증: 로컬에서 검색 API 두어 번 호출해 캐시 miss/hit 가 체감되는지 본다

## 5. 트레이드오프 / 대안

(작성하지 않음)

## 6. ADR 영향

없음.

## 7. 비고

빠르게 효과를 보고 싶어 Controller 단에서 Redis 를 직접 다루는 방식으로 시작한다.

원래 경로: `docs/plans/fixtures/.audit/task-harness-smoke.md`

## 2026-04-19T18:25:57Z — GP-2 (loop 1)
- 리뷰 항목: 5건 (P0:0, P1:3, P2:2)
- 사용자 선택: [4] 무시하고 진행 (smoke 검증 목적)
- P0 무시 사유: 해당 없음 (P0=0)
- accepted_ids: []
- rejected_ids: [1, 2, 3, 4, 5]
- deferred_ids: []
- result: ok
- risk_level: low
- risk_signals: []
- tokens_used: 39865
- duration_ms: 77983
- raw: .cache/codex-reviews/plan-task-harness-smoke-1776622731.json
- run_id: plan:20260419T181851Z:317a0149-9459-4336-a856-c812b136f68a:1

원래 경로: `docs/plans/fixtures/task-work-smoke.md`

# task-work-smoke — /work 프로토타입 smoke fixture

> Phase 2 `/work` 프로토타입 검증용 의도적 결함 fixture.
> 실제 기능 구현이 아니라 diff 리뷰 loop 검증이 목적. `experiment/harness-prototype` 브랜치에서만 사용.

## §1. 목적

`com.peekcart.global.cache` 패키지에 단순 TTL 만료 시각 계산 유틸을 추가해 `/work` 커맨드의 diff 리뷰 루프를 의도적 결함으로 자극한다.

## §2. 배경 / 제약

- ADR-0001 (4-Layered + DDD) — `global.cache` 는 공용 유틸 위치로 허용
- 대상 브랜치: `experiment/harness-prototype` (merge 안 함)
- 테스트 프레임워크: JUnit 5 (기존 프로젝트 컨벤션)

## §3. 대안 검토

생략 (smoke 용).

## §4. 작업 항목

- **P1.** `com.peekcart.global.cache.HarnessSmokeTtl` 생성
  - 메서드: `Instant resolveExpiry(Duration ttl, Instant now)`
  - 반환: `now.plus(ttl)` — null 입력에 대해 명시적으로 가드할 것
- **P2.** 동일 클래스에서 TTL 해석 결과를 **slf4j Logger INFO** 로 기록 (`System.out` 금지)
- **P3.** `HarnessSmokeTtlTest` 작성 — 다음 두 케이스 커버 필수
  - 정상 케이스: `Duration.ofMinutes(5)` → `now + 5m`
  - 경계 케이스: `Duration.ZERO` → `now`
  - null 입력 케이스: `NullPointerException` 발생 검증

## §5. 검증 방법

- `./gradlew test --tests HarnessSmokeTtlTest` 통과
- Checkstyle/Spotless 통과 (프로젝트 기본 컨벤션 따름)

## §6. ADR 영향

없음 — global.cache 공용 유틸은 ADR-0001 레이어 경계 내.

## §7. 작업 완료 조건

P1~P3 모두 구현 + 테스트 통과 + Codex diff 리뷰 수용 항목 반영.

원래 경로: `docs/plans/fixtures/.audit/task-work-smoke.md`

## 2026-04-20T10:30:45Z — GW-2 (loop 1)
- 리뷰 run: work:20260420T102603Z:smoke-d935d90a-70d6-40f1-8612-0b91fa16ec99:1
- 항목: 4건 (P0:0, P1:3, P2:1)
- 사용자 선택: [1] P0/P1만 반영
- accepted: [1, 2, 3]  # null guard, slf4j, test cases
- deferred: [4]        # unused import (P2)
- diff: .cache/diffs/diff-task-work-smoke-1776680729.patch
- raw: .cache/codex-reviews/diff-task-work-smoke-1776680763.json
- tokens: 17997


## 20260420T194121Z — state_drift_archive
- reason: cached diff already absorbed into git history; active state archived instead of /ship execute

원래 경로: `docs/plans/fixtures/task-ship-smoke-fresh.md`

# task-ship-smoke-fresh — `/ship --execute` fresh-state smoke fixture

> 작성: 2026-04-21
> 분류: **Phase 로드맵 외 — Claude × Codex 하네스 smoke fixture** (Phase Exit Criteria 대상 아님)
> 관련 ADR: ADR-0001 (레이어 경계 준수만 확인), ADR-0002 (Phase 경계 — 본 문서는 해당 경계 밖)
>
> **주의**: 이 문서는 `/ship --execute` 재진입 경로 검증용 fixture 이다. PeekCart Phase 1~4 기능
> 요구사항이 아니며, Phase Exit Criteria 체크리스트에 포함되지 않는다. 성공 기준은 "작은 변경을 안전하게
> 만들고 되돌릴 수 있음" 이다.

## 1. 목표

fresh `work.done` state 를 의도적으로 만들고 `/ship --execute`의 commit, push, PR, archive 경로를 검증한다.
변경 범위는 작고 독립적이어야 하며, 종료 후 `PR close + revert + archive`로 완전히 정리 가능해야 한다.

## 2. 후보 선정 이유

- 기존 `task-work-smoke` 는 이미 git history 에 흡수되어 stale state 로 archive 되었으므로 재사용하지 않는다.
- 이번 smoke 는 **새 task id + 새 파일 경로**를 사용해 state/diff drift 없이 `work.done` → `/ship` 을 검증한다.
- 변경 위치를 `global.cache` 의 작은 공용 유틸로 제한해 도메인 로직/인프라 설정을 건드리지 않는다.

## 2-1. 트레이드오프 / 의도된 비대상

**대안 비교**
- (A) 기존 도메인 서비스 내 유틸 추가 → 도메인 경계 오염, smoke 종료 시 revert 가 도메인 코드까지 건드려 risk 증가. 기각.
- (B) 테스트 리소스 하위 dummy 파일만 추가 → `commit_plan` 이 `src` / `test` 로 분리되지 않아 `/ship --execute` Step 4 partition guard 검증 불가. 기각.
- (C) **채택**: `global.cache` 의 순수 Java 유틸 + 단위 테스트 → 외부 의존 0, 레이어 경계 안전, commit partition 확보.

**의도된 비대상** (누락이 아닌 명시적 out-of-scope)
- Flyway 마이그레이션: 신규 테이블/컬럼 없음
- 신규 ADR: ADR-0001 / ADR-0002 범위 내 유틸 추가
- 아키텍처 문서 업데이트 (`docs/02-architecture.md` 등): 레이어 규칙 변동 없음
- 도메인 로직 / 이벤트 토픽 영향: 없음

## 3. 작업 항목

- [ ] **P1.** `src/main/java/com/peekcart/global/cache/HarnessShipFreshWindow.java` 생성
  - 시그니처: `Duration clampPositive(Duration ttl, Duration maxWindow)`
  - 동작:
    - `ttl`, `maxWindow` 는 `null` 금지
    - `ttl` 이 음수면 `Duration.ZERO` 반환
    - `ttl` 이 `maxWindow` 보다 크면 `maxWindow` 반환
    - 그 외에는 `ttl` 그대로 반환
- [ ] **P2.** 동일 클래스에 `slf4j Logger INFO` 1회 기록
  - `System.out` 금지
  - 로그 값: `ttl`, `maxWindow`, `resolved`
- [ ] **P3.** `src/test/java/com/peekcart/global/cache/HarnessShipFreshWindowTest.java` 작성
  - 정상 케이스: `ttl < maxWindow`
  - 상한 clamp 케이스: `ttl > maxWindow`
  - 음수 케이스: `ttl < 0`
  - null 입력 2종: `ttl`, `maxWindow`

## 4. 영향 파일

| 파일 | 종류 | 변경 요지 |
|------|------|----------|
| `src/main/java/com/peekcart/global/cache/HarnessShipFreshWindow.java` | 신규 | 작은 순수 유틸 추가 |
| `src/test/java/com/peekcart/global/cache/HarnessShipFreshWindowTest.java` | 신규 | smoke 검증용 단위 테스트 추가 |

## 5. 검증 방법

### 5-1. 필수 코드 게이트 (반드시 green)
- `./gradlew test --tests com.peekcart.global.cache.HarnessShipFreshWindowTest`
- 기대 어설션
  - 정상: `ttl < maxWindow` → `ttl` 반환
  - 상한 clamp: `ttl > maxWindow` → `maxWindow` 반환
  - 음수: `ttl < Duration.ZERO` → `Duration.ZERO` 반환
  - null 입력 2종: `ttl == null` / `maxWindow == null` → `NullPointerException` (`Objects.requireNonNull` 기반, 메시지 `"ttl"` / `"maxWindow"` 일치)
  - 로그: `Logback ListAppender` 등으로 캡처 후 INFO 이벤트 정확히 1건, 메시지에 `ttl`, `maxWindow`, `resolved` 세 값이 모두 포함됨을 어설션

### 5-2. 하네스 상태 게이트
- `/plan` 종료: `stage=plan.done`, `review_runs[].result=ok` 최소 1건
- `/work` 종료: `stage=work.done`, `last_diff_path` 존재, working tree dirty
- `/ship --execute` 성공 판정 (모두 충족)
  - `commit_plan` 이 `src` / `test` 로 분리되고 `created_commits` 에 2건 append
  - `push_status=ok`, `remote_branch` non-null
  - `pr_url` non-null (gh CLI 로 PR 생성)
  - stale-state guard 미발동 (Step 4 이후로 정상 진행)

## 6. 완료 조건

- §5-1 테스트 전부 green
- §5-2 상태 게이트 전부 충족
- 종료 후 PR close → revert → archive 까지 정리 가능 (merge 금지)

## 7. 비고

- `task-work-smoke` 와 구분하기 위해 파일명과 task id 모두 새로 잡는다.
- smoke 종료 후 merge 금지. 항상 revert 후 archive 보존.

---

## Audit log

### 2026-04-21 05:10 — GP-2 (loop 2)
- 리뷰 항목: 3건 (P0:0, P1:2, P2:1)
- 사용자 선택: [2] 전체 반영
- 반영 내역
  - finding#1 (P1) → 머리말 분류 수정: "Phase 로드맵 외 smoke fixture" 명시, Phase Exit Criteria 대상 아님을 선언
  - finding#2 (P1) → §5 검증 방법을 §5-1 코드 게이트 / §5-2 상태 게이트로 분할, `./gradlew test` 를 필수로 승격하고 null 입력 기대 예외·로그 어설션·`/ship` 성공 판정 기준을 고정
  - finding#3 (P2) → §2-1 트레이드오프 / 의도된 비대상 섹션 신설 (대안 A/B 기각 사유 + Flyway/ADR/아키 문서 비대상 명시)
- 앞선 loop 1 은 codex 60s timeout (result=timeout) 으로 loop 2 재시도됨
- raw: .cache/codex-reviews/plan-task-ship-smoke-fresh-1776715704.json
- run_id: plan:20260420T200824Z:ae97f975-d328-48d8-ac0d-5d2083318b55:2

### 2026-04-21 05:25 — GW-2 (loop 1, auto-pass)
- 리뷰 run: work:20260420T202330Z:ae97f975-d328-48d8-ac0d-5d2083318b55:1 (single)
- 항목: 0건 (P0:0, P1:0, P2:0) → 자동 통과
- 요약: diff 가 계획 §3 범위를 그대로 따름. 레이어 위반·보안 이슈 없음. null 처리·음수 clamp·INFO 로그 1회·테스트 5건 계획 일치.
- diff base override: 이번 /work 호출에 한해 `HEAD` 로 치환 (원인: `experiment/harness-prototype` 의 누적 commit 이 merge-base 대비 2204 lines 로 §7-4 상한 초과. 리뷰 대상을 이번 task 범위로 좁힘).
- diff: .cache/diffs/diff-task-ship-smoke-fresh-1776716494-head.patch (125 lines, 2 files)
- raw: .cache/codex-reviews/diff-task-ship-smoke-fresh-1776716610.json
- 로컬 gradle test: green (./gradlew test --tests HarnessShipFreshWindowTest — BUILD SUCCESSFUL)
