package com.peekcart.product.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.global.outbox.dto.ApprovalRecoveryExecutePayload;
import com.peekcart.global.outbox.dto.ApprovalRecoveryRequestPayload;
import com.peekcart.product.application.StockReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ApprovalRecoveryConsumer {
    private static final String REQUEST_GROUP = "product-svc-approval-recovery-request-group";
    private static final String EXECUTE_GROUP = "product-svc-approval-recovery-execute-group";
    private final StockReservationService service;
    private final IdempotencyChecker idempotencyChecker;
    private final KafkaMessageParser parser;
    private final ObjectMapper mapper;

    @KafkaListener(topics = "payment.approval-recovery.requested", groupId = REQUEST_GROUP)
    @Transactional
    public void snapshot(String message) {
        var root = parser.parse(message);
        var payload = mapper.convertValue(root.get("payload"), ApprovalRecoveryRequestPayload.class);
        idempotencyChecker.executeIfNew(root.get("eventId").asText(), REQUEST_GROUP,
                () -> service.snapshotForRecovery(payload));
    }

    @KafkaListener(topics = "payment.approval-recovery.execute", groupId = EXECUTE_GROUP)
    @Transactional
    public void execute(String message) {
        var root = parser.parse(message);
        var payload = mapper.convertValue(root.get("payload"), ApprovalRecoveryExecutePayload.class);
        idempotencyChecker.executeIfNew(root.get("eventId").asText(), EXECUTE_GROUP,
                () -> service.executeRecovery(payload));
    }
}
