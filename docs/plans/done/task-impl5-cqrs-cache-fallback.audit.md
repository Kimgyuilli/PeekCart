## 2026-08-30 — 계획 리뷰 라운드 1

- 항목: 7건 (P0:0, P1:6, P2:1)
- 처리: 반영 7건 / 기각 0건 (단 #1 은 "새 ADR 작성" 제안을 progress 기록으로 대체 — ④ 선례)
- 뒤집힌 전제:
  - **§2.3-c 반증** — 초안은 "`CachingConfigurer#cacheManager()` 오버라이드 시 빈 이름이 바뀌어 S7 의 `cache_manager=\"cacheManager\"` 라벨이 깨진다"고 적었으나 **틀렸다**. 라벨은 `@Bean` 메서드 이름에서 오고 오버라이드는 빈 이름을 바꾸지 않는다. 결론(미오버라이드)은 유지하되 근거를 "불필요한 명시 배선·CacheManager 모호성 회피"로 교체.
  - **§2.4 put 실패 "무해" 반증** — 응답 정확성은 유지되나 캐시가 안 채워져 **모든 후속 요청이 DB 를 친다**. Redis 장애의 DB 전이. P6/V5 신설.
  - **작업 항목 형식** — `.claude/scripts/lib/sync.sh:38-44` 의 `hpx_plan_lint` 는 `- [ ] **P1.**` 형식만 stable id 로 인식. `### P1.` 형식은 0개로 판정. 필수 섹션 "목표/목적"·"영향 파일"도 부재. 실행으로 확인 후 전면 재구성.
- 신설: Toxiproxy 하네스(무응답 검증), P7 운영/배포 계약, PHASE4 귀속 차이 절, §4 영향 파일
- raw: `.cache/codex-reviews/plan-task-impl5-cqrs-cache-fallback-1788050579.json`

## 2026-08-30 — 계획 리뷰 라운드 2

- 항목: 10건 (P0:0, P1:6, P2:4)
- 처리: 반영 9건 / 부분 1건 (#5 ADR 판정 — 근거만 교체, 결론 보류 후 3라운드에서 재지적)
- 뒤집힌 전제:
  - **V3 의 2s 상한 산술 오류** — `@Cacheable` 1회 요청이 **get 실패 → DB → put 실패**로 command timeout 을 **2회** 소비. 1s×2 = 2s 라 상한이 성립 불가. → timeout **500ms**, 상한 **1.5s** 로 재설정.
  - **`hpx_plan_lint` 가 zsh 에서 실행 불가** — `sync.sh:10` 의 `local path=` 가 zsh 의 `path`↔`PATH` 연동을 건드려 `PATH` 를 `docs/plans/...` 로 덮고 다음 줄 `python3` 가 죽는다. `zsh -c` / `bash -c` 대조로 재현 확인. → P10 신설.
  - **Toxiproxy 배선 누락 시 전면 false-green** — backend Redis 의 `@ServiceConnection` 을 유지한 채 프록시를 추가하면 앱 트래픽이 프록시를 우회. → `@ServiceConnection` 제거 + `@DynamicPropertySource` + V0 경유 단언.
  - **Toxiproxy 로 SET 만 실패시킬 수 없다** (L4 프록시, Redis 명령 미해석) → read-only ACL 대신 전면 장애 + `ProductRepository` 스파이로 변경.
- 신설: V0(하네스 자체 검증), V7(프로파일 바인딩 회귀), M7(cache stampede/DB pool), P10
- raw: `.cache/codex-reviews/plan-task-impl5-cqrs-cache-fallback-r2-*.json`

## 2026-08-30 — 계획 리뷰 라운드 3 (상한)

- 항목: 4건 (P0:0, P1:3, P2:1)
- 처리: 반영 3건 (#2·#3·#4) / **미해결 1건 (#1 — 사용자 판단 대기)**
- 뒤집힌 전제:
  - **P10 의 1줄 수정이 오히려 함수를 확실히 깬다** — `:10` 선언만 `plan_path` 로 바꾸고 `:11` 의 `python3 - "$path"` 참조를 남기면 bash 에서 빈 인자, zsh 에서 특수배열이 전달된다. → 선언+참조 동시 변경으로 정정.
  - **연결 거부와 무응답은 다른 기구다** — bandwidth toxic 은 정체만 만들고 거부하지 않는다. V1 = `Proxy#disable()`, V3 = downstream `timeout(_, 0)` toxic 으로 분리.
  - **`hpx_plan_lint` 는 제목 존재와 P 연속성만 본다**(`sync.sh:25-44`) — runbook 내용·임계치·문서 정합은 빈 제목으로도 통과. → V9 grep 계약 신설, 완료 조건 12(의미 정합)는 기계 판정에서 제외해 리뷰 체크로 분리, P7 감시 임계를 PromQL 수치로 고정.
- **미수렴 항목 (#1, P1)**: §2.1-a 의 "ADR-0012 에 정정할 사실 오류가 없다" 판정. 리뷰어는 ADR-0012:53 의 `"product_cache/장바구니 조회 충족"` 과 실제 `product_price_cache`(4컬럼, price/version 만 소비)의 차이를 **문언 재해석**으로 보고 README:11(Update Log) 또는 :14(새 ADR) 표면을 피하지 못한다고 지적. 라운드 상한 도달 → **사용자 판단으로 이관**.
- raw: `.cache/codex-reviews/plan-task-impl5-cqrs-cache-fallback-r3-*.json`

## 2026-08-30 — 미수렴 항목 처분 (사용자 판단)

- **r3 #1 (P1) 처분: 반영.** ADR-0012 를 `README:11-13` 의 **Update Log 경로**로 정정하기로 사용자가 결정.
- 초안 §2.1-a 의 "정정할 사실 오류가 없다" 논거는 **철회**. `:53` 의 `"product_cache/장바구니 조회 충족"` 은 소비처를 명시한 문장이 맞고, "payload 필드 계약일 뿐"이라는 축소는 결론에 맞춘 재해석이었다.
- 분류 근거: 틀린 것은 **테이블명·컬럼 범위·소비처 구현 상태**라는 사실 진술(README 예시의 "파일명·Phase 귀속·수치" 계열)이다. 결정 자체(이벤트로 동기 호출 대체, 7필드 계약)는 유효 → `:14`(트레이드오프 변경·Consequences 재해석 → 새 ADR)에 해당하지 않는다.
- 조치: **P11 신설** — ADR-0012 본문 정정 + `## Update Log` 절 + `fix(adr):` 분리 커밋. Status·ADR 개수 무변경. V9 grep 계약과 완료 조건 12 에 반영.
- 수렴 판정: 3라운드 P1 3건 중 2건은 코드 사실 확인 후 즉시 반영, 나머지 1건이 본 처분으로 종결 → **P1 = 0, 새 계약 표면은 P11(ADR Update Log) 하나이며 이는 리뷰 지적의 직접 수용이라 신규 리스크 표면이 아님.**

## 2026-08-30 — diff 리뷰 라운드 1

- 항목: 3건 (P0:0, P1:2, P2:1)
- 처리: 반영 3건 / 기각 0건
- 주요 지적:
  - **P1 #1** `ResilientCacheErrorHandler` 가 예외 종류를 보지 않고 전부 삼켰다 — 직렬화 불일치·ACL `NOPERM`·명령 문법 오류처럼 **연결은 멀쩡한 정합성 오류**까지 정상 fallback 으로 위장돼, 배포 후 모든 요청이 조용히 DB 로 가는 상태의 원인을 추적할 수 없다. → deny/allow 분리 + 단위 테스트 신설.
  - **P1 #2 (false-green)** V0/V3/V5 가 컨텍스트 **누적** `cache_fallback_total` 을 단언해 앞선 테스트 잔여로 통과 가능. 실제 실행 순서도 V1→V3→V0→V5 라 V0 진입 시 이미 양수였다. → `FallbackSnapshot` 증가분 단언.
  - **P2 #3** Update Log 의 커밋 해시가 자리표시자.
- 부수 확인: `@CacheEvict(allEntries=true)` 는 `handleCacheEvictError` 가 아니라 `handleCacheClearError` 로 온다. V4 를 evict 1 · clear 1 로 분리.
- 검증: 통합 7/7 · 단위 7/7 · 변이 검사 재실행(맨 `@Bean` → 6/7 FAILED)
- raw: `.cache/codex-reviews/diffreview-task-impl5-cqrs-cache-fallback-r1-*.json`

## 2026-08-30 — diff 리뷰 라운드 2

- 항목: 4건 (P0:0, P1:1, P2:3)
- 처리: 반영 4건 / 기각 0건
- **라운드1 수정이 만든 새 결함: 2건**
  - **P1 #1** 1라운드에서 허용 목록을 Lettuce **최상위 `RedisException`** 으로 넓힌 탓에 명령 거부·interrupt 까지 다시 삼킬 수 있게 됐다. 실측 체인 `RedisSystemException ← RedisException ← SocketException` 은 안쪽 `IOException` 으로 이미 판별되므로 최상위 타입이 불필요했다. → `RedisConnectionException`·`RedisCommandTimeoutException`·`IOException` 으로 재축소 + `RedisCommandInterruptedException` deny 추가.
  - **P2 #4** 1라운드가 ADR 에 새로 넣은 "직후 커밋에서 채웠다" 서술이 실제 이력과 불일치(직후는 `016f1ea`, 실제는 `7a7b719`).
- 그 외:
  - **P2 #2** 원인 체인 순회가 자기참조만 끊어 A→B→A 2노드 순환에서 무한 루프 → `IdentityHashMap` 방문 집합.
  - **P2 #3 (false-green)** `FallbackSnapshot` 이 `operation` 만 보고 `cache` 라벨을 합산 — 핸들러가 전부 `cache="unknown"` 으로 기록해도 통과했다. P3 의 `{cache,operation}` 계약이 미검증 → (cache, operation) 키로 교체.
- 부수: `observability-ssot-lint` D5-V2 가 `CacheConfig` 의 **javadoc 주석**("여기서는 `MeterRegistryCustomizer` 를 선언하지 않는다")을 S1 중복 선언으로 오탐 → 주석 라인 제외. 실제 `@Bean MeterRegistryCustomizer` 주입 시 여전히 검출됨을 변이로 확인.
- raw: `.cache/codex-reviews/diffreview-task-impl5-cqrs-cache-fallback-r2-*.json`

## 2026-08-30 — diff 리뷰 라운드 3 (실행 불가)

- **Codex CLI 를 실행할 수 없어 라운드 3 을 돌리지 못했다.** 세션 중(11:14) `codex` 심볼릭 링크가 `.codex-Sn17FtFx` 로 리네임되고 네이티브 의존성이 사라졌다:
  `Error: Missing optional dependency @openai/codex-darwin-arm64. Reinstall Codex: npm install -g @openai/codex@latest`
  첫 시도는 `codex-code-mode-host` 바이너리 부재로 리뷰어가 파일을 못 읽어 0건을 반환했고, 재시도는 `command not found`, `node` 직접 호출은 위 오류였다.
- **따라서 "2라운드 수정이 새 결함을 만들지 않았다"는 미검증이다.** 2라운드에서 P1 1건을 수정했으므로 수렴 조건상 1회 더 돌리는 것이 맞다.
- 남은 검증은 자동 게이트로 대체: 통합 7/7 · 단위 9/9 · 바인딩 2/2 · 회귀(S7·기존 캐시) 통과 · 변이 검사 6/7 FAILED · `ssot-lint` EXIT=0 · bats 55/55.
- 조치: Codex 재설치 후 `/work` 재실행으로 라운드 3 을 돌릴 것. 미충족으로 명시한다.

## 2026-08-30 — 최종 검증

- `./gradlew test` **BUILD SUCCESSFUL (10m 59s) · 실패 0건** (전 모듈)
- 게이트: 통합 `ProductCacheFallbackIntegrationTest` 7/7 · 단위 `ResilientCacheErrorHandlerTest` 9/9 · `RedisPropertiesBindingTest` 2/2 · S7 관측성·기존 캐시 회귀 통과 · `observability-ssot-lint.sh` EXIT=0 · bats 55/55
- 변이 검사 2회: (a) `errorHandler()` → 맨 `@Bean` 시 통합 7건 중 6건 FAILED, (b) ssot-lint 주석 제외 후에도 실제 `@Bean MeterRegistryCustomizer` 주입 시 검출됨
- **관측된 flake 1건 (해소 아님)**: 직전 전체 실행에서 `StockCompensationRefundIntegrationTest:273`(`payment.refunded` listener 배선, Kafka 왕복 20s await)이 1회 실패했고 재실행에서 통과했다. 본 PR 이 손대지 않은 경로이며 이 PR 이전 코드의 실행에서도 전체 빌드가 깨졌다. 원인 규명은 하지 않았다 — 재발 시 ④ 계열 부채로 다룰 것.


## 2026-08-30 — diff 리뷰 라운드 3 (Codex 재설치 후 실행)

- 항목: 2건 (P0:0, P1:2, P2:0)
- 처리: 반영 2건 / 기각 0건
- **2라운드 수정이 만든 새 결함: 2건 (둘 다 P1)**
  - **#1 — 2라운드의 allow-list 축소가 핵심 계약을 깼다.** lettuce-core 6.6.0 `DefaultEndpoint` 는 연결이 이미 끊긴 뒤 들어온 명령에 대해 **원인 없는 bare `RedisException`** 을 만든다(`"Connection is closed"` · `"Currently not connected. Commands are rejected."` · `"Connection disconnected"` — 바이트코드로 직접 확인). Spring `LettuceExceptionConverter` 는 이를 `RedisSystemException` 으로 감쌀 뿐 `IOException`/`RedisConnectionException` 을 원인에 붙이지 않는다. 따라서 2라운드의 좁은 허용 목록은 **in-flight 연결 종료를 5xx 로 되돌렸다** — 계획 §1 부정형 #1 위반.
    - 2라운드 판단의 오류: "`RedisException` 전체를 허용하면 명령 거부·interrupt 가 함께 들어온다"고 봤으나, **deny 를 먼저 검사하는 구조에서는 성립하지 않는다.** 예외 계층 확인 결과 `RedisLoadingException`/`BusyException`/`ReadOnlyException`/`NoScriptException` 은 전부 `RedisCommandExecutionException` 하위라 deny 한 줄로 함께 막힌다.
    - 조치: `RedisException` 을 허용 목록에 복원(deny 우선 유지). 단위 테스트를 `rethrowsBareRedisException` → `swallowsBareConnectionStateException`(실제 Lettuce 메시지 3종)으로 전환하고, LOADING/BUSY 가 여전히 되던져지는지를 `rethrowsCommandExecutionSubclasses` 로 고정.
  - **#2 — 2라운드의 lint 주석 제외가 D5-V2 를 우회 가능하게 만들었다.** 줄 시작 문자만 보고 **라인 전체를 버려서**, `/* note */ @Bean MeterFilter f() {...}` 처럼 주석 뒤에 실제 선언이 이어지면 검출되지 않았다(import 도 이미 제외되므로 `non_import` 가 비어 EXIT=0).
    - 조치: 라인 제외 대신 **주석 토큰만 제거하고 남은 코드**를 검사한다.
    - 구현 중 자체 결함 1건: sed 구분자를 `:` 로 쓰면 `grep -n` 접두사 캡처 `([0-9]+:)` 안의 `:` 가 s 명령을 끊어 **치환이 통째로 망가진다**(검출 0건). 변이 검사가 즉시 잡았다 — "평범한 선언"조차 검출 실패로 나왔다. 구분자를 `#` 로 교체.
- 변이 검사 3형태 전부 검출 확인: 평범한 선언 · `/* */` 뒤 같은 줄 선언 · `//` 뒤 같은 줄 선언. javadoc 오탐은 없음(EXIT=0).
- 회귀: 통합 7/7 · 단위 11/11 · 바인딩 2/2 · S7·기존 캐시 통과
- raw: `.cache/codex-reviews/diffreview-task-impl5-cqrs-cache-fallback-r3final-*.json`

## 2026-08-30 — diff 리뷰 라운드 4 (상한 초과 · 사용자 승인)

**상한(3회) 초과 사유**: 1R→2R→3R 세 라운드 연속으로 직전 라운드 수정이 새 결함을 만들었다. 3R 수정 자체도 검증되지 않은 새 변경이므로 1회 더 돌리기로 사용자가 결정.

- 항목: 2건 (P0:0, P1:2, P2:0)
- 처리: 반영 2건 / 기각 0건
- **3라운드 수정이 만든 새 결함: 2건 (둘 다 P1)** — 4라운드 연속이다.
  - **#1 — `RedisException` 복원이 이번엔 과했다.** lettuce-core 6.6.0 예외 계층을 `javap` 로 전수 확인한 결과, `NEVER_SWALLOWED` 밖이면서 가용성도 아닌 형제가 실재한다: `protocol.RedisProtocolException`, `cluster.PartitionException`(+`PartitionSelectorException`/`UnknownPartitionException`), `dynamic.CommandCreationException`(+`CommandMethodSyntaxException`), `support.caching.CacheFrontend$ValueRetrievalException`. 최상위 타입 허용은 프로토콜·명령 구성 결함을 캐시 미스로 위장한다.
    - 조치: 허용 목록에서 `RedisException` 을 빼고 `RedisConnectionException`·`RedisCommandTimeoutException`·`IOException`·Spring 번역 타입만 남긴 뒤, **원인 없는 bare 연결 종료**는 `getClass() == RedisException.class` **정확 일치**로만 별도 인정(`isBareAvailabilityRedisException`). 하위 타입은 전부 자동 제외된다.
    - 메시지 매칭까지 가지 않은 이유: 메시지는 Lettuce 버전에 따라 바뀌고, 그때 나타나는 것은 조용한 과삼킴이 아니라 **in-flight 종료의 5xx 회귀**라 더 위험하다. 알려진 한계로 javadoc 에 명시.
    - 회귀 고정: `rethrowsNonAvailabilityRedisExceptionSubclasses`(protocol/cluster/dynamic 3종).
  - **#2 — 3라운드의 sed 가 세 형태에서 여전히 깨졌다.** (a) 여러 줄 주석 종료 줄 `*/ @Bean MeterFilter f()` → 3번 규칙이 줄을 통째로 버려 **우회**, (b) `@Deprecated(since="http://x")` → 문자열 안 `//` 부터 잘려 **우회**, (c) `/* MeterRegistryCustomizer` 로 시작하는 여러 줄 주석 → 닫는 토큰을 못 찾아 **오탐**.
    - 조치: 줄 단위 정규식을 버리고 **블록주석/문자열/문자/텍스트블록 상태를 추적하는 lexer**(python3)로 교체.
    - **self-test 신설**: `scripts/tests/observability-ssot-lint-d5v2-selftest.sh` — 위반 5형태 + 정상 3형태를 fixture 로 고정(8케이스). 3라운드 sed 구현으로 되돌리는 변이에서 **정확히 3건 FAIL**(위 a·c 와 문자열 리터럴 오탐)을 확인해 판별력을 실증했다.
- 회귀: 통합 7/7 · 단위 12/12 · 바인딩 2/2 · S7·기존 캐시 통과 · bats 55/55 · D5-V2 self-test 8/8
- raw: `.cache/codex-reviews/diffreview-task-impl5-cqrs-cache-fallback-r4-*.json`

### 이 PR 의 리뷰 패턴 — 4라운드 연속 회귀

| 라운드 | 잡은 것 |
|---|---|
| 1R | 원본 결함 3건 (무차별 삼킴 · 누적 메트릭 false-green · ADR 해시) |
| 2R | **1R 수정이 만든 2건** (`RedisException` 통째 허용 · lint 라인 제외) |
| 3R | **2R 수정이 만든 2건** (축소가 과해 in-flight 종료 5xx · lint 우회) |
| 4R | **3R 수정이 만든 2건** (복원이 과해 비가용성 하위 삼킴 · sed 3형태 잔존) |

**교훈**: 이 표면은 "허용 범위" 라는 **연속적인 축**을 다루는데, 매 라운드가 그 축 위에서 한쪽으로 과보정했다. 넓히면 정합성 오류가 새고, 좁히면 가용성 장애가 5xx 가 된다. 수렴은 **경계를 타입 계층 전수 확인으로 고정**(4R #1)하고 **판별 fixture 를 테스트로 못 박은**(4R #2 self-test) 뒤에야 왔다. 다음에 이런 축을 만나면 라운드를 돌리기 전에 먼저 전수 열거부터 할 것.

## 2026-08-30 — diff 리뷰 라운드 5

- 항목: 3건 (P0:0, P1:2, P2:1)
- 처리: 반영 2건 / **기각 1건**
- **예외 분류 축(A) 은 이번 라운드 결함 0건 — 첫 수렴 신호.** 리뷰어가 lettuce-core 6.6.0 과 spring-data-redis 3.5.10 바이트코드를 대조해 "standalone Spring Cache 경로의 연결 장애가 누락된 증거를 찾지 못했다" 고 명시했다. `LettuceExceptionConverter` 가 `ChannelException`/`RedisConnectionException` → `RedisConnectionFailureException`, `RedisCommandTimeoutException`/`TimeoutException` → `QueryTimeoutException` 으로 번역하고, 연결 종료는 정확 타입 `RedisException` 으로 전달된다. 4R 의 경계 설정이 맞았다.
- 반영 2건 — 둘 다 **lint lexer(B)** 소관:
  - **#2 (P1) text block escape 미처리** — `in_text` 상태에서 backslash 를 소비하지 않아 유효한 `\"""` 의 첫 따옴표를 종료 토큰으로 오인했다. 상태가 뒤집혀 이후 실제 선언이 EOF 까지 문자열로 지워진다(false-green). → `eat_escape()` 신설(줄바꿈 보존 — 줄 번호가 밀리면 안 된다). 문자열/문자 리터럴에도 동일 적용.
  - **#3 (P2) self-test 가 검출과 고장을 구별 못 했다** — lint 의 모든 비정상 종료를 `detected` 로 취급해, lexer traceback 이나 preflight 실패(rc=2)에서도 위반 5케이스가 전부 ok 로 찍혔다. → 종료 코드를 그대로 판정(0=clean, 1=detected, 그 외 즉시 실패)하고 stderr 를 출력에 포함.
  - fixture 2종 추가(text block escape 뒤 선언 = 위반, text block 안 식별자 언급 = 정상). **변이 실증**: `eat_escape` 를 제거하면 새 fixture 만 정확히 FAIL.
- **기각 1건**:
  - **#1 (P1) 유니코드 escape 우회** — `@Bean MeterFilter rogue()` 는 javac 이 `MeterFilter` 로 해석하지만 원문 grep 후보 선별에서 빠져 통과한다. 지적은 사실이다.
  - **기각 사유**: 이 lint 는 **실수로 인한 중복 선언을 막는 가드레일**이지 적대적 우회를 막는 통제가 아니다. 식별자를 유니코드 escape 로 난독화해 lint 를 통과시키려는 개발자는 lint 자체를 지우거나 `@Bean` 을 다른 모듈에 두는 편이 더 쉽다 — 즉 이 구멍을 막아도 위협 모델이 닫히지 않는다. 반면 대응 비용은 후보 선별을 전체 Java 파일 순회 + 유니코드 변환으로 바꾸는 것이라 lint 실행 시간과 복잡도가 실질적으로 는다.
  - **재검토 조건**: 이 lint 가 보안 통제로 승격되거나(예: 외부 기여 수용), 유니코드 escape 가 코드베이스에 실제로 등장하면 다시 연다.
- 회귀: 통합 7/7 · 단위 12/12 · 바인딩 2/2 · S7·기존 캐시 통과 · lint EXIT=0 · D5-V2 self-test **10/10**
- raw: `.cache/codex-reviews/diffreview-task-impl5-cqrs-cache-fallback-r5-*.json`

## 2026-08-30 — /ship applied (PR #94)

- PR: https://github.com/Kimgyuilli/PeakCart/pull/94 (base `main`, 커밋 11개)
- Consistency precheck: `ok` (warnings 0, exit 0) → GS-1 자동 통과
- 갱신: `docs/TASKS.md` ⑤ 행 ✅ + PR 링크 · `phase4-prep-debt-roadmap.md` L-006 ✅ + PR 번호 · `PHASE4.md` 헤더 PR 링크
- **하네스 불일치 관측**: `/ship` Step 1 은 `docs/plans/<task>.state.json` 을 요구하고 없으면 중단하지만, `/plan`·`/work` 는 2026-08-26 축소에서 state machine 을 제거해 그 파일을 만들지 않는다. state 파일을 지어내지 않고 Step 1/10(state 조작)만 건너뛰고 나머지를 수행했다. `/ship` 스킬 정의를 축소된 계약에 맞추는 정리가 필요하다.
