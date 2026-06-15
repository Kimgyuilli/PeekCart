package com.peekcart.support;

import com.peekcart.global.port.SlackPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 통합 테스트 공통 설정.
 *
 * <p>no-op SlackPort mock을 제공한다.
 * 커스텀 SlackPort가 필요한 테스트(예: DlqIntegrationTest의 호출 카운터)는
 * 이 설정을 import하지 않고 자체 TestConfig를 유지한다.</p>
 */
@TestConfiguration
public class IntegrationTestConfig {

    @Bean
    SlackPort slackPort() {
        return message -> {};
    }
}
