# D-064 merchant order ID 감사

## 2026-10-21 계획

- 상태: 별도 Codex 리뷰 미호출.
- 기준 main `be535c5`, tree `/private/tmp/peakcart-worker-c-merchant`, branch `debt/d064-merchant-order-id`.
- 사용자의 “권장하는 방향으로 진행” 답변에 따라 신규 생성·저장·응답·동일 confirm 계약을 구현한다. webhook/409 PR #193과 분리하고 ADR-0042를 사용한다.
- Payment 생성·DTO·native approval fence·dispatch·query·recovery 및 migration을 직접 확인했다. 기존 NULL 식별자를 재작성하지 않는 방식과 실제 PG 없이 검증할 범위를 주 작업자에게 보고했다.
- 구 바이너리와 신규 checkout의 혼합 배포는 additive schema만으로 안전하지 않다는 제약을 발견했다. 접수 중지와 writer/checkout 전환 순서를 ADR에 명시하며 실제 배포는 수행하지 않는다.
- 아직 구현·테스트하지 않았다. heavy slot을 예약하지 않았다.
