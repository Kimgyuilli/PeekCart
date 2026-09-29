package com.peekcart.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.util.List;

/**
 * 리소스 서비스의 내부 토큰 <b>검증</b> 설정 (ADR-0017 D3).
 *
 * <p><b>키 도메인 분리</b>: Gateway 공개키는 여기({@code app.internal-token.public-keys})에만
 * 둔다. User 의 {@code app.jwt.rs256.public-keys}({@code RsaPublicKeyRegistry}) 에 넣으면
 * {@code JwkController} 가 JWKS 로 전량 게시해 내부 신뢰 앵커가 외부에 노출된다.
 * 이 분리는 {@code scripts/internal-key-ownership-lint.sh} 가 강제한다.
 *
 * @param mode          검증 모드. 기본 {@link Mode#SIGNED_ONLY}(평문 헤더 불신)
 * @param publicKeys    Gateway 서명 공개키 목록(kid → SPKI PEM). active/previous 회전 overlap 을 위해 복수 허용
 * @param skewSeconds   clock skew 허용치(초). NTP 전제이므로 좁게 유지한다
 * @param maxTtlSeconds 수용할 토큰 수명 상한(초). {@code exp-iat} 가 이보다 크면 거부한다
 */
@ConfigurationProperties(prefix = "app.internal-token")
public record InternalTokenProperties(
        Mode mode,
        List<PublicKeyEntry> publicKeys,
        Integer skewSeconds,
        Integer maxTtlSeconds
) {

    /** skew 허용 상한 — 이보다 크면 만료 검사가 사실상 무력해진다. */
    public static final int MAX_SKEW_SECONDS = 5;

    /** 수명 상한의 상한 — 정책이 아무리 느슨해도 내부 홉 토큰이 장수명이 되면 안 된다. */
    public static final int TTL_CEILING_SECONDS = 300;

    private static final int DEFAULT_SKEW_SECONDS = 5;
    private static final int DEFAULT_MAX_TTL_SECONDS = 120;

    /** 검증 모드 — 평문 헤더에서 서명 토큰으로 가는 롤아웃 단계와 1:1 대응한다. */
    public enum Mode {
        /**
         * 전환기: {@code X-Internal-Auth} 를 우선하되, 없으면 평문 {@code X-User-*} 도 수용한다.
         * 구 Gateway 이미지가 아직 평문을 주입하는 구간에서만 쓴다. {@code fid} 는 선택.
         */
        DUAL_ACCEPT,
        /**
         * 최종: {@code X-Internal-Auth} 만 수용하고 평문 {@code X-User-*} 는 <b>무시</b>한다
         * (401 이 아니라 미인증 — 공개 경로가 헤더 유무로 깨지지 않게). {@code fid} 필수.
         */
        SIGNED_ONLY
    }

    public InternalTokenProperties {
        mode = mode == null ? Mode.SIGNED_ONLY : mode;
        publicKeys = publicKeys == null ? List.of() : List.copyOf(publicKeys);
        skewSeconds = skewSeconds == null ? DEFAULT_SKEW_SECONDS : skewSeconds;
        maxTtlSeconds = maxTtlSeconds == null ? DEFAULT_MAX_TTL_SECONDS : maxTtlSeconds;
    }

    /** {@code fid} 필수 여부 — 모드에서 파생한다(따로 설정하면 둘이 어긋날 수 있다). */
    public boolean familyIdRequired() {
        return mode == Mode.SIGNED_ONLY;
    }

    /**
     * 부팅 시 오배선을 잡는다. 정상 배선만 통과시키는 게 아니라, 키가 하나도 없거나
     * 범위를 벗어난 설정이면 서비스 기동 자체를 거부한다 — 런타임에 전 요청 401 로 발현되면 늦다.
     */
    public void validate() {
        if (publicKeys.isEmpty()) {
            throw new IllegalStateException(
                    "app.internal-token.public-keys 가 비어 있다 — Gateway 서명을 검증할 수 없어 모든 요청이 거부된다");
        }
        if (skewSeconds < 0 || skewSeconds > MAX_SKEW_SECONDS) {
            throw new IllegalStateException(
                    "app.internal-token.skew-seconds=" + skewSeconds + " — 0..%d 범위여야 한다".formatted(MAX_SKEW_SECONDS));
        }
        if (maxTtlSeconds < 1 || maxTtlSeconds > TTL_CEILING_SECONDS) {
            throw new IllegalStateException(
                    "app.internal-token.max-ttl-seconds=" + maxTtlSeconds
                            + " — 1..%d 범위여야 한다".formatted(TTL_CEILING_SECONDS));
        }
    }

    /**
     * 공개키 1개 항목.
     *
     * @param kid      Gateway 가 헤더에 기록하는 키 식별자
     * @param location 공개키(SPKI PEM) 위치
     */
    public record PublicKeyEntry(String kid, Resource location) {
    }
}
