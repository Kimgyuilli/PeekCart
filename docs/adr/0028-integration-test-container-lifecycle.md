# ADR-0028: 통합 테스트 컨테이너 수명 = 모듈 싱글톤 — per-class 재부팅 폐기

- **Status**: Accepted
- **Date**: 2026-09-22
- **Deciders**: Kimgyuilli
- **관련 Phase**: Phase 5

## Context

PR 을 올리면 CI 가 36분 걸린다. 같은 검증을 로컬에서 한 번 더 돌리므로 체감은 그 두
배다. 무엇을 고칠지 정하기 전에 **시간이 어디로 가는지** 부터 측정했다.

### 측정 ① 임계경로 — 잡 4개가 직렬로 물려 있다

PR run [35731821466](https://github.com/Kimgyuilli/PeakCart/actions/runs/35731821466) (36분, 전체 success)

| 잡 | 소요 | 시작 |
|---|---|---|
| **test (order-service)** | **1051s** | 13:12:23 |
| test (product-service) | 808s | 13:12:23 |
| test (payment-service) | 581s | 13:12:23 |
| test (notification-service) | 433s | 13:12:23 |
| test (user-service) | 201s | 13:12:24 |
| test (platform) | 111s | 13:12:24 |
| guards | 48s | 13:12:23 |
| lint | 44s | 13:12:24 |
| gate | 15s | 13:29:58 |
| images (최장) | 173s | 13:30:43 |
| **e2e** | **856s** | 13:33:41 |

`test → gate → images → e2e` 가 한 줄로 이어진다. 36분 중 **34.5분이 이 사슬**이고,
lint·guards·나머지 5개 샤드는 전부 그 그늘에 숨어 비용이 0이다.

### 측정 ② order 샤드 내부 — 시간의 87%가 어디에도 기록되지 않는다

Gradle 로그와 JUnit XML 증적을 대조했다.

```
러너 셋업 + checkout + setup-java        9s
컴파일 (:common ~ :order-service:test 직전)   60s
:order-service:test                       975s   ← 13:13:34.0 → 13:29:49.3
```

그런데 `shard-results-order-service` artifact 의 `TEST-*.xml` 58개를 합산하면:

| 항목 | 값 |
|---|---|
| 클래스 58개 시간 합 | **131s** |
| 그중 testcase 본문 합 | **131s (100%)** |
| `:order-service:test` 실측 | **975s** |
| **어디에도 귀속되지 않은 시간** | **844s = 87%** |

JUnit 은 `@BeforeAll` / static initializer 의 컨테이너 기동과 Spring context 생성을
testcase 시간에 넣지 않는다. 그래서 이 844초가 그대로 **컨테이너 부팅 + Flyway +
context 기동** 비용이다. 컨테이너 보유 클래스 26개로 나누면 **클래스당 약 32초**다.

가장 느린 단일 클래스조차 본문은 63.5초이고, 나머지 57개를 다 합쳐도 68초다.
**테스트가 느린 것이 아니라, 테스트를 시작하기 위한 준비가 느리다.**

### 측정 ③ 왜 준비가 26번 반복되는가

`order-service/src/test` 의 `@Container` 선언 **77개**를 원문 그대로 집계했다.

| 선언 | 횟수 |
|---|---|
| `new KafkaContainer("apache/kafka:3.8.1")` | 26 |
| `new MySQLContainer<>("mysql:8.0").withDatabaseName("peekcart_test")` | 26 (줄바꿈 변형 2 포함) |
| `new GenericContainer<>("redis:7").withExposedPorts(6379)` | 25 |
| `new MySQLContainer<>("mysql:8.0.46")` | 1 (`OrderCursorQueryPlanTest` — EXPLAIN 안정성 목적) |

**선언이 사실상 전부 동일한데 클래스마다 따로 서 있다.** 전부 `static` 이라 per-class
수명은 지켜지지만, 클래스 경계를 넘으면 버려진다. `common/src/testFixtures/.../
AbstractIntegrationTest` 가 "컨테이너 선언은 각 자식 클래스에서 per-class 수명으로
유지한다" 를 규약으로 명시하고 있어, 이것은 사고가 아니라 **현행 설계다.**

Spring context 도 같이 버려진다. 컨테이너 포트가 매번 달라 context 캐시 키가 매번
바뀌기 때문이다. 그런데 `@SpringBootTest` 26개 클래스의 프로퍼티 지문을 세어 보면:

| 지문 | 클래스 수 |
|---|---|
| `spring.flyway.enabled, spring.flyway.locations` 뿐 | **17** |
| 그 외 (scheduler·outbox·dead-letter 튜닝 등) | 9 |

**17개가 완전히 동일하다.** 컨테이너만 합치면 context 캐시가 저절로 따라온다는 뜻이다.
즉 이 결정은 "새 구조를 도입한다" 가 아니라 **이미 수렴해 있는 것을 합치는** 일이다.

### 왜 ADR 인가

되돌림 비용이 파일 경계를 넘는다.

- 규약의 출처가 `common/src/testFixtures` 의 **공유 모듈**이고, 5개 서비스가 그것을 쓴다
- `cleanDatabase()` 의 계약이 "클래스마다 새 DB" 전제 위에 서 있다. 컨테이너를 공유하면
  그 전제가 사라지므로, 지금 `cleanDatabase()` 를 **호출하지 않는** 테스트들의 안전성이
  재평가 대상이 된다 (현행 주석: "cleanup이 불필요한 테스트(ShedLock 레코드 검증,
  메트릭 노출 검증 등)는 호출하지 않는다")
- 규약을 코드로 강제하지 않으면 새 테스트가 다시 `@Container` 를 선언하며 원상복구된다.
  그 lint 자체가 이 결정에 묶인 산출물이다

반대로 워크플로 그래프 변경(`images` 의 `needs` 완화, buildx 캐시)은 YAML 한 줄이고
즉시 되돌아간다. 같은 세션에서 함께 결정했지만 ADR 보호 대상이 아니라고 보아
아래 `Decision` §순서 에만 기록한다.

## Decision

**통합 테스트의 컨테이너 수명을 per-class 에서 모듈 싱글톤으로 바꾼다.**

각 서비스 모듈은 `TestcontainersConfiguration` 하나에 MySQL·Redis·Kafka 를 `static`
필드로 선언하고, `@ServiceConnection @Bean` 메서드가 **같은 인스턴스를 반환**한다.
테스트 클래스는 `@Import` 만 하고 `@Testcontainers` / `@Container` 를 **선언하지 않는다.**

이 결정은 세 가지를 한 묶음으로 포함한다. 분리하지 않는 이유는 §Consequences 에 적는다.

1. **싱글톤 전환** — 위 구조로 26개 클래스를 이관
2. **lint 강제** — 테스트 클래스의 `@Container` 직접 선언을 CI 에서 실패시킨다.
   `--self-test` 로 조작 입력에서 실제로 실패하는지 함께 고정한다
3. **셔플 검증** — 클래스 실행 순서를 섞어 돌려 `cleanDatabase()` 누락을 색출한다

예외는 하나다. `OrderCursorQueryPlanTest` 는 `mysql:8.0.46` 을 의도적으로 고정하므로
싱글톤에서 제외하고, 그 사유를 클래스 주석에 남긴다.

### 순서

같은 세션에서 결정한 CI 최적화 전체의 실행 순서다. 이 ADR 이 보호하는 것은 2단계이고,
1·3단계는 되돌림이 즉시라 기록 목적으로만 적는다.

| 단계 | 내용 | 성격 |
|---|---|---|
| 1 | `images: needs: [lint, gate]` → `[lint]` · `images` 에 buildx `type=gha` 캐시(**scope 이미지별 분리**) | YAML 전용, 즉시 되돌림 |
| **2** | **이 ADR 의 결정 (싱글톤 + lint + 셔플)**, order-service 로 먼저 검증 후 4개 모듈 확산 | 규약 변경 |
| 3 | `CLAUDE.md` 테스트 규칙에 싱글톤 규약 명문화 | 문서 |

1단계를 먼저 두는 이유는 **코드를 한 줄도 바꾸지 않고 36분 → 약 19분을 가져가기**
때문이다. 큰 수술은 그 다음이다.

## Alternatives Considered

### Alternative A: JUnit 5 병렬 실행 (`junit.jupiter.execution.parallel.enabled`)

- **장점**: 코드 수정이 거의 없다. 설정만으로 이론상 3~4배
- **단점**: Testcontainers 가 **공식적으로 미지원**이다 — 기본 extension 은 순차 실행만
  검증됐고 병렬은 "unintended side effects" 가 있을 수 있다고 명시한다
  ([testcontainers-java#1495](https://github.com/testcontainers/testcontainers-java/issues/1495)).
  컨테이너가 동시에 3~4배로 떠 러너 메모리에서 터질 위험도 있다
- **기각 사유**: 채택안과 **방향이 반대**다. 이 ADR 은 동시 컨테이너 수를 줄이는 결정이고,
  A 는 늘리는 결정이다. 둘을 같이 할 수 없다

### Alternative B: Kafka → Redpanda 교체

- **장점**: 기동이 2배 빠르다 (2.7s vs 5.1s, JVM 워밍업 부재)
- **단점**: 26곳 교체 + Kafka 호환성 표면이 미묘하게 다르다
- **기각 사유**: **채택안이 이 대안의 가치를 없앤다.** 현재는 26회 × 약 2.4초 = 약 60초의
  이득이지만, 싱글톤 후에는 컨테이너가 1개라 이득이 2.4초로 쪼그라든다.
  선후관계상 먼저 할 이유가 없다 — 싱글톤 이후 재측정 대상으로 보류한다

### Alternative C: `withReuse(true)` + `testcontainers.reuse.enable`

- **장점**: 설정 두 줄. 로컬 반복 실행이 빨라진다
- **단점**: 러너가 매번 새로 뜨는 CI 에서는 효과가 **0** 이다. 로컬에 stale 컨테이너가
  남아 "내 머신에서만 통과" 를 만든다
- **기각 사유**: CI 임계경로가 표적인데 CI 에 효과가 없다. 싱글톤 패턴의 대체재가
  아니라 로컬 개발 전용 최적화다. 싱글톤 이후에도 남는 로컬 이득이 있으면 그때 본다

### Alternative D: 러너 코어 재배분 (잡 간 코어 불균형 해소)

- **장점**: 금전 비용 없이 병목 잡에 여유를 몰아줄 수 있다
- **기각 사유**: **해당 사항이 없다.** 모든 잡이 `ubuntu-latest` 기본 러너이고 재배분할
  불균형이 존재하지 않는다

### Alternative E: e2e 를 PR 에서 제외하고 push(main) 전용으로

- **장점**: 임계경로에서 856초를 통째로 제거
- **기각 사유**: saga 회귀를 **머지 후에** 발견하게 된다. 이 레포가 음성 대조군까지 갖춰
  e2e 에 들인 투자에 역행한다. 더 좁은 수단(§후속 ①)이 효과의 대부분을 훨씬 낮은
  리스크로 가져간다

### Alternative F: 패키지/모듈을 더 잘게 쪼개기

- **기각 사유**: 반복되는 것은 모듈 경계가 아니라 **컨테이너 기동**이다. 경계를 어디에
  긋든 클래스마다 MySQL·Kafka·Redis 를 다시 띄우는 한 844초는 그대로다

## Consequences

### 긍정적 영향

- order 샤드 975초 → **150~250초** 추정. 844초 중 대부분이 표적이다
- **로컬이 같은 비율로 빨라진다.** 이 결정의 실제 값어치는 CI 벽시계보다 여기에 있다 —
  1단계(워크플로 그래프)가 이미 `test` 를 임계경로에서 빼내므로, CI 단축분만 보면
  19분 → 16분에 그친다. 반면 로컬 전량 실행은 직접 줄어든다
- **테스트가 늘어도 시간이 비례해 늘지 않는 구조**가 된다. 현재는 클래스 1개 추가가
  32초 추가지만, 싱글톤 후 새 클래스는 자기 본문 비용만 낸다
- context 캐시가 따라온다 — 지문이 동일한 17개 클래스가 context 1개를 공유한다

### 부정적 영향 / 트레이드오프

- **격리가 덮어 주던 문제가 드러난다.** 클래스마다 새 DB 였기에 정리하지 않아도 티가
  나지 않던 상태가 뒤 클래스로 이어진다. 실패하는 클래스와 원인이 있는 클래스가 달라져
  가장 잡기 어려운 종류의 flaky 가 된다. 셔플 검증(결정 3)이 이것을 색출하는 수단이고,
  `cleanDatabase()` 가 이미 `information_schema` 동적 조회로 구현돼 있어 **도구는 이미
  있다** — 없는 것은 "어느 테스트가 그것을 호출해야 하는가" 의 재평가다
- 테스트 클래스가 자기 인프라를 선언하지 않게 되어, 한 클래스만 읽어서는 어떤 컨테이너
  위에서 도는지 알 수 없다. `@Import` 대상을 따라가야 한다
- `OrderCursorQueryPlanTest` 가 규약 밖의 예외로 남는다. 예외가 하나라도 있으면 lint 는
  화이트리스트를 갖게 되고, 그 목록이 관리 대상이 된다

### 왜 셋(싱글톤·lint·셔플)을 한 묶음으로 두는가

lint 없이 싱글톤만 하면 **반년 뒤 원상복구된다.** 새 통합 테스트가 관행대로 `@Container`
를 선언하면 그 클래스만 조용히 컨테이너를 다시 띄우고, CI 시간은 다시 우상향한다.
이 레포는 이미 `scripts/*-lint.sh --self-test` 가 20개 넘게 서 있어 강제 수단의 자리가
마련돼 있다 — 규약을 문서에만 두는 것은 그 관례에도 어긋난다.

셔플 없이 싱글톤만 하면 **전환이 성공했는지 판정할 수단이 없다.** 순차 실행 한 번이
초록인 것은 "격리가 필요 없었다" 와 "이번 순서에서만 운이 좋았다" 를 구별하지 못한다.
이 레포가 음성 대조군으로 e2e 의 vacuous-green 을 막은 것과 같은 종류의 장치다.

### 후속 결정에 미치는 영향

1. **e2e 가 다음 병목이 된다.** 1·2단계 후 임계경로는 `max(test ~4분, images + e2e ~16분)`
   이 되어 e2e 가 단독 병목이다. e2e 856초의 내부는 이미 측정해 두었다 —
   셋업·이미지 로드·runner 빌드 50초 / **시나리오 4종 197초 / 음성 대조군 605초(71%)**.
   음성 대조군은 `product-service` 정지·기동 사이클과 "일어나지 않음" 의 대기로 채워져
   있다(`BUDGET_CONTROL=900s`). 이것만 push 전용으로 돌리면 e2e 가 약 4분이 되지만,
   vacuous-green 방어가 PR 에서 빠진다 — **2단계 완료 후 재측정하고 별도로 결정한다.**
   지금 정하면 순서가 틀린다
2. Redpanda(Alternative B)는 싱글톤 이후 이득이 2.4초로 줄어든 상태에서 재평가한다
3. `gradle.properties` 신설 / `setup-gradle` 전환은 표적인 컴파일 구간이 60초뿐이라
   상한이 낮다. 리스크가 없으므로 언제든 하되, 이 결정의 선후에 묶이지 않는다
4. `work.md` §8 의 로컬 검증 범위(`./gradlew test` 전량)는 이 결정 이후 재평가한다.
   싱글톤으로 로컬이 충분히 빨라지면 범위를 좁힐 이유가 사라진다

## References

- 측정 원본: PR run [35731821466](https://github.com/Kimgyuilli/PeakCart/actions/runs/35731821466)
  (잡별 소요 · `:order-service:test` 975s · `shard-results-order-service` artifact 의 `TEST-*.xml` 58개)
- `common/src/testFixtures/java/com/peekcart/support/AbstractIntegrationTest.java` — 현행 per-class 규약과 `cleanDatabase()`
- `order-service/src/test/java/com/peekcart/order/infrastructure/OrderCursorQueryPlanTest.java` — `mysql:8.0.46` 고정 예외
- `.github/workflows/ci.yml` — 샤드 매트릭스 · `images`/`e2e` 의존 그래프
- `scripts/saga-e2e-smoke.sh` — 음성 대조군과 `BUDGET_CONTROL`
- [SivaLabs — Run Spring Boot Testcontainers Tests at Jet Speed](https://www.sivalabs.in/blog/run-spring-boot-testcontainers-tests-at-jet-speed/) — 싱글톤 컨테이너 + `@ServiceConnection @Bean` 패턴, 컨테이너 4→2→1 측정
- [rieckpil — Spring Boot TestContext Cache Best Practices](https://rieckpil.de/spring-boot-testcontext-cache-best-practices/) — context 캐시 키를 깨는 요인
- [rieckpil — Reuse Containers With Testcontainers](https://rieckpil.de/reuse-containers-with-testcontainers-for-fast-integration-tests/) — `withReuse` 가 CI 해법이 아닌 이유
- [testcontainers-java#1495](https://github.com/testcontainers/testcontainers-java/issues/1495) — JUnit 5 병렬 실행 미지원
- [Docker — GitHub Actions cache backend](https://docs.docker.com/build/cache/backends/gha/) — 매트릭스 빌드의 `scope` 분리
- [미리디 — 테스트가 늘수록 느려지던 CI, 16분에서 3분이 되기까지](https://medium.com/miridih/%ED%85%8C%EC%8A%A4%ED%8A%B8%EA%B0%80-%EB%8A%98%EC%88%98%EB%A1%9D-%EB%8A%90%EB%A0%A4%EC%A7%80%EB%8D%98-ci-16%EB%B6%84%EC%97%90%EC%84%9C-3%EB%B6%84%EC%9D%B4-%EB%90%98%EA%B8%B0%EA%B9%8C%EC%A7%80-2744ffbbee25)
  — 같은 형태의 진단(`import 1354s vs tests 243s`)과, 규칙을 저장소에 고정해 효과를 유지하는 방법
