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

## 2026-10-21 — PR214 첫 실행 실패와 fixture 경로 수정

- draft PR은 https://github.com/Kimgyuilli/PeekCart/pull/214 이며 첫 head는 `2d6f7afe1f1606a176abdd6d71de6b6b8e460af9`다. 전용 run39597261792는 4개 앱 이미지 build를 성공했고 경량15 및 PG11+32 통과 후, 존재하지 않는 `scripts/e2e/pg-stub/Dockerfile`을 build하려다 실패했다. 실제4앱 체인은 전혀 실행하지 않았다. owner manifest/fixture artifact가 없어서 업로드도 no-files 실패했으며 이를 cleanup 실행 근거나 실제 체인 실패로 해석하지 않는다. 원 failed-job log를 gzip/hash와 함께 보존했다.
- 실제 기존 Compose는 `python:3.12-alpine`에 PG source를 read-only mount한다. 신규 Dockerfile을 만들지 않고 해당 service를 `extends`로 재사용하고 잘못 추가한 workflow build를 제거했다. 동일 대조에서 TossClient는 `/payments`를 base에 붙이므로 `/v1`이 필요한데 overlay에 누락했음을 source로 확인하고 기존 E2E의 정확한 base를 사용했다. 이 두 번째 경로 결함은 첫 실행에서 아직 도달하지 않은 정적 발견이다.
- 새 경량 계약은 기존 PG service/command/mount 및 client base와 대조한다. 이전 구성에서 missing extends ERROR와 base mismatch FAIL을 확인한 뒤 수정 구성4검사 PASS했다. 원 반례 로그도 보존했다. runtime 서비스 종류/정책/대기 한도/성공 조건은 늘리거나 약화하지 않았다. 실제 수정 구성 Actions와 독립 검토는 미완이다.
