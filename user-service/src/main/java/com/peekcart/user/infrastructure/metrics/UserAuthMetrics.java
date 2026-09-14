package com.peekcart.user.infrastructure.metrics;

import com.peekcart.global.metrics.CommitAwareMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * S9 인증 메트릭 중 <b>User 소유분</b> — reuse 감지 / 로그아웃 (ADR-0009 §Decision S9 · ADR-0024 D4).
 *
 * <p>나머지 절반(거부·429·403)은 Gateway 소유다. 같은 이름을 양쪽에서 등록하지 않는다.
 *
 * <p><b>커밋 이후에만 증가한다</b>({@link CommitAwareMetrics}). reuse 감지는 family 무효화를 커밋시키는
 * 트랜잭션 안에서 일어나는데, 본문에서 바로 올리면 이후 커밋이 실패해도 카운터만 남는다. reuse 카운터는
 * "탈취 의심이 몇 건 있었나" 를 말하는 보안 신호라, 실제로 무효화되지 않은 건이 섞이면 alert 가 거짓이 된다.
 */
@Component
public class UserAuthMetrics {

    static final String REUSE_DETECTED = "auth.token.reuse.detected";
    static final String LOGOUT = "auth.logout";

    private final Counter reuseDetected;
    private final Counter logout;

    public UserAuthMetrics(MeterRegistry registry) {
        this.reuseDetected = Counter.builder(REUSE_DETECTED)
                .description("refresh token reuse 감지 (family 무효화 동반)")
                .register(registry);
        this.logout = Counter.builder(LOGOUT)
                .description("로그아웃 — family deny + refresh 전체 무효화")
                .register(registry);
    }

    /** refresh token reuse 감지 1건. */
    public void reuseDetected() {
        CommitAwareMetrics.increment(reuseDetected);
    }

    /** 로그아웃 1건. */
    public void loggedOut() {
        CommitAwareMetrics.increment(logout);
    }
}
