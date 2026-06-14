package com.peekcart.global.jwt;

import com.peekcart.global.auth.TokenIssuer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 액세스 토큰 발급(sign) 전용 컴포넌트 (ADR-0014 D1-b sign, User 전속).
 * 검증(verify)은 common-auth {@code JwtTokenVerifier}가 담당한다.
 * 검증자와 동일 {@link JwtAuthProperties}(대칭키)를 바인딩한다.
 */
@Component
public class JwtTokenSigner implements TokenIssuer {

    private final JwtAuthProperties properties;
    private SecretKey key;

    public JwtTokenSigner(JwtAuthProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public IssuedTokens issue(Long userId, String role) {
        String accessToken = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + properties.accessTokenExpiry()))
                .signWith(key)
                .compact();
        String refreshTokenValue = UUID.randomUUID().toString();
        LocalDateTime refreshTokenExpiresAt = LocalDateTime.now().plusSeconds(properties.refreshTokenExpiry() / 1000);
        return new IssuedTokens(accessToken, refreshTokenValue, refreshTokenExpiresAt);
    }
}
