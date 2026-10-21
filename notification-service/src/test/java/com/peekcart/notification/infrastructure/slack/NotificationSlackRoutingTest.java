package com.peekcart.notification.infrastructure.slack;

import com.peekcart.global.port.SlackPort;
import com.peekcart.global.slack.SlackFallbackConfig;
import com.peekcart.global.slack.SlackNotificationClient;
import com.peekcart.notification.application.NotificationCommandService;
import com.peekcart.notification.application.port.UserNotificationPort;
import com.peekcart.notification.domain.model.NotificationType;
import com.peekcart.notification.domain.repository.NotificationRepository;
import com.peekcart.notification.infrastructure.kafka.NotificationKafkaConfig;
import com.peekcart.support.KafkaRetryContract;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationSlackRoutingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=k8s")
            .withUserConfiguration(NotificationSlackConfig.class, SlackNotificationClient.class,
                    SlackFallbackConfig.class, NotificationCommandService.class)
            .withBean(NotificationRepository.class, () -> mock(NotificationRepository.class));

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void businessMessageAndDlqAlertReachOnlyTheirOwnLoopbackEndpoint() throws Exception {
        List<String> users = new CopyOnWriteArrayList<>();
        List<String> ops = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/user", exchange -> {
            users.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/ops", exchange -> {
            ops.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            runner.withPropertyValues("SLACK_USER_WEBHOOK_URL=" + endpoint + "/user",
                            "SLACK_OPS_WEBHOOK_URL=" + endpoint + "/ops")
                    .run(ctx -> {
                        assertThat(ctx).hasNotFailed().hasSingleBean(SlackPort.class)
                                .hasSingleBean(UserNotificationPort.class);
                        ctx.getBean(NotificationCommandService.class).createNotification(
                                42L, NotificationType.ORDER_CREATED, "synthetic-user-message");
                        assertThat(users).containsExactly("{\"text\":\"synthetic-user-message\"}");
                        assertThat(ops).isEmpty();

                        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
                        when(kafka.send(any(ProducerRecord.class)))
                                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
                        var handler = new NotificationKafkaConfig(ctx.getBean(SlackPort.class))
                                .kafkaErrorHandler(kafka);
                        KafkaRetryContract.assertThreeJitteredRetriesThenRecovery(handler,
                                () -> assertThat(ops).isEmpty(),
                                () -> assertThat(ops).singleElement().asString().contains("[DLQ]", "retry.contract"));
                        assertThat(users).hasSize(1);
                        assertThat(ops).noneMatch(body -> body.contains("synthetic-user-message"));
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void legacyWebhookAloneCannotStartK8s() {
        runner.withPropertyValues("SLACK_WEBHOOK_URL=http://127.0.0.1:9/legacy")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void missingUserEndpointCannotStartK8s() {
        runner.withPropertyValues("SLACK_OPS_WEBHOOK_URL=http://127.0.0.1:9/ops")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void missingOpsEndpointCannotStartK8sEvenWithNoopEnabled() {
        runner.withPropertyValues("SLACK_USER_WEBHOOK_URL=http://127.0.0.1:9/user",
                        "slack.noop-fallback.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void sameEndpointCannotStartK8s() {
        runner.withPropertyValues("SLACK_USER_WEBHOOK_URL=http://127.0.0.1:9/shared",
                        "SLACK_OPS_WEBHOOK_URL=http://127.0.0.1:9/shared")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void normalizedSameEndpointCannotStartK8s() {
        runner.withPropertyValues("SLACK_USER_WEBHOOK_URL=http://LOCALHOST:80/a/../shared",
                        "SLACK_OPS_WEBHOOK_URL=http://localhost/shared")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void invalidEndpointCannotStartK8s() {
        runner.withPropertyValues("SLACK_USER_WEBHOOK_URL=not-an-endpoint",
                        "SLACK_OPS_WEBHOOK_URL=http://127.0.0.1:9/ops")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void emptyEndpointCannotStartK8s() {
        runner.withPropertyValues("SLACK_USER_WEBHOOK_URL=",
                        "SLACK_OPS_WEBHOOK_URL=http://127.0.0.1:9/ops")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
