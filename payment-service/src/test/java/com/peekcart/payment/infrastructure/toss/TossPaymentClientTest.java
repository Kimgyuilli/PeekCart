package com.peekcart.payment.infrastructure.toss;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Toss 취소/조회 계약 테스트 (계획 P5).
 *
 * <p>실 API 는 승인된 실거래가 있어야 호출할 수 있어 검증 불가다(ADR-0018 Consequences).
 * 여기서 고정하는 것은 <b>우리 쪽 계약</b> — 오류 분류와 멱등키 전송이다.
 */
@DisplayName("TossPaymentClient 승인/취소/조회 계약")
class TossPaymentClientTest {

    private static final String PAYMENT_KEY = "toss-key-1";
    private static final String BASE_URL = "https://api.tosspayments.com/v1";
    private static final String CANCEL_URL = BASE_URL + "/payments/toss-key-1/cancel";
    private static final String FIND_URL = BASE_URL + "/payments/toss-key-1";
    private static final String CONFIRM_URL = BASE_URL + "/payments/confirm";

    private MockRestServiceServer server;
    private TossPaymentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentClient("test-secret", BASE_URL, builder, new ObjectMapper());
    }

    // ---- 승인(confirm) 분류 — D-020/ADR-0023 D3 --------------------------------
    // 이 표면에 경계 테스트가 없어서 CI e2e 에서야 드러났다. execute() 를 mock 으로 덮은
    // ApprovalExecutorTest 는 **분류 자체**를 지나친다 — 분류는 여기서 고정한다.

    @Test
    @DisplayName("승인 2xx → SUCCEEDED, 그리고 Idempotency-Key 헤더가 전송된다")
    void confirmSuccess_sendsIdempotencyKey() {
        server.expect(requestTo(CONFIRM_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "approve-7"))
                .andRespond(withSuccess("{\"status\":\"DONE\",\"method\":\"카드\"}",
                        MediaType.APPLICATION_JSON));

        TossOutcome outcome = client.confirm(PAYMENT_KEY, "7", 10_000L, "approve-7");

        assertThat(outcome.kind()).isEqualTo(TossOutcome.Kind.SUCCEEDED);
        server.verify();
    }

    @Test
    @DisplayName("승인 4xx → PERMANENT_FAILURE — 카드사 거절은 재시도해도 바뀌지 않는다")
    void confirmClientError_isPermanent() {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"code\":\"REJECT_CARD_COMPANY\"}").contentType(MediaType.APPLICATION_JSON));

        TossOutcome outcome = client.confirm(PAYMENT_KEY, "7", 10_000L, "approve-7");

        assertThat(outcome.kind()).isEqualTo(TossOutcome.Kind.PERMANENT_FAILURE);
        assertThat(outcome.code()).isEqualTo("REJECT_CARD_COMPANY");
    }

    @Test
    @DisplayName("승인 5xx → TRANSIENT — 과금이 성립했는지 모르므로 실패로 확정하지 않는다")
    void confirmServerError_isTransient() {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("{\"code\":\"SERVER_ERROR\"}").contentType(MediaType.APPLICATION_JSON));

        assertThat(client.confirm(PAYMENT_KEY, "7", 10_000L, "approve-7").kind())
                .isEqualTo(TossOutcome.Kind.TRANSIENT);
    }

    @Test
    @DisplayName("승인 ALREADY_PROCESSED_PAYMENT 는 실패가 아니라 별도 분류다 (조회로 가른다)")
    void confirmAlreadyProcessed_isSeparateKind() {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"code\":\"ALREADY_PROCESSED_PAYMENT\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThat(client.confirm(PAYMENT_KEY, "7", 10_000L, "approve-7").kind())
                .isEqualTo(TossOutcome.Kind.ALREADY_PROCESSED);
    }

    @Test
    @DisplayName("[SAGA-P1-BASEURL] base-url 을 바꾸면 요청 URL 이 그쪽으로 간다 — 생성자 리터럴이 제거됐다")
    void baseUrlIsHonoured() {
        RestClient.Builder stubBuilder = RestClient.builder();
        MockRestServiceServer stubServer = MockRestServiceServer.bindTo(stubBuilder).build();
        TossPaymentClient stubClient =
                new TossPaymentClient("test-secret", "http://pg-stub:8080/v1", stubBuilder, new ObjectMapper());

        stubServer.expect(requestTo("http://pg-stub:8080/v1/payments/toss-key-1/cancel"))
                .andRespond(withSuccess("{\"status\":\"CANCELED\"}", MediaType.APPLICATION_JSON));

        stubClient.cancel(PAYMENT_KEY, "사유", "refund-7");
        stubServer.verify();
    }

    @Test
    @DisplayName("2xx → SUCCEEDED, 그리고 Idempotency-Key 헤더가 전송된다")
    void success_sendsIdempotencyKey() {
        server.expect(requestTo(CANCEL_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "refund-7"))
                .andRespond(withSuccess("{\"status\":\"CANCELED\"}", MediaType.APPLICATION_JSON));

        TossOutcome outcome = client.cancel(PAYMENT_KEY, "주문 보상 환불", "refund-7");

        assertThat(outcome.kind()).isEqualTo(TossOutcome.Kind.SUCCEEDED);
        server.verify();
    }

    @Test
    @DisplayName("5xx → TRANSIENT (재시도 대상)")
    void serverError_isTransient() {
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("{\"code\":\"SERVER_ERROR\"}").contentType(MediaType.APPLICATION_JSON));

        assertThat(client.cancel(PAYMENT_KEY, "이유", "refund-7").kind())
                .isEqualTo(TossOutcome.Kind.TRANSIENT);
    }

    @Test
    @DisplayName("4xx → PERMANENT_FAILURE (재시도해도 상태가 바뀌지 않는다)")
    void clientError_isPermanent() {
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"code\":\"NOT_CANCELABLE_PAYMENT\"}").contentType(MediaType.APPLICATION_JSON));

        TossOutcome outcome = client.cancel(PAYMENT_KEY, "이유", "refund-7");

        assertThat(outcome.kind()).isEqualTo(TossOutcome.Kind.PERMANENT_FAILURE);
        assertThat(outcome.code()).isEqualTo("NOT_CANCELABLE_PAYMENT");
    }

    @Test
    @DisplayName("ALREADY_CANCELED_PAYMENT 는 실패가 아니라 별도 분류다 (조회로 진실을 확정하기 위함)")
    void alreadyCanceled_isSeparateKind() {
        server.expect(requestTo(CANCEL_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"code\":\"ALREADY_CANCELED_PAYMENT\"}").contentType(MediaType.APPLICATION_JSON));

        assertThat(client.cancel(PAYMENT_KEY, "이유", "refund-7").kind())
                .isEqualTo(TossOutcome.Kind.ALREADY_CANCELED);
    }

    @Test
    @DisplayName("조회: cancels[] 합계가 전액이면 fullyCanceled")
    void find_sumsCancelAmounts() {
        server.expect(requestTo(FIND_URL))
                .andRespond(withSuccess("""
                        {"status":"CANCELED","cancels":[{"cancelAmount":30000},{"cancelAmount":20000}]}
                        """, MediaType.APPLICATION_JSON));

        Optional<TossPaymentSnapshot> snapshot = client.find(PAYMENT_KEY);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().canceledAmount()).isEqualTo(50_000L);
        assertThat(snapshot.get().isFullyCanceled(50_000L)).isTrue();
        assertThat(snapshot.get().isFullyCanceled(80_000L)).isFalse();
    }

    @Test
    @DisplayName("조회 실패는 empty — 진실을 모르는 상태를 성공/실패로 단정하지 않는다")
    void find_failureReturnsEmpty() {
        server.expect(requestTo(FIND_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(client.find(PAYMENT_KEY)).isEmpty();
    }
}
