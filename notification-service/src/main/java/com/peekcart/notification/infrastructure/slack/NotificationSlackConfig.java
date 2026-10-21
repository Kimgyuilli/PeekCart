package com.peekcart.notification.infrastructure.slack;

import com.peekcart.global.slack.SlackNotificationClient;
import com.peekcart.notification.application.port.UserNotificationPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/** 사용자 webhook은 운영 SlackPort 빈으로 등록하지 않는다. */
@Configuration
public class NotificationSlackConfig {

    @Bean
    public UserNotificationPort userNotificationPort(
            @Value("${notification.slack.user-webhook-url}") String userUrl,
            @Value("${slack.webhook.url}") String opsUrl) {
        URI user = endpoint(userUrl);
        URI ops = endpoint(opsUrl);
        if (user.equals(ops)) {
            throw new IllegalArgumentException("User and ops Slack endpoints must differ");
        }
        SlackNotificationClient client = new SlackNotificationClient(userUrl);
        return client::send;
    }

    private static URI endpoint(String value) {
        try {
            if (!value.equals(value.trim())) {
                throw new IllegalArgumentException();
            }
            URI uri = URI.create(value).normalize();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) {
                throw new IllegalArgumentException();
            }
            int port = uri.getPort();
            if (("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                    || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443)) {
                port = -1;
            }
            return new URI(uri.getScheme().toLowerCase(java.util.Locale.ROOT), null,
                    uri.getHost().toLowerCase(java.util.Locale.ROOT), port,
                    uri.getPath().isEmpty() ? "/" : uri.getPath(), null, null);
        } catch (IllegalArgumentException | java.net.URISyntaxException e) {
            // Do not include credentials from the URL in startup errors.
            throw new IllegalArgumentException("Slack endpoint must be an absolute HTTP(S) URL");
        }
    }
}
