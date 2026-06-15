package com.peekcart.global.slack;

import com.peekcart.global.port.SlackPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Slack Incoming Webhook으로 알림을 발송한다.
 * 발송 실패 시 로그만 남기고 예외를 전파하지 않는다.
 */
@Slf4j
@Component
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
