# PeekCart — 설계 통합 문서

> **고가용성 이커머스 플랫폼**
Java 17 · Spring Boot 3.x · Kafka · Redis · Toss Payments · Kubernetes
작성 기준: Phase 1 (모놀리식) → Phase 4 (MSA) 전체 설계
>

---

## 1. 프로젝트 개요

| 항목 | 내용 |
| --- | --- |
| 프로젝트명 | PeekCart |
| 유형 | 개인 포트폴리오 프로젝트 |
| 주제 | 대용량 트래픽 환경을 고려한 이커머스 플랫폼 설계 및 구현 |
| 개발 언어 | Java 17 |
| 프레임워크 | Spring Boot 3.x |
| 아키텍처 패턴 | 4-Layered Architecture + DDD |
| 레포 전략 | 모노레포 (Gradle 멀티모듈) |
| 서비스 구조 | 모놀리식으로 시작 후 핵심 서비스 MSA 분리 |
| 인프라 | Kubernetes (환경별 상세: §4 운영 환경) |

---

## 2. 프로젝트 목표

### 2-1. 기술적 목표

- 4-Layered Architecture + DDD 기반 도메인 모델 설계 및 구현
- Kafka 기반 이벤트 드리븐 아키텍처 + Transactional Outbox 패턴 적용
- Redis 캐싱 적용 전/후 성능 개선 수치 측정 및 검증
- Prometheus + Grafana 기반 실시간 모니터링 시스템 구축 (kube-prometheus-stack)
- nGrinder / k6를 활용한 부하 테스트 시나리오 작성 및 병목 개선
- Kubernetes HPA를 통한 자동 스케일아웃 검증
- Toss Payments 연동을 통한 실결제 플로우 구현 (가상 결제)
- 대규모 주문 데이터 처리 경험 (페이지네이션, 배치, 인덱싱 최적화)

### 2-2. 포트폴리오 목표

- 단순 CRUD를 넘어 트래픽 · 성능 · 안정성 · 인프라를 고려한 설계 능력 증명
- 기술 도입의 이유와 근거를 수치로 설명할 수 있는 프로젝트 완성
- "왜 이 기술을 썼는가?"에 대한 명확한 스토리라인 구성

---

## 3. 기술 스택

### 3-1. 기술 스택 목록

| 분류 | 기술 | 선택 이유 |
| --- | --- | --- |
| Language | Java 17 | LTS 버전, Record · Sealed class 활용 |
| Framework | Spring Boot 3.x | Spring 6 기반, Virtual Thread 지원 |
| 아키텍처 패턴 | 4-Layered + DDD | 도메인 로직 응집, 레이어 간 명확한 책임 분리 |
| Database | MySQL 8.x | 트랜잭션 정합성, 복잡한 조인 쿼리 |
| DB 마이그레이션 | Flyway | Phase 간 스키마 변경 이력 추적, 재현 가능한 마이그레이션 |
| Cache | Redis 7.x | 상품 캐싱, 분산 락, 블랙리스트 토큰 관리 |
| Message Queue | Apache Kafka | 주문 이벤트 비동기 처리, 서비스 간 디커플링 |
| API Gateway | Spring Cloud Gateway | 라우팅, JWT 인증 필터, Rate Limiting |
| 모니터링 | Prometheus + Grafana | kube-prometheus-stack, Pod 단위 메트릭 수집 |
| 결제 | Toss Payments API | 국내 표준 결제 플로우, 가상 결제 지원 |
| 알림 | Slack Webhook | Kafka Consumer 연동, 실제 발송 동작 증명 |
| 부하 테스트 | nGrinder + k6 | nGrinder: 분산 테스트 / k6: 시나리오 테스트 |
| 빌드 | Gradle | 멀티모듈 구성, 빌드 캐시 활용 |
| 컨테이너 | Docker + Kubernetes | 서비스별 독립 배포, HPA 자동 스케일아웃. 환경 상세는 §4 운영 환경 |
| 레포 전략 | 모노레포 (Gradle 멀티모듈) | 전체 구조 가시성, common 모듈 공유 용이 |
| 문서화 | Swagger (springdoc) | API 명세 자동화 |

### 3-2. 핵심 기술 선택 대안 비교

| 선택 항목 | 채택 | 대안 | 채택 이유 | 미채택 사유 |
| --- | --- | --- | --- | --- |
| RDBMS | MySQL 8.x | PostgreSQL | 국내 이커머스 레퍼런스 다수, 운영 경험 풍부한 생태계 | PostgreSQL의 JSONB·Advanced Index는 본 프로젝트에서 활용하지 않음 |
| Message Queue | Apache Kafka | RabbitMQ | 이벤트 리플레이, 파티션 기반 순서 보장, 높은 처리량 | RabbitMQ는 라우팅 유연성이 장점이나 이벤트 소싱/리플레이 불가 |
| 캐시 | Redis 7.x | Caffeine (로컬 캐시) | 분산 환경 캐시 일관성, 분산 락·블랙리스트 등 다목적 활용 | Caffeine은 단일 인스턴스에서만 유효, MSA 전환 시 캐시 불일치 |
| Outbox 발행 | Polling 스케줄러 | Debezium CDC | 추가 인프라 없이 Spring Scheduler로 구현 가능, 포트폴리오 범위에서 적정 | CDC는 지연 최소화에 유리하나 Kafka Connect 클러스터 운영 복잡도 증가 |
| 분산 락 | Redis (Redisson) | MySQL Named Lock | 성능 우수, TTL 자동 해제, 분산 환경 확장 용이 | DB Named Lock은 커넥션 점유, MSA 분리 시 한계 |

---

## 4. 운영 환경

> **본 섹션은 운영 환경 정보의 SSOT입니다.** 다른 문서에서 환경 관련 사실을 기술할 때는 본 섹션을 참조합니다.

### 4-1. Phase별 환경

Phase 1~4 의 단계별 환경 표 원문은 [Layer 1 이력 아카이브](progress/archive/layer1-history-phase1-4.md)로 옮겼다(D-116).

### 4-2. 로컬 개발 환경 (상시)

- Phase 3 이후에도 `k8s/overlays/minikube/` 는 **로컬 개발/검증용으로 계속 유효**합니다 (ADR-0005).
- 코드 변경 후 빠른 반복 검증은 로컬 minikube, 부하/HPA 등 정확한 측정이 필요한 작업은 GKE 를 사용합니다.

> 단계별 환경 진화의 의도(Phase 1~3)는 [Layer 1 이력 아카이브](progress/archive/layer1-history-phase1-4.md)로 옮겼다(D-116).
