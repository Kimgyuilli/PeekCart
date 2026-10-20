package com.peekcart.payment.presentation;

import com.peekcart.global.auth.CurrentUser;
import com.peekcart.global.auth.LoginUser;
import com.peekcart.global.response.ApiResponse;
import com.peekcart.payment.application.PaymentCommandService;
import com.peekcart.payment.application.PaymentQueryService;
import com.peekcart.payment.application.WebhookService;
import com.peekcart.payment.application.dto.ConfirmPaymentCommand;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.application.dto.PaymentStatusHint;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
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

    /** 승인 접수(202). 결과는 GET /api/v1/payments/{orderId}로 조회한다 (ADR-0034). */
    @Operation(summary = "결제 승인 접수", description = "재고 보호 확인 후 비동기 승인한다. 결과는 결제 조회로 확인한다.")
    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<PaymentResponse>> confirmPayment(
            @CurrentUser LoginUser loginUser,
            @Valid @RequestBody ConfirmPaymentRequest request
    ) {
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
                request.paymentKey(), request.orderId(), request.amount());
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

    /** Bound the raw stream before parsing, including requests without Content-Length. */
    @Operation(summary = "Toss 웹훅 수신", description = "일반 결제 알림을 제한된 조회 가속 신호로 접수한다.")
    @PostMapping(value = "/webhook", consumes = "application/json")
    public ResponseEntity<Void> handleWebhook(HttpServletRequest request) throws IOException {
        int maxBytes = 16 * 1024;
        if (request.getContentLengthLong() > maxBytes) {
            return ResponseEntity.status(413).build();
        }
        byte[] payload = request.getInputStream().readNBytes(maxBytes + 1);
        if (payload.length > maxBytes) {
            return ResponseEntity.status(413).build();
        }
        JsonNode node;
        try {
            node = objectMapper.reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(payload);
        } catch (JsonProcessingException malformed) {
            return ResponseEntity.badRequest().build();
        }
        if (node == null || !node.isObject() || !node.path("eventType").isTextual()
                || !node.path("data").isObject() || !node.path("data").path("paymentKey").isTextual()
                || !node.path("data").path("status").isTextual()) {
            return ResponseEntity.badRequest().build();
        }
        webhookService.processWebhook(new PaymentStatusHint(node.path("eventType").textValue(),
                node.path("data").path("paymentKey").textValue(), node.path("data").path("status").textValue()));
        return ResponseEntity.ok().build();
    }
}
