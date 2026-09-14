package com.peekcart.gateway.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * blacklist + family deny 조회 (reactive) — ADR-0013 D4 · ADR-0014 D1-c · 구현 ③ PR3a.
 *
 * <p><b>키 계약은 PR2 확정분을 그대로 재사용한다</b>(새 스킴 금지 — write owner 는 user-service
 * {@code TokenBlacklistRepository}):
 * <ul>
 *   <li>{@code auth:blacklist:<sha256hex(token)>} — logout 등 개별 토큰 차단(신키)</li>
 *   <li>{@code auth:deny:family:<familyId>} — reuse 감지 family 전체 차단</li>
 * </ul>
 *
 * <p><b>시맨틱</b>: miss=통과 / hit=차단(401) / <b>조회 실패=fail-closed</b>.
 * 단 fail-closed 를 401 이 아니라 <b>503</b>(의존성 장애)로 구분해 올리기 위해
 * {@link DenyLookupUnavailableException} 을 던진다(계획 P12 응답 행렬 — 보안 판정 실패와 의존성 장애 분리).
 *
 * <p><b>family-less 계약</b>: {@code familyId} 가 null/blank 면 family deny 를 조회하지 않고 blacklist 만
 * 검사한다. claim 부재는 "조회 실패"가 아니므로 fail-closed 대상이 아니며,
 * {@code auth:deny:family:null} 오조회를 막는다. PR3d 게이트 통과 후 이 경로는 제거된다.
 */
@Component
public class TokenDenyLookup {

    private static final Logger log = LoggerFactory.getLogger(TokenDenyLookup.class);

    private static final String BLACKLIST_PREFIX = "auth:blacklist:";
    private static final String FAMILY_DENY_PREFIX = "auth:deny:family:";

    private final ReactiveStringRedisTemplate redis;

    public TokenDenyLookup(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 차단 대상인지 조회한다.
     *
     * @return true=차단(401 대상), false=통과.
     *         Redis 장애 시 {@link DenyLookupUnavailableException} 으로 종료(→503, fail-closed)
     */
    public Mono<Boolean> isDenied(String token, String familyId) {
        // PR4: 전환기 legacy `bl:<원문토큰>` dual-read 제거 — 그 키를 쓰는 코드가 저장소에
        // 하나도 없다(write owner 였던 user-service 는 해시 신키만 기록한다). 원문 토큰을 키로
        // 조회하는 경로가 남아 있는 것 자체가 "원문 저장 금지"(ADR-0014) 와 어긋난 신호였다.
        return hasKey(BLACKLIST_PREFIX + sha256Hex(token))
                .flatMap(hit -> {
                    if (hit) {
                        return Mono.just(true);
                    }
                    if (familyId == null || familyId.isBlank()) {
                        return Mono.just(false);
                    }
                    return hasKey(FAMILY_DENY_PREFIX + familyId);
                })
                .onErrorMap(e -> !(e instanceof DenyLookupUnavailableException), e -> {
                    log.warn("[alert] blacklist/deny 조회 실패 — fail-closed(503)", e);
                    return new DenyLookupUnavailableException("Redis 조회 실패", e);
                });
    }

    private Mono<Boolean> hasKey(String key) {
        return redis.hasKey(key).defaultIfEmpty(false);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    /** Redis 의존성 장애 — fail-closed 이되 503 으로 분류(401 아님). */
    public static class DenyLookupUnavailableException extends RuntimeException {
        public DenyLookupUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
