package com.peekcart.payment.infrastructure.toss;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.payment.domain.model.PaymentApproval;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 서버 직접 PG 조회만 신뢰한다. 요청의 업로드/상태 주장은 받지 않는다. */
@Component
@RequiredArgsConstructor
public class ApprovalRecoveryEvidenceVerifier {
    private final TossPaymentClient client;
    private final ObjectMapper mapper;

    public Optional<Evidence> verify(PaymentApproval approval) {
        return client.find(approval.getPaymentKey()).flatMap(snapshot -> {
            try {
                JsonNode body = mapper.readTree(snapshot.rawResponse());
                if (!TossPaymentIdentity.matches(approval, body)) {
                    return Optional.empty();
                }
                String status = body.path("status").asText();
                if ("DONE".equals(status)) return Optional.of(new Evidence(status, snapshot.rawResponse()));
                if (!body.path("balanceAmount").isIntegralNumber() || !body.path("balanceAmount").canConvertToLong() || body.path("balanceAmount").longValue() != 0) {
                    return Optional.empty();
                }
                if ("ABORTED".equals(status) || "EXPIRED".equals(status)) {
                    if (snapshot.canceledAmount() != 0) return Optional.empty();
                    JsonNode cancels = body.path("cancels");
                    if (!cancels.isMissingNode() && !cancels.isNull()) {
                        if (!cancels.isArray()) return Optional.empty();
                        for (JsonNode cancel : cancels) {
                            JsonNode amount = cancel.path("cancelAmount");
                            if (!amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() != 0) return Optional.empty();
                        }
                    }
                    return Optional.of(new Evidence(status, snapshot.rawResponse()));
                }
                if ("CANCELED".equals(status) && snapshot.isFullyCanceled(approval.getAmount())) {
                    JsonNode cancels = body.path("cancels");
                    if (!cancels.isArray() || cancels.isEmpty()) return Optional.empty();
                    long total = 0;
                    for (JsonNode cancel : cancels) {
                        JsonNode amount = cancel.path("cancelAmount");
                        if (!amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() < 0) return Optional.empty();
                        total = Math.addExact(total, amount.longValue());
                    }
                    if (total != approval.getAmount()) return Optional.empty();
                    return Optional.of(new Evidence(status, snapshot.rawResponse()));
                }
                return Optional.empty();
            } catch (Exception invalidEvidence) {
                return Optional.empty();
            }
        });
    }

    /** 이미 엄격히 검증한 DONE 원문만 사용한다. 두 번째 조회 결과로 실패 해제하지 않는다. */
    public com.peekcart.payment.application.ApprovalOutcome succeeded(Evidence evidence) {
        if (!evidence.charged()) throw new IllegalArgumentException("Validated DONE evidence required");
        var confirmed = client.parseConfirmed(evidence.rawResponse());
        java.time.LocalDateTime approvedAt;
        try { approvedAt = java.time.OffsetDateTime.parse(confirmed.approvedAt()).toLocalDateTime(); }
        catch (Exception invalidTimestamp) { approvedAt = java.time.LocalDateTime.now(); }
        return com.peekcart.payment.application.ApprovalOutcome.succeeded(
                confirmed.method(), approvedAt, evidence.rawResponse());
    }

    public record Evidence(String status, String rawResponse) {
        public boolean charged() { return "DONE".equals(status); }
        public boolean refunded() { return "CANCELED".equals(status); }
    }
}
