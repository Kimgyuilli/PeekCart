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
 * order-service 의 DLQ quarantine 경로.
 *
 * <p><b>왜 별도 listener 인가</b>: consumer group 헤더를 판독하지 못한 레코드는 소유자를 group 으로
 * 가릴 수 없다. 4서비스가 전부 저장하면 중복이고 전부 skip 하면 유실이라, 소유자를 하나로 못박아야 한다.
 * → <b>원본 토픽을 발행하는 서비스</b>가 소유자다(ADR-0011 producer-owns-topic — 이미 그 {@code .dlq} 의
 * {@code NewTopic} 을 선언하는 서비스다).
 *
 * <p>그런데 <b>발행 서비스는 자기 토픽을 소비하지 않으므로 자기 {@code .dlq} 도 구독하지 않는다</b> —
 * {@link DeadLetterConsumer} 의 구독 목록에 {@code order.created.dlq} 가 없는 이유다. 그래서 소유자가
 * 그 레코드를 보려면 이 전용 구독이 필요하다.
 *
 * <p>group 이 <b>판독된</b> 레코드는 여기서 skip 한다 — 그건 소비 경로 소관이고, 두 listener 가
 * 같은 레코드를 적재하면 중복이 된다. 두 소유권 집합이 교차하지 않음은 계약 테스트가 강제한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterQuarantineConsumer {

    private static final PeekcartService SELF = PeekcartService.ORDER;

    private final DeadLetterRecorder recorder;

    @KafkaListener(
            id = DeadLetterContainerGuard.LISTENER_ID_PREFIX + "order-quarantine",
            topics = {
                    "order.created.dlq",
                    "order.cancelled.dlq",
                    "order.compensation.requested.dlq"
            },
            groupId = DlqTopology.ORDER_DLQ_QUARANTINE_GROUP,
            containerFactory = "deadLetterKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        DlqOrigin origin = DlqHeaders.parse(record);

        if (!DlqTopology.ownsQuarantine(SELF, record.topic(), origin.failedConsumerGroup())) {
            log.debug("quarantine 대상 아님 — skip. topic={}, group={}",
                    record.topic(), origin.failedConsumerGroup());
            return;
        }

        recorder.record(origin);
    }
}
