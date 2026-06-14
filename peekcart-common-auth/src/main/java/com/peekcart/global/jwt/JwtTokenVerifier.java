package com.peekcart.global.jwt;

import com.peekcart.global.auth.TokenClaims;
import com.peekcart.global.auth.TokenParseException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * JWT 액세스 토큰의 파싱·서명검증·만료검증을 담당하는 검증 전용 컴포넌트 (ADR-0014 D1-b verify).
 * jjwt {@code Claims} 타입을 외부에 노출하지 않고 {@link TokenClaims}로 캡슐화한다.
 * 발급(sign)은 User 전속 {@code JwtTokenSigner}가 담당한다.
 */
@Component
public class JwtTokenVerifier {

    private final JwtAuthProperties properties;
    private SecretKey key;

    public JwtTokenVerifier(JwtAuthProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 액세스 토큰을 파싱하여 {@link TokenClaims}로 반환한다.
     *
     * @throws TokenParseException 서명이 유효하지 않거나 토큰이 만료된 경우
     */
    public TokenClaims parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new TokenClaims(
                    Long.parseLong(claims.getSubject()),
                    claims.get("role", String.class),
                    claims.getExpiration().toInstant()
            );
        } catch (JwtException | IllegalArgumentException e) {
            throw new TokenParseException(e);
        }
    }
}
