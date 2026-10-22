# D-078 session prep audit

상태: 의도적 생략 (계획 리뷰·diff 리뷰 미실시, 별도 Codex 리뷰 미호출)

## 구현

- 브랜치 `docs/d078-readme-measurement`, 기준 `5567108`. README 개편 측정 수요를 D-078 에 합치고 과금 전 공백을 점검했다.
- 사용자가 Phase 4 측정 데이터 재검토를 요청했다. `d002bc` ①·경합 run 이 1,000 VU 재측정의 README 목적 질문에 답해 그 항목을 빼기로 사용자가 결정했다. ADR-0026 개선폭은 `d026` 세션의 쌍 비율(0.60 → 1.01)로 이미 측정돼 있었다.
- ⑤·⑥ 의 before 값은 Phase 4 증적에 있다(충돌 7·DLQ 7, 풀 1 에서 발행 185초 정지). 세션에서는 after 만 잰다.

## 검증 (2026-10-22, 로컬)

- `ProductKafkaRetryMetricsTest` 3, `StockRetryLoadIntegrationTest` 4, `StockConflictRetryPolicyTest` 3, `ProductKafkaConfigTopicConfigContractTest` 6, `StockSchedulerWiringIntegrationTest` 3. 실패 0.
- 렌더 diff 한 줄(mysql `limits.cpu` 500m → 2000m). namespace lint, lint-health selftest, integration-test-container lint, observability SSOT·PromQL lint 통과.
- product 전체 suite 와 전체 CI 는 PR 에서 확인한다.

## /ship

- PR https://github.com/Kimgyuilli/PeekCart/pull/225 (feat(product): 과금 세션 전 재시도·executor 계측 준비). consistency precheck 경고 0, 리뷰 처분 점검 ok, 커밋·본문·제목 문체 lint 통과.
- 갱신: TASKS D-078 상태 cell 에 PR 링크(부분 작업, 완료 이동 없음), PHASE5 작업 이력. 계획 이동 없음.
