package com.peekcart.global.kafka;

/**
 * "이 listener 가 이 토픽을 이 group 으로 구독한다" 는 한 줄 (readiness 판정용).
 *
 * <p>{@link DlqSubscription} 과 의도적으로 다른 타입이다 — 그쪽의 {@code consumerGroup} 은
 * <b>실패한 원본</b> group 이고 토픽은 {@code .dlq} 다. 여기는 <b>구독 중인 listener</b> 의
 * group 과 실제 구독 토픽이다. 같은 record 로 뭉뚱그리면 readiness 가 무엇을 확인하는지
 * 읽는 쪽에서 구별할 수 없다.
 *
 * @param topic         구독 토픽
 * @param consumerGroup 그 토픽을 구독하는 listener 의 group
 */
public record TopicGroup(String topic, String consumerGroup) {

    public TopicGroup {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic 은 필수입니다");
        }
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException("consumerGroup 은 필수입니다");
        }
    }
}
