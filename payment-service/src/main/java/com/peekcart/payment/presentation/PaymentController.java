package com.peekcart.payment.presentation;

import com.peekcart.payment.application.dto.PaymentCheckoutDto;

import com.peekcart.global.auth.CurrentUser;
import com.peekcart.global.auth.LoginUser;
import com.peekcart.global.response.ApiResponse;
import com.peekcart.payment.application.PaymentCommandService;
import com.peekcart.payment.application.PaymentQueryService;
import com.peekcart.payment.application.WebhookService;
import com.peekcart.payment.application.dto.ConfirmPaymentCommand;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.presentation.dto.request.ConfirmPaymentRequest;
import com.peekcart.payment.presentation.dto.response.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * 결제 API 컨트롤러.
 */
@Tag(name = "결제", description = "결제 승인 / 조회 / Toss 웹훅 수신")
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentCommandService paymentCommandService;
    private final PaymentQueryService paymentQueryService;
    private final WebhookService webhookService;
    private final ObjectMapper objectMapper;

    /** 클라이언트는 반환된 pgOrderId를 PG 인증과 confirm 양쪽에 그대로 사용한다. */
    @Operation(summary = "결제 준비", description = "소유자의 신규 결제에 저장된 PG 주문 식별자와 금액을 조회한다.")
    @PostMapping("/{orderId}/prepare")
    public ResponseEntity<ApiResponse<PaymentCheckoutDto>> preparePayment(
            @CurrentUser LoginUser loginUser, @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.of(paymentQueryService.preparePayment(loginUser.userId(), orderId)));
    }

    /** 승인 접수(202). 결과는 GET /api/v1/payments/{orderId}로 조회한다 (ADR-0034). */
    @Operation(summary = "결제 승인 접수", description = "재고 보호 확인 후 비동기 승인한다. 결과는 결제 조회로 확인한다.")
    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<PaymentResponse>> confirmPayment(
            @CurrentUser LoginUser loginUser,
            @Valid @RequestBody ConfirmPaymentRequest request
    ) {
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
                request.paymentKey(), request.orderId(), request.amount(), request.pgOrderId());
        PaymentDetailDto result = paymentCommandService.confirmPayment(loginUser.userId(), command);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/payments/" + result.orderId()))
                .body(ApiResponse.of(PaymentResponse.from(result)));
    }

    /**
     * 주문 ID로 결제 정보를 조회한다.
     */
    @Operation(summary = "결제 조회", description = "주문 ID로 결제 정보를 조회한다.")
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(
            @CurrentUser LoginUser loginUser,
            @PathVariable Long orderId
    ) {
        return ResponseEntity.ok(ApiResponse.of(
                PaymentResponse.from(paymentQueryService.getPaymentByOrderId(loginUser.userId(), orderId))));
    }

    /**
     * Toss 웹훅을 수신한다.
     * HMAC 서명 검증을 위해 원본 JSON 문자열을 그대로 수신한다.
     */
    @Operation(summary = "Toss 웹훅 수신", description = "Toss Payments 웹훅을 수신하여 HMAC 서명 검증 후 처리한다.")
    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = "Toss-Signature", required = false) String signature,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody String rawPayload
    ) throws Exception {
        JsonNode node = objectMapper.readTree(rawPayload);
        String paymentKey = node.path("paymentKey").asText("");
        String eventType = node.path("eventType").asText("");
        String idempKey = idempotencyKey != null ? idempotencyKey : paymentKey + "-" + eventType;

        webhookService.processWebhook(signature, paymentKey, eventType, idempKey, rawPayload);
        return ResponseEntity.ok().build();
    }
}
