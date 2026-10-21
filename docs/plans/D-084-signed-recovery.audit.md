# D-084 signed recovery 실행 감사

## 2026-10-21 — 범위 조사

- root가 PR210 동결/PR212 Actions 대기 중 다음 무료 계획 조사를 허용했다. 자기 tree를 actualmain897에서 만들고 ADR0040/PR181 기존 완료 범위, recovery controller/access/service/consumer/Product CAS/증빙 verifier, signed210와 PG stub을 읽었다.
- 기존 구현/단위 테스트를 새 기능으로 다시 만들지 않는다. 누락은 실제 User/JWKS/Gateway부터 분산 outbox/Kafka/최종 ack까지 연결한 검증이다. 기존 pg-stub의 resolved fence 응답에는 balanceAmount가 없어 정확한 recovery verifier에 불충분함을 source로 확인했다. fixture 응답 보완과 Handler 회귀만 제안하며 제품 판정은 바꾸지 않는다.
- 실제 구현/Actions는 아직 없고 별도 Codex 리뷰 미호출. root 설계 검토 뒤 직접 작은 overlay/runner로 진행한다. PR212 새 실패와 PR210 병합 준비를 먼저 처리한다. 운영·실 PG·유료 리소스·추가 agent는 사용하지 않았다.
