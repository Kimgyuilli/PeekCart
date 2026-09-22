# audit — D-029 base mysql CPU 상한 승격 판정

## 2026-09-22 — 계획 리뷰
- 상태: **의도적 생략**(file: 사용자 지시 2026-09-22 — Codex 리뷰를 호출하지 않는다)
- 게이트: `hpx_codex_allowed` → `blocked` / source `file` / origin `.cache/codex-off`
- 등급 L 이라 §5 대상이었으나 게이트 차단. **미결 아님**(/plan §8).
- 코드 검증(§2)은 그대로 수행 — 전제 11건(F1~F11) + B1 역의존 스윕 4행.
- 검증이 뒤집은 전제: **TASKS.md 트레이드오프 ①(minikube 2 vCPU 수용성)이 거의 해소됐다.**
  `limits` 는 스케줄링에 쓰이지 않고 base requests 합 2100m < 4000m 이며, 현재 limits 합
  7250m 로 이미 1.8배 오버서브스크립션이다. 실제 쟁점은 ②(`requests` 250m vs 실측 1,259m).
  초안 전에 이걸 몰랐으면 ADR Alternatives 순서를 잘못 썼을 것이다.
