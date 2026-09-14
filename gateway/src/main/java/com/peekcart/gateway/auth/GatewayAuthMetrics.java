package com.peekcart.gateway.auth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * S9 인증 실패 메트릭 — Gateway 소유 (ADR-0009 §Decision S9 · ADR-0024 D4).
 *
 * <p><b>이름 1개소</b>: {@code auth.failure} 는 여기서만 등록·증가한다. 리소스 서비스가 같은 이름을
 * 재선언하면 같은 series 에 두 의미가 섞인다(ADR-0009 S9 "이동·복제 금지").
 *
 * <p><b>모든 reason 을 부팅 시 0 으로 등록한다.</b> 지연 등록이면 아직 일어나지 않은 사유는 series 자체가
 * 없어서, 대시보드에서 "0 건" 과 "계측이 없다" 가 구분되지 않는다. alert 도 없는 series 를 기다린다.
 */
@Component
public class GatewayAuthMetrics {

    static final String AUTH_FAILURE = "auth.failure";

    private final Map<AuthFailureReason, Counter> failures = new EnumMap<>(AuthFailureReason.class);

    public GatewayAuthMetrics(MeterRegistry registry) {
        for (AuthFailureReason reason : AuthFailureReason.values()) {
            failures.put(reason, Counter.builder(AUTH_FAILURE)
                    .tag("reason", reason.tag())
                    .description("Gateway 가 거부한 요청 (401/503, 사유별)")
                    .register(registry));
        }
    }

    /** 거부 1건. {@code GatewayAuthenticationFilter.reject()} 단일 지점에서만 호출한다. */
    public void failed(AuthFailureReason reason) {
        failures.get(reason).increment();
    }
}
