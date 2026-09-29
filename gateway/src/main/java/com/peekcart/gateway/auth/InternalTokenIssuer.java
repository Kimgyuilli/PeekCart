package com.peekcart.gateway.auth;

import com.peekcart.internaltoken.InternalTokenContract;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Gateway 서명 내부 토큰 발행기 (ADR-0017 D1).
 *
 * <p>사용자 토큰 검증이 끝난 {@link GatewayClaims} 를 짧은 수명 RS256 JWT 로 재서명한다. 리소스 서비스는
 * 이 토큰만 신뢰하므로, 평문 헤더 위조로는 신원을 만들 수 없다(NetworkPolicy AND 서명).
 *
 * <p><b>부팅 fail-fast</b>: 개인키를 생성자에서 로드한다 — 키가 없거나 깨졌으면 context 기동을 거부한다.
 * 첫 요청까지 지연시키면 "배포는 성공했는데 전 요청이 실패하는" 상태가 된다.
 */
@Component
public class InternalTokenIssuer {

    private final String activeKid;
    private final int ttlSeconds;
    private final boolean requireFamilyId;
    private final RSAPrivateKey privateKey;
    private final Clock clock;

    @Autowired
    public InternalTokenIssuer(InternalTokenProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 테스트가 고정 시각을 주입하기 위한 생성자(교차모듈 conformance 테스트용). */
    InternalTokenIssuer(InternalTokenProperties properties, Clock clock) {
        properties.validate();
        this.activeKid = properties.activeKid();
        this.ttlSeconds = properties.ttlSeconds();
        this.requireFamilyId = properties.requireFamilyId();
        this.privateKey = loadPrivateKey(properties);
        this.clock = clock;
    }

    /**
     * 검증된 신원을 내부 토큰으로 서명한다.
     *
     * @throws IssuanceRefusedException {@code fid} 가 필수인데 없는 경우(fail-closed — 신원을 통과시키지 않는다)
     */
    public String issue(GatewayClaims claims) {
        String familyId = claims.familyId();
        boolean hasFamilyId = familyId != null && !familyId.isBlank();
        if (requireFamilyId && !hasFamilyId) {
            throw new IssuanceRefusedException("family-less 신원은 내부 토큰을 발행하지 않는다(userId=" + claims.userId() + ")");
        }

        Instant now = clock.instant();
        var builder = Jwts.builder()
                .header().keyId(activeKid).and()
                .issuer(InternalTokenContract.ISSUER)
                .subject(String.valueOf(claims.userId()))
                .claim(InternalTokenContract.CLAIM_ROLE, claims.role())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)));
        if (hasFamilyId) {
            builder.claim(InternalTokenContract.CLAIM_FAMILY_ID, familyId.trim());
        }
        return builder.signWith(privateKey, Jwts.SIG.RS256).compact();
    }

    private static RSAPrivateKey loadPrivateKey(InternalTokenProperties properties) {
        String description = properties.privateKeyLocation().getDescription();
        byte[] der;
        try (InputStream in = properties.privateKeyLocation().getInputStream()) {
            String pem = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            der = Base64.getDecoder().decode(pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", ""));
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Gateway 내부 토큰 개인키 읽기 실패: " + description, e);
        }
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Gateway 내부 토큰 개인키 로드 실패(PKCS#8 PEM 여야 함): " + description, e);
        }
    }

    /** 정책상 발행을 거부한 경우 — 클라이언트 신원 문제이므로 401 로 매핑한다. */
    public static class IssuanceRefusedException extends RuntimeException {
        public IssuanceRefusedException(String message) {
            super(message);
        }
    }
}
