package com.peekcart.global.deadletter;

import com.peekcart.global.port.SlackPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * DLQ listener 의 <b>최종 실패 처분</b> (계획 ④-c-2a §2.6-C · P8).
 *
 * <p>"DB 장애에서 offset 미커밋" 과 "poison record 가 파티션을 무기한 막지 않음" 은
 * durable 대체 저장소 없이 <b>동시에 만족할 수 없다</b>. 본 구현은 <b>무유실</b>을 택한다:
 * 재시도가 소진되면 offset 을 커밋하지 않은 채 컨테이너를 <b>정지</b>하고 사람을 부른다.
 *
 * <p>정지가 없으면 같은 레코드를 무한 재시도하며 로그만 쌓이고, 커밋하면 원장에 못 쓴 DLQ 레코드가
 * 영구 유실된다. 둘 다 §1 부정형 3번에 걸린다.
 *
 * <p>DB 가 죽었다면 이 서비스의 DLQ 적재는 전부 불가능하므로 <b>DLQ 계열 컨테이너 전체</b>를 세운다.
 * 재기동 절차는 runbook §DLQ listener 정지 복구.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterContainerGuard {

    /** DLQ 계열 listener id 접두사. 이 접두사를 가진 컨테이너만 정지 대상이다. */
    public static final String LISTENER_ID_PREFIX = "dlq-";

    private final ObjectProvider<KafkaListenerEndpointRegistry> registryProvider;
    private final SlackPort slackPort;

    /**
     * 재시도 소진 시 호출된다. {@code ackAfterHandle=false} 와 함께 쓰이므로 offset 은 커밋되지 않는다.
     * 정지된 컨테이너는 재기동 시 같은 offset 부터 다시 읽는다.
     */
    public void onRetriesExhausted(ConsumerRecord<?, ?> record, Exception exception) {
        log.error("DLQ listener 재시도 소진 — 컨테이너를 정지한다. topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset(), exception);

        int stopped = stopDeadLetterContainers();

        try {
            slackPort.send(String.format(
                    "[DLQ listener 정지] 원장 적재가 반복 실패해 DLQ 컨테이너 %d개를 정지했습니다.%n"
                            + "topic=%s, partition=%d, offset=%d, exception=%s%n"
                            + "offset 은 커밋되지 않았습니다(무유실). 조치: docs/runbooks/dlq-recovery.md",
                    stopped, record.topic(), record.partition(), record.offset(),
                    exception.getClass().getSimpleName()));
        } catch (Exception e) {
            log.warn("DLQ 컨테이너 정지 알림 발송 실패", e);
        }
    }

    private int stopDeadLetterContainers() {
        KafkaListenerEndpointRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            log.warn("KafkaListenerEndpointRegistry 를 찾지 못해 컨테이너를 정지하지 못했다");
            return 0;
        }
        int stopped = 0;
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            String id = container.getListenerId();
            if (id != null && id.startsWith(LISTENER_ID_PREFIX) && container.isRunning()) {
                container.stop();
                stopped++;
            }
        }
        return stopped;
    }
}
