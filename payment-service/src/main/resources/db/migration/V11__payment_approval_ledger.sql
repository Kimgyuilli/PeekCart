-- D-020: 승인 원장 (계획 P1, ADR-0023 D2)
--
-- 승인의 진행 상태를 소유하고, order_id UNIQUE 로 "동일 논리 승인 1건" fence 를 만든다.
-- payments.status 는 승인의 *결과*(APPROVED/FAILED)만 갖는다 — "PG 를 불렀는지 모르는" 중간 상태를
-- 담을 곳이 없어서 D-020 의 과금-고아가 관측조차 되지 않았다.
--
-- payment_key 를 여기에 복제해 두는 것이 이 테이블의 핵심 역할이다(ADR-0023 C2):
-- payments.payment_key 는 승인 트랜잭션이 롤백되면 생성 시 UUID placeholder 로 되돌아가지만,
-- 원장 행은 T1 에서 독립 커밋되므로 어떤 실패 경로에서도 PG 에 진실을 물을 키가 남는다.
CREATE TABLE payment_approvals (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    order_id          BIGINT       NOT NULL,
    payment_key       VARCHAR(255) NOT NULL,   -- payments.payment_key 와 동일 폭(좁으면 잘린 키로 조회한다)
    user_id           BIGINT       NOT NULL,
    amount            BIGINT       NOT NULL,
    status            VARCHAR(20)  NOT NULL,   -- CLAIMED / SUCCEEDED / FAILED / UNRESOLVED
    attempts          INT          NOT NULL DEFAULT 0,
    generation        BIGINT       NOT NULL DEFAULT 0,   -- fencing token — claim 마다 증가
    claimed_at        DATETIME(6)  NULL,       -- lease 기준. NULL = 웹훅 nudge(즉시 순회 대상, D7)
    last_error        VARCHAR(500) NULL,
    pg_response       TEXT         NULL,
    failure_code      VARCHAR(100) NULL,
    requested_at      DATETIME(6)  NOT NULL,
    resolved_at       DATETIME(6)  NULL,
    resolved_by       VARCHAR(100) NULL,
    resolution_reason VARCHAR(500) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_approvals_order UNIQUE (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- reconciliation 후보(lease 만료 CLAIMED · UNRESOLVED) 조회 지원.
CREATE INDEX idx_payment_approvals_status_claimed ON payment_approvals (status, claimed_at);

-- backlog 건수 / 최장 age 게이지(ADR-0023 Consequences) 지원.
CREATE INDEX idx_payment_approvals_status_requested ON payment_approvals (status, requested_at);

-- 웹훅 nudge 는 paymentKey 로 원장을 찾는다(D7) — orderId 를 모르는 유일한 진입점이다.
CREATE INDEX idx_payment_approvals_payment_key ON payment_approvals (payment_key);
