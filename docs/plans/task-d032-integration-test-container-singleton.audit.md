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
