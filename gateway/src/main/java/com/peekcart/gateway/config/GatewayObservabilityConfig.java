package com.peekcart.gateway.config;

import com.peekcart.global.config.MetricsConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 공유 관측성 설정을 gateway 컨텍스트에 들인다 (ADR-0009 §Decision S1 · ADR-0024 D2).
 *
 * <p><b>왜 명시 import 인가</b>: {@link MetricsConfig} 는 {@code com.peekcart.global.config} 에 있고
 * gateway 의 컴포넌트 스캔 기점은 {@code com.peekcart.gateway} 다. 의존성을 추가하는 것만으로는
 * 빈이 등록되지 않는다 — 실제로 그 상태에서 {@code http_server_requests_seconds} 는 발행되는데
 * {@code _bucket} 시계열만 없어서, <b>앱은 정상이고 p95 alert 만 조용히 NaN</b> 이 된다.
 *
 * <p>scan 기점을 {@code com.peekcart} 로 넓히지 않는 이유: gateway 는 WebFlux 전용이라 servlet 기반
 * 공유 설정을 우연히 끌어오면 부팅이 깨진다. 무엇을 들이는지 한 곳에 적어두는 편이 안전하다.
 */
@Configuration
@Import(MetricsConfig.class)
public class GatewayObservabilityConfig {
}
