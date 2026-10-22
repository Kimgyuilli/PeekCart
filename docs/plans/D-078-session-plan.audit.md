# D-078 session plan audit

상태: 의도적 생략 (계획 리뷰·diff 리뷰 미실시, 별도 Codex 리뷰 미호출)

## 구현

- 브랜치 `docs/d078-session-plan`, 기준 `4bed313`(#226 머지 후 main).
- Phase 4 증적(`pr3d-b2-gke-20260914-1320.md`, `d002bc-gke-20260917.md`, `d026-d002a-read-ceiling-20260920.md`)의 배포 편차를 절차 단계로 옮겼다: CSI upstream 설치, 노드 SA AR 권한, SSD 쿼터, 노드 3대, 키쌍 불일치.
- 키쌍은 새로 만들지 않는다. 로컬 dev 개인키 2개의 공개키 fingerprint 가 커밋된 ConfigMap 과 같음을 확인했다. 키 원문은 출력하지 않았다.
- 경합 조건(product replicas 3, outbox 폴링 500ms·500건)은 Phase 4 에서 파일 밖에 있었다. overlay 로 고정했다.

## 검증 (2026-10-23, 로컬)

- `gke-d078-contention` 렌더 diff: order env 2줄, product replicas 1 → 3. namespace lint, lint-health selftest 통과.
- `helm template` kube-prometheus-stack 91.4.0: grafana·kube-state-metrics·operator·prometheus·admission job 2개가 `peekcart.io/role=monitoring` 선택·toleration, node-exporter 는 무조건 toleration.
- outbox 폴링 값은 `@Value("${app.outbox.polling.batch-size:100}")`·`${app.outbox.polling.delay:5s}` 로 읽는다. Boot 의 `@Value` relaxed binding 으로 `APP_OUTBOX_POLLING_BATCHSIZE`·`APP_OUTBOX_POLLING_DELAY` 가 바인딩된다. 실제 Pod env 반영은 세션 S3 에서 확인한다.
