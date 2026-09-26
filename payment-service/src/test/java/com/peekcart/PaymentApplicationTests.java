package com.peekcart;

import com.peekcart.global.security.InternalGatewayPublicKeyRegistry;
import com.peekcart.global.security.InternalTokenVerifier;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * payment-service 독립 컨텍스트 부팅 스모크 (Payment peel PR-b 성공 기준 고정 — root app 해체 이후 마지막 서비스).
 *
 * <p>Toss 프로퍼티는 별도 stub 없이 {@code application.yml} 의 placeholder 로 주입된다 — 누락 시 부팅 실패하도록 둔다
 * ({@code TossPaymentClient}/{@code WebhookService} 의 {@code @Value} 필수성 고정). 그 외:
 * <ul>
 *   <li>ADR-0017 — common-auth 내부 토큰 검증 스택({@link InternalTokenVerifier}·{@link InternalGatewayPublicKeyRegistry})이 기동(Gateway 공개키 배선 fail-fast).</li>
 *   <li>{@link SecurityFilterChain} 정확히 1개({@code PaymentSecurityConfig}, ADR-0014 D1).</li>
 *   <li>Kafka listener container factory 배선({@code PaymentKafkaConfig} — order.created/stock.reservation.result/order.cancelled 소비).</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("payment-service 컨텍스트 부팅 스모크 (PR-b 성공 기준)")
class PaymentApplicationTests {

    @Autowired ApplicationContext ctx;
    @Autowired Map<String, SecurityFilterChain> securityFilterChains;

    @Test
    @DisplayName("내부 토큰 검증 스택이 Gateway 공개키와 함께 기동된다 (ADR-0017 fail-fast)")
    void internalTokenStack_bootsWithGatewayPublicKey() {
        assertThat(ctx.getBean(InternalTokenVerifier.class)).isNotNull();
        assertThat(ctx.getBean(InternalGatewayPublicKeyRegistry.class).kids()).isNotEmpty();
        assertThat(ctx.getBeanNamesForType(RedisTemplate.class)).isNotEmpty();
    }

    @Test
    @DisplayName("SecurityFilterChain 은 정확히 1개다 (PaymentSecurityConfig)")
    void singleSecurityFilterChain() {
        assertThat(securityFilterChains).hasSize(1);
    }

    @Test
    @DisplayName("Kafka listener container factory 가 배선된다 (PaymentEventConsumer 구동)")
    void kafkaListenerContainerFactory_present() {
        assertThat(ctx.getBeanNamesForType(ConcurrentKafkaListenerContainerFactory.class)).isNotEmpty();
    }
}
