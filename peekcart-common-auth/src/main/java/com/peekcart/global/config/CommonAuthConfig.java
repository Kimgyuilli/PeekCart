package com.peekcart.global.config;

import com.peekcart.global.jwt.JwtAuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * common-auth 모듈 설정. {@link JwtAuthProperties} 단일 설정 계약을 활성화한다 (ADR-0014 D1-b).
 * 각 서비스의 컴포넌트 스캔(com.peekcart.*)에 포함되어 전 서비스에서 동일 계약이 바인딩된다.
 */
@Configuration
@EnableConfigurationProperties(JwtAuthProperties.class)
public class CommonAuthConfig {
}
