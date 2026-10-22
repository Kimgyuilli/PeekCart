# D-078 capture prep audit

상태: 의도적 생략 (계획 리뷰·diff 리뷰 미실시, 별도 Codex 리뷰 미호출)

## 구현

- 브랜치 `chore/d078-capture-prep`, 기준 `577afbd`.
- 패널 id 를 넣을 때 처음에는 JSON 을 다시 써서 기존 대시보드 3개의 포맷이 통째로 바뀌었다. 되돌리고 패널 깊이의 `"title"` 앞에 `"id"` 한 줄씩만 넣었다.
- 첫 리허설에서 export 가 전부 0 시계열이었다. 원인은 스크립트가 아니라 fixture 였다. Prometheus 3 은 Content-Type 이 `application/octet-stream` 인 정적 파일을 거부한다(`fallback_scrape_protocol` 없음). fixture 설정을 고쳐 다시 돌렸다.
- 세션 범위: 배포 래퍼의 fail-closed preflight 가 빈 namespace 첫 배포를 막는다는 점을 확인했다. 첫 배포는 사유를 남겨 건너뛰고, 스택이 뜬 뒤 preflight 를 다시 돌리는 것을 D-065 ② 의 검증으로 쓴다.
- Slack webhook 값과 Grafana 운영 Secret 생성은 사용자가 실행한다. Claude 는 값을 보지 않는다.

## 검증 (2026-10-23, 로컬)

- `test_d078_capture.py` 10건 OK.
- 리허설: annotation 4건 생성, export 에서 fixture 메트릭이 있는 패널(누적 실패·DLQ 2, backlog 2, executor 2, 실패율 1)만 시계열, 나머지 0. `d-solo` 단독 패널 캡처에서 절대 범위·범례·annotation 구간 2개 확인. 컨테이너 3개·네트워크 제거 확인.
