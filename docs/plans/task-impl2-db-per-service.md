# task-impl2-db-per-service — 구현 ② 서비스별 DB 분리 (Flyway 독립 + 물리 스키마 분리)

> 구현 ①(5개 서비스 풀 분해, #48~#68) 완료 후, 5서비스가 여전히 **단일 공유 DB(`mysql:3306/peekcart`)** 를 쓰는 상태를 **DB-per-service** 로 닫는다. 각 서비스가 자기 스키마를 단독 소유하고(자기 자격증명·권한), 자기 Flyway 이력으로 자기 테이블만 마이그레이션한다.
> 선행 ADR: **ADR-0012**(D1 DB-per-service 경계·테이블 소유표·교차 FK 제거 결정 / D5 retention) · ADR-0010(서비스 경계) · ADR-0004(GKE)/ADR-0007(YAML 프로파일). **새 ADR 불필요** — DB-per-service 경계·교차 FK 제거·retention floor 는 ADR-0012 가 이미 SSOT. GP-1 자동통과(boundary 신호 발화·ADR 보유). 단 **물리 모델 선택(1 인스턴스 + 5 스키마 vs 5 인스턴스)** 은 ADR-0012 가 미규정 → 본 계획에서 결정(§2, 가역적 배포 선택, Codex 판단 위임).
> **⚠️ ADR-0012 D1 표 ↔ 코드 드리프트(Codex P0 #1, 코드로 검증)**: ADR-0012 D1 소유표(`docs/adr/0012-…:37,39`)는 Product=`inventories(+예약 컬럼, D3)`·Payment=`payments, payment_failures, webhook_logs` 로 적었으나, **실제 구현이 다르게 빌드됨**: (a) 예약은 D3 의 대안 "별도 reservation 테이블" 이 채택돼 **`stock_reservations`** 테이블 실재(V5/V8, `product.domain.model.StockReservation` — grep 확정), inventories 예약컬럼 아님. (b) **`payment_failures` 미구현**(마이그레이션 0건), 대신 `payment_cancellations`(V12, B9 영속 marker) 실재. → 본 계획의 소유표·마이그레이션은 **코드 현실을 정본**으로 한다(ADR=Why·코드=is, 코드가 결정의 실제 결과). **충돌 해소(ADR 거버넌스 정합·Codex 2차 P1 #1/3차 P1 #1, `docs/adr/README.md:8,11-14` — 결정 변경의 update-log 우회 금지)**: 두 드리프트 **모두 D1 결정 테이블 자체의 변경**이므로(payment_failures 미구현·payment_cancellations 는 별도 목적 B9 marker / inventories 예약컬럼 미채택·별도 테이블), 둘 다 update-log 가 아니라 **신규 ADR-0016 의 supersede 범위**로 기록한다. (a) `inventories(+예약 컬럼)`→별도 `stock_reservations` 테이블 = D3 "재검토" 대안 채택(strangler-1 #56 빌드). (b) `payment_failures`→`payment_cancellations` = D1 Payment 테이블 변경. → **신규 ADR-0016**(소급 기록, 두 결정 변경) + **ADR-0012 를 `Partially Superseded by ADR-0016`**(D1 Product 행·D1 Payment 행·D3) 로 표시 + **`docs/adr/README.md` 인덱스 갱신**. + Layer 1(`05-data-design.md §11`) 동기화. = PR2 **P14 명시 항목**. (update-log 는 순수 파일명/수치 오류 전용 — 본 건엔 사용 안 함.)
> **물리 모델 결정(사용자 게이트 2026-06-21)**: **1 MySQL 인스턴스 + 5 스키마**(`peekcart_user`/`peekcart_product`/`peekcart_order`/`peekcart_payment`/`peekcart_notification`) + 서비스별 계정·권한 격리. 논리적 DB-per-service. minikube/GKE 리소스 절약. 추후 인스턴스 분리는 datasource URL 교체만으로 승격 가능.
> **PR 분할(사용자 게이트 2026-06-21)**: 3 PR — **PR1 교차 FK 6개 드롭 + 소유 경계 검증(단일 DB 유지·저위험 체크포인트)** → **PR2 Flyway per-service 모듈화 + 물리 스키마 분리 + k8s/CI smoke 전환 + B5/B8b 전환기 잔재 제거** → **PR3 retention/cleanup 스케줄러(D5·L-008/011)**. 공유 인프라(k8s mysql·gradle·CI)를 PR2 에서 일괄 전환해 중간 드리프트 최소화.

## 1. 목표

5개 서비스가 각자 **독립 스키마 + 독립 Flyway 이력 + 자기 테이블만** 소유하도록 전환한다. 교차 도메인 FK 를 제거(ID 참조로 대체)하고, 단일 공유 스키마 마이그레이션(`:common/db/migration`)·전환기 단일 마이그레이터(order-service, B5)·공유 DB poller allowlist(B8b)를 per-service 로 분해한다. retention/cleanup 스케줄러로 `processed_events`/`outbox_events` 무한 증가를 닫는다(D5).

**성공 기준 (PR1)**: 교차 도메인 FK 6개(`fk_carts_user`·`fk_cart_items_product`·`fk_orders_user`·`fk_order_items_product`·`fk_payments_order`·`fk_notifications_user`) 드롭 마이그레이션 추가 + 잔존 교차 FK 0 검증 + `./gradlew build test` 8모듈 그린(단일 DB 유지, 동작 불변).

**성공 기준 (PR2)**: 5 스키마 + 5 계정 물리 분리, 각 서비스 `flyway.enabled:true` 로 **자기 스키마에 자기 마이그레이션만** 적용·`ddl-auto:validate` 부팅. order-service 전환기 마이그레이터(B5) 역할 소멸, `:common/db/migration` 소멸, `flywayMigrateShared` 제거. poller allowlist(B8b) 제거(스키마 분리로 자연 해소·ShedLock 이름만 per-service 유지). k8s mysql init(5 DB/계정)·per-service secret·order-service initContainer 게이트 제거. CI smoke 가 서비스별 자기 스키마로 부팅(앱 런타임 flyway 가 적용 → 별도 flyway 이미지 스텝 제거). `./gradlew build test` 그린(전 서비스 통합테스트가 자기 모듈 마이그레이션으로 컨텍스트 로드).

**성공 기준 (PR3)**: 발행/소비 서비스가 `processed_events`(retention floor=D5 max식) + `outbox_events`(PUBLISHED N일) cleanup 을 ShedLock 스케줄러로 수행. 보존기간이 D5 floor 미만이면 **부팅 실패(fail-fast)** 하는 가드 + 단위/통합 테스트(만료 행 삭제·미만료 보존·멱등 창 보호).

## 2. 배경 / 제약

### 현재 코드 (grep 검증 완료, 2026-06-21)

- **단일 공유 DB**: 5서비스 전부 `jdbc:mysql://mysql:3306/peekcart`(k8s)·`localhost:3306/peekcart`(local), 자격증명 `peekcart/peekcart`(`application-k8s.yml`·`application-local.yml` ×5).
- **Flyway 단일 이력**: 마이그레이션 V1~V12(`common/src/main/resources/db/migration/`, 12개 파일, B5 단일 소유). **order-service 만 `flyway.enabled:true`**(`order-service/application.yml:10-16`, 전환기 마이그레이터, B5 #65)로 공유 스키마 전체를 런타임 적용. 나머지 4서비스 `flyway.enabled:false`+`ddl-auto:validate` → **order-service 가 먼저 마이그레이션해야 validate-부팅**(cold-start ordering 의존).
- **테이블 소유표(ADR-0012 D1 + 엔티티 모듈 위치 grep 으로 확정)**:

| 스키마 | domain 테이블 | infra 테이블 |
|---|---|---|
| `peekcart_user` | users, refresh_tokens, addresses | — |
| `peekcart_product` | categories, products, inventories, **stock_reservations**(`product.domain.model.StockReservation`) | outbox_events, processed_events, shedlock |
| `peekcart_order` | carts, cart_items, orders, order_items, **product_price_cache**(`order.domain.model.ProductPriceCache` — 로컬 CQRS 캐시) | outbox_events, processed_events, shedlock |
| `peekcart_payment` | payments, webhook_logs, payment_cancellations | outbox_events, processed_events, shedlock |
| `peekcart_notification` | notifications | processed_events |

> `stock_reservations` 는 **Product 소유**(예약 원장, `product-service` 가 엔티티/리포지토리/consumer 보유 — grep 확정). `product_price_cache` 는 **Order 소유**(로컬 단가 캐시, strangler-2). `outbox_events`/`processed_events`/`shedlock` 은 현재 단일 공유 테이블 → 분리 후 발행/소비/스케줄러 보유 서비스마다 자기 스키마에 독립 사본.
> **ADR-0012 D1 표와의 차이(상단 ⚠️ 참조)**: ADR 표는 Product `inventories(+예약 컬럼)`·Payment `payment_failures` 로 적었으나 코드는 `stock_reservations`(별도 테이블, D3 대안)·`payment_cancellations`(payment_failures 미구현). 본 표가 코드 정본이며, **신규 ADR-0016**(두 드리프트의 결정 변경 소급 기록) + ADR-0012 `Partially Superseded by ADR-0016` + README 인덱스 + 05-data-design 동기화로 정합(P14). update-log 아님.

- **교차 도메인 FK(V1, drop 대상 6개)**: `fk_carts_user`(carts→users)·`fk_cart_items_product`(cart_items→products)·`fk_orders_user`(orders→users)·`fk_order_items_product`(order_items→products)·`fk_payments_order`(payments→orders)·`fk_notifications_user`(notifications→users). **유지(동일 스키마 내)**: refresh_tokens→users, addresses→users, categories→categories, products→categories, inventories→products, cart_items→carts, order_items→orders.
- **k8s**: 단일 `mysql` Deployment(`k8s/base/infra/mysql/mysql.yml`, `MYSQL_DATABASE: peekcart` 단일 DB·단일 user) + `mysql-secret`(DB_USERNAME/DB_PASSWORD=peekcart). per-service app secret 은 이 값을 공유 참조. PR3b 가 비-order initContainer 로 order-service readiness(마이그레이터) 게이트를 둠.
- **CI smoke**(`scripts/docker-health-smoke.sh`): mysql/redis/kafka compose 기동 후 **flyway 이미지(11.7.2 digest 고정)로 `:common/db/migration` 을 단일 `peekcart` 에 선적용**(`flywayMigrateShared` 가 gradle flyway DB 플러그인 미해석으로 깨져 우회 — PR3a 후속부채). 이후 앱 부팅.
- **docker-compose.yml**: smoke 용 mysql `MYSQL_DATABASE: peekcart`(단일).

### B1 — 역의존 스윕 (공유 마이그레이션 위치·스키마명의 인바운드 간선 처분)

PR2 가 옮기는 대상 = **공유 마이그레이션 위치(`:common/db/migration` → per-service module)** + **단일 스키마명(`peekcart` → 5 스키마)**. 인바운드 간선(grep 전수):

| # | 인바운드 (참조처) | 현재 의존 | PR | 처분 |
|---|---|---|---|---|
| 1 | 5서비스 `application.yml` `flyway.locations: classpath:db/migration` | :common 의 풀 V1~V12 | 2 | **재지정** — 마이그레이션을 각 서비스 모듈 `src/main/resources/db/migration/` 로 이관 → `classpath:db/migration` 가 **자기 모듈 자기 서브셋**으로 자연 해소. 5서비스 전부 `flyway.enabled:true`. |
| 2 | order-service `flyway.enabled:true`(B5 전환기 마이그레이터) | 공유 스키마 전체 적용 | 2 | **승계 역할 소멸** — 자기 스키마(`peekcart_order`)만 적용. 주석/cold-start ordering 의존 제거. |
| 3 | 전 서비스 통합테스트(`@TestPropertySource` `flyway.enabled=true, locations=classpath:db/migration`) — product 5·notification 3·payment 6·order 3·user 2 (grep) | 풀 공유 스키마를 Testcontainer 에 적용 | 2 | **자연 해소(검증 필요)** — 마이그레이션이 모듈로 이관되면 `classpath:db/migration` 가 그 서비스 자기 서브셋으로 resolve. 각 서비스 통합테스트는 peel 로 이미 자기 테이블만 접근 → 자기 서브셋이면 충분(B5/B10). **각 통합테스트 컨텍스트 로드 그린**을 PR2 검증으로 전수 확인. |
| 4 | `scripts/docker-health-smoke.sh` (`-v common/.../db/migration` + flyway 이미지로 단일 `peekcart` 적용) | 공유 위치·단일 스키마 | 2 | **제거/전환** — 서비스별 런타임 flyway(enabled:true)가 자기 스키마를 부팅 시 적용 → **별도 flyway 이미지 스텝 삭제**. compose mysql init 으로 5 DB/계정 생성. (flywayMigrateShared 후속부채도 동반 소멸.) |
| 5 | `build.gradle` `flywayMigrateShared` 태스크(+`flywayMigration` configuration·flyway 플러그인) | 공유 위치 단일 실행 지점 | 2 | **제거** — per-service 런타임 flyway 로 대체. flyway gradle 플러그인/configuration 정리. |
| 6 | `docker-compose.yml` mysql `MYSQL_DATABASE: peekcart` | 단일 스키마 | 2 | **재작성** — init SQL(5 DB + 5 계정 + grant) 마운트(`/docker-entrypoint-initdb.d`). smoke 와 공유. |
| 7 | `k8s/base/infra/mysql/mysql.yml` `MYSQL_DATABASE: peekcart`·단일 user / `mysql-secret` | 단일 DB·자격증명 | 2 | **재작성** — init ConfigMap(5 DB/계정/grant) 마운트. per-service secret 값을 자기 DB/계정으로 분화. order-service initContainer 게이트 제거(각 서비스 자기 DB 독립 마이그레이션). |
| 8 | per-service `application-k8s.yml`·`application-local.yml` datasource URL `…/peekcart` + 자격증명 | 단일 스키마/계정 | 2 | **재지정** — 각 서비스 URL `…/peekcart_<svc>` + 자기 계정(secret/env). ADR-0007 준수(연결정보=프로파일). |
| 9 | `:common/db/migration` 디렉토리 자체 | 5서비스·테스트·smoke·gradle 가 참조 | 2 | **소멸** — 전 소비자(1~8) 재지정 후 삭제. (B5 "단일 소유 위치" 가 per-service 로 분해됨.) |

> **B1b(string-level — DB명)**: 스키마명 `peekcart` 리터럴이 URL·secret·compose·k8s·smoke·gradle 에 산재 → `peekcart_<svc>` 로 치환. **flyway_schema_history** 테이블은 각 스키마에 독립 생성(자연). **검증 sweep**: `grep -rn "3306/peekcart\b"`(끝 단어경계 — `peekcart_order` 오탐 제외) → PR2 후 0(단, 의도된 인스턴스 호스트명 `mysql:3306` 은 유지). 브랜드 문자열(`peekcart` 패키지·이미지명)은 대상 아님 — **DB-name 컨텍스트(`/peekcart?`·`MYSQL_DATABASE`·`database:`)만** sweep.
> **B1b(인프라→리소스명 간선 — Codex P1 #3)**: PR2 가 per-service secret 분화 + mysql init ConfigMap 신설 → **k8s 매니페스트가 리소스를 `name:` 으로 참조하는 간선**을 함께 스윕해야 한다(PLAN-BLINDSPOTS B1b "인프라→공유리소스 이름 간선", PR3b `secretKeyRef.name: peekcart-secret` 누락→MySQL CreateContainerConfigError 선례). **sweep**: `grep -rnE "secretKeyRef:|configMapKeyRef:|envFrom:|name: .*(secret|mysql|config)" k8s/` 로 참조처 열거 → 각 간선에 유지/분리/삭제 처분(아래 P9-sweep 표). mysql Deployment 의 `secretKeyRef.name`(root mysql-secret), 각 service deployment 의 `<svc>-secret` DB 키 참조, 신설 init ConfigMap 이름 참조를 빠짐없이.

> **P9-sweep 처분표(B1b·Codex 3차 P2#2 — grep 으로 현재 name 확정, 2026-06-22)**: 인프라→리소스명 간선의 계획-단계 처분. (`mysql-secret` keys=DB_USERNAME/DB_PASSWORD=peekcart/peekcart · 각 `<svc>-secret`=DB_USERNAME/DB_PASSWORD=peekcart · `<svc>-config`=envFrom, DB URL 은 `application-k8s.yml` 소관 · `mysql.yml` `MYSQL_DATABASE: peekcart` literal.)

| 리소스 / 참조처 | 현재 name | PR2 이후 | 처분 | 검증 |
|---|---|---|---|---|
| mysql Deployment env `MYSQL_ROOT_PASSWORD` → secretKeyRef | `mysql-secret`.DB_PASSWORD | `mysql-secret` (root 전용) | **유지** — root 자격증명만 | mysql Pod 기동·root 접속 |
| mysql Deployment env `MYSQL_USER`/`MYSQL_PASSWORD` → secretKeyRef | `mysql-secret`.DB_USERNAME/PASSWORD | (제거) | **삭제** — 단일 앱 user 생성 책임이 init(5 계정)으로 이동 | env 부재·5 계정은 init 산출 |
| mysql Deployment env `MYSQL_DATABASE` | literal `peekcart` | (제거) | **삭제** — init 이 5 DB 생성 | `MYSQL_DATABASE` env 부재 |
| mysql Deployment `/docker-entrypoint-initdb.d` 마운트 | (없음) | `mysql-init-config`(ConfigMap, 비밀 없는 DDL 골격) + 비밀번호는 Secret 경로(P9 ⚠️) | **신설** — volume + volumeMount, deployment `name:` 참조 일치 | init 후 5 DB+5 계정 존재·ConfigMap literal pw 0 |
| 각 service Deployment `envFrom.secretRef` | `<svc>-secret`(DB_USERNAME/PASSWORD=peekcart) | `<svc>-secret`(값=peekcart_<svc> / per-svc pw, **name 불변**) | **분리(값만)** — 자기 계정 자격증명 | 각 Pod 가 자기 계정으로 자기 스키마만 접속(P15-7) |
| 각 service Deployment `envFrom.configMapRef` | `<svc>-config` | `<svc>-config`(불변; DB URL `…/peekcart_<svc>` 는 `application-k8s.yml` 소관 P6) | **유지** | envFrom 참조 해소·URL=peekcart_<svc> |
| 비-order Deployment `initContainers`(order readiness 게이트) | PR3b 도입분 | (제거) | **삭제(P11)** — 독립 마이그레이션 | 비-order Pod 가 order 대기 없이 기동 |

> 처분 후 검증 = P15-(8) `kubectl kustomize` 렌더의 `name:` 해소 + minikube apply `rollout status`. (PR3b `secretKeyRef.name: peekcart-secret` 누락→CreateContainerConfigError 선례 재발 방지.)

### B5 — 공유 리소스(마이그레이션)의 물리 위치 재배치
현재 `:common/db/migration` 단일 위치를 **모든 소비자**(5 런타임 + 5 모듈 테스트 fixture + smoke + gradle)가 클래스패스/볼륨으로 참조(B1 표). PR2 는 이를 **각 서비스 모듈 `src/main/resources/db/migration/`** 로 분해한다.
- **마이그레이션 재작성 방식(택1, /work 착수 시 확정 — 권고: consolidation)**: 신규 5 스키마는 **빈 그린필드**(포트폴리오·보존할 prod 이력 없음)다. (a) **권고: per-service 통합 베이스라인** — 각 서비스 `V1__init_<svc>.sql` 1개에 V1~V12 누적 결과 중 **자기 테이블 최종 형태만** 담는다(교차 FK 제외, PR1 에서 이미 드롭). 13개 교차 마이그레이션을 끌고 가지 않음. **트레이드오프**: 마이그레이션 이력 단절(과거 alter 추적 상실) vs 단순·정확. 그린필드라 수용. (b) 대안: V1~V12 를 서비스별로 필터/분할 — V1 이 전 테이블 생성이라 분해 복잡·오류 위험. → **(a) 채택 권고**, Codex 판단 위임.
- 각 서비스 테스트가 자기 서브셋을 읽는지(B1 #3) 컨텍스트 로드로 검증.

### B8 — 공유 DB 전환기 잔재(poller allowlist) 제거 = DB 분리로 자연 해소
B8b 가 명시: "**DB 가 실제 분리되면 자연 해소되나 전환기엔 필수**". 현재 3 발행 서비스(order/payment/product)가 같은 `outbox_events` 를 공유 → `app.outbox.polling.aggregate-types` allowlist(`ORDER`/`PAYMENT`/`PRODUCT`)로 소유권 분리. PR2 가 `outbox_events` 를 각 스키마로 분리하면 **각 poller 는 자기 스키마만 봄 → allowlist 불필요**.
- **처분**: `aggregate-types` allowlist 주입/`findPendingEvents(aggregateTypes,…)` 필터를 **제거**(또는 자기 단일 타입으로 무력화). **ShedLock 이름은 per-service 유지**(같은 인스턴스라도 잡 충돌 방지·관측 라벨). `processed_events` 도 각 스키마 독립.
- **주의(회귀 방지)**: allowlist 제거 시 `findPendingEvents` 쿼리 시그니처 변경 → 해당 단위/통합테스트(OutboxPolling·ProductOutboxOwnership 등) 동반 갱신.

### B10 — @SpringBootTest(flyway) 통합테스트의 스키마 적용 주체
peel 선례로 각 서비스 통합테스트는 이미 `@TestPropertySource(flyway.enabled=true, locations=classpath:db/migration)` 보유(grep 19개). PR2 가 마이그레이션을 모듈로 이관하면 `classpath:db/migration` 이 자기 서브셋으로 resolve → **override 형태 자체는 불변, 내용만 자기 서브셋으로 좁아짐**. 단 통합테스트가 **타 도메인 테이블을 시드/참조**하면 깨진다 → peel 로 이미 격리됐는지(자기 테이블만) 각 테스트 전수 컨텍스트 로드 검증(B1 #3). Testcontainer 단일 DB 에 자기 스키마만 적용되면 충분.

### B2 — ADR 타깃 ≠ 현재 코드
- **ADR-0012 D1 표가 코드와 드리프트(상단 ⚠️·Codex P0 #1)**: ADR 은 "inventories 예약 컬럼"·"payment_failures" 를 적시하나 코드는 **별도 `stock_reservations` 테이블**(D3 대안 채택)·**`payment_cancellations`**(payment_failures 미구현). ADR 표를 코드에 맞추지 않고 인용하면 P4 마이그레이션이 존재하지 않는 테이블/컬럼을 생성하게 됨 → 코드 정본 + 신규 ADR-0016(두 결정 변경 소급) + ADR-0012 `Partially Superseded by ADR-0016` + README 인덱스 정합(P14)으로 닫음. (update-log 아님 — 결정 변경의 우회 금지, README:11-14.)
- Product `outbox_events/processed_events` 소유 = 이미 존재(outbox 복제 #62).
- ADR-0012 D5 retention 스케줄러는 "구현 ②" 로 적시했으나 **현재 코드에 cleanup 스케줄러 부재**(grep: outbox/processed cleanup 없음) → PR3 의 **신규 작성 명시 항목**으로 승격.
- `05-data-design.md §11` Product DB 에 outbox/processed 부재(ADR-0012 D1 주석이 "Layer 1 정정 필요") + 예약/취소 테이블 표기 드리프트 → PR2 에서 Layer 1 동기화(P14).

### 트레이드오프
- **1 인스턴스 + 5 스키마(논리 분리)**: 진짜 물리 인스턴스 격리(장애 도메인) 아님. 단 자격증명·권한·스키마·Flyway 이력은 완전 격리 → ADR-0012 D1 "단독 소유" 계약 충족. 인스턴스 분리는 URL 교체로 가역 승격(③ 이후 후보). minikube/GKE 리소스 절약 우선.
- **베이스라인 consolidation**: 과거 13개 마이그레이션 이력 단절(그린필드라 무영향) vs 단순·정확. (a) 채택 권고.
- **3 PR**: PR1(FK drop, 단일 DB·저위험 그린 체크포인트) → PR2(물리 분리, 큰 변경) → PR3(retention). 각 PR 독립 롤백 가능. 대안 서비스별 5+1 PR 은 공유 인프라(k8s mysql·CI·gradle) 증분 전환이 어색해 기각(사용자 게이트).
- **결과적 일관성 심화**: 교차 FK 제거로 DB 레벨 무결성 보장 상실 → 이벤트/로컬 캐시로 대체(ADR-0012 D2/D3, 이미 strangler 에서 수용). 고아 참조(없는 user_id 등)는 애플리케이션/이벤트 정합이 책임.

## 3. 작업 항목

### PR1 — 교차 FK 드롭 + 소유 경계 검증 (단일 DB 유지)

- [ ] **P1.** 교차 도메인 FK 6개 드롭 마이그레이션 추가(`common/src/main/resources/db/migration/V13__drop_cross_domain_fks.sql`, 현 단일 이력 말미): `fk_carts_user`·`fk_cart_items_product`·`fk_orders_user`·`fk_order_items_product`·`fk_payments_order`·`fk_notifications_user`. 컬럼(user_id/product_id/order_id)은 **유지**(ID 참조 보존), 제약만 제거. order-service 마이그레이터가 런타임 적용.
- [ ] **P2.** 소유 경계 검증: 각 서비스 JPA 엔티티가 **자기 테이블만** 매핑(타 도메인 테이블 `@Table`/`@SecondaryTable`/네이티브 쿼리 0)임을 grep 확인. 교차 참조 발견 시 ID 컬럼 매핑으로 처분(이미 peel 로 격리 예상 — 미발견이 정상).
- [ ] **P3.** 빌드/검증: `./gradlew build test` 8모듈 그린(FK 드롭이 기존 통합테스트 깨지 않음 확인 — 테스트가 FK 무결성에 의존하면 그 시드 정리). 잔존 교차 FK 0 확인(`information_schema` 또는 마이그레이션 후 `SHOW CREATE TABLE` 스폿체크는 PR2 물리 검증에서, PR1 은 마이그레이션 적용 그린으로 충분).

### PR2 — Flyway per-service 모듈화 + 물리 스키마 분리 + k8s/CI

- [ ] **P4.** 마이그레이션 모듈 이관(B5, consolidation 권고): 각 서비스 `src/main/resources/db/migration/V1__init_<svc>.sql` 신설 — V1~V13 누적 결과 중 자기 테이블 최종 형태(교차 FK 제외). 소유표(§2)대로 분배: user(users/refresh_tokens/addresses) · product(categories/products/inventories/stock_reservations/outbox_events/processed_events/shedlock) · order(carts/cart_items/orders/order_items/product_price_cache/outbox_events/processed_events/shedlock) · payment(payments/webhook_logs/payment_cancellations/outbox_events/processed_events/shedlock) · notification(notifications/processed_events). 동일 스키마 내 FK 만 유지.
- [ ] **P5.** 각 서비스 `application.yml` `flyway.enabled:true`(5서비스 전부) + `locations: classpath:db/migration`(자기 모듈 자기 서브셋으로 resolve). order-service B5 전환기 주석/특권 제거(P-B5). `ddl-auto:validate` 유지.
- [ ] **P6.** datasource per-schema 재지정(B1 #8, ADR-0007 연결정보=프로파일): 각 서비스 `application-k8s.yml`·`application-local.yml` URL `…/peekcart_<svc>` + 자기 계정 `${DB_USERNAME}/${DB_PASSWORD}`(per-service secret/env 주입). 호스트 `mysql:3306` 유지(단일 인스턴스).
- [ ] **P7.** `:common/db/migration` 소멸(B1 #9) + `build.gradle` `flywayMigrateShared`·`flywayMigration` configuration·flyway 플러그인 제거(B1 #5). (gradle flyway 후속부채도 동반 소멸.)
- [ ] **P8.** B8b 전환기 잔재 제거: `app.outbox.polling.aggregate-types` allowlist 주입 제거 + `findPendingEvents` 쿼리 자기 스키마 전체 PENDING 조회로 단순화(스키마 분리로 소유권 자연 보장). ShedLock 이름 per-service 유지. 관련 OutboxPolling/Ownership 단위·통합테스트 동반 갱신(B8 주의).
- [ ] **P9.** k8s mysql init(B1 #7): init SQL(`CREATE DATABASE peekcart_<svc>` ×5 + 서비스 계정 ×5 — **MySQL 호환 문법, Codex 2차 P1#2**: `CREATE USER 'peekcart_<svc>'@'%' IDENTIFIED BY …;` + `GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON peekcart_<svc>.* TO 'peekcart_<svc>'@'%';` — 타 스키마 GRANT 부여 안 함=격리). **⚠️ 비밀번호 위치(Codex 3차 P1#3 — credential source 충돌)**: `IDENTIFIED BY <literal>` 을 **ConfigMap 에 절대 두지 않는다**(ConfigMap 은 평문 노출 — per-service secret 분화와 모순). 처분(택1, /work 착수 시 확정): (a) **권고** — ConfigMap 엔 비밀 없는 DDL(`CREATE DATABASE`+`CREATE USER … IDENTIFIED BY '${DB_<svc>_PASSWORD}'` 가 아닌, 비밀번호 placeholder 없는 골격)만 두고, 실제 비밀번호는 **Secret env 를 읽는 `.sh` entrypoint**(`/docker-entrypoint-initdb.d/*.sh`)가 런타임에 `CREATE USER … IDENTIFIED BY "$DB_<svc>_PASSWORD"` 를 생성·실행. (b) 대안 — init SQL 자체를 **Secret 으로 마운트**(literal 포함, ConfigMap 아님). **검증**: 렌더된 ConfigMap 에 `IDENTIFIED BY '<평문>'` literal 0(grep), 비밀번호는 Secret 경로로만 흐름. init 산출물(ConfigMap DDL 골격 + Secret/entrypoint 비밀번호)을 `/docker-entrypoint-initdb.d` 마운트. `mysql.yml` 단일 `MYSQL_DATABASE`/user 제거(init 이 담당). per-service `secret.yml` 값을 자기 DB/계정으로 분화. `mysql-secret`(root) 유지. **k8s 리소스명 스윕(B1b 인프라 간선·Codex P1 #3)**: `grep -rnE "secretKeyRef:|configMapKeyRef:|name: .*(secret|config)" k8s/` 로 참조처 전수 열거 → 신설 init ConfigMap·분화 secret 의 `name:` 이 deployment/mysql 참조와 일치하는지 처분표로 확인(렌더만으론 못 잡음).
- [ ] **P10.** 전환 모드 = **dev/test 데이터 폐기**(Codex P1 #2): 포트폴리오 — 보존할 prod 데이터 없음(백업 불요 근거). compose mysql named volume 제거(`docker compose down -v`)·GKE `mysql-pvc` 삭제/재생성으로 빈 DB 에서 init+per-service flyway 재현. **롤백**: PR2 revert 시 단일 `peekcart` DB init 으로 복귀(volume/PVC 재초기화). (운영 데이터 마이그레이션 SQL 은 prod 단계 — 본 PR 범위 외 명시.)
- [ ] **P11.** k8s order-service initContainer 게이트 제거(B1 #7): 비-order 서비스의 order-service readiness 대기(PR3b 도입, 마이그레이터 ordering) 삭제 — 각 서비스가 자기 DB 를 독립 마이그레이션하므로 cross-service 순서 의존 소멸. mysql readiness 만 게이트.
- [ ] **P12.** CI smoke 전환(B1 #4): `scripts/docker-health-smoke.sh` 의 flyway 이미지 선적용 스텝 제거(각 서비스 런타임 flyway 가 자기 스키마 적용). `docker-compose.yml` mysql init(5 DB/계정) 마운트(B1 #6). smoke 가 `<svc>:ci` 부팅 시 자기 스키마로 마이그레이션·validate 그린. **이미지 COPY 컨텍스트 검증(B5·Codex 3차 P2#5·[[project_multimodule_dockerfile_context]])**: 마이그레이션이 :common→서비스 모듈로 이동했으므로 각 `<svc>:ci` 이미지에 자기 마이그레이션이 실제 포함되는지 확인 — `BOOT-INF/classes/db/migration/V1__init_<svc>.sql` 존재 assert(또는 부팅 로그에서 Flyway 가 해당 script 를 서비스별로 적용했음을 assert). 로컬 gradle 그린 ≠ Docker 그린.
- [ ] **P13.** 통합테스트 자기 서브셋 검증(B1 #3·B10): 마이그레이션 이관 후 전 서비스 통합테스트(19개) 컨텍스트 로드 그린 — 자기 모듈 `db/migration` 서브셋으로 Testcontainer 적용. 타 도메인 테이블 시드/참조 발견 시 제거(peel 로 격리 예상). 마이그레이션 위치 변경된 `@TestPropertySource` 는 `classpath:db/migration` 그대로(내용만 좁아짐).
- [ ] **P14.** ADR 정합 + Layer 1 동기화(B2·Codex P0#1/2차 P1#1/**3차 P1#1·P2#2**, 거버넌스 `README.md:8,11-14,20-21`): **거버넌스 재판정(Codex 3차)** — `payment_failures`→`payment_cancellations` 는 단순 사실 오류(파일명/수치)가 **아니라** D1 결정 테이블 자체의 변경(payment_failures 미구현·payment_cancellations 가 별도 목적 B9 marker)이다. README:11-14("결정 변경·대안 추가는 Update Log 우회 금지")에 따라 **Update Log 로 정정하지 않는다**. (a) **신규 ADR-0016**(소급) — ① 예약 모델 = 별도 `stock_reservations` 테이블 채택(D3 "재검토" 대안, strangler-1 #56 빌드), ② Payment 테이블 = `payment_cancellations`(payment_failures 미구현). 두 결정 변경을 ADR-0016 의 supersede 범위에 함께 기록. (b) **ADR-0012 를 `Partially Superseded by ADR-0016`** 으로 표시 + 무효화 범위 명시: **D1 Product 행(예약 테이블)·D1 Payment 행(payment_failures)·D3 예약 모델**. (c) **`docs/adr/README.md` 인덱스 갱신**(README:20-21 — 신규 ADR 추가 시 인덱스 행 추가): ADR-0012 status `Accepted`→`Partially Superseded`, ADR-0016 행 신설(맨 아래). (d) `05-data-design.md §11`: Product DB 에 outbox_events/processed_events·`stock_reservations` 반영, Payment `payment_failures`→`payment_cancellations`, 단일 DB→DB-per-service(5 스키마). `02-architecture.md` 단일 DB→5 스키마. Layer 1 은 `(see ADR-0012, ADR-0016)` 참조. **PR2 완료조건**: ADR-0012 status 변경 + ADR-0016 인덱스 행이 README 에 반영됨.
- [ ] **P15.** 빌드/물리 검증: `./gradlew build test` 8모듈 그린. **물리 분리 판정 쿼리(Codex P2 #5, §5 에 SQL 박음)**: (1) `information_schema.tables` 로 각 `peekcart_<svc>` 가 소유표(§2)만 보유 확인, (2) `information_schema.referential_constraints` 로 cross-schema FK = 0, (3) 서비스 계정으로 실제 접속해 타 스키마 SELECT 거부(GRANT 격리), (4) 각 `peekcart_<svc>.flyway_schema_history` **독립 존재 + `script` 가 `V1__init_<svc>.sql` 자기 파일명과 일치 + 자기 소유표만 생성**(Codex 2차 P2#3 — 전 서비스 V1 이라 version 집합 disjoint 판정은 오판, 파일명·테이블 귀속으로 판정). **k8s 배포 검증(Codex 3차 P1#4 — Phase4 Exit=독립 배포 정상동작 `07-roadmap:110-115`·B1b 이름참조 누락→배포장애 `PLAN-BLINDSPOTS:21-25`)**: (5) `kubectl kustomize`(렌더) + `kubectl apply --dry-run=server`(또는 `--dry-run=client`) 로 5서비스+mysql overlay 가 참조 무결(secretKeyRef/configMapKeyRef 이름 해소)하게 렌더되는지, (6) minikube overlay 실제 apply 후 mysql + 5서비스 `rollout status` 그린, (7) 각 Pod 가 자기 Secret(분화된 `<svc>-secret`)·자기 DB URL(`…/peekcart_<svc>`)로 기동(env 덤프/로그 확인), (8) P9 의 init ConfigMap·분화 secret 이름참조 **처분표를 plan/audit 에 기록**(렌더만으론 못 잡는 간선, PR3b CreateContainerConfigError 선례).

### PR3 — retention/cleanup 스케줄러 (D5 · L-008/011)

- [ ] **P16.** `processed_events` retention 스케줄러(ShedLock 잡, 발행/소비 서비스별): 보존기간 `app.idempotency.retention` 경과 행 삭제. **floor 가드 = fail-fast(경고 아님, Codex P2 #4)**: `retention < max(floor 입력)` 이면 **부팅 실패**(`@PostConstruct`/`@Validated` ConfigurationProperties). floor 입력 설정키(전부 `application.yml` base — 동작 정책, ADR-0007): `app.idempotency.floor.kafka-topic-retention`·`max-consumer-downtime`·`dlq-replay-window`·`backfill-replay-window`. D5 식 = 이 4값의 `max`. 기본값은 base 에 명시(예: 7d/24h/7d/7d → floor 7d), `retention` 기본은 floor 이상.
- [ ] **P17.** `outbox_events` cleanup 스케줄러(ShedLock 잡, 발행 서비스별): PUBLISHED 상태 + `app.outbox.retention`(N일) 경과 행 삭제. 미발행(PENDING)·실패 행은 보존.
- [ ] **P18.** 테스트: 만료 행 삭제 / 미만료·PENDING 보존 / **floor 미만 설정 시 부팅 실패(fail-fast)** 단위테스트 + floor 4입력 조합별 `max` 계산 테스트(Codex P2 #4) + floor 설정키가 base 에만 있고 프로파일 override 가 ADR-0007(연결정보=프로파일·정책=base) 위반 안 함을 검증 + ShedLock 단일 실행 통합테스트(서비스 1곳 대표).
- [ ] **P19.** 빌드/검증: `./gradlew build test` 그린. ADR-0012 D5 floor 식 충족을 plan/audit 에 기록(L-008/011 종결).

## 4. 영향 파일

**신규(PR1)**: `common/src/main/resources/db/migration/V13__drop_cross_domain_fks.sql`.
**신규(PR2)**: 5× `<svc>/src/main/resources/db/migration/V1__init_<svc>.sql` · k8s mysql init ConfigMap(`k8s/base/infra/mysql/`) · (compose init SQL) · `docs/adr/0016-*.md`(예약 모델 = 별도 stock_reservations 테이블, ADR-0012 Partially Superseded — P14).
**신규(PR3)**: 발행/소비 서비스별 retention/cleanup 스케줄러 클래스(`<svc>/.../infrastructure/...` 또는 global 복제 패턴) + 테스트.
**수정(PR2)**: 5× `application.yml`(flyway.enabled:true) · 5× `application-k8s.yml`·`application-local.yml`(URL/계정 per-schema) · 5× k8s `secret.yml`(DB 자격증명 분화) · `k8s/base/infra/mysql/mysql.yml`(단일 DB 제거·init) · order-service B5 주석/특권 제거 · OutboxPolling 코드+테스트(allowlist 제거) · `build.gradle`(flyway 플러그인/태스크 제거) · `scripts/docker-health-smoke.sh`(flyway 스텝 제거) · `docker-compose.yml`(mysql init) · k8s 비-order deployment(initContainer 게이트 제거) · `docs/adr/0012-phase4-db-event-saga-contract.md`(**status→`Partially Superseded by ADR-0016`** + 무효화 범위, update-log 아님 — P14) · `docs/adr/README.md`(**인덱스: ADR-0012 status 갱신 + ADR-0016 행 추가** — Codex 3차 P2#2) · `docs/05-data-design.md`·`docs/02-architecture.md` · (이미지 검증 시 필요하면) `Dockerfile` COPY 컨텍스트([[project_multimodule_dockerfile_context]] — 마이그레이션 위치 이동분).
**삭제(PR2)**: `common/src/main/resources/db/migration/` 전체(V1~V13) · `build.gradle` flywayMigrateShared 블록.
**불변**: 도메인 로직(엔티티/서비스) · 이벤트 토폴로지(토픽/consumer group) · common payload DTO · Dockerfile(COPY 컨텍스트는 마이그레이션 위치 이동분 확인 — [[project_multimodule_dockerfile_context]]).

## 5. 검증 방법

- **PR1**: `./gradlew build test` 8모듈 그린. V13 적용 후 6개 교차 FK 제약 부재(스폿 `SHOW CREATE TABLE carts/orders/payments/notifications/cart_items/order_items`), 컬럼은 잔존. 기존 통합테스트 그린(FK 의존 시드 정리 확인).
- **PR2**:
  - `grep -rn "3306/peekcart\b" --include="*.yml" --include="*.sh" --include="*.gradle"`(단어경계 — `_<svc>` 제외) → 0(인스턴스 호스트 `mysql:3306` 제외). `:common/db/migration` 디렉토리 부재.
  - `./gradlew build test` 8모듈 그린 — 전 서비스 통합테스트(19)가 자기 모듈 마이그레이션으로 컨텍스트 로드(B1 #3/B10).
  - **물리 분리(compose/smoke) — 판정 SQL(Codex P2 #5)**: 5 서비스 같은 mysql 의 자기 스키마에 부팅 후:
    - 테이블 귀속: `SELECT table_schema, table_name FROM information_schema.tables WHERE table_schema LIKE 'peekcart_%'` → 스키마별 §2 소유표만(타 도메인 0).
    - cross-schema FK 0: `SELECT * FROM information_schema.referential_constraints WHERE constraint_schema LIKE 'peekcart_%' AND unique_constraint_schema <> constraint_schema` → 0행.
    - GRANT 격리: 서비스 계정으로 접속 `mysql -u<svc> -p … peekcart_<other> -e 'SELECT 1 FROM <other_table>'` → 거부(ERROR 1142/1044).
    - flyway 이력 독립: 각 `SELECT version, script FROM peekcart_<svc>.flyway_schema_history` 존재 + `script`=`V1__init_<svc>.sql`(자기 파일명) — version 값 동일(전부 V1)은 정상, **파일명·스키마 귀속으로 판정**(Codex 2차 P2#3).
    - order-service 가 더 이상 타 서비스 선행 마이그레이터 아님(독립 cold-start).
  - smoke: 각 `<svc>:ci` 가 flyway 이미지 스텝 없이 자기 스키마 마이그레이션·헬스 그린. CI 이미지 매트릭스 그린.
  - poller(B8b): 각 서비스 outbox poller 가 allowlist 없이 자기 스키마 PENDING 만 발행(타 스키마 미접근 = 물리 격리). 중복 발행 0.
- **PR3**: 만료 `processed_events`/`outbox_events` 행 삭제 + 미만료/PENDING 보존(단위테스트). floor 미만 설정 시 가드 발화. ShedLock 단일 실행(통합테스트 1곳). `./gradlew build test` 그린.

## 6. 완료 조건

- 5 스키마(`peekcart_<svc>`) + 5 계정 물리 분리, 각 서비스 자기 Flyway 이력으로 자기 테이블만 마이그레이션·`validate` 부팅. 교차 FK 6개 제거(ID 참조 대체).
- B5 전환기 단일 마이그레이터(order-service)·`:common/db/migration`·`flywayMigrateShared` 소멸. B8b poller allowlist 제거(스키마 분리로 자연 해소).
- k8s mysql init(5 DB/계정)·per-service DB secret·initContainer 게이트 제거. CI smoke 가 서비스별 자기 스키마로 부팅(flyway 이미지 스텝 소멸).
- D5 retention/cleanup 스케줄러(processed_events·outbox_events) + floor **fail-fast** 가드(미만 시 부팅 실패) → L-008/011 종결.
- Layer 1 동기화(05-data-design Product outbox/processed·02-architecture DB-per-service).
- `./gradlew build test` 8모듈 그린 + 물리 격리(GRANT·flyway_schema_history per-schema) 검증.
- 인스턴스 물리 분리(5 인스턴스)·Gateway(③)·Saga 잔여(④)는 범위 외(후속 명시).
