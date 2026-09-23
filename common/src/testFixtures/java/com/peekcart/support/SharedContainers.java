package com.peekcart.support;

import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

import java.util.List;

/**
 * 모듈 싱글톤 컨테이너 (ADR-0028 · D-032).
 *
 * <p><b>왜</b>: 종전에는 테스트 클래스마다 {@code @Container} 로 MySQL/Redis/Kafka 를 따로
 * 선언했다. {@code @Testcontainers} extension 의 수명이 곧 per-class 라, 클래스가 바뀔 때마다
 * 셋을 다시 띄우고 Flyway 를 다시 돌리고 Spring context 를 다시 만들었다. 실측으로
 * {@code :order-service:test} 975초 중 844초(87%)가 그 비용이었다 — JUnit 은 static
 * initializer 를 testcase 시간에 넣지 않아 XML 증적에는 131초만 잡혔다.
 *
 * <p><b>왜 컨테이너를 빈으로 노출하지 않는가</b>: 노출하면 <b>Spring 이 수명을 가져간다</b>. {@code TestcontainersLifecycleBeanPostProcessor}
 * 는 {@code DestructionAwareBeanPostProcessor} 라 빈이 파괴될 때 컨테이너를 stop 하고,
 * 다음 context 가 뜰 때 다시 start 해 <b>새 컨테이너를 만든다</b>. {@code @Bean(destroyMethod = "")}
 * 로는 막히지 않는다 — 그 경로가 아니다.
 *
 * <p>실측으로 확인한 파괴 사슬은 이렇다. {@code @DirtiesContext} 가 붙은 클래스의 context 가
 * 닫히면서 MySQL 만 stop·remove 되고(5초 뒤 새 컨테이너가 뜬다), 이미 캐시된 다른 context 들은
 * <b>옛 포트</b>를 든 datasource 를 쥔 채 남아 이후 전부 {@code ConnectException} 으로 죽었다.
 * 클래스 순서에 따라 전멸하거나 멀쩡해서 셔플 없이는 드러나지 않는다.
 *
 * <p>그래서 컨테이너 대신 {@code ConnectionDetails} 빈만 노출한다. 그것은 {@code Startable} 이
 * 아니므로 위 post-processor 의 대상이 아니고, 수명은 static initializer 와 Testcontainers 의
 * Ryuk 이 소유한다. {@code @DynamicPropertySource} 는 쓸 수 없다 — {@code @Import} 로 들어온
 * 클래스의 그 메서드는 TestContext 프레임워크가 읽지 않아 datasource URL 이 비어 부팅이 깨진다
 * (실측 확인).
 *
 * <h3>사용법</h3>
 * <pre>{@code
 * @SpringBootTest
 * @Import(SharedContainers.class)
 * class SomethingIntegrationTest extends AbstractIntegrationTest { ... }
 * }</pre>
 *
 * <p>Spring context 가 없는 테스트(raw {@code Admin} 클라이언트 등)는 {@link #KAFKA} 같은
 * 정적 필드를 직접 읽는다. 필드 접근만으로 static initializer 가 돌아 기동이 보장된다.
 *
 * <h3>격리 규약</h3>
 * <p>컨테이너를 공유하므로 앞 클래스가 남긴 데이터가 뒤 클래스로 이어진다. 데이터에 의존하는
 * 테스트는 {@code @BeforeEach} 에서 {@link AbstractIntegrationTest#cleanDatabase()} 를,
 * 애플리케이션 고정 토픽을 읽는 테스트는 {@code cleanKafkaTopics()} 를 호출한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SharedContainers {

    public static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0").withDatabaseName("peekcart_test");

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7").withExposedPorts(6379);

    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    static {
        // 순차 기동이면 셋의 기동 시간이 더해진다. deepStart 는 병렬로 올리고 전부 ready 가
        // 될 때까지 블록한다.
        Startables.deepStart(MYSQL, REDIS, KAFKA).join();
    }

    @Bean
    JdbcConnectionDetails sharedJdbcConnectionDetails() {
        return new JdbcConnectionDetails() {
            @Override public String getUsername() { return MYSQL.getUsername(); }
            @Override public String getPassword() { return MYSQL.getPassword(); }
            @Override public String getJdbcUrl() { return MYSQL.getJdbcUrl(); }
            @Override public String getDriverClassName() { return MYSQL.getDriverClassName(); }
        };
    }

    @Bean
    RedisConnectionDetails sharedRedisConnectionDetails() {
        // 람다를 쓸 수 없다 — RedisConnectionDetails 는 메서드가 전부 default 라 함수형
        // 인터페이스가 아니다.
        return new RedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return Standalone.of(REDIS.getHost(), REDIS.getMappedPort(6379));
            }
        };
    }

    @Bean
    KafkaConnectionDetails sharedKafkaConnectionDetails() {
        return () -> List.of(KAFKA.getBootstrapServers());
    }
}
