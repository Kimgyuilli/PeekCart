package com.peekcart.global.cache;

/**
 * Redis 와 Pod 사본에 함께 담는 캐시 값과 그 값을 DB 에서 읽은 시각 (ADR-0046).
 *
 * <p>값의 나이를 "DB 에서 읽은 시각"으로 재야 재고의 soft·hard 판정과 장애 허용 범위가 맞다.
 * Redis 에서 읽은 값의 나이를 모르면, Pod 사본을 내는 시간이 Redis TTL 위에 더해진다.
 *
 * @param value          캐시한 메서드의 반환값
 * @param loadedAtMillis 로더를 시작한 시각(epoch ms). DB 읽기보다 이르므로 나이를 크게 잰다
 */
public record CacheEnvelope(Object value, long loadedAtMillis) {
}
