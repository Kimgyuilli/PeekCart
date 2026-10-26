# 문서 맵

PeekCart 저장소 문서의 위치와 역할이다. 문서를 새로 두거나 옮기면 이 맵을 같이 고친다.

## 레이어

| 레이어 | 위치 | 담는 것 |
|---|---|---|
| Layer 1 | `docs/01~07` | 현재 상태(What). 코드와 어긋나면 What 만 고친다 |
| Layer 2 | [adr/](adr/README.md) | 결정 근거(Why). 바꾸지 않고, 사실 오류만 Update Log 로 정정한다 |
| Layer 3 | `progress/` | 작업 이력(When). 단계별 작업 이력과 완료 행 |

새 결정은 ADR 을 먼저 쓰고 Layer 1 에서 `(see ADR-NNNN)` 으로 가리킨다.

## Layer 1 — 현재 상태

| 문서 | 내용 | 참조 시점 |
|---|---|---|
| [01-project-overview.md](01-project-overview.md) | 프로젝트 개요, 기술 스택 | 기술 선택 근거 확인 |
| [02-architecture.md](02-architecture.md) | 아키텍처, 패키지 구조 | 파일 위치·레이어 결정 |
| [03-requirements.md](03-requirements.md) | 기능 요구사항, API 명세 | 기능 구현 범위 확인 |
| [04-design-deep-dive.md](04-design-deep-dive.md) | 설계 결정, 트레이드오프 | 구현 방식 결정 |
| [05-data-design.md](05-data-design.md) | ERD, 인덱스 전략 | DB 스키마·Flyway 작성 |
| [06-testing-strategy.md](06-testing-strategy.md) | 테스트 전략 | 테스트 코드 작성 |
| [07-roadmap-portfolio.md](07-roadmap-portfolio.md) | 로드맵, 포트폴리오 | Phase 범위 확인 |

## 작업 추적

| 위치 | 내용 |
|---|---|
| [TASKS.md](TASKS.md) | 열린 Task 와 부채 행 |
| [progress/TASKS-done.md](progress/TASKS-done.md) | 완료한 D- 행 |
| `progress/PHASE{N}.md` | 단계별 작업 이력. 지금 단계는 [PHASE5.md](progress/PHASE5.md) |
| `progress/TASKS-archive-*.md` | Phase 1~4 의 task 이력(보호 이력) |
| [progress/archive/](progress/archive/README.md) | 이력 문서(Phase 1·2 설계 아카이브, Layer 1 의 Phase 1~4 서술, 옛 loadtest 절차, Phase 4 로드맵, 2026-04 리뷰 보고서·도구 평가, 상태 파일 하네스 fixture) |
| [review/](review/README.md) | 옛 리뷰 보고서 자리. 대응표만 있다 |

## 계획

| 위치 | 내용 |
|---|---|
| `plans/<ID>.md` · `plans/<ID>.audit.md` | 진행 중 계획과 그 리뷰·검증 기록 |
| [plans/done/](plans/done/INDEX.md) | 완료 계획(보호 이력). `INDEX.md` 는 `scripts/plans-index.sh` 가 만든다 |
| [plans/evidence/](plans/evidence/README.md) | 옛 증적 자리. 대응표와 D-104 안내 파일만 있다 |

## 증적

| 위치 | 내용 |
|---|---|
| [evidence/](evidence/README.md) | 계획·검증 원자료와 README(계획 ID 별 폴더) |
| `progress/evidence/` | 원자료 없이 혼자 읽히는 산문 증적 |
| `loadtest/reports/<날짜>/` | 측정 세션 원자료(저장소 루트 기준) |

규칙은 [증적 규약](conventions/evidence.md)에 있다.

## 규약과 운영

| 위치 | 내용 |
|---|---|
| [conventions/writing.md](conventions/writing.md) | 커밋·PR 문체 |
| [conventions/docs-preservation.md](conventions/docs-preservation.md) | 문서 이동과 완료 이력 보존 |
| [conventions/evidence.md](conventions/evidence.md) | 증적 위치와 README |
| [conventions/plan-blindspots.md](conventions/plan-blindspots.md) | 계획 블라인드스팟 체크리스트 |
| [conventions/codex-review.md](conventions/codex-review.md) | Codex 리뷰 공통 규칙 |
| [conventions/harness-rationale.md](conventions/harness-rationale.md) | 하네스 커맨드 근거 |
| [conventions/test-comments.md](conventions/test-comments.md) | 테스트 주석의 의미와 출처 |
| `runbooks/` | 운영 runbook 7개(DLQ 원장, outbox FAILED 재개, Redis 캐시 장애, Slack webhook 교체, 사용자 서명키 회전, 승인 복구, GHCR 부분 publish) |
| `preservation/` | 문서 이동·정정의 보존 manifest. 기존 파일은 바꾸거나 지우지 않는다 |

## 하네스 앵커

하네스와 CI 가 경로를 문자열로 읽는 곳이다. 옮기지 않는다(D-116 결정 0-2).

- `docs/TASKS.md`, `docs/progress/TASKS-done.md`, `docs/progress/PHASE{N}.md`, `docs/progress/TASKS-archive-*.md`
- `docs/plans/` 루트, `docs/plans/done/`, `docs/adr/`
- `docs/preservation/`

## 로컬 전용

`docs/learning/` 은 gitignore 된 블로그 연재 초안과 학습 자료다. 추적 문서는 이 경로를 근거로 인용하지 않는다(D-116 결정 3-6).
