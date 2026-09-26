package com.peekcart.user.application;

import com.peekcart.support.SharedContainers;
import com.peekcart.support.TestRsaKeys;
import com.peekcart.user.application.dto.TokenResult;
import com.peekcart.user.domain.exception.UserException;
import com.peekcart.user.presentation.dto.request.SignupRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Refresh Token Reuse Detection 통합 회귀 (ADR-0013 D4, 구현 ③ PR2).
 * 실 MySQL/Redis 로 상태전이·grace 원자성·reuse→family revoke→deny write·마이그레이션 스키마를 검증한다.
 */
@SpringBootTest
@Import(SharedContainers.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@DisplayName("Refresh Token Reuse Detection 통합 테스트")
class RefreshTokenReuseIntegrationTest {

    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        TestRsaKeys.register(registry);
    }

    @Autowired AuthService authService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RedisTemplate<String, String> redisTemplate;

    @org.junit.jupiter.api.BeforeEach
    void cleanState() {
        // @SpringBootTest 는 롤백되지 않으므로 전역 ACTIVE 카운트 오염을 막기 위해 메서드마다 초기화한다.
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        java.util.Set<String> denyKeys = redisTemplate.keys("auth:deny:family:*");
        if (denyKeys != null && !denyKeys.isEmpty()) {
            redisTemplate.delete(denyKeys);
        }
    }

    private TokenResult signup() {
        return authService.signup(new SignupRequest(
                "reuse-" + System.nanoTime() + "@peekcart.com", "password123", "리유즈테스트"));
    }

    private int activeCount() {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE status = 'ACTIVE'", Integer.class);
        return c == null ? 0 : c;
    }

    // ── 마이그레이션 회귀 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("V2 마이그레이션: token 컬럼 제거 + token_hash unique index 존재 + 발급 토큰은 ACTIVE")
    void migration_schemaShape_andActiveOnIssue() {
        // token 평문 컬럼은 드롭됨
        Integer tokenCol = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_name = 'refresh_tokens' AND column_name = 'token'", Integer.class);
        assertThat(tokenCol).isZero();

        // token_hash unique index 존재
        Integer uniqIdx = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics " +
                        "WHERE table_name = 'refresh_tokens' AND index_name = 'uk_refresh_tokens_token_hash' " +
                        "AND non_unique = 0", Integer.class);
        assertThat(uniqIdx).isPositive();

        signup();
        assertThat(activeCount()).isEqualTo(1);
    }

    // ── 정상 로테이션 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("ACTIVE 로테이션: 새 토큰 발급 후 family 내 ACTIVE 는 1개 유지")
    void rotateActive_keepsSingleActive() {
        TokenResult first = signup();

        TokenResult rotated = authService.refresh(first.refreshToken());

        assertThat(rotated.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(activeCount()).isEqualTo(1);
    }

    // ── grace 동시성 (원자성) ──────────────────────────────────────────────────

    @Test
    @DisplayName("동일 ACTIVE 토큰 병렬 2요청: 정확히 1건만 성공(원자적 로테이션)")
    void concurrentActiveRotation_exactlyOneSucceeds() throws Exception {
        TokenResult issued = signup();
        String raw = issued.refreshToken();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Callable<Void> task = () -> {
            try {
                authService.refresh(raw);
                success.incrementAndGet();
            } catch (UserException e) {
                rejected.incrementAndGet();
            }
            return null;
        };
        List<Future<Void>> futures = pool.invokeAll(List.of(task, task));
        for (Future<Void> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        assertThat(activeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("grace 성공(동시요청): 기존 replacement 강제 로테이션 → ACTIVE 1개 + replacement 재제시 거부")
    void graceSuccess_keepsSingleActive_andRejectsPriorReplacement() {
        TokenResult zero = signup();
        // 1차 로테이션 — raw0 → ROTATED(grace 활성), raw1 ACTIVE
        TokenResult one = authService.refresh(zero.refreshToken());
        // grace 내 raw0 재제시(동시요청) → raw2 발급 + raw1 강제 로테이션
        TokenResult two = authService.refresh(zero.refreshToken());

        assertThat(two.refreshToken()).isNotEqualTo(one.refreshToken());
        assertThat(activeCount()).isEqualTo(1);

        // 강제 로테이션된 raw1(grace 없음) 재제시 → reuse 로 거부
        assertThatThrownBy(() -> authService.refresh(one.refreshToken()))
                .isInstanceOf(UserException.class);
    }

    @Test
    @DisplayName("grace(raw0) ↔ replacement(raw1) 정상 refresh 동시: family 내 ACTIVE 는 2개가 되지 않는다")
    void graceAndReplacementRefreshConcurrent_neverTwoActive() throws Exception {
        TokenResult zero = signup();
        TokenResult one = authService.refresh(zero.refreshToken()); // raw0 ROTATED(grace), raw1 ACTIVE

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Void> graceReq = () -> { swallow(() -> authService.refresh(zero.refreshToken())); return null; };
        Callable<Void> activeReq = () -> { swallow(() -> authService.refresh(one.refreshToken())); return null; };
        for (Future<Void> f : pool.invokeAll(List.of(graceReq, activeReq))) {
            f.get();
        }
        pool.shutdown();

        // 어느 순서로 겹치든 ACTIVE 2개(이중 발급)는 발생하지 않아야 한다
        assertThat(activeCount()).isLessThanOrEqualTo(1);
    }

    private void swallow(Runnable r) {
        try {
            r.run();
        } catch (UserException ignored) {
            // 경쟁 패배/reuse 거부 — 본 테스트는 ACTIVE 2개 불변식만 검증
        }
    }

    @Test
    @DisplayName("forceRotate miss(결정적): grace 성공했으나 replacement 가 이미 전이됨 → family 전체 REVOKED(새 토큰 포함) + deny")
    void graceSuccessButReplacementAlreadyRotated_revokesWholeFamily() {
        TokenResult zero = signup();
        TokenResult one = authService.refresh(zero.refreshToken()); // raw0 ROTATED(grace), raw1 ACTIVE
        authService.refresh(one.refreshToken());                    // raw1 → ROTATED, raw2 ACTIVE

        // raw0 은 아직 grace 유효 → consumeGraceOnce 성공하나 replacement(raw1)이 이미 ROTATED → forceRotate=0
        assertThatThrownBy(() -> authService.refresh(zero.refreshToken()))
                .isInstanceOf(UserException.class);

        // family 전체 무효화 — 방금 INSERT 한 새 토큰까지 ACTIVE 0, 모든 row REVOKED
        assertThat(activeCount()).isZero();
        Integer nonRevoked = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE status <> 'REVOKED'", Integer.class);
        assertThat(nonRevoked).isZero();
        assertThat(redisTemplate.keys("auth:deny:family:*")).isNotEmpty();
    }

    // ── reuse 감지 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("grace 초과 재제시(reuse): family 전체 REVOKED + Redis family deny 기록")
    void reuseAfterGrace_revokesFamilyAndWritesDeny() {
        TokenResult zero = signup();
        authService.refresh(zero.refreshToken()); // raw0 → ROTATED(grace_until = now+10s)

        // grace 창을 과거로 밀어 reuse 상황을 만든다
        jdbcTemplate.update(
                "UPDATE refresh_tokens SET grace_until = NOW() - INTERVAL 1 MINUTE WHERE status = 'ROTATED'");

        assertThatThrownBy(() -> authService.refresh(zero.refreshToken()))
                .isInstanceOf(UserException.class);

        // family 전체 무효화 — ACTIVE 0
        assertThat(activeCount()).isZero();
        // Redis family deny 키 기록
        assertThat(redisTemplate.keys("auth:deny:family:*")).isNotEmpty();
    }
}
