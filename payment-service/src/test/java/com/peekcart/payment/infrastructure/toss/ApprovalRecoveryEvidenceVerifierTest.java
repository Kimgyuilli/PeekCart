package com.peekcart.payment.infrastructure.toss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovalRecoveryEvidenceVerifierTest {
    private final TossPaymentClient client = mock(TossPaymentClient.class);
    private final ApprovalRecoveryEvidenceVerifier verifier = new ApprovalRecoveryEvidenceVerifier(client, new ObjectMapper());
    private final com.peekcart.payment.domain.model.PaymentApproval approval = PaymentFixture.claimedApproval();

    private void response(String status, long canceled, String key, String order, long amount, Long balance) {
        String body = "{\"status\":\"" + status + "\",\"paymentKey\":\"" + key
                + "\",\"orderId\":\"" + order + "\",\"totalAmount\":" + amount
                + (balance == null ? "" : ",\"balanceAmount\":" + balance)
                + ",\"cancels\":[{\"cancelAmount\":" + canceled + "}]}";
        when(client.find(approval.getPaymentKey())).thenReturn(Optional.of(new TossPaymentSnapshot(status, canceled, body)));
    }

    @Test
    void rejects404PartialCancellationAndUncertainStates() {
        when(client.find(approval.getPaymentKey())).thenReturn(Optional.empty());
        assertThat(verifier.verify(approval)).isEmpty();
        for (String status : new String[]{"PARTIAL_CANCELED", "READY", "IN_PROGRESS", "UNKNOWN"}) {
            response(status, 0, approval.getPaymentKey(), "1", approval.getAmount(), 0L);
            assertThat(verifier.verify(approval)).isEmpty();
        }
    }

    @Test
    void rejectsMismatchedIdentityAmountAndMissingBalance() {
        response("ABORTED", 0, "other", "1", approval.getAmount(), 0L);
        assertThat(verifier.verify(approval)).isEmpty();
        response("ABORTED", 0, approval.getPaymentKey(), "2", approval.getAmount(), 0L);
        assertThat(verifier.verify(approval)).isEmpty();
        response("ABORTED", 0, approval.getPaymentKey(), "1", approval.getAmount() + 1, 0L);
        assertThat(verifier.verify(approval)).isEmpty();
        response("ABORTED", 0, approval.getPaymentKey(), "1", approval.getAmount(), null);
        assertThat(verifier.verify(approval)).isEmpty();
        response("ABORTED", 0, approval.getPaymentKey(), "1", approval.getAmount(), 1L);
        assertThat(verifier.verify(approval)).isEmpty();
    }

    @Test
    void distinguishesChargedAndProvenNegative() {
        response("DONE", 0, approval.getPaymentKey(), "1", approval.getAmount(), approval.getAmount());
        assertThat(verifier.verify(approval).orElseThrow().charged()).isTrue();
        response("ABORTED", 0, approval.getPaymentKey(), "1", approval.getAmount(), 0L);
        assertThat(verifier.verify(approval).orElseThrow().charged()).isFalse();
        response("CANCELED", approval.getAmount() - 1, approval.getPaymentKey(), "1", approval.getAmount(), 0L);
        assertThat(verifier.verify(approval)).isEmpty();
        response("CANCELED", approval.getAmount(), approval.getPaymentKey(), "1", approval.getAmount(), 0L);
        assertThat(verifier.verify(approval).orElseThrow().refunded()).isTrue();
    }
}
