# D-107 deploy topics audit

## 2026-10-25 03:40 — 계획 리뷰
- 상태: 해당 없음(등급 M)
- 코드 검증(§2.1): 표 16행. kafka 선행 적용 방법은 라벨 셀렉터·base 단독 적용·리소스 분리와 비교해 렌더 선택으로 정했다(§2.2).
  근거로 kubectl v0.35.3 `apply.go`·cli-runtime `builder.go`(라벨 필터), Kafka 3.8.1 `BrokerServer.scala`·`SocketServer.scala`
  (unfence 뒤 리스너 소켓을 연다), spring-kafka 3.3.14 `KafkaAdmin.java`(기존 토픽은 다른 config 키만 SET) 소스를 직접 읽었다.
- 범위 변화: 없음. 측정 overlay 를 덮기 위해 `loadtest/README.md` 는 배포 명령 한 곳만 바꾼다(레인 A 경계).

## 2026-10-25 04:00 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 게이트 판정: `hpx_codex_allowed` 가 `blocked` / `file` / 위 사유 / `.cache/codex-off`.
- 검증 V1~V6: `python3 -B scripts/tests/test_deploy_overlay.py` 13 tests OK(약 29초). 빈 클러스터(gke, gke-d002bc)는
  호출 순서가 kustomize, apply -f, rollout, exec(생성), exec(describe), apply -k 이고 가짜 브로커가 emit 30개·선언 파티션과 같다.
  재배포(30개와 `__consumer_offsets` 50)는 상태가 그대로다. 실패 주입 8종(생성 실패, 1파티션·3파티션 불일치, describe 실패·빈
  출력·형식 불명, 롤아웃 실패, 선행 apply 실패, 정본 실패 3종, kafka 없는·렌더 불가 overlay, preflight 차단)은 각각 exit 1 또는 2
  이고 apply -k 가 0회다. 앱 overlay 8개 모두 먼저 적용한 객체가 Namespace·Service·PVC·Deployment 넷이고 pyyaml 로 읽은 같은
  렌더의 객체와 같다. gke 의 PVC 는 `standard-rwo` 다.
- 검증 V7 변이(래퍼를 바꾼 뒤 원복, `git diff` 없음 확인): 대조 제거 4 실패, apply -k 를 앞으로 24 실패, `--if-not-exists` 제거
  3 실패, 롤아웃 실패 무시 9 실패, 정본 실패를 exit 1 로 1 실패, Namespace 를 선행 적용에서 뺌 10 실패. 각각 의도한 테스트에서 잡혔다.
- lint: lint job "Run CI policy lints" 명령 50개를 로컬에서 그대로 실행해 전부 exit 0(새 테스트 포함). `bash -n
  scripts/deploy-overlay.sh`, `ci-lint-wiring-lint.py`, docs-preservation-ci(base `6905952`)·자기검증 2종·`plans-archive.sh
  --self-test` 통과. 같은 job 의 DLQ contract lab(Docker) 단계는 로컬에서 돌리지 않았다.
- `./gradlew test` 는 돌리지 않았다. Gradle 소스 세트에 들어가는 변경이 없다(스크립트·워크플로·문서). 모듈 전체는 PR CI 로 확인한다.
- 실제 클러스터(kind·minikube·GKE)는 쓰지 않았다(사용자 지시 2026-10-21). 가짜 kubectl 은 `kubectl kustomize` 만 실제 kubectl 로
  오프라인 렌더하고(`KUBECONFIG=/dev/null`), exec 는 래퍼의 실제 생성 루프를 로컬 sh 로 돌린다.
- 계획 대비: P4 의 `loadtest/README.md` 변경이 "이유 한 문장" 에서 "이유와 preflight 를 건너뛰는 사유" 로 늘었다(계획서 먼저 정정).

## 2026-10-25 04:06 — /ship
- PR: https://github.com/Kimgyuilli/PeekCart/pull/241 (base main `2ffa8d6`)
- 커밋: `675079c` 계획서, `fdc93bd` 래퍼·fixture·테스트·CI 배선, `80c49a3` 배포 문서, `faaccdc` 검증 기록, `69e5e31` origin/main 병합,
  이 /ship 기록 1건.
- 병합: 작업 중 main 이 #240(`2ffa8d6`)으로 나아갔다. 겹치는 파일은 없었고, base `2ffa8d6` 의 문서 보존 검사가 #240 이 더한 보존
  manifest 부재로 실패해 rebase·force push 없이 origin/main 을 병합했다(`69e5e31`). 병합 뒤 새 테스트 13건을 다시 돌려 통과했다.
- precheck: `hpx_ship_preflight` ok, consistency ok(warnings 0), `hpx_review_health` ok, writing-lint(커밋 4건, 병합 1건 제외·본문·제목)
  통과, docs-preservation-ci(base `2ffa8d6`) 통과, `plans-index.sh --check`·`harness-context.sh --check` 통과.
- 갱신하지 않은 것: TASKS D-107 행과 PHASE5 이력은 root 가 마지막 기록 PR 에서 한다(병렬 레인 규칙).
