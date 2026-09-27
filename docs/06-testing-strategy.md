## 13. 테스트 전략

### 13-1. 레이어별 테스트

| 레이어 | 테스트 유형 | 도구 | 핵심 검증 항목 |
| --- | --- | --- | --- |
| Domain | 단위 테스트 | JUnit 5 | 주문 상태 전이 로직, 재고 차감/복구, 비즈니스 규칙 검증 |
| Application | 단위 테스트 | JUnit 5 + Mockito | UseCase 조율 로직, 트랜잭션 경계 내 도메인 호출 순서 |
| Infrastructure | 통합 테스트 | Testcontainers | DB Repository 쿼리, Redis 캐시/락, Kafka Producer/Consumer |
| Presentation | 슬라이스 테스트 | MockMvc + @WebMvcTest | 요청/응답 직렬화, 인증/인가 필터, Bean Validation |
| E2E (단일 서비스) | 통합 테스트 | @SpringBootTest + Testcontainers | 주문 → 결제 → 알림 전체 플로우 |
| E2E (cross-service saga) | 실제 스택 | `docker-compose.e2e.yml` + `scripts/saga-e2e-smoke.sh` | 4서비스를 실제 HTTP 진입점으로 구동해 saga 4종(결제 실패·예약 실패·환불 체인·DLQ intake) 관측 (구현 ④-d-2) |
| 음성 대조군 | 결함 주입 | `saga-e2e-smoke.sh --negative-control` | **검사가 결함을 실제로 잡는지** — poller 정지·서비스 정지·재고 충분·listener 부재·project 병렬·egress 격리 양음 대조 |
| 계약 게이트 | 증적 대조 | `scripts/saga-contract-matrix-lint.sh` | 매트릭스(`docs/plans/fixtures/saga-contract-matrix.tsv`) ↔ JUnit XML · E2E manifest 를 `evidence_key` 단위 exact equality 로 대조 |

### 13-1-a. cross-service saga 계약 게이트 (구현 ④-d-2)

**통과하는 검사는 그것이 무엇을 잡을 수 있는지 말해주지 않는다.** 그래서 세 층으로 나눈다.

1. **매트릭스가 정본이다** — "이 saga 의 어떤 계약이 무엇으로 증명되는가" 를 `saga-contract-matrix.tsv` 한 곳에 적는다. `expected` 는 canonical JSON object 로 고정한다(자유 문장 금지 — 대조가 문자열 비교이기 때문이다).
2. **required-ID 정본은 lint 안에 둔다** — 매트릭스가 기대 행의 **유일한** 입력이면 행을 지우는 것이 검사 대상만 줄여 조용히 통과한다. "무엇이 있어야 하는가" 는 매트릭스 **밖**에 있어야 게이트가 성립한다.
3. **증적 키는 `testcase@classname` + `[SAGA-xxx]`** — `testsuite@name` 은 클래스 `@DisplayName` 으로 덮이므로 키가 될 수 없다. 표시명 문구를 다듬어도 게이트가 깨지지 않는다.

**음성 대조군을 CI 에서 매번 돌린다.** 양성만 보면 "격리돼서 실패" 와 "도구가 없어서 실패" 를 구별하지 못한다 — 실제로 egress 음성 프로브가 앱 이미지에 `python3` 가 없어 통과하던 false-green 을 양성 대조가 잡아냈다. 판정은 종료코드를 특정한다.

### 13-1-b. 음성 대조군 목록 (see ADR-0031)

결함은 스택 바깥의 기존 수단으로만 주입한다(서비스에 테스트 제어 표면을 두지 않는다). 판정은 runner
(`saga_e2e.py negative-control <이름>`)가 **실패 지점**(`Timeout.stage`)까지 특정해서 한다. "일어나지
않음" 은 원인 관측 + 시스템 주기에서 유도한 창 + 주입 제거 후 복원 확인으로 증명한다.

| # | 주장하는 불변식 | 주입 수단 | 실패 지점 | 부재 창 | 복원 확인 |
|---|---|---|---|---|---|
| ① | 시작 이벤트는 실제 outbox poller 를 지나야 예약에 닿는다 | ShedLock 행 선점 (해제는 `lock_until` UPDATE, DELETE 금지) | `reservation` + outbox `PENDING` 유지 | 30초 (poller 5초 × 6) | 행 삭제 후 같은 주문 예약 |
| ② | 예약은 product-service 의 소비로만 성립한다 | product-service 정지 | `reservation` + outbox `PUBLISHED` | 30초 | 재기동 후 밀린 예약 도착 |
| ④ | readiness 는 업무 listener 부재를 group 검사로 잡는다 | ② 의 정지 상태 재사용 | `consumer-group` | 60초 (세션 타임아웃 45초 + 여유) | 재기동 후 readiness 통과 |
| ③ | 시나리오 B 의 단언은 아무 취소가 아니라 예약 실패 취소를 본다 | 재고 100 으로 주문 | 예약 성공 관측 후 취소 부재 | 45초 고정 (유도 근거 없음) | — |
| ⑥ | internal 전용 앱은 다른 네트워크의 호스트에 닿지 못한다 | canary 컨테이너, 양·음 쌍 | curl rc 7/28 (127 거부) | — | 양성 쌍이 대신한다 |
| ⑤ | compose project 둘이 동시에 뜬다(호스트 포트·container_name 고정 없음) | 두 번째 project 기동 | 기동 실패 | — | — |

⑤·⑥ 은 compose 토폴로지 자체가 대상이라 스택 밖 경계로 옮길 수 없다. 이 표는 D-042 가 서비스 단위
계약 테스트로 옮길 수 있는 항목을 판정하는 입력이다.

**PR 필수 체크 `gate` 는 최종 집계자다 (see ADR-0030).** `lint`·`test`·`guards`·
`images`(6개 health smoke)·`e2e`(시나리오와 음성 대조군)의 실패·skip 중 하나라도
있으면 실패한다. 이미지 빌드와 JVM 테스트는 병렬로 진행하고, main push 의 GHCR
`publish` 는 `gate` 성공 뒤에만 시작한다. e2e 와 publish 는 빌드 artifact 의 checksum 과
이미지 ID(config digest)를 확인한다. SHA 태그로 push 한 원격 manifest 의 config digest 를
같은 ID 와 대조한 뒤, 그 manifest digest 그대로 `latest` 를 승격한다.

**스케줄러는 "돈다는 사실" 을 따로 고정한다.** `@InjectMocks` 객체를 직접 호출하는 단위 테스트는 `@Scheduled` 를 지워도 통과한다. 실제 Spring scheduling 발화 후 DB 상태를 기다리는 통합 테스트를 별도로 두고, 운영 주기·lock 기본값은 properties 계약 테스트가 고정한다(한 테스트에 두 관심사를 넣으면 둘 중 하나는 반드시 거짓이 된다).

**자율 writer 는 테스트에서 기본 off 다 (see ADR-0029).** `@Scheduled` 스케줄러와 `@KafkaListener` 리스너는 아무도 요청하지 않았는데 도메인 상태를 고치는 주체라, 컨테이너를 공유하면(ADR-0028) 테스트가 단언하는 상태와 경주한다. `app.scheduling.enabled` · `app.kafka.listener.enabled` 로 게이트하고(프로덕션 기본값은 켜짐), 그 동작을 검증하는 테스트만 `@TestPropertySource` 인라인으로 켜며, 켠 테스트는 `@DirtiesContext(AFTER_CLASS)` 로 수명을 자기 클래스에 가둔다 — context 캐시 때문에 켜진 writer 가 자기 테스트가 끝난 뒤에도 계속 돈다. 리스너 게이트는 Boot 의 `spring.kafka.listener.auto-startup` 이 아니라 container factory 의 `autoStartup` 에 건다(서비스가 factory 를 직접 만들어 Boot 속성이 도달하지 않는다).

### 13-2. 커버리지 목표

| 대상 | 목표 | 비고 |
| --- | --- | --- |
| Domain 레이어 | 90%+ | 비즈니스 로직이 집중된 핵심 레이어 |
| Application 레이어 | 80%+ | UseCase 조율 로직 |
| 전체 프로젝트 | 70%+ | Presentation/Infrastructure 포함 |

### 13-3. 주요 테스트 시나리오

- 주문 상태 전이: 허용되지 않은 상태 변경 시 예외 발생 검증
- 재고 동시성: 동시 주문 시 오버셀링 방지 (멀티스레드 테스트)
- 결제 실패 보상: 결제 실패 시 주문 취소 + 재고 복구 플로우
- Kafka 멱등성: 동일 이벤트 중복 소비 시 비즈니스 로직 1회만 실행
- 결제 타임아웃: 15분 초과 주문 자동 취소 스케줄러 동작

---

## 14. 성능 테스트 시나리오

| 시나리오 | 도구 | 조건 | 검증 항목 |
| --- | --- | --- | --- |
| 상품 목록 대량 조회 | nGrinder | 500 VUser, 5분 | 캐싱 전/후 TPS, 응답시간 비교 |
| 동시 주문 폭주 | k6 | 1,000 VUser 동시 요청 | 재고 정합성, 오버셀링 방지 |
| 결제 연속 처리 | nGrinder | 300 VUser, 3분 | 결제 성공률, Kafka Lag |
| K8s HPA 스케일아웃 | nGrinder | 점진적 VUser 증가 | Pod 수 변화, TPS 회복 시간 |
| 전체 플로우 E2E | k6 | 100 VUser, 10분 | 전체 TPS, p95/p99 응답시간 |
