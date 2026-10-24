# D-106 topic guard and throttle panel audit

## 2026-10-25 00:56 — 계획 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)

## 2026-10-25 01:47 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 결정: 스로틀 패널은 기존 description 이 ADR-0027 초 단위 축을 의도했다는 사실을 놓고 사용자가 periods 비율을 확정했다(계획 §2.2).
- 검증 V1~V4: `python3 -B scripts/tests/test_kafka_topic_state_guard.py` 23 tests OK. 가드 변이 4종(행 없음을 할당으로 처리,
  파티션 수 검사 제거, group 판정에 선언 파티션 사용, group 행 관대 파싱)이 각각 해당 테스트로 실패했다.
  GKE S4 원 출력의 판정은 payment 6개 토픽 × 4키(retention.bytes·segment.bytes·segment.ms·message.timestamp.before.max.ms)이고
  나머지 24개는 통과한다(손 대조와 같다). 소스 유도값은 가드 표와 같고 GKE 의 `order.cancelled`·`.dlq` 동적 값과도 같다.
  상수·yml·선언 삭제 변이 3종은 모두 실패한다.
- 검증 V5 (`apache/kafka:3.8.1`, E2E compose 의 kafka 단독): 선언대로 만든 30개는 토픽·config 위반 0(서비스가 없어 업무 group
  27개 부재만 위반). `payment.completed` 의 동적 config 를 지우면 그 토픽만 4키 위반, `payment.requested` 를 1파티션으로 다시
  만들면 `1 (선언 3)`. 실제 group 출력에서 전 할당 group 은 통과, 자동 생성(1파티션) 토픽을 구독한 채 3파티션으로 늘린
  group(D-107 ②)은 `1,2 미할당`, consumer 를 끝낸 group 은 `0,1,2 미할당`(CONSUMER-ID `-`). metadata 갱신(약 5분) 뒤 D-107
  group 은 3파티션을 받아 통과했다. "no active members" 문구는 stderr 로만 나온다.
- 검증 V6: id 16 식이 periods 메트릭 둘만 쓰고 unit `percentunit`, `d078-capture.py` 의 `find_panel` 이 id 16 을 찾는다.
  promtool 3.12.0 check·test rules 통과(컨테이너별 합 비율 0.25, `POD`·다른 namespace 제외). minikube(containerd 2.2.1) +
  차트 91.4.0(digest `sha256:0032315f…`) + minikube values: kubelet ServiceMonitor `/metrics/cadvisor` 에 drop 규칙이 있고,
  기존 식은 빈 결과·`container_cpu_cfs_throttled_seconds_total` 시계열 0, 새 식은 `burn` 0.989 / `idle` 0.0.
  첫 시도(docker 29.2.1 runtime)는 사용량 메트릭까지 pod 단위 시계열만 있어 판정할 수 없었다. GKE 세션 export p15
  (`container="mysql"`)에 데이터가 있으므로 GKE 에는 컨테이너 단위 시계열이 있다.
- 쿼리식 sweep: `container_cpu_cfs_throttled_seconds_total` 을 쓰는 expr 0건(`k8s/`·`scripts/`·`loadtest/scripts/`).
- lint job 명령 53개 로컬 실행: 52개 통과. `replay-entrypoint-lint` 는 gitignore 된 로컬 `docs/learning/drafts/` 파일로 실패했고,
  이 변경을 얹은 깨끗한 worktree 에서는 통과했다. docs-preservation-ci(base `origin/main`)·자기검증 2종·D-099 check-mutants 통과.
  같은 job 의 DLQ contract lab(Docker) 단계는 로컬에서 돌리지 않았다.
- `./gradlew test` 는 돌리지 않았다. Gradle 소스 세트에 들어가는 변경이 없다(스크립트·워크플로·대시보드·문서). 모듈 전체는 PR CI 로 확인한다.
