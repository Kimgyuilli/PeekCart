# D-116-layer1 audit

## 2026-10-27 03:10 — 계획 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 등급 L 근거: CI 게이트인 보존 검사기를 고치고, 하네스가 읽는 ADR 색인과 Layer 1 전체가 바뀐다. 되돌리려면 ADR 이 가리키는 앵커와 색인 파서를 함께 봐야 한다.
- 사용자 사전 승인(2026-10-26): CI 통과·리뷰 수렴 뒤 직접 머지, 새 판단은 보수적 기본값으로 진행하고 기록. 2026-10-27 에 권장안(검사기 보정)과 남은 단계의 순서대로 진행을 다시 지시받았다.
- 계획 전 처리: `/sync` 아카이브가 `Unsupported multiline backtick span` 으로 실패했다. 원인은 P4a 계획서 P2 줄의 코드 스팬 안 이스케이프 backtick 이고, 검사기가 빈 줄까지를 한 문단으로 보아 다음 목록 항목의 backtick 과 짝지은 오탐이다. 규약이 원문 수정을 막으므로 검사기를 고쳤다(P1).

## 2026-10-27 04:20 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 자체 검토: 바뀐 문서를 다시 읽고 서술마다 계획 §2.1 의 코드 근거와 대조했다. 그 과정에서 03 토큰 grace 문장의 대상, 04 승인 복구 결과 토픽 설명(해제 확인 뒤 COMPLETED), 04 SYS-005 누락, ADR 색인 커밋 메시지의 건수(26 에서 20)를 바로잡았다.
- 검증(계획 §4, 대조군은 괄호):
  1. 보존: `--base c65205d6` PASS. 셀프테스트 64건 PASS(보정 전에는 새 양성 케이스가 실패).
  2. 낡은 문구: Layer 1 과 루트 README 8개 파일에서 0건(07 사본에 자리표시자 문장을 되살리면 2건 검출).
  3. ERD: 도메인 테이블과 공통 메시징 테이블 5개가 네 서비스의 마이그레이션 최종 스키마와 같다(`refresh_tokens.grace_until`, `dead_letter_records.last_replay_by` 를 각각 지우면 검출).
  4. API·토픽: 엔드포인트 38개(컨트롤러 30, DLQ 관리자 8)가 03 과, 업무 토픽 15개의 producer·consumer 가 04 §8-1 과 같다(03 의 notifications 행, 04 의 승인 복구 결과 행을 지우면 검출).
  5. ADR 색인: 44개 제목이 H1 과, 열이 인용 계산과 같다(제목 20건, 열 29행 변경). `harness-context.sh --check --base c65205d6` ok, 다이제스트의 ADR 줄 그대로. `consistency-hints.sh` 종료 0(0007 제목을 바꾸면 검출).
  6. loadtest: 옛 README 320줄과 아카이브 본문이 순서까지 같다(줄 하나를 지우면 검출).
  7. 링크: 바꾼 문서와 ADR-0010·0012 의 상대 링크 115개가 대상·앵커까지 해석된다(없는 파일과 앵커는 검출).
  8. CI lint 잡 6단계를 ci.yml 원문대로 로컬 실행해 PASS. 첫 실행은 DLQ 계약 단계가 지난 세션이 남긴 `.cache/dlq-contract/evidence` 때문에 시작하지 못해, 그 디렉터리를 지우지 않고 옆으로 옮긴 뒤 다시 돌렸다(CI 는 새 작업 공간이라 해당 없음). `plans-index.sh --check` 최신, 앵커 7곳과 기존 manifest 의 이동·삭제 0건, writing-lint 15건 통과.
- `./gradlew test --continue`: BUILD SUCCESSFUL(Java 입력 무변경, 54개 태스크 up-to-date).
- 계획과 달라진 것: 06 의 saga E2E 수(5종에서 6종)와 04 §10-6 의 gateway deny 조회 fail-closed 는 코드 확인 중 찾은 사실 오류라 같은 절에서 함께 고쳤다. 05 인덱스 표에는 refresh_tokens 외에 lease·정리 인덱스 3행을 더했다. ShedLock 도입 근거는 Layer 1 이력 아카이브가 "이후 수정하지 않는다" 라 04 안에 과거 시점 주석으로 남겼다.
- 대조 스크립트(ERD·API·토픽·ADR 색인·문구·링크)는 세션 scratchpad 에만 두었다(계획 §6). 재발 방지 자동화 여부는 PR 의 확인 요청으로 남긴다.

## 2026-10-27 04:40 — /ship
- PR: https://github.com/Kimgyuilli/PeekCart/pull/256 (base main `c65205d`)
- 커밋 16개(chore 1 · docs 14 · adr 1). 분류 검사 통과, writing-lint(커밋 16건·본문·제목) 통과. 보존 검사기 수정이 있어 CI 는 전체 매트릭스
- precheck: consistency ok, `hpx_review_health` ok, 보존 검사 `--base c65205d6` PASS
- 갱신: TASKS D-116 행에 PR 링크(진행 중 유지), PHASE5 엔트리. 상위 계획의 P4b 칸과 P4 상자는 머지 뒤 다음 단계가 채운다
