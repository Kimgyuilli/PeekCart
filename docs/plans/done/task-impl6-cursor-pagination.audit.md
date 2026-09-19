# task-impl6-cursor-pagination — 계획 리뷰 audit

## 2026-08-30 — 계획 리뷰 라운드 1
- 항목: 12건 (P0:0, P1:6, P2:6)
- 처리: 반영 12건 / 기각 0건
- 뒤집힌 전제:
  - V11 `@Min/@Max` 가 400 을 준다 → **500**. `GlobalExceptionHandler` 에 `HandlerMethodValidationException` 핸들러 부재, 포괄 `Exception` 핸들러가 선점
  - V12 롤아웃 중 단일 계약 유지 → **불가**. `deployment.yml:11-17` RollingUpdate `maxSurge:1`
  - V13 JVM 기본 timezone 고정 존재 → **grep 0건**. epoch 인코딩 불가
  - V5 "외부 소비자 0" → 증명한 것은 "레포 내 소비자 0" 뿐
- raw: `.cache/codex-reviews/plan-task-impl6-cursor-pagination-1788084999.json`

## 2026-08-30 — 계획 리뷰 라운드 2
- 항목: 10건 (P0:0, P1:5, P2:5)
- 처리: 반영 10건 / 기각 0건
- **1R 수정이 만든 새 결함 5건** (D4↔N5 모순 · P6 구현 불가 · ISO 가변길이 정밀도 · EXPLAIN 형식 혼용 · SQL 출처 미규정)
- 뒤집힌 전제:
  - `ISO_LOCAL_DATE_TIME` 고정 길이 → **가변**(실측 `03:04:00` / `.000001` / `.123456789`)
  - `OrderException` 커스텀 메시지 가능 → **불가**(`BusinessException(ErrorCode,String)` 이 `protected`)
  - `int size` 안전 → `MethodArgumentTypeMismatchException` 핸들러 **0건** → 500
- 리뷰가 확인해준 것: `ALGORITHM=INPLACE, LOCK=NONE` 문법 유효 · `/api-docs` 공개 · 기존 `OrderSecurityIntegrationTest` 무파라미터 · 게이트웨이 파라미터 미주입
- raw: `.cache/codex-reviews/plan-task-impl6-cursor-pagination-1788085500.json`

## 2026-08-30 — 계획 리뷰 라운드 3 (상한)
- 항목: 8건 (P0:0, P1:5, P2:3)
- 처리: 반영 8건 / 기각 0건
- **2R 수정이 만든 새 결함 5건** (파싱 레이어↔미호출 단언 자기모순 · `defaultValue` 가 빈값 치환 · `StatementInspector` 바인드값 불가 · `ResolverStyle` SMART 날짜 보정 · `String size` 의 OpenAPI 불일치)
- 뒤집힌 전제:
  - `ofPattern` 이 잘못된 날짜를 거부 → **SMART 기본값이 `2026-02-30`→`2026-02-28` 보정**(실측)
  - `StatementInspector` 가 바인드 값 제공 → **`String inspect(String)` 뿐**(hibernate-core-6.6.44 javap)
  - `@RequestParam(defaultValue)` 가 빈 문자열을 오류로 → **defaultValue 로 치환**(spring-web 6.2.17)
- 리뷰가 확인해준 것: T3 의 T1·T2 재사용은 순환 참조 아님
- **수렴 미달** — P1 = 5 (0 아님). 라운드 상한 3 도달 → 사용자 판정 필요
- raw: `.cache/codex-reviews/plan-task-impl6-cursor-pagination-1788085931.json`

## 2026-08-30 — 계획 리뷰 라운드 4 (사용자 승인으로 상한 연장)
- 항목: 6건 (P0:0, P1:4, P2:2)
- 처리: 반영 6건 / 기각 0건
- **3R 수정이 만든 새 결함 3건** (연도범위만으론 DATETIME 상한 미차단 · HttpServletRequest 이동이 만든 다중값 의미 · EXPLAIN 대조군 트리 형태 상이)
- 뒤집힌 전제:
  - 연도 1000~9999 검사면 충분 → MySQL 8 상한은 **`9999-12-31 23:59:59.499999`**
  - `getParameterMap()` 을 단일값처럼 사용 가능 → 값은 **`String[]`**, `getParameter()` 는 첫 값만 봐 우선순위 우회
  - 양성 대조군이 정상군과 같은 JSON 경로 → filesort 시 table 이 **`ordering_operation` 아래**로 이동
  - T4 배치 고정만으로 변이 사멸 → **size·A건수 미고정 시 변이 생존**
- 리뷰가 해소해준 것(5건): HttpServletRequest 시그니처 정상 · 인증 필터 request 미wrapping · List+Pageable+JPQL ORDER BY 는 count 쿼리 미실행 · OpenApiConfig 충돌 없음 · AbstractIntegrationTest 컨테이너 미소유
- **수렴 미달** — P1 = 4 (0 아님). 신규 결함 추세 5→5→3, 해소 항목 첫 5건
- raw: `.cache/codex-reviews/plan-task-impl6-cursor-pagination-1788087993.json`

## 2026-08-30 — 계획 리뷰 라운드 5
- 항목: 6건 (P0:0, P1:5, P2:1)
- 처리: 반영 6건 / 기각 0건
- **4R 수정이 만든 새 결함 5건**
- **뒤집힌 전제 — 이번엔 "정정의 정정"**:
  - 4R 이 채택한 `DATETIME(6)` 상한 `9999-12-31 23:59:59.499999` 가 **오류**. MySQL 8.0.46 실측: `.499999`/`.500000`/`.999999` 전부 원형 저장. 그대로 갔으면 정상 데이터를 400 으로 거부
  - 고정 패턴 `uuuu` + STRICT 가 부호형 연도를 거부 → **`+10000`·`-0001` 통과**(실측). MySQL 은 연도 10000 거부(ERROR 1292)
  - T5(@WebMvcTest)의 양성 대조군이 DB 계약 검증 → **DB 도달 불가**(전 케이스 서비스 미호출 단언)
  - `@DataJpaTest` 가 Testcontainers MySQL 을 그대로 사용 → `@AutoConfigureTestDatabase(replace=NONE)` 없으면 임베디드 대체
  - `@DataJpaTest` fixture 가 새 JDBC 연결에서 보임 → **미커밋이라 안 보임**
- 리뷰가 해소해준 것(5건): DataJpa 슬라이스에 Flyway 포함 · common testFixtures 이미 클래스패스 · denylist page/sort 가 Pageable 기본 이름과 일치 · GET 이라 POST body 혼입 무관 · P3 첫/다음 분기는 D7 과 무모순
- **수렴 미달** — P1 = 5. 신규 결함 추세 5→5→3→5 (4R 의 "꺾였다" 판단은 성급했음)
- raw: `.cache/codex-reviews/plan-task-impl6-cursor-pagination-1788088924.json`

## 종료 판정 (2026-08-30)
- **수렴하지 않은 채 사용자 결정으로 계획 리뷰 종료.** 5라운드 P1 = 5 (수렴 조건 P1=0 + 새 표면 무추가 미충족)
- 라운드별: 12(P1:6) → 10(P1:5) → 8(P1:5) → 6(P1:4) → 6(P1:5). **신규 결함(직전 수정이 만든 것): 5 → 5 → 3 → 5**
- 종료 근거: 남은 결함이 구조·계약이 아니라 **배선 디테일**(슬라이스 어노테이션·트랜잭션 가시성)로 이동했고, 이 종류는 문서 논리가 아니라 **실행해봐야** 판정된다. R5 #1 이 그 증거 — 4R 이 문서 논리로 수용한 상한이 실측에서 뒤집혔다. `/work` 의 diff 리뷰가 실제 코드로 동일 검사를 더 싸게 한다
- **`/work` 착수 시 우선 확인할 잔여 위험** (계획이 코드로 검증되지 않은 지점):
  1. `@DataJpaTest` + `@ServiceConnection` + `@AutoConfigureTestDatabase(replace=NONE)` 조합이 이 레포에서 실제로 뜨는가 (레포 첫 사례)
  2. `Session#doWork` 로 fixture 가시성이 실제로 확보되는가
  3. springdoc 2.8.6 이 `@Operation(parameters=...)` 만으로 파라미터를 생성하는가
  4. `EXPLAIN FORMAT=JSON` 의 `ordering_operation.table` 이동이 이 MySQL 패치에서 실제로 일어나는가

---

## 2026-08-30 — diff 리뷰 라운드 1
- 항목: 5건 (P0:0, P1:3, P2:2)
- 처리: 반영 5건 / 기각 0건
- **5건 전부 "내 테스트가 false-green" 유형** — 이 프로젝트에서 반복된 유형이라 최우선 처리했다
  1. (P1) 계획 P8 이 요구한 "캡처 SQL 직접 실행 결과 == 리포지터리 결과" 미구현 — 비어있지 않은지만 봤다
  2. (P1) `examinedRows` 가 TREE 전체를 합산 — orders 스캔 노드가 없어도 통과
  3. (P1) `used_key_parts` 미단언 + T8 이 수기 SQL 사용 → 운영 JPQL 회귀를 못 잡음
  4. (P2) 계획 대비 규모 축소(20,000→5,000)와 evidence 문서 부재
  5. (P2) T9 가 `idx_orders_reservation_expiry` 누락
- 리뷰가 유효 판정한 것: T1 이 실제 동률을 만든다 · `verifyNoInteractions` 가 선제 거부를 증명한다 · 커서가 권한 근거가 아니다 · `OrderPageQuery` 의 domain 예외 사용이 레이어 규칙에 부합 · 범위 밖 초과구현 없음
- raw: `.cache/codex-reviews/diff-task-impl6-cursor-pagination-1788091865.json`

## 2026-08-30 — diff 리뷰 라운드 2 (재리뷰)
- 항목: 3건 (P0:0, **P1:0**, P2:3) → **수렴**
- 처리: 반영 3건 / 기각 0건
- **라운드1 수정이 만든 새 결함 2건**:
  1. `runForIds` 가 첫 컬럼을 id 로 가정 — JPQL 은 `SELECT o` 라 SELECT 목록 순서가 계약이 아니다. **동등성을 보강하며 들여온 구현 종속성** → `ResultSetMetaData` 로 id 컬럼 탐색
  2. `containsExactlyInAnyOrder` 가 "이 6개만 존재" 계약이 돼 향후 정당한 인덱스 추가를 차단 → 보존 계약(`contains`)으로 완화
  3. (문서) P9 본문만 정정하고 T8 표·완료조건·§7 이 원안대로 남아 계획서 내부 불일치 → 전부 동기화
- 리뷰가 직접 검증해준 것: MySQL 8.0.46 에서 대상 테스트 4/4 실행해 TREE 정규식이 cursor·offset 모두 **정확히 1개 노드**를 잡고 20/20/20 vs 40/420/920 을 산출함을 확인 · 복수 노드는 의도대로 실패 · `used_key_parts` 단언은 패치 핀과 함께라 타당 · `captureSql`/`capture` 는 반환값 필요 여부가 달라 중복 아님
- raw: `.cache/codex-reviews/diff-task-impl6-cursor-pagination-1788092646.json`

## 실측으로 확정된 것
- **D3 (InnoDB 세컨더리 인덱스의 PK 암묵 부착) 확인** — 인덱스는 `(user_id, ordered_at)` 2컬럼인데 커서 질의의 `used_key_parts` 가 `[user_id, ordered_at, id]` **3개**. 계획이 뒀던 "반증 시 `(user_id, ordered_at, id)` 로 정정" 조건은 발생하지 않았다
- **성능**: cursor examined rows `[20, 20, 20]` (평탄) vs offset `[40, 420, 920]` (선형). 깊이 900 에서 46배
- **실패 주입**: 커서 predicate 제거 시 실행계획 테스트 4건 중 2건 FAILED

## 2026-08-30 — /ship
- PR: https://github.com/Kimgyuilli/PeakCart/pull/96
- precheck: `ok` (warnings 0)
- 커밋 3개: `feat(order)` / `test(order)` / `docs(⑥)`
- 갱신: `docs/TASKS.md` 구현 ⑥ → ✅ + PR 링크 · `docs/progress/PHASE4.md` 작업 이력
- **미충족(머지 전 확인 필요)**: 전 모듈 스위트 로컬 미완주 → CI 이관. `ContainerLaunchException`(자원, 코드 아님). **CI 그린 전 머지 금지**
- ADR 신설 없음 — 외부 의존성·아키텍처 경계·인프라 변경 없음. 설계문서 §10-3 은 What 정정이라 Layer 1 직접 수정
