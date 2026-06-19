package com.peekcart;

import com.peekcart.global.auth.RedisTokenBlacklistLookupAdapter;
import com.peekcart.support.IntegrationTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * order-service 독립 컨텍스트 부팅 스모크 (Order peel PR-a 성공 기준 고정).
 *
 * <p>개별 기능 통합 테스트와 별개로, peel 후 order-service 가 자기 횡단 배선으로 부팅됨을 명시적으로 못박는다:
 * <ul>
 *   <li>ADR-0014 fail-closed — common-auth {@link RedisTokenBlacklistLookupAdapter} 가 {@link RedisTemplate} 과 함께 기동(data-redis 무조건성).</li>
 *   <li>{@link SecurityFilterChain} 정확히 1개({@code OrderSecurityConfig}, ADR-0014 D1).</li>
 *   <li>Kafka listener container factory 배선({@code OrderKafkaConfig} — consumer 4종 구동).</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import(IntegrationTestConfig.class)
@DisplayName("order-service 컨텍스트 부팅 스모크 (PR-a 성공 기준)")
class OrderApplicationTests {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired ApplicationContext ctx;
    @Autowired Map<String, SecurityFilterChain> securityFilterChains;

    @Test
    @DisplayName("Redis blacklist 검증 어댑터가 RedisTemplate 과 함께 기동된다 (ADR-0014 fail-closed)")
    void redisBlacklistAdapter_bootsWithRedisTemplate() {
        assertThat(ctx.getBean(RedisTokenBlacklistLookupAdapter.class)).isNotNull();
        assertThat(ctx.getBeanNamesForType(RedisTemplate.class)).isNotEmpty();
    }

    @Test
    @DisplayName("SecurityFilterChain 은 정확히 1개다 (OrderSecurityConfig)")
    void singleSecurityFilterChain() {
        assertThat(securityFilterChains).hasSize(1);
    }

    @Test
    @DisplayName("Kafka listener container factory 가 배선된다 (OrderEventConsumer 4종 구동)")
    void kafkaListenerContainerFactory_present() {
        assertThat(ctx.getBeanNamesForType(ConcurrentKafkaListenerContainerFactory.class)).isNotEmpty();
    }
}
