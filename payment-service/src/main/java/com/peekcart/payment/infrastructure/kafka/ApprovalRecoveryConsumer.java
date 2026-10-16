package com.peekcart.payment.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.global.outbox.dto.ApprovalRecoveryResultPayload;
import com.peekcart.global.outbox.dto.ApprovalRecoverySnapshotPayload;
import com.peekcart.payment.application.ApprovalRecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ApprovalRecoveryConsumer {
    private static final String SNAPSHOT_GROUP = "payment-svc-approval-recovery-snapshot-group";
    private static final String RESULT_GROUP = "payment-svc-approval-recovery-result-group";
    private final ApprovalRecoveryService service;
    private final IdempotencyChecker idempotencyChecker;
    private final KafkaMessageParser parser;
    private final ObjectMapper mapper;

    @KafkaListener(topics = "stock.approval-recovery.snapshot", groupId = SNAPSHOT_GROUP)
    @Transactional
    public void snapshot(String message) {
        var root = parser.parse(message);
        var payload = mapper.convertValue(root.get("payload"), ApprovalRecoverySnapshotPayload.class);
        idempotencyChecker.executeIfNew(root.get("eventId").asText(), SNAPSHOT_GROUP,
                () -> service.snapshot(payload));
    }
    @KafkaListener(topics = "stock.approval-recovery.result", groupId = RESULT_GROUP)
    @Transactional
    public void result(String message) {
        var root = parser.parse(message);
        var payload = mapper.convertValue(root.get("payload"), ApprovalRecoveryResultPayload.class);
        idempotencyChecker.executeIfNew(root.get("eventId").asText(), RESULT_GROUP,
                () -> service.result(payload));
    }
}
