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
 * order-service 의 DLQ 소비 경로.
 *
 * <p><b>자기 group 의 실패분만 적재한다.</b> DLQ 토픽은 공유라 {@code payment.completed.dlq} 에는
 * order·product·notification 세 서비스의 실패가 함께 쌓인다. 소유권을 가르지 않으면 한 사건이
 * 원장 3행이 되거나(중복) 아무도 안 쓴다(유실).
 *
 * <p>구독 토픽과 소유 group 의 정본은 {@link DlqTopology} 다 — 여기 하드코딩된 토픽 목록이
 * 그 매핑과 어긋나면 계약 테스트가 실패한다.
 *
 * <p>group 헤더를 판독하지 못한 레코드는 <b>여기서 처리하지 않는다</b> — 그건 원본 토픽 발행
 * 서비스의 quarantine listener 소관이다({@link DeadLetterQuarantineConsumer}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterConsumer {

    private static final PeekcartService SELF = PeekcartService.ORDER;

    private final DeadLetterRecorder recorder;

    @KafkaListener(
            id = DeadLetterContainerGuard.LISTENER_ID_PREFIX + "order-consumption",
            topics = {
                    "payment.requested.dlq",
                    "payment.completed.dlq",
                    "payment.failed.dlq",
                    "payment.refunded.dlq",
                    "stock.reservation.result.dlq",
                    "product.updated.dlq"
            },
            groupId = DlqTopology.ORDER_DLQ_GROUP,
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
