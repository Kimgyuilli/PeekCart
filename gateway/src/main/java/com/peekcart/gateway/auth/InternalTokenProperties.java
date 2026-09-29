package com.peekcart.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Gateway 내부 토큰 <b>발행</b> 설정 (ADR-0017 D1/D2).
 *
 * <p>개인키는 산출물에 포함하지 않는다 — 파일 마운트(운영: Secret Manager CSI, 로컬: gitignored 파일)로
 * 주입한다(ADR-0013 D2). 환경변수 직접 주입은 금지한다.
 *
 * <p><b>skew 를 두지 않는 이유</b>: 발행은
 * 자기 시계로 {@code iat=now} 를 찍을 뿐이라 skew 를 쓸 곳이 없다. clock skew 흡수는 검증측
 * ({@code app.internal-token.skew-seconds}) 단독 책임이며, 발행측에 미사용 설정을 두면 "조정했는데
 * 아무 효과가 없는 손잡이" 가 된다.
 *
 * @param activeKid          발행 시 JWT 헤더 {@code kid} 로 기록할 현재 키 식별자
 * @param privateKeyLocation 서명 개인키(PKCS#8 PEM) 위치
 * @param ttlSeconds         토큰 수명(초). 내부 홉 전용이라 짧게 유지한다
 * @param requireFamilyId    {@code fid} 필수 여부. true 면 family-less 신원의 발행을 거부한다(fail-closed)
 */
@ConfigurationProperties(prefix = "app.gateway.internal-token")
public record InternalTokenProperties(
        String activeKid,
        Resource privateKeyLocation,
        Integer ttlSeconds,
        Boolean requireFamilyId
) {

    /** 내부 홉 전용 토큰의 수명 상한 — 이보다 길면 탈취 시 재사용 창이 과도해진다. */
    public static final int MAX_TTL_SECONDS = 120;

    private static final int DEFAULT_TTL_SECONDS = 30;

    public InternalTokenProperties {
        ttlSeconds = ttlSeconds == null ? DEFAULT_TTL_SECONDS : ttlSeconds;
        requireFamilyId = requireFamilyId == null ? Boolean.TRUE : requireFamilyId;
    }

    /**
     * 부팅 시 설정 자체의 정합성을 강제한다(fail-fast). 키 로딩 실패는
     * {@link InternalTokenIssuer} 생성자가 별도로 잡는다.
     */
    public void validate() {
        if (activeKid == null || activeKid.isBlank()) {
            throw new IllegalStateException("app.gateway.internal-token.active-kid 가 비어 있다 — 내부 토큰 발행 불가");
        }
        if (privateKeyLocation == null) {
            throw new IllegalStateException("app.gateway.internal-token.private-key-location 미설정 — 내부 토큰 발행 불가");
        }
        if (ttlSeconds < 1 || ttlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalStateException(
                    "app.gateway.internal-token.ttl-seconds=" + ttlSeconds + " — 1..%d 범위여야 한다".formatted(MAX_TTL_SECONDS));
        }
    }
}
