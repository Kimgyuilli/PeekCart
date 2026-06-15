-- ============================================================
-- Phase 1 초기 스키마 (모놀리식, 단일 DB)
-- ============================================================

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
);

CREATE TABLE refresh_tokens (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    token      VARCHAR(512) NOT NULL,
    expires_at DATETIME(6)  NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_token (token),
    KEY idx_refresh_tokens_user_id (user_id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE addresses (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    receiver_name VARCHAR(100) NOT NULL,
    phone         VARCHAR(20)  NOT NULL,
    zipcode       VARCHAR(10)  NOT NULL,
    address       VARCHAR(500) NOT NULL,
    is_default    BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    CONSTRAINT fk_addresses_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE categories (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    name      VARCHAR(100) NOT NULL,
    parent_id BIGINT       NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id)
);

CREATE TABLE products (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    category_id BIGINT       NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description TEXT         NULL,
    price       BIGINT       NOT NULL,
    image_url   VARCHAR(500) NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_products_category_status (category_id, status),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id)
);

CREATE TABLE inventories (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    product_id BIGINT      NOT NULL,
    stock      INT         NOT NULL,
    version    INT         NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventories_product_id (product_id),
    CONSTRAINT fk_inventories_product FOREIGN KEY (product_id) REFERENCES products (id)
);

CREATE TABLE carts (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_carts_user_id (user_id),
    CONSTRAINT fk_carts_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE cart_items (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    cart_id    BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity   INT    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_cart_items_cart    FOREIGN KEY (cart_id)    REFERENCES carts (id),
    CONSTRAINT fk_cart_items_product FOREIGN KEY (product_id) REFERENCES products (id)
);

CREATE TABLE orders (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    order_number  VARCHAR(50)  NOT NULL,
    total_amount  BIGINT       NOT NULL,
    status        VARCHAR(30)  NOT NULL,
    receiver_name VARCHAR(100) NOT NULL,
    phone         VARCHAR(20)  NOT NULL,
    zipcode       VARCHAR(10)  NOT NULL,
    address       VARCHAR(500) NOT NULL,
    ordered_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_order_number (order_number),
    KEY idx_orders_user_id_status (user_id, status),
    KEY idx_orders_status_ordered_at (status, ordered_at),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE order_items (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    order_id   BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity   INT    NOT NULL,
    unit_price BIGINT NOT NULL COMMENT '주문 시점 가격 스냅샷',
    PRIMARY KEY (id),
    KEY idx_order_items_order_id (order_id),
    CONSTRAINT fk_order_items_order   FOREIGN KEY (order_id)   REFERENCES orders (id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products (id)
);

CREATE TABLE payments (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    BIGINT       NOT NULL,
    payment_key VARCHAR(255) NOT NULL,
    amount      BIGINT       NOT NULL,
    status      VARCHAR(30)  NOT NULL,
    method      VARCHAR(30)  NULL,
    approved_at DATETIME(6)  NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payments_payment_key (payment_key),
    UNIQUE KEY uk_payments_order_id (order_id),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE TABLE webhook_logs (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    payment_key      VARCHAR(255) NOT NULL,
    event_type       VARCHAR(50)  NOT NULL,
    idempotency_key  VARCHAR(255) NOT NULL,
    payload          TEXT         NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    received_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_webhook_logs_idempotency_key (idempotency_key)
);

CREATE TABLE notifications (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    type       VARCHAR(50)  NOT NULL,
    message    VARCHAR(500) NOT NULL,
    is_read    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notifications_user_id (user_id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id)
);
