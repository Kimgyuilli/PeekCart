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
| D-027 | Harness / Cost | 🔄 ② 완료([#128](https://github.com/Kimgyuilli/PeakCart/pull/128)) · ①③ 재개 조건 미충족 대기 |
| D-030 | Observability / Ops | 🔲 Outbox `FAILED` ↔ DLQ Slack 채널 분리 + DLQ 적재량 메트릭 (L-004 승격, [#133](https://github.com/Kimgyuilli/PeakCart/pull/133)) |

---

## 작업 이력

> 엔트리 형식은 PHASE4.md 와 동일: `## <제목> ([PR](...), YYYY-MM-DD)`

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
