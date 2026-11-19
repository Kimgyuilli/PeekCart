# D-108-probe-policy audit

## 2026-11-20 — 계획 리뷰
- 상태: 해당 없음(등급 M)

## 2026-11-20 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 검증: Java 변경 없음, `./gradlew test` 는 돌리지 않았다(k8s 매니페스트만 변경) · scripts/*-lint.sh 24개 통과
- 렌더 대조(scratchpad 스크립트, main `5d66f566` 렌더 기준): `gke`·`minikube`·`gke-d078-contention` 에서 바뀐 필드는 대상 서비스의
  liveness/readiness `timeoutSeconds`·`failureThreshold` 뿐이고, 측정 overlay 의 order-service 는 main 과 같다. readiness 판정(30초) < liveness(120초)
- 역검증: (a) payment base 의 liveness 두 값을 되돌리면 3개 overlay 에서 payment 지목 실패 (b) 측정 patch 에서 readiness 를 빼면
  측정 order-service 불일치로 실패 (c) payment readiness failureThreshold 12 면 기대값·판정 순서 검사가 실패. 각각 되돌린 뒤 PASS
- 계획 정정: 측정 overlay 렌더 전체 동일 → order-service 만 동일(계획서 §정정 이력)

## 2026-11-20 — /ship
- PR: https://github.com/Kimgyuilli/PeekCart/pull/276
- precheck: consistency ok · preflight ok · 문체 lint 통과(커밋 3건, 본문, 제목)
- 갱신: TASKS D-108 행을 TASKS-done 으로 이동(✅) · PHASE6 엔트리 · 이 블록
