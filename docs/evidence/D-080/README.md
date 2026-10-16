# D-080 측정 재집계 근거

2026-10-17에 남아 있던 원본 XML/log/jstack과 실제 dependency jar를 다시 읽었다.
새 부하·통합 실행 결과가 아니라 2026-10-16 재현 자료의 재집계다.

원본 위치: `/private/tmp/claude-501/-Users-kimgyuill-dev-projects-PeakCart/c3dd6e96-1c7b-44d7-a29c-9faca0642fb3/scratchpad`.
원본 전체의 임시 보존에 의존하지 않도록 클래스별 시각·시간·테스트 결과와 XML SHA-256을
[class-timings.tsv](class-timings.tsv)에 보존했다. 이 표는 XML에 기록된 값이며 static initializer나
JVM 종료 비용을 직접 측정한 필드가 아니다. `shutdown-stack.txt`는 실제 종료 스레드의 발췌다.

## CI 원본

[run 35731821466](https://github.com/Kimgyuilli/PeakCart/actions/runs/35731821466)의 저장된
`shard-results-order-service` XML 58개와 `order-job.log`를 대조했다.

- `:order-service:test` 로그: 2026-09-22T13:13:34.0116234Z.
- 다음 task `:order-service:check`: 13:29:49.3118461Z. 차이 약 975.3초.
- 첫 XML timestamp: 13:13:53.510Z. 마지막 XML timestamp + time: 13:21:14.693Z.
- XML time 합: 131.202초. 마지막 클래스 종료 추정부터 다음 task까지 약 514.6초.

따라서 975초에서 XML time 131초를 뺀 844초를 전부 클래스 기동 비용으로 볼 수 없다.
XML timestamp/time과 로그 간 경계는 근사치이며 미귀속 시간을 상세 비용으로 재명명하지 않는다.

## 로컬 재현 자료

| 자료 | task 시작 UTC | task 끝 UTC | task 초 | 첫 클래스 전 | 클래스 구간 | XML time 합 | 마지막 클래스 뒤 |
|---|---|---|---|---|---|---|---|
| e1/pre | 17:21:40.764195 | 17:40:09.013477 | 1108.2 | 31.3 | 597.5 | 153.1 | 479.5 |
| e1/pre-lc1s | 17:41:22.960282 | 17:59:38.789698 | 1095.8 | 29.6 | 974.0 | 202.3 | 92.3 |

둘 다 2026-10-16, 클래스 58개·테스트 420개·실패 0개. `pre-lc1s`는
`spring.lifecycle.timeout-per-shutdown-phase=1s` 변이(`log.gradle`에서 환경변수 E1_LIFECYCLE을 전달).
종료 꼬리는 줄었지만 클래스 구간이 늘었으므로 총 시간 개선으로 일반화하지 않는다.
`pre`의 인접 클래스 간 3초 초과 gap은 26개, 합 430.7초, 중앙값 16.5초다.
이 gap도 클래스 준비 비용의 관측 대리값이지 각 내부 단계의 독립 계측은 아니다.

원본 SHA-256:

- `e1/pre-gradle.log`: `31fbfed21bd7c77b2510116e436b1dd044d5ea3f43bfa3fc6800067f6c7e0d22`
- `e1/pre-lc1s-gradle.log`: `b5a3be3963aae5095861341869fdc96fbca6cc26318f1601692f7e9b633cf1e8`
- `e1/pre-jstack/173844.txt`: `73e0e1ea915ac5978b812e3bb662337c12f36c624915b06a466798bfdfb5a932`

jstack의 `SpringApplicationShutdownHook`은 `DefaultLifecycleProcessor$LifecycleGroup.stop`의
CountDownLatch await에 있다. 따라서 종료 대기 비용을 기동 비용으로 합산할 수 없다.

## 실제 jar 확인

`javap -p -c`로 로컬 Gradle 캐시의 다음 버전을 검사했다.

- Spring Boot `spring-boot-testcontainers:3.5.12`의 `ServiceConnectionContextCustomizer$CacheKey`는
  `Container<?> container` 필드를 가진 record이며 constructor는 container supplier의 객체를 담는다.
- Testcontainers `testcontainers:1.21.4`의 `GenericContainer.equals(Object)`는
  `aload_0; aload_1; if_acmpne`로 객체 동일성을 비교한다.

클래스마다 다른 static 컨테이너 객체라면 포트가 바뀌는지와 무관하게 이 캐시 키가 다르다.
컨테이너를 Spring bean으로 등록했을 때의 생명주기 문제와 이 캐시 키 문제는 구분한다.

## 셔플 이전 순차 실패 원본

후속 독립 검증에서 원본 세션을 확보해 작성자가 다시 확인했다.
`~/.claude/projects/-Users-kimgyuill-dev-projects-PeakCart/bf37ea21-0531-4800-8ebf-4ee2fcc96d3b.jsonl`
SHA-256: `851ee864f2b9396491c35ced498a179ccbbc924cd13c16ea1b01202d0025cc1f`.

| 원본 행 | UTC(2026-09-22) | 관측 |
|---|---|---|
| 1281 / 1287 | 17:03:47 / 17:10:16 | 순차 run1 명령 후 실제 Order adapter 검증 실패 |
| 1382 / 1386 | 17:21:49 / 17:28:29 | 순차 run3 명령 후 같은 검증 실패 |
| 1464 / 1465 | 17:44:12 / 17:48:19 | 순차 run5 명령 후 같은 검증 실패 |
| 1592 | 18:02:49 | ClassOrderer$Random 셔플 도입 |

세 실패 모두 `stock.reservation.result 는 실제 Order 를 조회해 갈린다` 검증에서
`PENDING·미확정 주문은 통과해야 한다` assertion이 `이미 예약이 확정됐다` 사유로 실패했다.
따라서 결함 4를 셔플이 최초 발견했다고 할 수 없다. 셔플이 같은 결함을 재현하고 다른 결함을
드러내는 데 기여한 사실은 유지하며 나머지 세 결함의 최초 관측 시점을 이 자료로 판단하지 않는다.
초기 run1/run3/run5와 이후 셔플 v4.txt.run1.log의 run 번호를 혼동하지 않는다.
