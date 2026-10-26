# D-084 signed ADMIN 분산 복구 체인 증적

계획: [D-084-signed-recovery](../../plans/done/D-084-signed-recovery.md). 2026-10-21 병렬 부채 캠페인에서 "Signed approval recovery" 워크플로 결과를 내려받아 둔 사본과 로컬 로그다. 2026-10-26 D-116 에서 이 README 를 더했다.

| 파일 | 내용 |
|---|---|
| `first-runtime-manifest.json` · `first-runtime-failure.log.gz` | 첫 run 39597261792(head `2d6f7af`). 실제 애플리케이션 체인이 실행되지 않은 실패 기록 |
| `original-runtime-negative.log.gz` | 수정 전 구현의 음성 대조 로그 |
| `actions-39605979637.tar.gz` · `-manifest.json` | 최종 run(head `81fc518`, checkout `5d54ab4`). 압축본 64,573 바이트와 SHA256 |

원본 run 의 artifact 는 2026-10-28 과 2027-01-19 에 만료된다.
