package com.peekcart.gateway.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.security.Key;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;

/**
 * Gateway 전용 JWT 검증기 (reactive) — ADR-0013 D1/D3 · 구현 ③ PR3a.
 *
 * <p>servlet 측 {@code JwtTokenVerifier} 와 <b>동일 계약</b>을 재구현한다(B6: common-auth 는 servlet MVC
 * 스택이라 재사용 불가). 동등성은 conformance golden vector 로 고정한다(계획 P19 · loop2 #6).
 *
 * <p><b>alg allow-list</b>: <b>RS256 단독</b>(JWKS kid 선택). HS 계열·none 은 전부 거부한다 —
 * 전환기 HS512 fallback 은 PR4 에서 제거했다(발급측이 RS256 단독이라 수용할 레거시 토큰이 없다).
 *
 * <p>키 해석이 비동기(JWKS fetch)라 jjwt {@code keyLocator}(동기) 대신
 * <b>헤더 선파싱 → 키 해석(Mono) → 서명 검증</b> 순서로 처리한다. 선파싱한 헤더는 키 <i>선택</i>에만
 * 쓰이며, 신뢰는 이후 서명 검증이 부여한다.
 */
@Component
public class GatewayJwtVerifier {

    private final JwksKeyRegistry keyRegistry;
    private final JwtGatewayProperties properties;
    private final ObjectMapper objectMapper;
    public GatewayJwtVerifier(JwksKeyRegistry keyRegistry,
                              JwtGatewayProperties properties,
                              ObjectMapper objectMapper) {
        this.keyRegistry = keyRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 토큰을 검증하고 클레임을 반환한다.
     *
     * @return 실패 시 {@link InvalidTokenException}(→401) 또는
     *         {@link JwksKeyRegistry.JwksUnavailableException}(→503) 로 종료되는 Mono
     */
    public Mono<GatewayClaims> verify(String token) {
        final JwsHeader header;
        try {
            header = readHeader(token);
        } catch (RuntimeException e) {
            return Mono.error(new InvalidTokenException(AuthFailureReason.MALFORMED, "JWT 헤더 파싱 실패", e));
        }
        return resolveKey(header).flatMap(key -> parseClaims(token, key));
    }

    private Mono<Key> resolveKey(JwsHeader header) {
        if ("RS256".equals(header.alg())) {
            return keyRegistry.resolve(header.kid())
                    .map(Key.class::cast)
                    // unknown kid = 위조/폐기 → 401. JwksUnavailableException 은 전파해 503.
                    .onErrorMap(JwksKeyRegistry.UnknownKidException.class,
                            e -> new InvalidTokenException(AuthFailureReason.UNKNOWN_KID, e.getMessage(), e));
        }
        return Mono.error(new InvalidTokenException(AuthFailureReason.ALG_NOT_ALLOWED,
                "허용되지 않은 서명 알고리즘: " + header.alg()));
    }

    private Mono<GatewayClaims> parseClaims(String token, Key key) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith((PublicKey) key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            // exp 필수(GW-2 c1:1): jjwt 는 exp 가 *있을 때만* 만료를 검사한다. null 을 허용하면
            // 서명만 유효한 무기한 토큰이 통과해 만료·로그아웃·회전이 무력화된다.
            if (claims.getExpiration() == null) {
                return Mono.error(new InvalidTokenException(AuthFailureReason.MISSING_EXP, "exp 클레임 부재 — 무기한 토큰 거부"));
            }
            return Mono.just(new GatewayClaims(
                    Long.parseLong(claims.getSubject()),
                    claims.get("role", String.class),
                    claims.get("family_id", String.class),
                    claims.getExpiration().toInstant()
            ));
        } catch (ExpiredJwtException e) {
            // 만료를 서명오류와 합치면 "정상적으로 늙은 토큰" 과 "위조 시도" 가 같은 숫자가 된다 —
            // 전자는 기저율이고 후자는 사건이다(S9 · ADR-0024 D4).
            return Mono.error(new InvalidTokenException(AuthFailureReason.EXPIRED, "JWT 만료", e));
        } catch (RuntimeException e) {
            // 서명 불일치·subject 형식 오류 등 → 401
            return Mono.error(new InvalidTokenException(AuthFailureReason.BAD_SIGNATURE, "JWT 검증 실패", e));
        }
    }

    private JwsHeader readHeader(String token) {
        int dot = token.indexOf('.');
        if (dot <= 0) {
            throw new IllegalArgumentException("JWT 형식 오류");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(token.substring(0, dot));
        try {
            JsonNode node = objectMapper.readTree(decoded);
            return new JwsHeader(text(node, "alg"), text(node, "kid"));
        } catch (Exception e) {
            throw new IllegalArgumentException("JWT 헤더 디코딩 실패", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    record JwsHeader(String alg, String kid) {
    }

    /**
     * 서명/만료/alg/kid 문제 — 인증 실패로 401.
     *
     * <p>{@link AuthFailureReason} 을 함께 나른다(S9 · ADR-0024 D4). message 는 로그용이고,
     * 메트릭 태그가 되는 것은 <b>reason 뿐</b>이다 — message 에는 토큰 파편이 섞일 수 있다.
     */
    public static class InvalidTokenException extends RuntimeException {
        private final AuthFailureReason reason;

        public InvalidTokenException(AuthFailureReason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public InvalidTokenException(AuthFailureReason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public AuthFailureReason reason() {
            return reason;
        }
    }
}
