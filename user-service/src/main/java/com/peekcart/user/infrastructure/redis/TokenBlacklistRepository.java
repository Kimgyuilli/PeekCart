package com.peekcart.user.infrastructure.redis;

import com.peekcart.global.auth.TokenBlacklistPort;
import com.peekcart.global.auth.TokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis 기반 토큰 블랙리스트 저장소 (write owner — ADR-0014 D1-c · PR2b/U5).
 * 로그아웃된 액세스 토큰을 블랙리스트에 등록하고,
 * 토큰 로테이션 시 유효성 확인을 위한 그레이스 피리어드를 관리한다.
 *
 * <p><b>U5 namespace 마이그레이션</b>: 블랙리스트 신키 = {@code auth:blacklist:<sha256hex(token)>}
 * (원문 대신 해시 저장 — ADR-0014 "토큰 원문 금지" 충족). read(common-auth)는 전환기 dual-read 로
 * legacy {@code bl:<token>} 도 TTL 동안 함께 조회한다. 그레이스 피리어드(refresh UUID)는 별도
 * namespace 로 변경 없음.
 */
@Repository
@RequiredArgsConstructor
public class TokenBlacklistRepository implements TokenBlacklistPort {

    private static final String BLACKLIST_PREFIX = "auth:blacklist:";
    private static final String GRACE_PERIOD_PREFIX = "gp:";

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 액세스 토큰을 블랙리스트에 등록한다. 신키(해시)로만 기록한다. TTL은 토큰 잔여 유효 기간으로 설정한다.
     *
     * @param token      블랙리스트에 추가할 토큰
     * @param ttlSeconds Redis 키 만료 시간(초)
     */
    public void addToBlacklist(String token, long ttlSeconds) {
        redisTemplate.opsForValue().set(BLACKLIST_PREFIX + TokenHasher.sha256Hex(token), "1", ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 토큰 로테이션 직후 짧은 시간 동안 구 토큰 재사용을 허용하기 위해
     * 그레이스 피리어드 항목을 Redis에 저장한다.
     *
     * @param token      구 리프레시 토큰
     * @param userId     토큰 소유자 ID
     * @param ttlSeconds 그레이스 피리어드 유효 시간(초)
     */
    public void addGracePeriod(String token, long userId, long ttlSeconds) {
        redisTemplate.opsForValue().set(GRACE_PERIOD_PREFIX + token, String.valueOf(userId), ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 그레이스 피리어드가 유효한 토큰에서 소유자 ID를 원자적으로 조회하고 즉시 삭제한다.
     * Redis GETDEL 명령어로 조회와 삭제가 원자적으로 처리되어 동시 요청에 의한 이중 발급을 방지한다.
     *
     * @param token 구 리프레시 토큰
     * @return 소유자 userId, 그레이스 피리어드가 없거나 만료됐으면 {@code empty}
     */
    public Optional<Long> consumeGracePeriod(String token) {
        String value = redisTemplate.opsForValue().getAndDelete(GRACE_PERIOD_PREFIX + token);
        return Optional.ofNullable(value).map(Long::parseLong);
    }
}
