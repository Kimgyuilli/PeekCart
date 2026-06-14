package com.peekcart.global.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer 메트릭 설정.
 * <p>HTTP 요청 histogram bucket 을 활성화하여 Prometheus 에서 p50/p95/p99 계산이 가능하도록 한다.
 * <p>YAML 프로파일 병합 이슈를 회피하기 위해 {@code management.metrics.distribution} 대신
 * Java Config 으로 관리한다 (see D-001).
 */
@Configuration
public class MetricsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> httpHistogramCustomizer() {
        return registry -> registry.config().meterFilter(
                new MeterFilter() {
                    @Override
                    public DistributionStatisticConfig configure(
                            io.micrometer.core.instrument.Meter.Id id,
                            DistributionStatisticConfig config) {
                        if (id.getName().equals("http.server.requests")) {
                            return DistributionStatisticConfig.builder()
                                    .percentilesHistogram(true)
                                    .build()
                                    .merge(config);
                        }
                        return config;
                    }
                });
    }
}
