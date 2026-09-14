package com.peekcart.payment.infrastructure.toss;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Toss Payments API 클라이언트.
 * RestClient(Spring 6.1+)를 사용하며 Basic Auth 방식으로 인증한다.
 */
@Slf4j
@Component
public class TossPaymentClient {

    private static final String ALREADY_CANCELED = "ALREADY_CANCELED_PAYMENT";
    private static final String ALREADY_PROCESSED = "ALREADY_PROCESSED_PAYMENT";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * @param baseUrl PG endpoint. 운영/로컬/E2E 가 서로 다른 값을 쓰는 <b>연결 정보</b>다(ADR-0007).
     *                base {@code application.yml} 은 <b>도달 불가 sentinel</b>(discard 포트)을 기본값으로
     *                두어 설정 누락이 실 PG 로 새지 않게 하고, {@code application-k8s.yml} 은
     *                실 PG endpoint 를 <b>기본값으로</b> 선언한다({@code ${TOSS_BASE_URL:...}}).
     *                base-url 은 자격증명이 아니라 endpoint 라 기본값 없이 강제하면 값 주입 전까지
     *                배포가 부팅에 실패할 뿐 얻는 안전이 없다 — 환경변수 override 는 유지된다.
     * @param builder 타임아웃은 {@link TossClientConfig} 의 {@code RestClientCustomizer} 가 이미
     *                적용한 상태로 주입된다 — 여기서 {@code requestFactory} 를 다시 세팅하지 않는다
     *                (그러면 테스트의 {@code MockRestServiceServer} 바인딩까지 덮어쓴다)
     */
    public TossPaymentClient(@Value("${toss.payments.secret-key}") String secretKey,
                             @Value("${toss.payments.base-url}") String baseUrl,
                             RestClient.Builder builder,
                             ObjectMapper objectMapper) {
        String credentials = Base64.getEncoder().encodeToString((secretKey + ":").getBytes());
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Toss 결제 승인 API를 호출한다 (ADR-0023 D3).
     *
     * <p>예외를 던지지 않고 {@link TossOutcome} 으로 분류해 돌려준다. {@code .retrieve()} 로 예외를
     * 던지면 <b>타임아웃(과금 성립 가능)과 4xx(성립 불가)가 같은 실패로 뭉개진다</b> — 호출자가 둘을
     * 구분하지 못하면 일시 실패를 영구 실패로 확정하게 된다(D-020 의 결함 중 하나).
     *
     * @param idempotencyKey 재시도·재실행에서 <b>동일한 값</b>이어야 한다. PG 측에서 중복 승인을 흡수한다
     */
    public TossOutcome confirm(String paymentKey, String orderId, long amount, String idempotencyKey) {
        try {
            return restClient.post()
                    .uri("/payments/confirm")
                    .header(IDEMPOTENCY_HEADER, idempotencyKey)
                    .body(Map.of(
                            "paymentKey", paymentKey,
                            "orderId", orderId,
                            "amount", amount
                    ))
                    .exchange((request, response) ->
                            toConfirmOutcome(response.getStatusCode(), readBody(response)), false);
        } catch (Exception e) {
            // 연결 실패·타임아웃·응답 파싱 불가 — 승인이 성립했는지 알 수 없다
            log.warn("Toss 승인 호출 실패(결과 불명) — paymentKey={}", paymentKey, e);
            return TossOutcome.unknown(e.getMessage());
        }
    }

    /**
     * 승인 응답 원문에서 {@code method}/{@code approvedAt} 을 읽는다 (ADR-0023 D5).
     *
     * <p>승인 호출의 성공 응답과 조회 응답이 <b>같은 필드</b>를 담으므로 파싱을 한 곳에 둔다 —
     * reconciliation 은 조회 원문으로 같은 값을 얻어야 로컬 전이를 동일하게 만들 수 있다.
     * JSON 해석은 외부 연동 지식이라 클라이언트 경계에 남긴다.
     *
     * @return 파싱 불가 시 필드가 {@code null} 인 응답 (호출자가 대체값을 정한다)
     */
    public TossConfirmResponse parseConfirmed(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            return new TossConfirmResponse(
                    root.path("paymentKey").asText(null),
                    root.path("orderId").asText(null),
                    root.path("status").asText(null),
                    root.path("method").asText(null),
                    root.path("approvedAt").asText(null));
        } catch (Exception e) {
            log.warn("Toss 승인 응답 파싱 실패 — 원문은 원장에 그대로 남긴다", e);
            return new TossConfirmResponse(null, null, null, null, null);
        }
    }

    /**
     * 결제를 취소(전액 환불)한다 (ADR-0018 D3/D5).
     *
     * <p>예외를 던지지 않고 {@link TossOutcome} 으로 분류해 돌려준다 — 호출자(dispatcher)는 이
     * 분류로 원장 상태를 정하며, 분류 자체는 외부 연동 지식이라 여기에 둔다.
     *
     * @param idempotencyKey 재시도 시 <b>동일한 값</b>이어야 한다. PG 측에서 중복 취소를 흡수한다
     */
    public TossOutcome cancel(String paymentKey, String cancelReason, String idempotencyKey) {
        try {
            return restClient.post()
                    .uri("/payments/{paymentKey}/cancel", paymentKey)
                    .header(IDEMPOTENCY_HEADER, idempotencyKey)
                    .body(Map.of("cancelReason", cancelReason))
                    .exchange((request, response) -> toOutcome(response.getStatusCode(), readBody(response)), false);
        } catch (Exception e) {
            // 연결 실패·타임아웃·응답 파싱 불가 — 취소가 성립했는지 알 수 없다
            log.warn("Toss 취소 호출 실패(결과 불명) — paymentKey={}", paymentKey, e);
            return TossOutcome.unknown(e.getMessage());
        }
    }

    /**
     * 결제를 조회한다 (ADR-0018 D3 — crash 복구의 진실 확정 수단).
     *
     * @return 조회 실패 시 empty. 성공 시 응답 원문
     */
    public Optional<TossPaymentSnapshot> find(String paymentKey) {
        try {
            String raw = restClient.get()
                    .uri("/payments/{paymentKey}", paymentKey)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(raw);
            long canceledAmount = 0L;
            JsonNode cancels = root.get("cancels");
            if (cancels != null && cancels.isArray()) {
                for (JsonNode cancel : cancels) {
                    JsonNode amount = cancel.get("cancelAmount");
                    canceledAmount += amount == null ? 0L : amount.asLong();
                }
            }
            String status = root.path("status").asText(null);
            return Optional.of(new TossPaymentSnapshot(status, canceledAmount, raw));
        } catch (Exception e) {
            log.warn("Toss 결제 조회 실패 — paymentKey={}", paymentKey, e);
            return Optional.empty();
        }
    }

    /**
     * 승인 응답 분류. 취소({@link #toOutcome})와 갈라 두는 이유는 "이미 일어남" 코드가 다르기
     * 때문이다 — 취소는 {@code ALREADY_CANCELED_PAYMENT}, 승인은 {@code ALREADY_PROCESSED_PAYMENT}.
     * 하나로 합치면 감사 로그의 {@code code} 가 실제로 일어난 사건을 가리키지 못한다.
     */
    private TossOutcome toConfirmOutcome(HttpStatusCode status, String body) {
        if (status.is2xxSuccessful()) {
            return TossOutcome.succeeded(body);
        }
        String code = extractCode(body);
        if (ALREADY_PROCESSED.equals(code)) {
            return TossOutcome.alreadyProcessed(body);
        }
        if (status.is5xxServerError() || status.value() == 429) {
            return TossOutcome.transient_(code, body);
        }
        // 4xx — 금액 불일치·만료·인증 실패 등. 재시도해도 상태가 바뀌지 않는다.
        return TossOutcome.permanentFailure(code != null ? code : "HTTP_" + status.value(), body);
    }

    private TossOutcome toOutcome(HttpStatusCode status, String body) {
        if (status.is2xxSuccessful()) {
            return TossOutcome.succeeded(body);
        }
        String code = extractCode(body);
        if (ALREADY_CANCELED.equals(code)) {
            return TossOutcome.alreadyCanceled(body);
        }
        if (status.is5xxServerError() || status.value() == 429) {
            return TossOutcome.transient_(code, body);
        }
        // 4xx — 기간 초과·금액 불일치·인증 실패 등. 재시도해도 상태가 바뀌지 않는다.
        // code 가 없어도 null 을 그대로 두지 않는다 — 회신 payload 의 failureCode 는 실패 시 필수다(ADR-0018 D1).
        return TossOutcome.permanentFailure(code != null ? code : "HTTP_" + status.value(), body);
    }

    private String extractCode(String body) {
        try {
            return objectMapper.readTree(body).path("code").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String readBody(org.springframework.http.client.ClientHttpResponse response) {
        try (var is = response.getBody()) {
            return new String(is.readAllBytes());
        } catch (Exception e) {
            return "";
        }
    }
}
