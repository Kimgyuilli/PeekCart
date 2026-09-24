# task-d032-integration-test-container-singleton — audit

## 2026-09-22 — 계획 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 등급: L (공유 모듈 `common/src/testFixtures` 변경 · 테스트 격리 계약 변경 ·
  26개 클래스 이동으로 되돌림이 국소가 아님)
- 코드 검증(§2): 수행. 표적 크기 · 선언 수렴 · 공유 상태 위험 3축 · B3 인바운드 스윕
- PLAN-BLINDSPOTS: B1(역의존 스윕) · B3(공유 테스트 인프라 소유처) · B4(구체 메커니즘) 수행
  - B1: AbstractIntegrationTest 4모듈 52곳, IntegrationTestConfig 4모듈 49곳
  - B3: 둘 다 2개 모듈 초과 → 싱글톤 설정도 `:common` testFixtures 단일 소유로 결정
  - B4: 기존 IntegrationTestConfig 에 얹지 않고 SharedContainers 신설.
    전자에 얹으면 49곳이 동시에 전환돼 "order 먼저 검증" 단계 구분이 무너진다
- 미확인으로 남긴 것: cleanDatabase() 미호출 8개 중 5개의 본문. P1 으로 승격해
  계획서 §2-4 표를 채우게 했다. 확인 없이 안전 판정하지 않는다

## 2026-09-22 — 구현 (P1·P2·P3·P3b·P9·P9b)
- 전건 통과: 420 테스트 · 실패 0 (로컬 5분35초, 베이스라인 CI 975초)
- **계획서 §2-3 의 오판정 1건**: "Kafka 위험 없음" 이 틀렸다. 테스트 헬퍼가 만드는 UUID
  토픽만 확인했고 애플리케이션 고정 토픽(order.created 등)을 보지 않았다. §2-3b 에 기록
- **계획에 없던 근본 원인 발견**: 테스트에서 @EnableScheduling 이 기본 on 이라 배경 잡이
  자율적으로 DB 를 고쳤다. 각 테스트가 delay=1h 로 개별 무력화해 왔고, 그 프로퍼티가
  context 캐시 키라 파편화까지 유발했다. 비결정성과 파편화가 같은 원인이었다
  → P9(기본값 반전)로 처분. 사용자 승인 후 범위 편입
- 꼬리: opt-in 클래스의 타이머가 context 캐시 때문에 자기 테스트 종료 후에도 계속 돌아
  공유 DB 를 고쳤다 → @DirtiesContext(AFTER_CLASS)
- 진단 근거: 스케줄링 off 로 전환한 run4 에서 Kafka/DB 오염 실패가 전부 소멸하고
  타이머 검증 테스트 3건만 남았다. 오염원이 하나였다는 증거
- 미완: P4 · P5 · P6 · P7(V-4 셔플) · P8
- 스케줄링 정책은 ADR-0028 범위 밖 → 별도 ADR 필요

## 2026-09-23 — V-4 셔플 (결함 4건)

- 순차 실행에서 안 보이던 결함 4건. ADR-0028 §Consequences 가 적은 "이번 순서에서만 운이
  좋았다" 가 실증됐다
- 1·2: `deleteRecords` 가 low watermark 만 올려 end offset 절대값 단언이 앞 클래스분을 셈.
  drain 타임아웃도 같은 원인(한 회차 2424초) → 상대값으로 수정
- 3: 컨테이너를 `@Bean` 으로 노출하면 `TestcontainersLifecycleBeanPostProcessor` 가 context
  파괴 시 stop 하고 새 포트로 재기동 → 캐시된 다른 context 전멸. `ConnectionDetails` 만
  노출하도록 재작성. `@Bean(destroyMethod="")` 로는 안 막힌다(바이트코드 확인)
- 틀린 가설 2건 기록: `destroyMethod=""` 로 고쳤다는 판단, `@DynamicPropertySource` 경로
- 원인을 가른 것은 추론이 아니라 관측. `docker ps` 5초 폴링으로 mysql 재생성 전이를 포착
- 4: `@KafkaListener` 자율 writer. blocker 로 남기고 D-036 에 처분을 위임

## 2026-09-24 — D-036 ADR 작성 + P10 적용

- 계획 리뷰: 해당 없음(사용자 지시로 Codex 리뷰 미호출). diff 리뷰도 같은 사유
- **계획서보다 진단이 한 단계 깊었다.** 결함 4의 테스트에는 이미 자기 context 리스너를
  `stop()` 하는 워크어라운드가 있었는데(2026-09-11 `89955c1`) 그런데도 실패했다.
  `groupId` 가 상수라 캐시된 다른 context 의 consumer 가 같은 그룹으로 파티션을 넘겨받는다
  (실측: `order-svc-stock-result-group` 에 context 2개의 consumer 공존, `generation 4`)
- per-context 수단이 구조적으로 무효라는 것이 ADR-0029 가 필요했던 이유
- ADR-0029 결정: 스케줄러/리스너 대칭 게이트 · opt-in · `@DirtiesContext` · DLQ 개별 opt-in.
  대안 5건 기각 사유 기록
- P10: `KafkaListenerStartupConfig` 신설(타입으로 factory 를 잡아 게이트 누수 방지),
  opt-in 전수 조사 4클래스, stop 워크어라운드 제거, 죽은 opt-out 잔재 4클래스 제거
- V-4 재실행: 재현되던 4시드 + 신규 2시드 전부 420/0/0, 221~277초. **blocker 해소**
- V-5·V-6 실측 기록. V-6 은 25→15 로 기대 미만이고 사유는 `@DirtiesContext` 4클래스다.
  격리를 사서 캐시 적중을 내준 것으로 판정하고 그대로 기록
- **ADR-0028 본문 정정**: §Decision 의 `@ServiceConnection @Bean` 서술이 결함 3 때문에
  사실과 달라졌다. 결정이 아니라 사실 진술이므로 새 ADR 이 아니라 `fix(adr):` + Update Log

## 2026-09-24 — /ship

- PR: https://github.com/Kimgyuilli/PeakCart/pull/138
- preflight: ok (등급 L) · consistency precheck: ok (warnings 0) · review health: ok
- 문체 lint: 커밋 통과(경고 1: 제목 서술 종결) · 본문 통과 · 제목 통과
- 갱신: TASKS.md(D-032 PR 링크 · D-036 완료) · PHASE5.md(작업 이력 2건) · 이 파일
- 미충족으로 남긴 것: V-7(CI 실측), run5 이상치 판정. 둘 다 이 PR 의 CI run 에 걸려 있다
