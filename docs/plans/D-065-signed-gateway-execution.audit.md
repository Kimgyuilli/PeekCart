# D-065 signed gateway execution audit

## 2026-10-21 — 계획 준비

- 별도 Codex 리뷰 미호출. Root가 PR207 Actions 대기 중 계획·소스전제 준비를 승인했다.
- own tree/branch는 mainbdc에서 생성했다. PR202의 선행 plan은 보존하고 새 실행계획으로 Actions 우선/3JVM matrix/actual image archive identity를 구체화했다.
- 제품·workflow·Compose 구현과 실제 Gradle/Docker 실행은 아직 없다. PR207 최초실패대응 및 PR205 요청을 우선한다. 공유 문서/운영 상태 변경 없음.

## 2026-10-21 — 최소 구성 구현

- PR207 미사용 info 명령 제거와 새 Actions 시작 뒤 이 tree 구현을 이어갔다. 실제 main d506을 통합했으며 새 PR 전에 당시 actual main과 다시 대조한다.
- 생성 YAML3개와 polling loop를 Compose JSON/native wait로 줄였다. 기존 image identity record/load와 DB init 파일을 재사용하고 신규 runtime service는 검증 runner만 둔다.
- 전체 소스는 아직 미실행이다. 로컬은 hermetic safety/evidence controls만 실행하며 실제 이미지·HTTP·DB는 Actions에서 확인한다. 별도 Codex 리뷰 미호출. 제품코드·운영설정·replay.enabled 변경 없음.

- Draft 준비 시 actual main43a1745를 정상 통합했다. 실행 source는 그대로이고 보존gate/consistency/key ownership/gateway exposure/diff/writing 검사가 통과했다. hermetic 11 methods는 실제 체인 증거가 아니다. ship preflight는 P2–P6 미완으로 blocked이며 사용자·root가 승인한 Actions 검증용 draft 생성만 진행한다. 미완 항목을 체크해 우회하지 않는다.

- Draft PR210 첫 head4268af5/전용39587253206/일반39587253300 제출 후 source 대조에서 direct plaintext 음성이 잘못된 X-Internal-Auth까지 동반함을 발견했다. 이대로면 DUAL_ACCEPT도 거부하므로 SIGNED_ONLY 증거가 되지 않는다. direct 요청만 세 X-User-* 헤더로 좁히고 실제 baseline 함수의 요청 인자를 캡처하는 hermetic 대조를 추가했다. Gateway 헤더 위조 사례는 그대로 둔다. 첫 head의 실제 HTTP PASS 주장은 없으며 수정 head Actions를 새로 실행한다.

- 실제 최초4서비스실패 bundle44files(97,856bytes), 파일별/archive hash 및 완료되지 않은 경계를 repo에 보존했다. USER500은 독립 PR211로 분리했으며 승인된 exactd882fec 선행을 정상 merge했다. signed delta는 fixture/control/evidence만이며 제품 guard는 선행 소유다. 수정 head의 최초12 hermetic PASS, 실제 stack Actions는 새로 실행한다.
