## 11. ERD 설계

### Phase 1 — 단일 DB (모놀리식)

> 모든 도메인 테이블이 하나의 MySQL DB에 통합됩니다.
Phase 1에서는 `@TransactionalEventListener`를 사용하므로 Outbox/processed_events 테이블이 없습니다.
>

```mermaid
erDiagram
  users {
    bigint id PK
    string email UK
    string password_hash
    string name
    string role
    timestamp created_at
    timestamp updated_at
  }
  refresh_tokens {
    bigint id PK
    bigint user_id FK
    string token UK
    timestamp expires_at
    timestamp created_at
  }
  addresses {
    bigint id PK
    bigint user_id FK
    string receiver_name
    string phone
    string zipcode
    string address
    boolean is_default
  }
  categories {
    bigint id PK
    string name
    bigint parent_id FK
  }
  products {
    bigint id PK
    bigint category_id FK
    string name
    string description
    bigint price
    string image_url
    string status
    timestamp created_at
  }
  inventories {
    bigint id PK
    bigint product_id FK
    int stock
    int version
    timestamp updated_at
  }
  carts {
    bigint id PK
    bigint user_id FK
    timestamp created_at
  }
  cart_items {
    bigint id PK
    bigint cart_id FK
    bigint product_id FK
    int quantity
  }
  orders {
    bigint id PK
    bigint user_id FK
    string order_number UK
    bigint total_amount
    string status
    string receiver_name
    string phone
    string zipcode
    string address
    timestamp ordered_at
  }
  order_items {
    bigint id PK
    bigint order_id FK
    bigint product_id FK
    int quantity
    bigint unit_price "주문 시점 가격 스냅샷"
  }
  payments {
    bigint id PK
    bigint order_id FK
    string payment_key UK
    bigint amount
    string status
    string method
    timestamp approved_at
    timestamp created_at
  }
  webhook_logs {
    bigint id PK
    string payment_key
    string event_type
    string idempotency_key UK
    string payload
    string status
    timestamp received_at
  }
  notifications {
    bigint id PK
    bigint user_id FK
    string type
    string message
    boolean is_read
    timestamp created_at
  }

  users ||--o{ refresh_tokens : has
  users ||--o{ addresses : has
  users ||--o{ carts : has
  users ||--o{ orders : places
  users ||--o{ notifications : receives
  categories ||--o{ products : contains
  categories ||--o{ categories : parent
  products ||--|| inventories : tracks
  products ||--o{ cart_items : in
  products ||--o{ order_items : in
  carts ||--o{ cart_items : contains
  orders ||--o{ order_items : contains
  orders ||--|| payments : paid_by
```

> **`order_items.unit_price` 설계 의도**: Phase 1에서도 단일 DB FK로 `products` 테이블을 조인할 수 있지만, 상품 가격이 변경되어도 주문 당시 가격을 보존하기 위해 주문 시점의 가격을 스냅샷으로 저장합니다.
**`payment_failures` 미포함 근거**: Phase 1에서는 `payments.status = 'FAILED'`로 결제 실패를 충분히 표현할 수 있으므로 별도 테이블 없이 운영합니다. 상세 실패 사유 로깅은 Phase 4에서 `payment_failures` 테이블을 도입하여 대응합니다.
>

### Phase 2 — ERD 변경점 (Delta)

Phase 2에서 Kafka + Outbox 패턴 도입에 따라 아래 테이블이 추가됩니다.

```mermaid
erDiagram
  outbox_events {
    bigint id PK
    string aggregate_type
    string aggregate_id
    string event_type
    string event_id UK
    string payload
    string status "PENDING / PUBLISHED / FAILED"
    int retry_count
    timestamp last_attempted_at
    timestamp created_at
    timestamp published_at
    string trace_id "MDC traceId — D-010 (see ADR-0008)"
    string user_id "MDC userId — D-010 (see ADR-0008)"
  }
  processed_events {
    bigint id PK
    string event_id "UK(event_id, consumer_group)"
    string consumer_group
    timestamp processed_at
  }
  shedlock {
    string name PK
    timestamp lock_until
    timestamp locked_at
    string locked_by
  }
```

> Phase 1 → Phase 2 스키마 마이그레이션은 Flyway로 관리합니다.
`outbox_events`, `processed_events`, `shedlock` 테이블이 Phase 2에서 추가되는 스키마 변경입니다.
>

### Phase 4 — 서비스별 DB 분리 (MSA)

> DB 간 FK 제약 없음 — 필요한 데이터는 이벤트 수신 시점에 스냅샷으로 저장합니다.
각 서비스 DB에 `outbox_events` 테이블이 포함됩니다.
>

### User DB

```mermaid
erDiagram
  users {
    bigint id PK
    string email UK
    string password_hash
    string name
    string role
    timestamp created_at
    timestamp updated_at
  }
  refresh_tokens {
    bigint id PK
    bigint user_id FK
    string token UK
    timestamp expires_at
    timestamp created_at
  }
  addresses {
    bigint id PK
    bigint user_id FK
    string receiver_name
    string phone
    string zipcode
    string address
    boolean is_default
  }
  users ||--o{ refresh_tokens : has
  users ||--o{ addresses : has
```

> Redis는 로그아웃된 Refresh Token의 블랙리스트 저장소로 별도 운영합니다.
>

### Product DB

```mermaid
erDiagram
  categories {
    bigint id PK
    string name
    bigint parent_id FK
  }
  products {
    bigint id PK
    bigint category_id FK
    string name
    string description
    bigint price
    string image_url
    string status
    timestamp created_at
  }
  inventories {
    bigint id PK
    bigint product_id FK
    int stock
    int version
    timestamp updated_at
  }
  categories ||--o{ products : contains
  categories ||--o{ categories : parent
  products ||--|| inventories : tracks
```

> Product 가 Phase 4 에서 `product.updated`/`stock.reservation.result` 발행 + `order.created`/`payment.*` 소비를 하므로 `outbox_events`/`processed_events` 를 소유하고, `inventories` 에 예약 컬럼(available/reserved)을 도입한다 (see ADR-0012 §D1/§D3). DDL 은 구현 ②.

### Order DB

> `order_items`의 `product_name`, `unit_price`는 주문 시점 스냅샷으로 저장 (상품 가격 변경 대응)
`cart_items`는 최신 상품 정보를 반영해야 하므로 스냅샷 컬럼을 포함하지 않습니다. 장바구니 조회 시에는 CQRS 로컬 캐시(섹션 9-13)에서 최신 상품 정보를 조합합니다.
>

```mermaid
erDiagram
  carts {
    bigint id PK
    bigint user_id
    timestamp created_at
  }
  cart_items {
    bigint id PK
    bigint cart_id FK
    bigint product_id
    int quantity
  }
  orders {
    bigint id PK
    bigint user_id
    string order_number UK
    bigint total_amount
    string status
    string receiver_name
    string phone
    string zipcode
    string address
    timestamp ordered_at
  }
  order_items {
    bigint id PK
    bigint order_id FK
    bigint product_id
    string product_name
    int quantity
    bigint unit_price
  }
  outbox_events {
    bigint id PK
    string aggregate_type
    string aggregate_id
    string event_type
    string event_id UK
    string payload
    string status
    int retry_count
    timestamp last_attempted_at
    timestamp created_at
    timestamp published_at
  }
  processed_events {
    bigint id PK
    string event_id "UK(event_id, consumer_group)"
    string consumer_group
    timestamp processed_at
  }
  carts ||--o{ cart_items : contains
  orders ||--o{ order_items : contains
```

### Payment DB

```mermaid
erDiagram
  payments {
    bigint id PK
    bigint order_id
    string order_number
    string payment_key UK
    bigint amount
    string status
    string method
    timestamp approved_at
    timestamp created_at
  }
  payment_failures {
    bigint id PK
    bigint payment_id FK
    string reason
    string raw_response
    timestamp failed_at
  }
  outbox_events {
    bigint id PK
    string aggregate_type
    string aggregate_id
    string event_type
    string event_id UK
    string payload
    string status
    int retry_count
    timestamp last_attempted_at
    timestamp created_at
    timestamp published_at
  }
  processed_events {
    bigint id PK
    string event_id "UK(event_id, consumer_group)"
    string consumer_group
    timestamp processed_at
  }
  webhook_logs {
    bigint id PK
    string payment_key
    string event_type
    string idempotency_key UK
    string payload
    string status
    timestamp received_at
  }
  payments ||--o{ payment_failures : logs
```

### Notification DB

> Notification Service 가 DB 를 소유함을 ADR-0010 §D1 (F1) 에서 확정 — `02-architecture.md §5` DataLayer 와 정합 (see ADR-0010).

```mermaid
erDiagram
  notifications {
    bigint id PK
    bigint user_id
    string type
    string message
    boolean is_read
    timestamp created_at
  }
  processed_events {
    bigint id PK
    string event_id "UK(event_id, consumer_group)"
    string consumer_group
    timestamp processed_at
  }
```

### 인덱스 전략

| 테이블 | 인덱스 | 용도 |
| --- | --- | --- |
| `orders` | `idx_orders_user_id_status (user_id, status)` | 사용자별 주문 내역 조회 (상태 필터) |
| `orders` | `idx_orders_status_ordered_at (status, ordered_at)` | 타임아웃 스케줄러 조회 (PAYMENT_REQUESTED + 시간 조건) |
| `order_items` | `idx_order_items_order_id (order_id)` | 주문별 상품 목록 조회 |
| `products` | `idx_products_category_status (category_id, status)` | 카테고리별 상품 목록 조회 |
| `outbox_events` | `idx_outbox_status_created (status, created_at)` | Polling 스케줄러 대상 조회 (PENDING 상태) |
| `outbox_events` | `trace_id` / `user_id` 컬럼은 인덱스 없음 | trace 기반 조회는 사후 ad-hoc 분석용 — 인덱스 추가 시 insert/update 비용만 증가 (see ADR-0008) |
| `processed_events` | `uk_processed_event_consumer (event_id, consumer_group)` | 멱등성 체크 (중복 소비 방지, 복합 UK) |
| `notifications` | `idx_notifications_user_id (user_id)` | 사용자별 알림 목록 조회 |
| `refresh_tokens` | `idx_refresh_tokens_user_id (user_id)` | 사용자별 토큰 조회/삭제 |

### ERD Phase 1 vs Phase 4 비교

| 항목 | Phase 1 | Phase 4 |
| --- | --- | --- |
| DB 수 | 1개 (통합) | 5개 (서비스별 분리) |
| FK 제약 | DB 레벨 FK 사용 | FK 제약 없음 (이벤트 참조) |
| 상품 정보 저장 | `product_id` FK로 조인 | 주문 시점 스냅샷 저장 |
| 결제-주문 연결 | `order_id` FK | `order_number` 이벤트 참조 |
| 데이터 정합성 | DB 트랜잭션 보장 | Saga 패턴으로 보장 |
| 이벤트 유실 방지 | 해당 없음 (로컬 이벤트) | 서비스별 DB Outbox 테이블 |
| 멱등성 처리 | 해당 없음 (로컬 이벤트) | 서비스별 DB processed_events 테이블 |
| 웹훅 중복 처리 | webhook_logs 테이블 | Payment DB webhook_logs 테이블 |
