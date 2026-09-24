package com.peekcart.global.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;

/**
 * {@code @KafkaListener} 컨테이너의 자동 기동을 프로퍼티로 게이트한다 (see ADR-0029).
 *
 * <p><b>왜 필요한가</b>: 리스너는 스케줄러와 같은 <b>자율 writer</b> 다. 아무도 요청하지 않았는데
 * 토픽을 읽어 도메인 상태를 고친다. 컨테이너를 클래스마다 새로 띄우던 동안에는 브로커가 달라
 * 보이지 않았을 뿐이고, ADR-0028 로 브로커를 공유하면서 테스트와의 경합이 상시화됐다.
 *
 * <p><b>왜 테스트가 자기 컨테이너를 {@code stop()} 하는 것으로는 안 되는가</b>: {@code groupId} 가
 * 하드코딩 상수라 <b>캐시된 모든 context 가 같은 그룹으로 같은 브로커에 붙는다</b>. 자기 context 의
 * 컨테이너를 세워도 다른 context 의 consumer 가 파티션을 넘겨받아 계속 소비한다(실측:
 * {@code order-svc-stock-result-group} 에 context 2개의 consumer 공존). per-context 수단으로는
 * 구조적으로 막을 수 없다.
 *
 * <p><b>왜 {@code spring.kafka.listener.auto-startup} 이 아닌가</b>: 5개 서비스 전부
 * {@link AbstractKafkaListenerContainerFactory} 를 직접 {@code @Bean} 으로 만들어 Boot 의
 * auto-configured factory 를 쓰지 않는다. 그 속성은 도달하지 않는다.
 *
 * <p><b>기본값은 켜짐</b>이므로 프로덕션 동작은 변하지 않는다. 이 빈은 프로퍼티가 명시적으로
 * {@code false} 일 때만 등록되고, 그렇게 두는 것은 Gradle {@code test} 태스크뿐이다. 리스너 소비를
 * 검증하는 테스트만 {@code @TestPropertySource("app.kafka.listener.enabled=true")} 로 다시 켜며,
 * 그 경우 {@code @DirtiesContext(AFTER_CLASS)} 로 수명을 자기 클래스에 가둔다 — 켜둔 채 context 가
 * 캐시되면 리스너가 계속 돌며 공유 브로커·DB 를 고친다.
 */
@Configuration
public class KafkaListenerStartupConfig {

    /**
     * factory 의 {@code autoStartup} 을 끈다. 서비스마다 factory 이름·개수가 다르므로
     * 이름이 아니라 타입으로 잡는다 — 새 factory 가 생겨도 게이트에서 새지 않는다.
     */
    @Bean
    @ConditionalOnProperty(name = "app.kafka.listener.enabled", havingValue = "false")
    static BeanPostProcessor kafkaListenerAutoStartupDisabler() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
                    factory.setAutoStartup(false);
                }
                return bean;
            }
        };
    }
}
