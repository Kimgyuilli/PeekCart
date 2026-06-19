package com.peekcart.payment.infrastructure.toss;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Map;

/**
 * Toss Payments API 클라이언트.
 * RestClient(Spring 6.1+)를 사용하며 Basic Auth 방식으로 인증한다.
 */
@Component
public class TossPaymentClient {

    private final RestClient restClient;

    public TossPaymentClient(@Value("${toss.payments.secret-key}") String secretKey) {
        String credentials = Base64.getEncoder().encodeToString((secretKey + ":").getBytes());
        this.restClient = RestClient.builder()
                .baseUrl("https://api.tosspayments.com/v1")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Toss 결제 승인 API를 호출한다.
     *
     * @throws org.springframework.web.client.RestClientException Toss API 호출 실패 시
     */
    public TossConfirmResponse confirm(String paymentKey, String orderId, long amount) {
        return restClient.post()
                .uri("/payments/confirm")
                .body(Map.of(
                        "paymentKey", paymentKey,
                        "orderId", orderId,
                        "amount", amount
                ))
                .retrieve()
                .body(TossConfirmResponse.class);
    }
}
