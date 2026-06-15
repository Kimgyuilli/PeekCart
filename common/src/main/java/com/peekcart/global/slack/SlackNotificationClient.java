package com.peekcart.global.slack;

import com.peekcart.global.port.SlackPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Slack Incoming Webhook으로 알림을 발송한다.
 * 발송 실패 시 로그만 남기고 예외를 전파하지 않는다.
 *
 * <p>{@code slack.webhook.url} 이 선언된 서비스(root·notification 등 Slack 사용 모듈)에서만 빈으로 로드된다
 * (ADR-0011 §D2 횡단 인프라 · PR2b GW-2 P2 #1). Slack 미사용 서비스(user 등)는 :common 을 스캔해도 본 빈을
 * 로드하지 않아 서비스 yml 에 더미 webhook 을 둘 필요가 없다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "slack.webhook.url")
public class SlackNotificationClient implements SlackPort {

    private final RestClient restClient;
    private final String webhookUrl;

    public SlackNotificationClient(@Value("${slack.webhook.url}") String webhookUrl) {
        this.webhookUrl = webhookUrl;
        this.restClient = RestClient.create();
    }

    @Override
    public void send(String message) {
        try {
            restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", message))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("Slack 알림 발송 실패: {}", e.getMessage());
        }
    }
}
