# D-084 signed recovery 실행 감사

## 2026-10-21 — 범위 조사

- root가 PR210 동결/PR212 Actions 대기 중 다음 무료 계획 조사를 허용했다. 자기 tree를 actualmain897에서 만들고 ADR0040/PR181 기존 완료 범위, recovery controller/access/service/consumer/Product CAS/증빙 verifier, signed210와 PG stub을 읽었다.
- 기존 구현/단위 테스트를 새 기능으로 다시 만들지 않는다. 누락은 실제 User/JWKS/Gateway부터 분산 outbox/Kafka/최종 ack까지 연결한 검증이다. 기존 pg-stub의 resolved fence 응답에는 balanceAmount가 없어 정확한 recovery verifier에 불충분함을 source로 확인했다. fixture 응답 보완과 Handler 회귀만 제안하며 제품 판정은 바꾸지 않는다.
- 실제 구현/Actions는 아직 없고 별도 Codex 리뷰 미호출. root 설계 검토 뒤 직접 작은 overlay/runner로 진행한다. PR212 새 실패와 PR210 병합 준비를 먼저 처리한다. 운영·실 PG·유료 리소스·추가 agent는 사용하지 않았다.

## 2026-10-21 — 최소 fixture 구현과 첫 Actions 준비

- root가 계획을 승인했다. 선행 PR210의 실제 main7894dcc를 자기 tree에 정상 통합했다. 기존 signed Lab의 키/수명/nonce cleanup/secret scan/이미지 출처와 HTTP/SQL helper를 그대로 재사용한다. 작은 Compose overlay, 직접 phase runner, 독립 evidence 검사, 별도 workflow만 추가했다. 기존 PR181 단위/제품 정책/Java/DDL은 변경하지 않았다.
- 네 앱과 기존 DB/Redis/Kafka/PG stub을 사용한다. 준비 seed와 실제 ADMIN request 이후 event 전이를 분리하며 24개 HTTP/상태 관측, 네 outbox/inbox event hop, 실제 Kafka envelope 재전달, Product pause 구간의 ack 전 미완료, stale version 거부를 검증하도록 구성했다. 기대 수치는 아직 실행 PASS가 아니다.
- 새 경량 계약3 + 기존 signed 계약12 PASS. PG stub 실제 HTTP self-test는 초기 sandbox loopback bind 제한으로 미실행됐고, 허용된 loopback 실행에서11+기존32 PASS했다. 임시 사본에서 새 balanceAmount0 한 줄만 제거한 원응답 반례는 추가 회귀1FAIL/exit1을 냈다. tracked source는 변이하지 않았다. UNKNOWN/READY에는 balanceAmount를 발명하지 않으며 fixture 확정 ABORTED에만0을 더한다.
- 사용자 지시에 따라 Docker/Gradle 로컬 실행은 하지 않는다. 실제 분산 체인/cleanup/8개 실제 artifact 변이/이미지 일치 및 일반 전체 CI는 첫 Actions에서 검증할 미완 항목이다. P2–P6는 실제 증거 전 체크하지 않는다. 자동 ship의 완료 체크를 허위로 통과시키지 않고 root가 승인한 draft Actions 흐름을 사용한다.
- 별도 Codex 리뷰 미호출. 독립 검토는 root가 별도 배정한다. 운영/실 PG/운영404 해제/추가 유료 자원은 사용하지 않았다.
- 첫 fixture source commit은 `a588a35`다. Python4파일 AST, 새 계약3/기존12, replay 진입점/network/internal-key/release-gate lint, actualmain789 기준 문서 보존, consistency0 warnings가 통과했다. ship preflight는 미완 P2–P6 때문에 blocked이며 root 승인 draft Actions 예외를 명시적으로 적용한다. 완료나 독립 리뷰 통과로 기록하지 않는다.
