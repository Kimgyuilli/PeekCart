## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

## Project: PeekCart

### 프로젝트 개요

- **프로젝트명**: PeekCart (레포명: PeakCart)
- **목적**: 대용량 트래픽 환경을 고려한 이커머스 플랫폼 — 포트폴리오
- **스택**: Java 17 · Spring Boot 3.x · MySQL 8.x · Redis 7.x · Apache Kafka · Kubernetes (환경별 상세: `docs/01-project-overview.md` §4)
- **아키텍처**: 4-Layered + DDD, 모놀리식 → MSA (Phase 1~4)
- **베이스 패키지**: `com.peekcart`

### 문서 참조 맵

| 문서 | 경로 | 참조 시점 |
|------|------|-----------|
| 프로젝트 개요 / 기술 스택 | `docs/01-project-overview.md` | 기술 선택 근거 확인 시 |
| 아키텍처 / 패키지 구조 | `docs/02-architecture.md` | 파일 위치 / 레이어 결정 시 |
| 기능 요구사항 / API 명세 | `docs/03-requirements.md` | 기능 구현 범위 확인 시 |
| 설계 결정 / 트레이드오프 | `docs/04-design-deep-dive.md` | 구현 방식 결정 시 |
| ERD / 인덱스 전략 | `docs/05-data-design.md` | DB 스키마 / Flyway 작성 시 |
| 테스트 전략 | `docs/06-testing-strategy.md` | 테스트 코드 작성 시 |
| 로드맵 / 포트폴리오 | `docs/07-roadmap-portfolio.md` | Phase 범위 확인 시 |
| 아키텍처 결정 이력 (ADR) | `docs/adr/README.md` | 결정 근거/트레이드오프 확인 시 |

> **문서 레이어 원칙**: Layer 1 (01~07) = 현재 상태(What) · Layer 2 (adr/) = 결정 근거(Why, immutable) · Layer 3 (progress/) = 작업 이력(When).
> 새 결정 시 ADR을 먼저 작성하고, Layer 1에서는 `(see ADR-NNNN)` 형태로 참조합니다.

### 아키텍처 규칙 (4-Layered + DDD)

```
presentation/   Controller, Request/Response DTO — 비즈니스 로직 없음
application/    UseCase, Command/Query 서비스 — 트랜잭션 경계
domain/         Entity, VO, Repository 인터페이스 — 핵심 비즈니스 로직
infrastructure/ Repository 구현체, 외부 연동 (Redis, Toss, Slack 등)
```

- **비즈니스 로직 위치**: Service가 아닌 Entity / Domain Service 내부
- **JPA 절충안**: 도메인 엔티티에 `@Entity`, `@Id` 허용. `EntityManager`, `Session` 등 JPA API 직접 의존 금지
- **의존 방향**: Presentation → Application → Domain ← Infrastructure
- **도메인 간 직접 호출 금지**: 이벤트 기반 통신 (Phase별 구현 방식은 `docs/07-roadmap-portfolio.md` 참고)

### 코드 컨벤션

**패키지 구조** (`docs/02-architecture.md` Section 12 참고):
```
com.peekcart.{domain}.presentation
com.peekcart.{domain}.application
com.peekcart.{domain}.domain
com.peekcart.{domain}.infrastructure
com.peekcart.global.{config|exception|jwt|response}
```

**명명 규칙**:
- Command 서비스: `{Domain}CommandService`
- Query 서비스: `{Domain}QueryService`
- Repository 인터페이스: `{Domain}Repository` (domain 패키지)
- JPA Repository: `{Domain}JpaRepository` (infrastructure 패키지)
- Repository 구현체: `{Domain}RepositoryImpl` (infrastructure 패키지)
- Event Listener: `{Domain}EventListener` (infrastructure/event 패키지)

**API URL 규칙**: `/api/v1/{도메인}/...`

**에러 응답 포맷** (`docs/04-design-deep-dive.md` Section 9-12 참고):
```json
{ "status": 400, "code": "ORD-001", "message": "...", "timestamp": "..." }
```

**에러 코드 접두사**: USR / PRD / ORD / PAY / SYS

### DB / Flyway 규칙

- 마이그레이션 파일 위치: `src/main/resources/db/migration/`
- 파일명 규칙: `V{번호}__{설명}.sql` (예: `V1__init_schema.sql`)

### 설정 / YAML 프로파일 규칙 (see ADR-0007)

- **원칙**: `application-{profile}.yml` 은 "환경마다 달라지는 연결 정보·자격증명"만 선언한다. 런타임 동작을 바꾸는 정책은 `application.yml` (base) 또는 `@Configuration` Java Config 로 관리한다.
- **판단 기준**: "환경마다 달라야 하는 값인가(→ 프로파일), 아니면 동작 규약인가(→ base/Java Config)?"
- **허용**(프로파일): `spring.datasource.*`, `spring.data.redis.host/port`, `spring.kafka.bootstrap-servers`, 환경변수 참조 자격증명
- **금지**(프로파일): `management.metrics.*` (식별자/분포 설정), `management.endpoints.web.exposure.include` (최소 노출은 base), `spring.kafka.producer/consumer.properties.*`, `spring.jpa.hibernate.ddl-auto`, `spring.application.name`
- **회색지대 예외**: `logging.level.*`, `spring.jpa.show-sql`, 테스트 전용 타임아웃 등 관용적 환경 차이는 허용하되 YAML 상단에 `# [ADR-0007 exception] ...` 주석으로 의도 명시
- **Java Config 우선 케이스**: 최상위 키 트리(예: `management.*`)가 base/프로파일 간 병합 충돌 위험이 있거나, 조건부 활성화/타입 안전성이 필요한 경우 `@Configuration` 클래스로 선언 (D-001 재발 방지, 예시: `MetricsConfig.java`)
- **관측성 계약 SSOT**: 메트릭 정책/태그/노출/보안/scrape/alerts 의 surface 별 SSOT 위치는 ADR-0009 §Decision 표가 단일 결정. 5서비스 분리 완료 후 per-service 정정(태그/alert/lint = `<svc>-service`)은 ADR-0015 (see ADR-0009, ADR-0015)

### 테스트 규칙 (`docs/06-testing-strategy.md` 참고)

- Domain 레이어: 단위 테스트 (JUnit 5), 목표 커버리지 90%+
- Application 레이어: 단위 테스트 (Mockito), 목표 80%+
- Infrastructure 레이어: 통합 테스트 (Testcontainers)
- Presentation 레이어: 슬라이스 테스트 (`@WebMvcTest`)

---