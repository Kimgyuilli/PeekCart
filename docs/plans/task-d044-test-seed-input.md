---
grade: M
---
# task-d044-test-seed-input

PR: [#149](https://github.com/Kimgyuilli/PeekCart/pull/149)

## 1. 명제

변경 없이 `./gradlew :<svc>-service:test` 를 두 번 돌렸을 때 두 번째가 `UP-TO-DATE` 가 아니면 미완이다.
또 `-PtestSeed=<N>` 으로 시드를 명시한 재현 실행이 `UP-TO-DATE` 로 건너뛰어지면 미완이다.

배경: 서비스 5모듈(`user`·`product`·`order`·`payment`·`notification`)의 `build.gradle` 이 시드 기본값을
`System.currentTimeMillis()` 로 잡아 `systemProperty 'junit.jupiter.execution.order.random.seed'` 로 넘긴다
(예: `user-service/build.gradle:73-83`). `systemProperty` 는 test 태스크 입력이므로 매 실행 입력이 바뀐다.
D-040 로컬 실측 568초.

코드 확인 (2026-09-27):

| 전제 | 확인 결과 |
|---|---|
| 시드 기본값이 5모듈 모두 같은 패턴 | 확인. `findProperty('testSeed') ?: getenv('TEST_SEED') ?: currentTimeMillis()` |
| CI 가 빌드 캐시로 test 를 건너뛸 수 있나 | 아니다. `org.gradle.caching` 없음, `gradle.properties` 없음, `setup-java cache: gradle` 은 의존성 캐시뿐. CI 는 매번 test 를 돌린다 → **CI 동작 변화 없음**, CI 벽시계 측정 불필요 |
| 시드를 참조하는 다른 곳 | `CLAUDE.md:167` 재현 명령뿐. 명령 형식은 유지된다 |
| Gradle 버전 | 8.14.4 |

결정 (사용자 확인): 시드를 명시하지 않은 경우에만 입력에서 뺀다. 명시한 시드는 입력으로 남긴다 —
재현 실행이 직전 무작위 실행과 입력이 달라 반드시 돈다.
**맞바꾸는 것**: 변경 없는 재실행은 더 이상 새 순서로 한 번 더 돌지 않는다(D-035 의 "매 실행 섞는다" 중
"변경 없는 재실행" 부분만 포기). 코드가 바뀌면 여전히 새 시드로 섞인다. 새 순서가 필요하면 `--rerun`.

ADR 판단: 외부 의존성·경계·인프라 변화 없음 → 불필요.

## 2. 작업 항목

- [x] P1. 5모듈 `build.gradle` 에서 시드 결정을 바꾼다. 명시 시드(`testSeed`/`TEST_SEED`)가 있으면 지금처럼
  `systemProperty` 로 넘기고(입력), 없으면 `doFirst` 안에서 무작위 시드를 생성해 `systemProperty` 로 주입한다
  (입력 스냅샷 이후라 입력이 아니다). 시드 로그 줄은 유지한다
- [x] P2. 시드 관련 주석을 새 동작으로 고친다. 특히 `order-service/build.gradle:106-107` 의
  "UP-TO-DATE 로 건너뛰지 않는다 … 받아들인다" 는 사실과 달라지므로 정정
- [x] P3. `docs/TASKS.md` D-044 행 상태 갱신, 실측 전후 기록 (PR 링크와 ✅ 는 `/ship` §8)

## 3. 검증 방법

모듈 하나(`user-service`, 가장 짧음)로 전부 확인하고, 나머지 4모듈은 V1 만 확인한다.

- V1. `test` 연속 2회 → 2회차 `UP-TO-DATE`. (변경 전 코드에서 같은 절차가 2회차도 실행됨을 먼저 확인해 대조군으로 삼는다)
- V2. 무작위 실행 2회(사이에 테스트 소스 한 줄 변경)의 로그 시드가 서로 다르고, 실제 클래스 실행 순서도 다르다 — 섞기가 살아 있음
- V3. 무작위 실행 직후 `-PtestSeed=<V2 로그의 시드>` → `UP-TO-DATE` 가 아니고 실행된다. 같은 시드로 한 번 더 → `UP-TO-DATE`
- V4. V3 실행의 클래스 순서가 해당 시드의 원 실행 순서와 같다 — 시드가 JUnit 에 실제로 전달됨 (주입이 조용히 무시되면 여기서 걸린다)
- V5. `TEST_SEED=<N>` 환경변수로도 V3 과 동일하게 동작

### 검증 결과 (2026-09-27, 로컬)

| 항목 | 결과 |
|---|---|
| V1 대조군 (변경 전, user) | 2회 모두 실행 (1m10s, 1m5s) |
| V1 (변경 후, user) | 1회차 실행 1m11s, 2회차 `UP-TO-DATE` 4s |
| V1 (변경 후, 전 모듈) | `./gradlew test` 2회차에서 notification·order·payment 가 `UP-TO-DATE`. product 는 1회차 실패로 재실행됐고, user 는 1회차가 product 실패에서 멈춰 돌지 않았고, 2회차에서 돈 것은 직전 실행이 `TEST_SEED` 명시라 입력이 달랐기 때문이다(의도한 동작) |
| V2 | 테스트 클래스 바이트코드 변경 후 새 시드(1790505716611, 직전 1790505599130), 클래스 순서 다름. 주석만 바꾸면 바이트코드가 같아 `UP-TO-DATE` (정상) |
| V3 | 무작위 실행이 `UP-TO-DATE` 인 상태에서 `-PtestSeed=1790505599130` 실행됨, 같은 시드 재실행은 `UP-TO-DATE` |
| V4 | V3 실행의 클래스 순서가 원 실행(시드 1790505599130)과 일치. `doFirst` 주입이 JUnit 에 전달됨 |
| V5 | `TEST_SEED=1790505716611` 실행됨, 순서가 원 실행과 일치, 재실행 `UP-TO-DATE` |

`./gradlew test` 1회차에서 product-service 89건 실패. 원인은 `SharedContainers` 컨테이너 한 개의
기동 대기 초과(`LogMessageWaitStrategy` `ContainerLaunchException`)이고, 뒤따른 실패는 정적 초기화
실패로 인한 `NoClassDefFoundError` 연쇄다. 시드는 클래스 순서만 바꾸므로 컨테이너 기동과 무관하다고
판단한다. 2회차에서 product-service 전량 통과. V3 의 `-PtestSeed` 실행이 4m38s 로 평소(약 1분)보다
길었던 것도 같은 시간대의 Docker 지연으로 보이나 원인은 확정하지 않았다.

## 미해결

없음.
