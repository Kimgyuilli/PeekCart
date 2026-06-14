# task-adr0013-gateway-security — audit log

## 2026-06-14 — GP-2 (loop 1)
- 리뷰 항목: 8건 (P0:0, P1:5, P2:3)
- 사용자 선택: [2] 전체 반영
- 반영 내용:
  - P1#1 "리소스 서버 공개키 검증" ↔ "서비스 미재검증" 모순 → §1 D1·P4·D6 Gateway-only 검증으로 정리
  - P1#2 blacklist 검증 owner 누락 → P6 Gateway 검증 순서(서명/만료→blacklist→헤더 주입)·Redis SPOF·owner 분리·D3
  - P1#3 reuse detection이 현 삭제 방식 위에서 불성립 → P7 이력 모델(status/token_hash/family_id/grace_until/replaced_by) 삭제→상태전이·D4·완료조건
  - P1#4 RS256 키 안전조건 부족 → P4 kid 필수·alg allow-list·JWKS cache·키 overlap>access TTL·완료조건
  - P1#5 L-002 비교축 오류 → P5 3안(KMS 비대칭/Secret Manager PEM/Vault)·§7
  - P2#6 ADR-0009 surface 갱신 경로 → P8 6컬럼·영향파일 0009 추가·완료조건
  - P2#7 헤더 신뢰 모델 검증 약함 → P6/D3 외부헤더 제거·direct ingress 거부·누락 방침
  - P2#8 Rate Limit 정책 뼈대만 → P6 route-class별·fail-open/closed·429 owner
- P0 무시 사유: 없음
- raw: .cache/codex-reviews/plan-task-adr0013-gateway-security-*.json
- run_id: plan:20260614T063339Z:80dcf9d1-66ad-40be-aa1e-1e6ac73ba6e5:1
- tokens: 95,310

## 2026-06-14 — GP-2 (loop 2, 재리뷰)
- 리뷰 항목: 1건 (P0:0, P1:1, P2:0) — 신규
- 사용자 선택: 전체 반영
- 반영: P1#1 reuse family revoke ↔ Gateway access token 차단 미연결 → access token family_id/session_id 클레임 + reuse 감지 시 family deny를 Redis(blacklist source)에 기록 → Gateway ② 단계가 이미 발급된 access token 차단 (P6·P7·D4·완료조건). 대안 bounded TTL risk 명시
- run_id: plan:20260614T064259Z:80dcf9d1-66ad-40be-aa1e-1e6ac73ba6e5:2
- tokens: 110,822
- 종료: 2차까지 완료(사용자 요청), 수렴 8→1 → plan.done

## 2026-06-14 — GP-2 (loop 3, 최종)
- 리뷰 항목: 0건 — clean pass (P0/P1=0 자동 통과)
- 수렴: 8 → 1 → 0. 내부 정합·범위·id 규약 통과
- run_id: plan:20260614T064930Z:80dcf9d1-66ad-40be-aa1e-1e6ac73ba6e5:3
- tokens: 42,267
- 종료: plan.done. 추가 리뷰 실익 없음

## 2026-06-14 — GW-2 (work, loop 1)
- 브랜치: feat/adr0013-gateway-security
- 구현: P1~P14 (ADR-0013 작성 + Layer1 04/03/05/02 + ADR-0009 S9 + README). 코드 0건
- diff: 448줄 / 단일 리뷰. attempt 1 ok — P0/P1/P2=0/2/2 (tokens 70,647)
  - P1#1 blacklist/family deny Redis 조회 실패 정책 미정 → D3 ② fail-closed(401/503+alert)
  - P1#2 Layer1 Redis 역할 충돌 → 03 §7-2·05 family/session deny 포함 정정
  - P2#3 D2 채택 상태 흐림 → 결정(Secret Manager PEM·CSI 주입·회전)/후속(KMS 별도 개정) 분리
  - P2#4 S9 검증 User counter 누락 → owner별 검증 분리
- GW-2: 전체 반영 → work.done. 다음: /ship
