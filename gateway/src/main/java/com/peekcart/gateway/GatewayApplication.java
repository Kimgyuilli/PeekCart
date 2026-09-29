package com.peekcart.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Cloud Gateway — 단일 외부 진입점 (ADR-0013 D3).
 *
 * <p><b>WebFlux(reactive) 전용</b>: {@code :common}(servlet starter-web 를 api 전이 노출)을 의존하지
 * 않는다. servlet 클래스가 classpath 에 유입되면 Boot 가 MVC 로 부팅해 gateway 가 동작하지 않는다
 * (`assertGatewayHasNoServletDeps` 가드 + `GatewayReactiveBootstrapTest` 로 고정).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
