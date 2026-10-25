package com.peekcart.payment.infrastructure.toss;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.peekcart.payment.application.ApprovalOutcome;
import com.peekcart.payment.application.RefundOutcome;
import com.peekcart.payment.application.RefundProperties;
import com.peekcart.payment.domain.model.PaymentApproval;
import com.peekcart.payment.domain.model.PaymentRefund;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Toss 테스트 환경 실응답 계약 (D-064).
 *
 * <p>fixture 는 2026-10-26 테스트 환경 실호출(결제 1건, 10,000원)의 응답 원문이다. 식별값(결제 키, 주문 ID, 상점 ID, 거래 키,
 * 카드 번호 일부, 주소, secret)만 {@code scripts/toss-sandbox/sandbox.py fixtures} 가 가짜 값으로 바꿨고 구조와 판정 필드는 그대로다.
 * 합성 본문으로 분류를 고정하는 {@link TossPaymentClientTest} 와 달리, 실제 본문이 클라이언트와 두 실행기를 지나 원장이 내릴 결론에
 * 닿는지 본다. 각 성공 경우에는 실제 필드를 바꾸면 결론이 바뀌는 경우를 짝으로 둔다.
 */
@DisplayName("Toss 테스트 환경 실응답 계약 (D-064)")
class TossSandboxResponseTest {

    private static final String BASE_URL = "https://api.tosspayments.com/v1";
    private static final String PAYMENT_KEY = "tsandbox_fixture_payment_key";
    private static final String PG_ORDER_ID = "pc_fixture_order_0001";
    private static final long AMOUNT = 10_000L;
    private static final String CONFIRM_URL = BASE_URL + "/payments/confirm";
    private static final String FIND_URL = BASE_URL + "/payments/" + PAYMENT_KEY;
    private static final String CANCEL_URL = FIND_URL + "/cancel";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockRestServiceServer server;
    private TossPaymentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentClient("test-secret", BASE_URL, builder, MAPPER);
    }

    // ---- 승인 ------------------------------------------------------------------

    @Test
    @DisplayName("승인 성공 본문: 식별자 3개가 원장과 맞고 approvedAt 을 대체 없이 읽어 SUCCEEDED 로 닫는다")
    void confirmDone_closesWithParsedFields() {
        server.expect(requestTo(CONFIRM_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "approve-1"))
                .andRespond(response("confirm-done"));

        ApprovalOutcome outcome = approvalExecutor().execute(approval()).outcome();

        assertThat(outcome.kind()).isEqualTo(ApprovalOutcome.Kind.SUCCEEDED);
        assertThat(outcome.method()).isEqualTo("간편결제");
        // 파싱이 실패하면 현재 시각으로 대체된다. 정확한 값이면 실제 형식(+09:00 오프셋)을 읽은 것이다.
        assertThat(outcome.approvedAt()).isEqualTo(LocalDateTime.of(2026, 10, 26, 1, 37, 13));
        server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"paymentKey", "orderId", "totalAmount"})
    @DisplayName("승인 성공 본문의 식별자를 하나라도 바꾸면 확정하지 않는다")
    void confirmDone_identityChanged_isUnresolved(String field) {
        ObjectNode body = (ObjectNode) body("confirm-done");
        if ("totalAmount".equals(field)) {
            body.put(field, AMOUNT + 1);
        } else {
            body.put(field, body.get(field).asText() + "-other");
        }
        server.expect(requestTo(CONFIRM_URL)).andRespond(response(200, body.toString()));

        assertThat(approvalExecutor().execute(approval()).outcome().kind()).isEqualTo(ApprovalOutcome.Kind.UNRESOLVED);
    }

    @Test
    @DisplayName("같은 멱등키 재승인: Toss 는 ALREADY_PROCESSED 가 아니라 첫 응답(200)을 다시 주고, 같은 결론으로 닫힌다")
    void confirmSameKey_replaysFirstResponse() {
        assertThat(status("confirm-same-key")).isEqualTo(200);
        assertThat(body("confirm-same-key").path("status").asText()).isEqualTo("DONE");
        server.expect(requestTo(CONFIRM_URL)).andRespond(response("confirm-same-key"));

        assertThat(approvalExecutor().execute(approval()).outcome().kind()).isEqualTo(ApprovalOutcome.Kind.SUCCEEDED);
    }

    @Test
    @DisplayName("다른 멱등키 재승인: 400 ALREADY_PROCESSED_PAYMENT 는 별도 분류다")
    void confirmOtherKey_isAlreadyProcessed() {
        server.expect(requestTo(CONFIRM_URL)).andRespond(response("confirm-other-key"));

        assertThat(client.confirm(PAYMENT_KEY, PG_ORDER_ID, AMOUNT, "approve-1").kind())
                .isEqualTo(TossOutcome.Kind.ALREADY_PROCESSED);
    }

    @Test
    @DisplayName("다른 멱등키 재승인 본문의 code 를 바꾸면 영구 실패로 분류된다")
    void confirmOtherKey_codeChanged_isPermanentFailure() {
        ObjectNode body = (ObjectNode) body("confirm-other-key");
        body.put("code", "SOME_OTHER_ERROR");
        server.expect(requestTo(CONFIRM_URL)).andRespond(response(400, body.toString()));

        assertThat(client.confirm(PAYMENT_KEY, PG_ORDER_ID, AMOUNT, "approve-1").kind())
                .isEqualTo(TossOutcome.Kind.PERMANENT_FAILURE);
    }

    @Test
    @DisplayName("다른 멱등키 재승인 뒤 실제 DONE 조회 본문으로 SUCCEEDED 를 확정한다")
    void confirmOtherKey_isResolvedByLookup() {
        server.expect(requestTo(CONFIRM_URL)).andRespond(response("confirm-other-key"));
        server.expect(requestTo(FIND_URL)).andExpect(method(HttpMethod.GET)).andRespond(response("lookup-done"));

        assertThat(approvalExecutor().execute(approval()).outcome().kind()).isEqualTo(ApprovalOutcome.Kind.SUCCEEDED);
        server.verify();
    }

    // ---- 취소 ------------------------------------------------------------------

    @Test
    @DisplayName("취소 성공 본문: SUCCEEDED 로 닫는다")
    void cancelDone_succeeds() {
        server.expect(requestTo(CANCEL_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "refund-1"))
                .andRespond(response("cancel-done"));

        assertThat(refundExecutor().execute(refund()).outcome().kind()).isEqualTo(RefundOutcome.Kind.SUCCEEDED);
        server.verify();
    }

    @Test
    @DisplayName("같은 멱등키 재취소: Toss 는 첫 응답(200)을 다시 주고 SUCCEEDED 로 닫힌다")
    void cancelSameKey_replaysFirstResponse() {
        assertThat(status("cancel-same-key")).isEqualTo(200);
        server.expect(requestTo(CANCEL_URL)).andRespond(response("cancel-same-key"));

        assertThat(refundExecutor().execute(refund()).outcome().kind()).isEqualTo(RefundOutcome.Kind.SUCCEEDED);
    }

    @Test
    @DisplayName("다른 멱등키 재취소: 400 ALREADY_CANCELED_PAYMENT 는 실제 CANCELED 조회 본문으로 전액 취소를 확인해 SUCCEEDED 가 된다")
    void cancelOtherKey_isResolvedByLookup() {
        server.expect(requestTo(CANCEL_URL)).andRespond(response("cancel-other-key"));
        server.expect(requestTo(FIND_URL)).andExpect(method(HttpMethod.GET)).andRespond(response("lookup-canceled"));

        assertThat(refundExecutor().execute(refund()).outcome().kind()).isEqualTo(RefundOutcome.Kind.SUCCEEDED);
        server.verify();
    }

    // ---- 조회 ------------------------------------------------------------------

    @Test
    @DisplayName("조회: DONE 본문은 취소 합계 0, CANCELED 본문은 전액 취소다")
    void lookup_readsStatusAndCancelSum() {
        server.expect(requestTo(FIND_URL)).andRespond(response("lookup-done"));
        server.expect(requestTo(FIND_URL)).andRespond(response("lookup-canceled"));

        Optional<TossPaymentSnapshot> done = client.find(PAYMENT_KEY);
        Optional<TossPaymentSnapshot> canceled = client.find(PAYMENT_KEY);

        assertThat(done).hasValueSatisfying(s -> {
            assertThat(s.status()).isEqualTo("DONE");
            assertThat(s.canceledAmount()).isZero();
        });
        assertThat(canceled).hasValueSatisfying(s -> {
            assertThat(s.status()).isEqualTo("CANCELED");
            assertThat(s.canceledAmount()).isEqualTo(AMOUNT);
            assertThat(s.isFullyCanceled(AMOUNT)).isTrue();
        });
    }

    @Test
    @DisplayName("CANCELED 조회 본문에서 cancels 를 비우면 전액 취소가 아니다")
    void lookupCanceled_withoutCancels_isNotFullyCanceled() {
        ObjectNode body = (ObjectNode) body("lookup-canceled");
        body.putArray("cancels");
        server.expect(requestTo(FIND_URL)).andRespond(response(200, body.toString()));

        assertThat(client.find(PAYMENT_KEY)).hasValueSatisfying(s -> assertThat(s.isFullyCanceled(AMOUNT)).isFalse());
    }

    // ---- fixture -----------------------------------------------------------------

    private static JsonNode fixture(String name) {
        try {
            String text = new ClassPathResource("toss-sandbox/" + name + ".json").getContentAsString(StandardCharsets.UTF_8);
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException("fixture 를 읽지 못했다: " + name, e);
        }
    }

    private static int status(String name) {
        return fixture(name).path("status").asInt();
    }

    private static JsonNode body(String name) {
        return fixture(name).path("body").deepCopy();
    }

    private static ResponseCreator response(String name) {
        JsonNode fixture = fixture(name);
        return response(fixture.path("status").asInt(), fixture.path("body").toString());
    }

    private static ResponseCreator response(int status, String body) {
        return withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private ApprovalExecutor approvalExecutor() {
        return new ApprovalExecutor(client, MAPPER);
    }

    private RefundExecutor refundExecutor() {
        RefundProperties properties = new RefundProperties();
        properties.setMaxAttempts(3);
        properties.setRetryBackoff(Duration.ofMillis(1));
        return new RefundExecutor(client, properties);
    }

    /** 실호출과 같은 식별자(정리된 가짜 값)를 가진 CLAIMED 원장. 주문 ID 는 PaymentFixture 의 1 이다. */
    private static PaymentApproval approval() {
        PaymentApproval approval = PaymentFixture.claimedApproval();
        ReflectionTestUtils.setField(approval, "paymentKey", PAYMENT_KEY);
        ReflectionTestUtils.setField(approval, "pgOrderId", PG_ORDER_ID);
        ReflectionTestUtils.setField(approval, "amount", AMOUNT);
        return approval;
    }

    private static PaymentRefund refund() {
        PaymentRefund refund = mock(PaymentRefund.class);
        given(refund.getOrderId()).willReturn(1L);
        given(refund.getPaymentKey()).willReturn(PAYMENT_KEY);
        given(refund.getAmount()).willReturn(AMOUNT);
        return refund;
    }
}
