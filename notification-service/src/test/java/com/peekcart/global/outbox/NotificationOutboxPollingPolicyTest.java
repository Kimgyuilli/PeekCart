package com.peekcart.global.outbox;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * notification outbox poller <b>정책값</b> 계약 (ADR-0045, ADR-0007).
 *
 * <p>notification 의 outbox 는 replay 재발행 전용이라 3서비스의 처리율 상향을 따르지 않고 종전 값(5s·4s·100)을
 * 유지한다. 스케줄러에 인라인 기본값이 없어졌으므로 그 값을 base yml 이 명시해야 하고, 명시한 값이 부팅 검증을
 * 통과해야 한다. 값이 실제 스케줄러·ShedLock 에 들어가는지는 공유 모듈의 {@code OutboxPollingSchedulerWiringTest} 가 본다.
 */
@DisplayName("[OUTBOX-POLICY-NOTIFICATION] notification outbox poller 정책값")
class NotificationOutboxPollingPolicyTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    @Test
    @DisplayName("[OUTBOX-POLICY-NOTIFICATION] base application.yml 이 종전 값(주기 5s·최소 보존 4s·배치 100)을 명시하고 부팅 검증을 통과한다")
    void baseYamlDeclaresPolicy() throws IOException {
        Binder base = binder(RESOURCES.resolve("application.yml"));
        OutboxPollingProperties p = base.bind("app.outbox.polling", OutboxPollingProperties.class).get();

        assertThat(p.getDelay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.getLockAtLeastFor()).isEqualTo(Duration.ofSeconds(4));
        assertThat(base.bind("app.outbox.polling.batch-size", Integer.class).get()).isEqualTo(100);
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(p)).isEmpty();
        }
    }

    @Test
    @DisplayName("[OUTBOX-POLICY-NOTIFICATION] 프로파일 yml 은 outbox poller 정책을 덮지 않는다 — 동작 정책은 base 소유")
    void profilesDoNotOverridePolicy() throws IOException {
        int profiles = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(RESOURCES, "application-*.yml")) {
            for (Path file : files) {
                profiles++;
                assertThat(binder(file).bind("app.outbox.polling", Bindable.mapOf(String.class, Object.class)).isBound())
                        .as("%s 가 app.outbox.polling 을 선언한다", file.getFileName())
                        .isFalse();
            }
        }
        // 앞 단언이 "프로파일 파일이 없어서" 참이 된 게 아님을 고정한다
        assertThat(profiles).isPositive();
    }

    private static Binder binder(Path file) throws IOException {
        return new Binder(ConfigurationPropertySources.from(
                new YamlPropertySourceLoader().load(file.toString(), new FileSystemResource(file))));
    }
}
