package com.peekcart.global.deadletter;

import com.peekcart.global.kafka.DlqHeaders;
import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.DlqTopology;
import com.peekcart.global.kafka.PeekcartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * payment-service 의 DLQ 소비 경로.
 *
 * <p><b>자기 group 의 실패분만 적재한다.</b> DLQ 토픽은 공유라 한 {@code .dlq} 에 여러 서비스의
 * 실패가 함께 쌓인다. 소유권을 가르지 않으면 한 사건이 원장 여러 행이 되거나(중복) 아무도 안 쓴다(유실).
 *
 * <p>구독 토픽과 소유 group 의 정본은 {@link DlqTopology} 다 — 여기 하드코딩된 목록이 그 매핑과
 * 어긋나면 계약 테스트가 실패한다.
 *
 * <p>group 헤더를 판독하지 못한 레코드는 여기서 처리하지 않는다 — 원본 토픽 발행 서비스의
 * quarantine listener 소관이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterConsumer {

    private static final PeekcartService SELF = PeekcartService.PAYMENT;

    private final DeadLetterRecorder recorder;

    @KafkaListener(
            id = DeadLetterContainerGuard.LISTENER_ID_PREFIX + "payment-consumption",
            topics = {
                    "order.created.dlq",
                    "order.cancelled.dlq",
                    "stock.reservation.result.dlq",
                    "stock.compensation.requested.dlq",
                    "order.compensation.requested.dlq"
            },
            groupId = DlqTopology.PAYMENT_DLQ_GROUP,
            containerFactory = "deadLetterKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        DlqOrigin origin = DlqHeaders.parse(record);

        if (!DlqTopology.ownsConsumption(SELF, record.topic(), origin.failedConsumerGroup())) {
            log.debug("DLQ 소유자 아님 — skip. topic={}, group={}", record.topic(), origin.failedConsumerGroup());
            return;
        }

        recorder.record(origin);
    }
}
