package com.peekcart.global.security;

import com.peekcart.global.auth.LoginUser;
import com.peekcart.internaltoken.InternalTokenContract;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

/**
 * Gateway 서명 내부 토큰 검증기 (ADR-0017 D3).
 *
 * <p>사용자 access token 을 그대로 제시하는 우회를 막기 위해 <b>iss·kid·alg 를 모두 핀</b>한다 —
 * 서명이 유효해도 발행자가 Gateway 가 아니거나 kid 가 승인 집합 밖이면 거부한다.
 *
 * <p><b>거부 사유는 로그로만</b> 남기고 응답에는 싣지 않는다(토큰 내용 유추 방지).
 */
@Component
public class InternalTokenVerifier {

    private static final Set<String> ALLOWED_ROLES = Set.of("USER", "ADMIN");

    private final InternalGatewayPublicKeyRegistry keyRegistry;
    private final InternalTokenProperties properties;
    private final Clock clock;

    @Autowired
    public InternalTokenVerifier(InternalGatewayPublicKeyRegistry keyRegistry,
                                 InternalTokenProperties properties) {
        this(keyRegistry, properties, Clock.systemUTC());
    }

    /** 테스트가 고정 시각을 주입하기 위한 생성자(교차모듈 conformance 테스트용). */
    InternalTokenVerifier(InternalGatewayPublicKeyRegistry keyRegistry,
                          InternalTokenProperties properties,
                          Clock clock) {
        this.keyRegistry = keyRegistry;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 내부 토큰을 검증하고 신원을 반환한다.
     *
     * @throws InvalidInternalTokenException 서명·alg·iss·kid·수명·claim 중 하나라도 계약 위반인 경우
     */
    public LoginUser verify(String token) {
        Claims claims = parse(token);

        Date issuedAt = claims.getIssuedAt();
        Date expiration = claims.getExpiration();
        if (issuedAt == null) {
            throw new InvalidInternalTokenException("iat 클레임 부재");
        }
        // jjwt 는 exp 가 *있을 때만* 만료를 검사한다 — 부재를 허용하면 무기한 내부 토큰이 통과한다.
        if (expiration == null) {
            throw new InvalidInternalTokenException("exp 클레임 부재 — 무기한 토큰 거부");
        }
        if (!expiration.after(issuedAt)) {
            throw new InvalidInternalTokenException("exp 가 iat 이후가 아님");
        }
        long lifetimeSeconds = (expiration.getTime() - issuedAt.getTime()) / 1000;
        if (lifetimeSeconds > properties.maxTtlSeconds()) {
            throw new InvalidInternalTokenException(
                    "토큰 수명 " + lifetimeSeconds + "s 가 상한 " + properties.maxTtlSeconds() + "s 초과");
        }
        Instant now = clock.instant();
        if (issuedAt.toInstant().isAfter(now.plusSeconds(properties.skewSeconds()))) {
            throw new InvalidInternalTokenException("iat 가 미래 시각");
        }

        return new LoginUser(parseUserId(claims), parseRole(claims), parseFamilyId(claims));
    }

    private Claims parse(String token) {
        try {
            return Jwts.parser()
                    .keyLocator(keyLocator())
                    .clock(() -> Date.from(clock.instant()))
                    .clockSkewSeconds(properties.skewSeconds())
                    // 사용자 access token(iss 다름)이 내부 토큰 자리에 오면 여기서 걸린다.
                    .requireIssuer(InternalTokenContract.ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (InvalidInternalTokenException e) {
            throw e;
        } catch (RuntimeException e) {
            // 서명 불일치·만료·형식 오류·iss 불일치 등 — 사유를 응답에 노출하지 않는다.
            throw new InvalidInternalTokenException("내부 토큰 검증 실패: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * kid 로 Gateway 공개키를 선택하면서 alg 를 함께 핀한다. 대칭키 서명(HS*)이나 {@code none} 은
     * 여기서 키 해석 자체가 실패해 서명 우회가 성립하지 않는다.
     */
    private Locator<Key> keyLocator() {
        return new Locator<>() {
            @Override
            public Key locate(Header header) {
                if (!(header instanceof JwsHeader jwsHeader)) {
                    throw new InvalidInternalTokenException("서명되지 않은 토큰");
                }
                if (!InternalTokenContract.ALGORITHM.equals(jwsHeader.getAlgorithm())) {
                    throw new InvalidInternalTokenException("허용되지 않은 알고리즘: " + jwsHeader.getAlgorithm());
                }
                Key key = keyRegistry.find(jwsHeader.getKeyId());
                if (key == null) {
                    throw new InvalidInternalTokenException("승인되지 않은 kid: " + jwsHeader.getKeyId());
                }
                return key;
            }
        };
    }

    private Long parseUserId(Claims claims) {
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidInternalTokenException("sub 클레임 부재");
        }
        long userId;
        try {
            userId = Long.parseLong(subject.trim());
        } catch (NumberFormatException e) {
            throw new InvalidInternalTokenException("sub 형식 오류");
        }
        if (userId <= 0) {
            throw new InvalidInternalTokenException("sub 가 양의 정수가 아님");
        }
        return userId;
    }

    private String parseRole(Claims claims) {
        String role = claimAsString(claims, InternalTokenContract.CLAIM_ROLE);
        if (role == null || !ALLOWED_ROLES.contains(role)) {
            throw new InvalidInternalTokenException("허용되지 않은 role");
        }
        return role;
    }

    private String parseFamilyId(Claims claims) {
        String familyId = claimAsString(claims, InternalTokenContract.CLAIM_FAMILY_ID);
        boolean present = familyId != null && !familyId.isBlank();
        if (properties.familyIdRequired() && !present) {
            throw new InvalidInternalTokenException("fid 클레임 부재 — SIGNED_ONLY 모드에서는 필수");
        }
        return present ? familyId.trim() : null;
    }

    /** claim 을 문자열로 읽는다. 타입이 다르면(숫자/객체 등) 계약 위반으로 거부한다. */
    private String claimAsString(Claims claims, String name) {
        try {
            return claims.get(name, String.class);
        } catch (RuntimeException e) {
            throw new InvalidInternalTokenException(name + " 클레임 타입 오류");
        }
    }

    /** 내부 토큰 계약 위반 — 인증 실패(401)로 매핑한다. */
    public static class InvalidInternalTokenException extends RuntimeException {
        public InvalidInternalTokenException(String message) {
            super(message);
        }

        public InvalidInternalTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
