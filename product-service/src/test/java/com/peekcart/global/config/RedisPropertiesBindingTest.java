package com.peekcart.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code spring.data.redis} 하위 트리가 base(timeout)와 프로파일(host/port)로 나뉘어도
 * 최종 바인딩이 깨지지 않는지 고정한다.
 *
 * <p><b>왜 필요한가</b>: ADR-0007 은 "최상위 키 트리에 base/프로파일 간 병합 충돌 가능성이 있으면
 * Java Config 로 declare" 하라고 한다(D-001 의 {@code management.*} 사례). 타임아웃은 동작 규약이라
 * base 소유가 맞지만, host/port 는 연결 정보라 프로파일 소유다 — 즉 같은 하위 트리를 둘이 나눠 갖는다.
 * 이 구성이 안전한지를 <b>추정하지 않고 실제 바인딩으로 실증</b>한다.
 *
 * <p>Spring 컨텍스트를 띄우지 않고 YAML 로더 + {@link Binder} 로 직접 검증한다 — 검증 대상이
 * "프로퍼티 소스 병합 결과" 자체이므로 DB/Redis 컨테이너가 필요 없고, 값이 지워지면 즉시 실패한다.
 */
@DisplayName("Redis 프로퍼티 프로파일 병합 바인딩 (ADR-0007 조건1 실증)")
class RedisPropertiesBindingTest {

    private static final YamlPropertySourceLoader LOADER = new YamlPropertySourceLoader();

    @ParameterizedTest(name = "{0} 프로파일 → host={1}, port={2}")
    @CsvSource({
            "local, localhost, 6379",
            "k8s,   redis,     6379"
    })
    @DisplayName("프로파일은 host/port 를 덮고, base 의 timeout/connect-timeout 은 살아남는다")
    void profileOverridesConnectionButKeepsBaseTimeouts(String profile, String host, int port) throws IOException {
        RedisProperties bound = bind(profile);

        // 프로파일이 소유하는 연결 정보
        assertThat(bound.getHost()).isEqualTo(host);
        assertThat(bound.getPort()).isEqualTo(port);

        // base 가 소유하는 동작 규약 — 프로파일 병합에 지워지지 않아야 한다
        assertThat(bound.getTimeout())
                .as("command timeout 이 지워지면 무응답 Redis 에서 Lettuce 기본 60s 를 기다린다 (L-006)")
                .isEqualTo(Duration.ofMillis(500));
        assertThat(bound.getConnectTimeout())
                .as("connect timeout 이 지워지면 연결 단계에서 fail-open 이 늦어진다")
                .isEqualTo(Duration.ofMillis(300));
    }

    /**
     * {@code application.yml} 위에 {@code application-{profile}.yml} 을 얹어 바인딩한다.
     * 프로파일 소스를 먼저(addFirst) 넣어 Spring Boot 의 우선순위(프로파일 > base)를 재현한다.
     */
    private static RedisProperties bind(String profile) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(load("application.yml"));
        environment.getPropertySources().addFirst(load("application-" + profile + ".yml"));

        return Binder.get(environment)
                .bind("spring.data.redis", RedisProperties.class)
                .orElseThrow(() -> new AssertionError("spring.data.redis 바인딩 실패 — profile=" + profile));
    }

    private static PropertySource<?> load(String fileName) throws IOException {
        List<PropertySource<?>> sources = LOADER.load(fileName, new ClassPathResource(fileName));
        assertThat(sources).as("%s 가 비어 있으면 안 된다", fileName).isNotEmpty();
        return sources.get(0);
    }
}
