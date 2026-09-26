---
grade: M
---
# task-d035-user-container-singleton

D-035(컨테이너 싱글톤 4모듈 확산) 의 첫 PR. 범위는 user-service 하나다.
순서는 규모가 작은 쪽부터 user → notification → payment → product.

## 1. 명제

아래 중 하나라도 성립하면 미완이다.

- user-service 테스트가 `@Container` / `@Testcontainers` 로 컨테이너를 직접 선언한다
- `scripts/integration-test-container-lint.sh` 가 user-service 를 검사하지 않아, 직접 선언이
  되살아나도 CI 가 초록이다
- 클래스 순서를 섞었을 때 순서에 따라 결과가 달라진다(공유 DB/Redis 상태 누수)

### 코드 검증 (2026-09-26, 현재 코드 기준)

| 전제 | 확인 결과 | 범위 영향 |
|---|---|---|
| 공유 인프라 존재 | `common/src/testFixtures/.../SharedContainers.java` · `AbstractIntegrationTest` · `SchedulingConfig`(`app.scheduling.enabled`) · `KafkaListenerStartupConfig`(`app.kafka.listener.enabled`) 모두 common 에 있음 | 새로 만들 것 없음 |
| 전환 대상 | `@Container` 선언 파일 4개: `RefreshTokenReuseIntegrationTest` · `UserObservabilityMetricsIntegrationTest` · `UserSecurityIntegrationTest` · `UserCleanupMatrixIntegrationTest`. 전부 MySQL + Redis, `@ServiceConnection` 방식 | — |
| 자율 writer | user-service · common-auth · common-observability main 에 `@Scheduled` · `@KafkaListener` **0개**. `UserApplication` 은 `KafkaAutoConfiguration` 제외 | **D-035 ③ opt-in 전수 조사 결과 = 0건.** `@EnableScheduling` 제거 대상도 없음(user 진입점에 없음) |
| Kafka 토픽 누적(④) | user 는 Kafka 미사용 | 이 PR 에서 관측 불가. product/payment/notification PR 로 이월 |
| 공유 상태 | `RefreshTokenReuse` 는 `@BeforeEach` 에서 `refresh_tokens` 와 `auth:deny:family:*` 를 지운다. `Observability` 의 카운터 단언은 before/after 차분. 고정 이메일(`*-metric@peekcart.test`)은 그 클래스에만 있고 클래스는 한 번만 돈다. `Security` 는 `System.nanoTime()` 이메일 | 기존 cleanup 으로 충분해 보임. **셔플 실행으로 확인한다**(P4) |
| lint 대상 | `MODULES = ["order-service"]` 뿐 | user-service 추가 필요 |

### 트레이드오프

- **Kafka 컨테이너가 불필요하게 뜬다.** `SharedContainers` 의 static initializer 가
  MySQL·Redis·Kafka 를 한꺼번에 `deepStart` 한다. user 는 Kafka 를 쓰지 않지만 한 번 뜬다
  (병렬 기동이라 벽시계 비용은 셋 중 최댓값). 컨테이너별 lazy holder 로 쪼개면 피할 수 있지만
  common testFixtures(order 가 쓰는 공유 표면)를 바꾸게 된다. 모듈당 1회 비용이라 수용하고,
  측정치(P5)에 Kafka 기동이 포함된다는 점을 적어 둔다.
- **context 는 클래스별로 따로 뜬다.** 각 클래스의 `@DynamicPropertySource`(TestRsaKeys) 와
  `@TestPropertySource` 가 달라 context 캐시 키가 다르다. 이 PR 이 줄이는 것은 컨테이너 기동
  비용이고, context 병합은 목표가 아니다.
- **writer 0 인데 `build.gradle` 에 off 프로퍼티를 넣는다.** 지금 효과는 없다. 넣는 이유는
  ADR-0029 의 모듈 기본값을 user 에도 걸어 두기 위해서다. 나중에 user 에 writer 가 생기면
  기본 off 상태에서 출발하고, 그 writer 를 검증하려는 테스트는 opt-in 을 해야 초록이 된다.
  빼면 order 와 규약이 갈린다.

## 2. 작업 항목

- [x] P1. 4개 클래스에서 `@Testcontainers` · `@Container` · `@ServiceConnection` 필드와
  관련 import 를 제거하고 `@Import(SharedContainers.class)` 를 붙인다. `@DynamicPropertySource`
  (TestRsaKeys) 와 `@TestPropertySource` 는 그대로 둔다. javadoc 의 "Kafka 컨테이너 없음" 문구는
  더 이상 사실이 아니므로 고친다(Kafka 는 뜨지만 user 가 연결하지 않음).
- [x] P2. `user-service/build.gradle` `test` 태스크에 order 와 같은 세 가지를 넣는다:
  `app.scheduling.enabled=false` · `app.kafka.listener.enabled=false` · 클래스 순서 랜덤 +
  시드 출력(`-PtestSeed` 재현).
- [x] P3. `scripts/integration-test-container-lint.sh` 의 `MODULES` 에 `user-service` 추가.
  예외(ALLOWED) 는 추가하지 않는다.
  **(구현 중 추가)** `--self-test` 픽스처가 order-service 디렉터리만 만들어, 대상이 늘자
  "정상 배선(픽스처)" 케이스가 ITC-001 로 깨졌다. 픽스처에 user-service 디렉터리를 만들고,
  user-service 위반을 잡는 케이스를 추가했다(6/6 → 7/7).
- [x] P4. 셔플 검증: 서로 다른 시드로 `:user-service:test` 를 3회 돌려 전부 초록인지 본다.
  실패하면 cleanup 누락이다 — 해당 클래스에 `cleanDatabase()` 등 cleanup 을 넣는다.
- [x] P5. 전후 실측: 전환 전·후 `:user-service:test` 벽시계 시간(`--rerun-tasks`, 캐시 배제)을
  기록한다. 컨테이너 기동 횟수(전: 클래스당 2개 × 4, 후: 3개 × 1)도 로그로 확인한다.

## 3. 검증 방법

- **lint 가 막는지**: user-service 테스트 한 곳에 `@Container` 를 되돌려 넣고
  `bash scripts/integration-test-container-lint.sh` 가 `ITC-002` 로 exit 1 인지 본다. 되돌린 뒤
  exit 0 · `--self-test` 6/6 통과 확인.
- **싱글톤이 실제로 공유되는지**: 테스트 실행 중 `docker ps` 로 mysql:8.0 / redis:7 컨테이너가
  각각 1개인지 본다(전환 전에는 클래스마다 새로 뜬다).
- **cleanup 누락이 셔플에서 드러나는지**: `RefreshTokenReuseIntegrationTest` 의
  `@BeforeEach` cleanup 을 "자기 클래스가 만든 행만 지운다"(클래스 시작 시점 `MAX(id)` 초과분만
  DELETE)로 임시 변경하고 시드별로 돌린다. 이 클래스가 먼저 도는 시드는 통과하고, 다른 클래스가
  먼저 ACTIVE 토큰을 남기는 시드는 실패해야 셔플이 클래스 간 누수를 검출한다는 증거다. 확인 후 원복.
  - 정정 이력: 초안은 DELETE 를 통째로 빼는 뮤테이션이었다. 그러면 클래스 **안** 테스트끼리의
    누수로도 실패해서 순서와 무관하게 빨개지므로, 셔플의 검출력을 증명하지 못한다.
- **writer off 가 걸렸는지**: `app.scheduling.enabled=false` 상태에서 `SchedulingConfig` 빈이
  context 에 없음을 한 번 확인한다(일회성 확인, 테스트로 남기지 않는다). 음성 대조로 build.gradle 의
  프로퍼티를 뺐을 때 같은 단언이 실패하는지도 본다.

### 검증 결과 (2026-09-26)

| 항목 | 결과 |
|---|---|
| 전후 실측(P5) | `:user-service:test --rerun` 176초 → **58초**. 테스트 65개 전부 통과 |
| 싱글톤 공유 | 실행 중 `docker ps` 샘플링 최댓값 3개(mysql·redis·kafka 각 1) |
| 셔플(P4) | 시드 1790417009622 · 11 · 42 전부 초록 |
| 셔플 검출력 | 뮤테이션 적용 시 Reuse 가 맨 앞인 시드 11 만 통과, 시드 42·1·2·3 은 Reuse 7건 실패 |
| lint | user-service 에 `@Testcontainers` 복원 → ITC-002 exit 1. MODULES 에서 user-service 제거 → self-test 신규 케이스 false-green 검출 |
| writer off | `SchedulingConfig` 빈 부재 확인. 프로퍼티 제거 시 `["schedulingConfig"]` 로 실패(음성 대조 성립) |
- 최종: `./gradlew test` 전량 (work.md §8).

## 미해결 / 이월

- D-035 ④ Kafka 토픽 누적 관측 → Kafka 를 쓰는 notification/payment/product PR 에서.
- `SharedContainers` 컨테이너별 lazy 기동 → 불필요한 Kafka 기동 비용이 측정상 무시할 수 없을 때만
  재검토. 이번 PR 범위 아님.
