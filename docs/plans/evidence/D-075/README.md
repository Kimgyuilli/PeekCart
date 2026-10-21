# D-075 로컬 outbox 비용 분해

2026-10-21 KST, 기준/실행 command/source hash는 [execution.json](execution.json)에 고정했다. 실제 측정은 로컬 MySQL/Kafka와 현재 production poller/repository를 사용했다. 300개는 제한된 비교 fixture이며 목표 TPS·backlog 수용치가 아니다. 이 결과로 과거 GKE 천장을 확정하거나 현행 정책을 수용하지 않는다.

## 입력과 실행

```sh
SPRING_PROFILES_ACTIVE=test PEEKCART_OUTBOX_BENCHMARK=true ./gradlew :order-service:test --tests 'com.peekcart.global.outbox.OutboxThroughputLocalProbeTest' --offline --no-daemon --no-parallel --max-workers=1 --console=plain --rerun-tasks
```

성능 probe는 위 환경 변수로 opt-in하며 일반 CI에서는 실행하지 않는다. 전체 CI는 소스 컴파일과 기존 suite를 검사하고 실제 성능 근거는 여기 보존한 로컬 실행이다. production 설정·공유 poller는 수정하지 않았다. 계측용 repository decorator/registry와 명시 profile 값을 주어 공유 OutboxPollingService를 생성했다. 실제 DI poller bean의 scheduler wiring 자체는 검사 범위가 아니다.

- SharedContainers: MySQL8.0.46, Kafka3.8.1 단일 broker, Redis7. order의 실제 Flyway schema, 실제 Spring Data repository proxy와 KafkaTemplate을 사용했다. Redis는 공유 fixture의 부수 구성이다.
- 토픽별 partition3/replication1, 서로 다른32key, UUID eventId, sequence와900 ASCII padding을 포함한 JSON payload. payload 전체 byte 수는 eventId/sequence를 포함해 더 크다. 앱 KafkaListener와 배경 scheduler는 껐으며 probe 소유 Spring fixed-delay task 하나만 돌렸다.
- 실제 producer 설정: acks=-1(all), idempotence=true, linger.ms=0, batch.size=16384, compression=none, max.in.flight.requests.per.connection=5, delivery.timeout=5000ms, request.timeout=3000ms, max.block=3000ms. 모든 대조에 같은 producer를 사용했다.
- 각 scheduled trial은 별도 토픽의 broker warmup1회와 실제 poller/JPA warmup20개를 측정 밖에서 수행했다. seed/최종 검증도 측정 밖이다. 4profile 순서를 반복마다 회전하고 각3회 실행했다. producer-only는 같은 producer에서 DB 없이 window1/32를 비교한다. window32는32개를 보낸 뒤 모두 기다리는 제한된 진단이며 production 비동기 outbox가 아니다.
- fixed-delay와 실제 JDBC ShedLock core를 조합했다. minlock4s/max5m는 production annotation과 대조했다. 전체 앱의 scheduling proxy, 여러 Pod, scheduler pool4의 다른 잡 부하는 재현하지 않았다. minlock0은 원인 분리용 fixture 설정이며 배포 제안이 아니다.

## 측정 경계와 단위

[raw-results.jsonl](raw-results.jsonl)은 XML stdout의 실제53개 JSON marker payload다. [results.json](results.json)은 같은 row에 아래 차분을 추가한 선택 결과이며 [summary.md](summary.md)는 반복별 중앙값/최소/최대다. 표의 각 열은 독립 중앙값이며 단일 실행의 정확한 합산 분해는 raw row를 따른다.

| 필드 | 의미 |
|---|---|
| wallNs | 첫 schedule 직전부터 drain 신호 뒤 task 취소·scheduler shutdown까지. 첫 cycle은 즉시 시작한다. |
| attempts.startNs/endNs | 위 시작 기준 상대 nanoTime. 실제 실행/skip 각각을 기록한다. |
| bodyStartNs/bodyEndNs | 실제 lock 획득 후 unchanged pollAndPublish 호출의 시작/끝. skip은0이다. |
| findNs/saveNs | decorator가 실제 repository proxy 호출을 재는 누적 nanoTime. save는 호출 반환까지다. |
| publishTimerNs | production outbox.publish success Timer의 누적 nanoTime. buildRecord/send/get을 포함하고 성공 save 이전에 끝난다. 순수 broker 처리 시간은 아니다. |
| schedulerWaitAndLifecycleNs | wall에서 전체 attempt 시간을 뺀 값. scheduler 대기와 초기/종료 overhead가 섞여 있다. |
| lockDispatchOverheadNs | attempt에서 poll body를 뺀 값. lock SQL/dispatch 등이며 순수 lock DB 시간으로 단정하지 않는다. |
| pollResidualNs | body에서 find/save/publish Timer를 뺀 계측 외 잔여. 이벤트 상태 전이·반복·계측 overhead 등이 섞인다. |

poller 전체에는 Transactional이 없고 테스트도 트랜잭션 밖임을 단언했다. adapter는 Spring Data save로 위임하므로 저장 호출별 transaction이 반환 전에 완료된다. 각 save 계측은 merge/read/update/flush/commit·connection/JVM 대기 등을 함께 포함한다. 이 기록은 서버 내부 commit/SQL/CPU를 따로 측정한 결과가 아니다. annotation과 proxy 경계의 코드 사실 및 실제 호출 시간을 구분한다.

## 관측과 선택지

전체 반복값은 summary/raw를 따른다. scheduled active body 중앙값은 약1.81~2.13초이고 save 누적은 약1.49~1.68초, publish Timer는 약0.32~0.38초였다. 첫 반복은 뒤 반복보다 느렸으므로3회 중앙값을 고정 성능 보장으로 쓰지 않는다.

1. **대기 간격**: baseline drain 중앙값12.255초에서 delay-only9.408초로 줄었다. 후자는 매번12lock skip이 있었고 minlock0 진단은3.094초였다. 현재4초 락 때문에 delay500ms가 실행 주기500ms를 뜻하지 않는다는 반례다. lock 최소 보존 의미와 목표가 정해진 뒤 delay/lock을 함께 검토한다.
2. **batch**: batch500 profile은300개를1cycle로 비워 중앙값1.825초였다. batch100의3cycle과 달리 cycle 사이 대기가 없어진 효과를 포함한다. batch500 지속 부하의 처리율이나 장시간 cycle의 안전성을 증명하지 않는다. 먼저 기대 backlog·cycle timeout·DB 점유 영향을 판단해야 한다.
3. **순차 발행**: producer-only window1은238.1ms, window32는24.8ms 중앙값이었다. 이 로컬 환경에서 pipelining의 기회가 관측됐지만 DB 상태 저장이 빠진 진단이다. 실제 outbox에 도입하려면 ack 실패/늦은 ack/저장 실패/중단/중복·순서/replay drain 계약을 별도 설계·검증해야 한다(ADR-0012/0020/0022).
4. **DB 호출**: 실제 active path에서 save 호출 시간이 가장 큰 비중이었다. 로컬에서 순차 broker ack만이 잔여 비용 전부라는 가정은 지지되지 않았다. DB 서버 측 SQL/transaction 계수와 저장 경계를 먼저 더 분해하는 것이 합리적이다. transaction 묶기나 비동기 저장은 발행/커밋 실패 의미를 바꾸므로 이번에 production 변경을 하지 않았다.

300개는 첫 cycle 즉시 시작 후 두 번만 기다리는 유한 drain이다. 이 drain의 평균 records/second를 정상상태 batch/delay 처리율 상한과 직접 비교하지 않는다. 과거13ms/event와552초 배수는 과거 GKE 관찰이며 이번 입력이나 결과에 섞지 않았다. 목표·최대 backlog·초과 대응 정책은 사용자 결정으로 남아 있다.

## 정합성과 음성 대조

모든12scheduled trial에서300회 publish Timer/성공 save, batch별3회 또는1회 find를 확인했다. DB PUBLISHED=300/PENDING=0/FAILED=0과 별도 broker end offsets/record 개수, eventId별1회, payload byte/키, 같은 key의 순서를 확인했다. producer-only6trial도 별도 broker 검증을 통과했다. 실제 업무 consumer 처리·멱등성·replay 복구를 이 결과로 보증하지 않는다.

- 실제 save 앞에5ms를30회 주입한 진단은 save313.97ms를 기록해 주입150ms 이상을 포함했다. 원본 profile은 지연0이다. 이 대조는 계측 경계 확인이며 DB 지연 원인 재현이 아니다.
- 실제 broker에 같은 fixture를2회 발행한 negative는 verifier가 AssertionError로 거부했다. 정상 trial은 같은 verifier를 통과했다.
- [분석 검사 반례](extractor-controls.txt): 정상 원본 XML은 통과하고 JUnit 실패, marker 누락, 반복 중복, broker 미검증, timer 건수 오류, 역전된 attempt 시간이 각각 거부됐다. `python3 -B docs/plans/evidence/D-075/check-extractor.py`로 실제 원본의 임시 복사본에서 재현한다.
- 첫 시도는 API 오용으로 compileTestJava에서 실패했다. 성능 표본은 없었다. 실제 jar javap로 MeterRegistry.close/AbstractConfig.values를 확인한 뒤 수정했고 두 번째 실행이 통과했다. 실패 로그도 보존한다.

## 원본·재현·한계

- [probe.xml.gz](probe.xml.gz), [성공 Gradle 로그](probe-gradle.log.gz), [첫 compile 실패](compile-failed.log.gz)는 원본 byte를 바꾸지 않고 gzip으로 저장했다. [raw-sha256.json](raw-sha256.json)에 압축 전/후 해시와 크기가 있다.
- XML53marker, logs/stdout/stderr를 확인했으며 비밀·credential은 없었다. XML에는 local hostname, 로그에는 임시 worktree 경로와 일반 실행 metadata가 있다. 원본과 압축 해제 byte 일치를 확인했다.
- [resource-snapshot.txt](resource-snapshot.txt): Docker VM8CPU/약7.65GiB, fixture의 개별 CPU/memory hard limit은0(별도 제한 없음)이다. 원래 momens3container가 같은 VM에 남아 있었으며 건드리지 않았다. snapshot은 전 구간 자원/CPU 계수가 아니다. 실제 GKE 자원/네트워크/복제/경합을 대체하지 않는다.
- focused run은1test/errors0/failures0, XML testcase102.438초, Gradle2분16초였다. 종료 뒤 fixture3개와 Ryuk가 사라지고 원래3container만 남았음을 확인한 뒤 heavy slot을 반환했다.

원본만으로 재집계할 수 있다:

```sh
gzip -dc docs/plans/evidence/D-075/probe.xml.gz > /private/tmp/d075-recheck.xml
python3 -B docs/plans/evidence/D-075/extract-results.py /private/tmp/d075-recheck.xml --out /private/tmp/d075-recheck
```

전체 로컬 Gradle suite, GKE·유료 실행·외부 PG/Slack·운영 변경은 수행하지 않았다. 전체 PR CI는 별도 확인하며 D-075 전체 정책 선택과 D-078의 실제 jitter/DLQ·scheduler 부하 후속은 열린 상태다.
