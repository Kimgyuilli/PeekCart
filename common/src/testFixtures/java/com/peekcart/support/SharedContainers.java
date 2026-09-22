package com.peekcart.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/**
 * 모듈 싱글톤 컨테이너 (ADR-0028 · D-032).
 *
 * <p><b>왜</b>: 종전에는 테스트 클래스마다 {@code @Container} 로 MySQL/Redis/Kafka 를 따로
 * 선언했다. {@code @Testcontainers} extension 의 수명이 곧 per-class 라, 클래스가 바뀔 때마다
 * 셋을 다시 띄우고 Flyway 를 다시 돌리고 Spring context 를 다시 만들었다. 실측으로
 * {@code :order-service:test} 975초 중 844초(87%)가 그 비용이었다 — JUnit 은 static
 * initializer 를 testcase 시간에 넣지 않아 XML 증적에는 131초만 잡혔다.
 *
 * <p><b>무엇</b>: 컨테이너를 {@code static} 필드로 두고 static initializer 에서 한 번만
 * 기동한다. {@code @Testcontainers}/{@code @Container} 를 의도적으로 쓰지 않는다 — 그
 * 어노테이션 쌍이 per-class 수명의 출처다. Gradle 은 모듈마다 test JVM 을 따로 띄우므로
 * 결과적으로 모듈당 1세트다.
 *
 * <p>포트가 고정되므로 {@code @SpringBootTest} 의 context 캐시 키가 클래스 간에 같아진다.
 * 프로퍼티 지문이 동일한 테스트들은 context 를 공유한다.
 *
 * <h3>사용법</h3>
 * <pre>{@code
 * @SpringBootTest
 * @Import(SharedContainers.class)
 * class SomethingIntegrationTest extends AbstractIntegrationTest { ... }
 * }</pre>
 *
 * <p>Spring context 가 없는 테스트(raw Admin 클라이언트 등)는 {@code @Import} 경로가 닫혀
 * 있으므로 {@link #KAFKA} 같은 정적 필드를 직접 읽는다. 필드 접근만으로 static initializer 가
 * 돌아 기동이 보장된다.
 *
 * <h3>격리 규약</h3>
 * <p>컨테이너를 공유하므로 앞 클래스가 남긴 데이터가 뒤 클래스로 이어진다. 데이터에 의존하는
 * 테스트는 {@code @BeforeEach} 에서 {@link AbstractIntegrationTest#cleanDatabase()} 를
 * 호출해야 한다. Kafka 토픽은 테스트마다 UUID 이름으로 만들어 충돌을 피한다.
 *
 * <p>컨테이너를 stop 하지 않는다. Testcontainers 의 Ryuk 이 JVM 종료 시 정리한다.
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
    @ServiceConnection
    MySQLContainer<?> sharedMysql() {
        return MYSQL;
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> sharedRedis() {
        return REDIS;
    }

    @Bean
    @ServiceConnection
    KafkaContainer sharedKafka() {
        return KAFKA;
    }
}
