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
 * payment outbox poller <b>정책값</b> 계약 (ADR-0045, ADR-0007).
 *
 * <p>값이 실제 스케줄러·ShedLock 에 들어가는지는 공유 모듈의 {@code OutboxPollingSchedulerWiringTest} 가 본다.
 * 여기서는 이 서비스의 base yml 이 <b>무슨 값을 선언했는가</b>와 그 값이 부팅 검증을 통과하는지를 고정한다.
 * 스프링 컨텍스트로는 값의 출처가 지워지므로 base 파일을 직접 읽는다.
 */
@DisplayName("[OUTBOX-POLICY-PAYMENT] payment outbox poller 정책값")
class PaymentOutboxPollingPolicyTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    @Test
    @DisplayName("[OUTBOX-POLICY-PAYMENT] base application.yml 이 주기 500ms·최소 보존 400ms·배치 500 을 선언하고 부팅 검증을 통과한다")
    void baseYamlDeclaresPolicy() throws IOException {
        Binder base = binder(RESOURCES.resolve("application.yml"));
        OutboxPollingProperties p = base.bind("app.outbox.polling", OutboxPollingProperties.class).get();

        assertThat(p.getDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(p.getLockAtLeastFor()).isEqualTo(Duration.ofMillis(400));
        assertThat(base.bind("app.outbox.polling.batch-size", Integer.class).get()).isEqualTo(500);
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(p)).isEmpty();
        }
    }

    @Test
    @DisplayName("[OUTBOX-POLICY-PAYMENT] 프로파일 yml 은 outbox poller 정책을 덮지 않는다 — 동작 정책은 base 소유")
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
