# PeekCart — 고가용성 이커머스 플랫폼 

> **대용량 트래픽 환경을 고려한 이커머스 설계 및 구현**
> Java 17 · Spring Boot 3.x · Kafka · Redis · Toss Payments · Kubernetes
> 현재 구조: 도메인 서비스 5개와 Spring Cloud Gateway, 서비스별 MySQL 스키마, Kafka 이벤트 사가 (2026-10-27 기준)

---

## 클론 후 1회 설정

```bash
git config core.hooksPath .githooks
```

훅 두 개가 커밋 시점에 규약을 강제합니다. 설정하지 않으면 검사가 조용히 빠집니다.

| 훅 | 검사 | 탈출구 |
|---|---|---|
| `pre-commit` | "한 커밋 = 한 분류" (`/ship` §3) | `HPX_ALLOW_MIXED=1 git commit ...` |
| `commit-msg` | 메시지 문체 (`docs/conventions/writing.md`) | `HPX_ALLOW_STYLE=1 git commit ...` |

탈출구 변수를 나눈 이유는 분류와 문체가 다른 축이기 때문입니다. 하나로 묶으면 혼합
커밋을 의도할 때 문체 검사까지 덩달아 꺼집니다.

---

## 📖 프로젝트 문서 (Docs)

문서 전체의 위치와 역할은 [문서 맵](docs/README.md)에 있다. 아래 7개는 현재 상태를 적는 설계 문서다.

1.  [**프로젝트 개요 및 기술 스택**](docs/01-project-overview.md)
    - 프로젝트 개요, 목표, 상세 기술 스택 및 대안 비교, 운영 환경
2.  [**아키텍처 설계**](docs/02-architecture.md)
    - 코드 아키텍처 (4-Layered + DDD), 시스템 다이어그램, 모듈·배포 구조
3.  [**요구사항 명세**](docs/03-requirements.md)
    - 기능적/비기능적 요구사항, 핵심 API 명세
4.  [**상세 설계 및 결정 사항**](docs/04-design-deep-dive.md)
    - Kafka 이벤트 설계, 주요 설계 결정 (재고 동시성, Saga 등), 한계 및 트레이드오프
5.  [**데이터베이스 설계**](docs/05-data-design.md)
    - 서비스별 ERD, 공통 메시징 테이블, 인덱스 전략
6.  [**테스트 전략 및 시나리오**](docs/06-testing-strategy.md)
    - 레이어별 테스트 전략, E2E 계약 게이트, 성능 테스트 시나리오
7.  [**포트폴리오 어필 및 로드맵**](docs/07-roadmap-portfolio.md)
    - 핵심 스토리라인, 기술별 어필 포인트, 단계별 진행

| 찾는 것 | 위치 |
| --- | --- |
| 결정 근거 | [ADR 색인](docs/adr/README.md) |
| 열린 작업과 부채 | [docs/TASKS.md](docs/TASKS.md) |
| 단계별 작업 이력 | `docs/progress/PHASE1.md`~`PHASE6.md` |
| 운영 절차 | `docs/runbooks/` |
| 부하 측정 절차 | [loadtest/README.md](loadtest/README.md) |

---

## 🛠 프로젝트 요약

| 항목 | 내용 |
| --- | --- |
| 프로젝트명 | PeekCart |
| 유형 | 개인 포트폴리오 프로젝트 |
| 주제 | 대용량 트래픽 환경을 고려한 이커머스 플랫폼 설계 및 구현 |
| 개발 언어 | Java 17 |
| 프레임워크 | Spring Boot 3.x |
| 아키텍처 패턴 | 4-Layered Architecture + DDD |
| 레포 전략 | 모노레포 (Gradle 멀티모듈) |
| 서비스 구조 | 도메인 서비스 5개와 Spring Cloud Gateway (모놀리식에서 단계적으로 분리) |
| 인프라 | Kubernetes (로컬 minikube, 측정 GKE). 환경은 `docs/01-project-overview.md` §4 |
| 현재 단계 | Phase 6 — 수요 기반, 로드맵 없음 (`docs/07-roadmap-portfolio.md` §16) |
