# D-065 실제 collector adapter 검증 증적

계획: [D-065-collector-adapter](../../plans/done/D-065-collector-adapter.md). 2026-10-21 병렬 부채 캠페인에서 "Actual replay collector adapters" 워크플로 결과를 내려받아 둔 사본과 로컬 대조 로그다. 2026-10-26 D-116 에서 이 README 를 더했다.

| 파일 | 내용 |
|---|---|
| `first-actions-39591368823.tar.gz` · `-manifest.json` | 첫 run(head `8667537`). 완료되지 않았고 9개 사례가 통과, `no-committed-offset`·`restored` 는 실행되지 않았다 |
| `diagnostic-actions-39592563904.tar.gz` · `-manifest.json` | 진단 run(head `2efb3be`). `no-commit-member-assignment` 단계에서 멈췄다 |
| `actions-39593585908.tar.gz` · `-manifest.json` | 최종 run(head `5e74fcd`). 완료 |
| `guard-negative-controls.log.gz` · `-manifest.json` | replay lint 반례 3개(다른 경로의 같은 SQL 거부, 같은 경로의 바뀐 UPDATE 거부, 원래 설정 허용). Docker 없이 실행 |
| `pr212-stdin-original-negative.log.gz` · `pr212-stdin-fixed-positive.log.gz` · `stdin-pair-manifest.json` | Docker 와 비슷한 stdin 클라이언트로 돈 Bash 루프 대조. 원래 구현은 order 대상만 관측해 실패, 고친 구현은 6개 메서드 통과 |

manifest 마다 압축본의 SHA256 이 있다. 원본 run 의 artifact 는 2026-10-28 에 만료된다.
