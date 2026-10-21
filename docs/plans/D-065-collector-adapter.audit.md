# D-065 actual collector adapter 감사

## 2026-10-21 — 계획 조사

- root가 PR210/211 Actions 대기 중 다음 무료 actualDB/Kafka collector 검증의 계획 조사를 허용했다. 자기 `/private/tmp/peakcart-worker-c-collector-adapter`, branch `debt/d065-collector-adapter`, base3bb01bb에서 plan만 작성한다.
- PR189 exact8cfdcb3 shell의 SQL/query/describe 인자 및 합성 adapter와 기존 e2e Compose 두 infra/init를 대조했다. 새 framework보다 기존 infra의 선택 기동과 좁은 소유 label/9092 overlay를 선택했다. 기존 source/신규파일은 아직 구현·실행하지 않았다.
- 별도 Codex 리뷰 미호출. actual case/CLI 형식과 실행 예산은 아직 미검증이며 P2부터P6를 완료로 표시하지 않는다. 운영 리소스/무료 외 비용/새 agent는 사용하지 않는다.
