package com.peekcart.payment.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.payment.infrastructure.security.PaymentSecurityConfig;
import com.peekcart.global.config.TestSecurityConfig;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.application.PaymentCommandService;
import com.peekcart.payment.application.PaymentQueryService;
import com.peekcart.payment.application.WebhookService;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.presentation.dto.request.ConfirmPaymentRequest;
import com.peekcart.support.WithMockLoginUser;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@WebMvcTest(controllers = PaymentController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = PaymentSecurityConfig.class))
@Import(TestSecurityConfig.class)
@DisplayName("PaymentController 슬라이스 테스트")
class PaymentControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean PaymentCommandService paymentCommandService;
    @MockitoBean PaymentQueryService paymentQueryService;
    @MockitoBean WebhookService webhookService;

    // ── POST /api/v1/payments/confirm ──

    @Test
    @WithMockLoginUser
    @DisplayName("POST /confirm: 결제 접수 직후 이미 승인되었어도 202을 반환한다")
    void confirmPayment_success_returns200() throws Exception {
        given(paymentCommandService.confirmPayment(eq(1L), any()))
                .willReturn(PaymentFixture.approvedPaymentDetailDto());

        ConfirmPaymentRequest request = new ConfirmPaymentRequest(
                PaymentFixture.DEFAULT_PAYMENT_KEY, PaymentFixture.DEFAULT_ORDER_ID, PaymentFixture.DEFAULT_AMOUNT);

        mockMvc.perform(post("/api/v1/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/payments/1"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.method").value("카드"));
    }

    @Test
    @WithMockLoginUser
    @DisplayName("POST /confirm: 접수 직후 실패가 확정되어도 202와 현재 상태를 반환한다")
    void confirmPayment_failed_returnsPay005() throws Exception {
        given(paymentCommandService.confirmPayment(eq(1L), any()))
                .willReturn(PaymentFixture.failedPaymentDetailDto());

        ConfirmPaymentRequest request = new ConfirmPaymentRequest(
                PaymentFixture.DEFAULT_PAYMENT_KEY, PaymentFixture.DEFAULT_ORDER_ID, PaymentFixture.DEFAULT_AMOUNT);

        mockMvc.perform(post("/api/v1/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/payments/1"))
                .andExpect(jsonPath("$.data.status").value("FAILED"));
    }

    @Test
    @WithMockLoginUser
    @DisplayName("POST /confirm: 접수 후 PENDING과 202를 반환한다")
    void confirmPayment_unresolved_returnsPay012() throws Exception {
        given(paymentCommandService.confirmPayment(eq(1L), any()))
                .willReturn(PaymentFixture.pendingPaymentDetailDto());

        ConfirmPaymentRequest request = new ConfirmPaymentRequest(
                PaymentFixture.DEFAULT_PAYMENT_KEY, PaymentFixture.DEFAULT_ORDER_ID, PaymentFixture.DEFAULT_AMOUNT);

        mockMvc.perform(post("/api/v1/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/payments/1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @WithMockLoginUser
    @DisplayName("POST /confirm: paymentKey가 빈 문자열이면 400을 반환한다")
    void confirmPayment_blankPaymentKey_returns400() throws Exception {
        ConfirmPaymentRequest request = new ConfirmPaymentRequest(
                "", PaymentFixture.DEFAULT_ORDER_ID, PaymentFixture.DEFAULT_AMOUNT);

        mockMvc.perform(post("/api/v1/payments/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/v1/payments/{orderId} ──

    @Test
    @WithMockLoginUser
    @DisplayName("GET /{orderId}: 결제 조회 성공 시 200을 반환한다")
    void getPayment_success_returns200() throws Exception {
        given(paymentQueryService.getPaymentByOrderId(1L, PaymentFixture.DEFAULT_ORDER_ID))
                .willReturn(PaymentFixture.approvedPaymentDetailDto());

        mockMvc.perform(get("/api/v1/payments/{orderId}", PaymentFixture.DEFAULT_ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(PaymentFixture.DEFAULT_ORDER_ID))
                .andExpect(jsonPath("$.data.amount").value(PaymentFixture.DEFAULT_AMOUNT));
    }

    @Test
    @WithMockLoginUser
    void cancelledPaymentStillExposesUnresolvedApproval() throws Exception {
        var dto = new com.peekcart.payment.application.dto.PaymentDetailDto(
                1L, PaymentFixture.DEFAULT_ORDER_ID, "key", 1000L, "CANCELLED", "UNRESOLVED",
                null, null, java.time.LocalDateTime.now());
        given(paymentQueryService.getPaymentByOrderId(1L, PaymentFixture.DEFAULT_ORDER_ID)).willReturn(dto);
        mockMvc.perform(get("/api/v1/payments/{orderId}", PaymentFixture.DEFAULT_ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.approvalStatus").value("UNRESOLVED"));
    }

    @Test
    @WithMockLoginUser
    @DisplayName("GET /{orderId}: 결제 정보가 없으면 404를 반환한다")
    void getPayment_notFound_returns404() throws Exception {
        given(paymentQueryService.getPaymentByOrderId(1L, 99L))
                .willThrow(new PaymentException(ErrorCode.PAY_003));

        mockMvc.perform(get("/api/v1/payments/{orderId}", 99L))
                .andExpect(status().isNotFound());
    }

    // ── POST /api/v1/payments/webhook ──

    @Test
    @DisplayName("POST /webhook: 정상 웹훅 수신 시 200을 반환한다")
    void handleWebhook_success_returns200() throws Exception {
        String payload = "{\"paymentKey\":\"pk-123\",\"eventType\":\"PAYMENT_DONE\"}";

        mockMvc.perform(post("/api/v1/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Toss-Signature", "valid-signature")
                        .header("Idempotency-Key", "idem-1")
                        .content(payload))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /webhook: 서명 검증 실패 시 401을 반환한다")
    void handleWebhook_invalidSignature_returns401() throws Exception {
        String payload = "{\"paymentKey\":\"pk-123\",\"eventType\":\"PAYMENT_DONE\"}";

        doThrow(new PaymentException(ErrorCode.PAY_006))
                .when(webhookService).processWebhook(any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/v1/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Toss-Signature", "invalid")
                        .content(payload))
                .andExpect(status().isUnauthorized());
    }
}
