-- D-078 B: outbox 발행 대기 원자료. order·product 스키마 **각각**에서 실행한다(같은 테이블 구조).
-- A 의 왕복 지연을 구간으로 나누는 입력이다 — 왕복에는 order 와 product 의 폴링 대기가 한 번씩 들어 있다.
--
-- 적용 예(product):
--   kubectl -n peekcart exec -i deploy/mysql -- \
--     mysql -B -upeekcart_product -ppeekcart_product peekcart_product < d078-outbox-latency.sql > <run>-outbox-product.tsv
--
-- 미발행(PENDING)·실패(FAILED) 행은 published_at 이 NULL 이다. 집계 쪽에서 제외 건수로 센다.
-- ADR-0049 부터 published_at 은 broker ack 시각이 아니라 poller 가 사이클 끝에 PUBLISHED 를 기록한 시각이다.
-- 발행 대기가 한 사이클 이하로 길게 잡히므로, 그 이전 세션 값과 비교할 때 측정 조건 차이로 적는다.
SELECT event_type,
       status,
       TIMESTAMPDIFF(MICROSECOND, created_at, published_at) AS publish_wait_us
FROM outbox_events
ORDER BY id;
