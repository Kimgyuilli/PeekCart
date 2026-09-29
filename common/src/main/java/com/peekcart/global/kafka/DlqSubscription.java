package com.peekcart.global.kafka;

/**
 * "이 서비스가 이 {@code .dlq} 의 이 consumer group 실패분을 소유한다" 는 한 줄.
 *
 * @param dlqTopic      DLQ 토픽 이름 ({@code <원본토픽>.dlq})
 * @param consumerGroup 실패한 <b>원본</b> consumer group. DLQ listener 자신의 group 이 아니다
 */
public record DlqSubscription(String dlqTopic, String consumerGroup) {

    public DlqSubscription {
        if (dlqTopic == null || dlqTopic.isBlank()) {
            throw new IllegalArgumentException("dlqTopic 은 필수입니다");
        }
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException("consumerGroup 은 필수입니다");
        }
    }

    /** 원본 토픽 이름 (`.dlq` 접미사 제거). */
    public String originTopic() {
        return dlqTopic.substring(0, dlqTopic.length() - DlqTopology.DLQ_SUFFIX.length());
    }
}
