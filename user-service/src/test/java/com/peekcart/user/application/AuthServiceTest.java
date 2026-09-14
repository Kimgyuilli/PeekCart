package com.peekcart.user.application;

import com.peekcart.global.auth.TokenBlacklistPort;
import com.peekcart.global.auth.TokenHasher;
import com.peekcart.global.auth.TokenIssuer;
import com.peekcart.global.jwt.JwtAuthProperties;
import com.peekcart.user.infrastructure.metrics.UserAuthMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.UserFixture;
import com.peekcart.user.application.dto.TokenResult;
import com.peekcart.user.domain.exception.UserException;
import com.peekcart.user.domain.model.RefreshToken;
import com.peekcart.user.domain.model.User;
import com.peekcart.user.domain.repository.RefreshTokenRepository;
import com.peekcart.user.domain.repository.UserRepository;
import com.peekcart.user.presentation.dto.request.LoginRequest;
import com.peekcart.user.presentation.dto.request.SignupRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ServiceTest
@DisplayName("AuthService 단위 테스트")
class AuthServiceTest {

    AuthService authService;

    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock TokenBlacklistPort tokenBlacklistPort;
    @Mock TokenIssuer tokenIssuer;
    @Mock PasswordEncoder passwordEncoder;

    private static final String ACCESS_TOKEN = "access.token.value";
    private static final String REFRESH_TOKEN_VALUE = "refresh-token-uuid";
    private static final String REFRESH_TOKEN_HASH = TokenHasher.sha256Hex(REFRESH_TOKEN_VALUE);
    private static final long ACCESS_TTL_MS = 1_800_000L;
    // record 는 mock 대상이 아님 — 실제 값으로 주입(reuse deny TTL = accessTokenExpiry/1000)
    private final JwtAuthProperties jwtAuthProperties = new JwtAuthProperties(ACCESS_TTL_MS, 604_800_000L);

    private SimpleMeterRegistry meterRegistry;
    private UserAuthMetrics authMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        authMetrics = new UserAuthMetrics(meterRegistry);
        authService = new AuthService(userRepository, refreshTokenRepository, tokenBlacklistPort,
                tokenIssuer, passwordEncoder, jwtAuthProperties, authMetrics);
    }

    private TokenIssuer.IssuedTokens issuedTokens() {
        return new TokenIssuer.IssuedTokens(ACCESS_TOKEN, REFRESH_TOKEN_VALUE, LocalDateTime.now().plusDays(7));
    }

    /** save() 가 id 가 채워진 영속 토큰을 반환하도록 스텁한다(issueInFamily 가 newTokenId 를 읽음). */
    private void stubIssue() {
        given(tokenIssuer.issue(anyLong(), anyString(), anyString())).willReturn(issuedTokens());
        given(refreshTokenRepository.save(any(RefreshToken.class)))
                .willAnswer(inv -> UserFixture.withId(inv.getArgument(0), 100L));
    }

    // ── signup ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("signup: 정상 가입 시 새 family 로 TokenResult를 반환한다")
    void signup_success() {
        SignupRequest request = new SignupRequest("new@example.com", "password123", "홍길동");
        User savedUser = UserFixture.userWithId();
        given(userRepository.existsByEmail(request.email())).willReturn(false);
        given(passwordEncoder.encode(request.password())).willReturn(UserFixture.DEFAULT_PASSWORD_HASH);
        given(userRepository.save(any(User.class))).willReturn(savedUser);
        stubIssue();

        TokenResult result = authService.signup(request);

        assertThat(result.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(result.refreshToken()).isEqualTo(REFRESH_TOKEN_VALUE);
        then(refreshTokenRepository).should().save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("signup: 이메일이 중복되면 USR-001 예외가 발생한다")
    void signup_duplicateEmail_throwsUSR001() {
        SignupRequest request = new SignupRequest("dup@example.com", "password123", "홍길동");
        given(userRepository.existsByEmail(request.email())).willReturn(true);

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_001);
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("login: 올바른 인증 정보면 기존 토큰을 무효화하고 TokenResult를 반환한다")
    void login_success() {
        User user = UserFixture.userWithId();
        LoginRequest request = new LoginRequest(UserFixture.DEFAULT_EMAIL, "password123");
        given(userRepository.findByEmail(request.email())).willReturn(Optional.of(user));
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(true);
        stubIssue();

        TokenResult result = authService.login(request);

        assertThat(result.accessToken()).isEqualTo(ACCESS_TOKEN);
        then(refreshTokenRepository).should().revokeAllByUserId(user.getId());
        then(refreshTokenRepository).should().save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("login: 이메일이 없으면 USR-002 예외가 발생한다")
    void login_emailNotFound_throwsUSR002() {
        LoginRequest request = new LoginRequest("none@example.com", "password123");
        given(userRepository.findByEmail(request.email())).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_002);
    }

    @Test
    @DisplayName("login: 비밀번호가 틀리면 USR-002 예외가 발생한다")
    void login_wrongPassword_throwsUSR002() {
        User user = UserFixture.userWithId();
        LoginRequest request = new LoginRequest(UserFixture.DEFAULT_EMAIL, "wrongpassword");
        given(userRepository.findByEmail(request.email())).willReturn(Optional.of(user));
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_002);
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("logout: familyId 가 있으면 family deny 후 회원 리프레시 토큰을 무효화한다")
    void logout_withFamily_deniesAndRevokes() {
        authService.logout(1L, "fam-1");

        then(tokenBlacklistPort).should().denyFamily(eq("fam-1"), anyLong());
        then(refreshTokenRepository).should().revokeAllByUserId(1L);
    }

    @Test
    @DisplayName("logout: family-less(전환기 레거시) 토큰이면 family deny 없이 리프레시만 무효화한다")
    void logout_familyLess_revokesOnly() {
        authService.logout(1L, null);

        then(tokenBlacklistPort).should(never()).denyFamily(anyString(), anyLong());
        then(refreshTokenRepository).should().revokeAllByUserId(1L);
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("refresh: 알 수 없는 토큰 해시면 USR-004 예외가 발생한다")
    void refresh_unknownTokenHash_throwsUSR004() {
        given(refreshTokenRepository.findByTokenHash(REFRESH_TOKEN_HASH)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN_VALUE))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_004);
    }

    @Test
    @DisplayName("refresh: ACTIVE 토큰이면 원자적으로 로테이션하고 TokenResult를 반환한다")
    void refresh_activeToken_rotates() {
        User user = UserFixture.userWithId();
        RefreshToken active = UserFixture.activeRefreshToken(user.getId(), REFRESH_TOKEN_VALUE);
        given(refreshTokenRepository.findByTokenHash(REFRESH_TOKEN_HASH)).willReturn(Optional.of(active));
        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
        stubIssue();
        given(refreshTokenRepository.rotateActive(eq(REFRESH_TOKEN_HASH), eq(100L), any(), any())).willReturn(1);

        TokenResult result = authService.refresh(REFRESH_TOKEN_VALUE);

        assertThat(result.accessToken()).isEqualTo(ACCESS_TOKEN);
        then(refreshTokenRepository).should().rotateActive(eq(REFRESH_TOKEN_HASH), eq(100L), any(), any());
    }

    @Test
    @DisplayName("refresh: ACTIVE 이지만 만료된 토큰이면 USR-005 예외가 발생한다")
    void refresh_expiredActive_throwsUSR005() {
        User user = UserFixture.userWithId();
        RefreshToken expired = UserFixture.expiredRefreshToken(user.getId(), REFRESH_TOKEN_VALUE);
        given(refreshTokenRepository.findByTokenHash(REFRESH_TOKEN_HASH)).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN_VALUE))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_005);
    }

    @Test
    @DisplayName("refresh: ACTIVE 로테이션 동시 경쟁에서 패배(affected=0)하면 USR-004 예외가 발생한다")
    void refresh_activeConcurrentLost_throwsUSR004() {
        User user = UserFixture.userWithId();
        RefreshToken active = UserFixture.activeRefreshToken(user.getId(), REFRESH_TOKEN_VALUE);
        given(refreshTokenRepository.findByTokenHash(REFRESH_TOKEN_HASH)).willReturn(Optional.of(active));
        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
        stubIssue();
        given(refreshTokenRepository.rotateActive(eq(REFRESH_TOKEN_HASH), anyLong(), any(), any())).willReturn(0);

        assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN_VALUE))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_004);
    }

    @Test
    @DisplayName("refresh: ROTATED 토큰을 grace 내 재제시(consume 성공)하면 새 토큰 발급 + 기존 replacement 강제 로테이션")
    void refresh_rotatedWithinGrace_reissuesAndForceRotates() {
        User user = UserFixture.userWithId();
        RefreshToken rotated = UserFixture.rotatedRefreshToken(
                user.getId(), REFRESH_TOKEN_VALUE, 55L, LocalDateTime.now().plusSeconds(5));
        given(refreshTokenRepository.findByTokenHash(REFRESH_TOKEN_HASH)).willReturn(Optional.of(rotated));
        given(refreshTokenRepository.consumeGraceOnce(eq(REFRESH_TOKEN_HASH), any())).willReturn(1);
        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
        given(refreshTokenRepository.forceRotate(eq(55L), eq(100L), any())).willReturn(1);
        stubIssue();

        TokenResult result = authService.refresh(REFRESH_TOKEN_VALUE);

        assertThat(result.accessToken()).isEqualTo(ACCESS_TOKEN);
        // 기존 replacement(55L)를 새 토큰(100L)으로 강제 로테이션 → family 내 ACTIVE 1개
        then(refreshTokenRepository).should().forceRotate(eq(55L), eq(100L), any());
        then(refreshTokenRepository).should(never()).revokeFamily(anyString());
    }

    @Test
    @DisplayName("refresh: grace 성공했으나 replacement 가 이미 전이됨(forceRotate=0)이면 보수적으로 family 무효화 후 USR-004")
    void refresh_graceForceRotateMiss_revokesFamily() {
        User user = UserFixture.userWithId();
        RefreshToken rotated = UserFixture.rotatedRefreshToken(
                user.getId(), REFRESH_TOKEN_VALUE, 55L, LocalDateTime.now().plusSeconds(5));
        given(refreshTokenRepository.findByTokenHash(REFRESH_TOKEN_HASH)).willReturn(Optional.of(rotated));
        given(refreshTokenRepository.consumeGraceOnce(eq(REFRESH_TOKEN_HASH), any())).willReturn(1);
        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
        given(refreshTokenRepository.forceRotate(eq(55L), eq(100L), any())).willReturn(0);
        stubIssue();

        assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN_VALUE))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_004);

        then(refreshTokenRepository).should().revokeFamily(UserFixture.DEFAULT_FAMILY_ID);
        then(tokenBlacklistPort).should().denyFamily(UserFixture.DEFAULT_FAMILY_ID, ACCESS_TTL_MS / 1000);
    }

    @Test
    @DisplayName("refresh: ROTATED 토큰을 grace 초과 재제시(consume 실패)하면 reuse — family revoke + deny 후 USR-004")
    void refresh_rotatedReuse_revokesFamilyAndDenies() {
        User user = UserFixture.userWithId();
        RefreshToken rotated = UserFixture.rotatedRefreshToken(
                user.getId(), REFRESH_TOKEN_VALUE, 55L, LocalDateTime.now().minusSeconds(5));
        given(refreshTokenRepository.findByTokenHash(REFRESH_TOKEN_HASH)).willReturn(Optional.of(rotated));
        given(refreshTokenRepository.consumeGraceOnce(eq(REFRESH_TOKEN_HASH), any())).willReturn(0);

        assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN_VALUE))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_004);

        then(refreshTokenRepository).should().revokeFamily(UserFixture.DEFAULT_FAMILY_ID);
        // deny TTL = accessTokenExpiry/1000 = 1800s
        then(tokenBlacklistPort).should().denyFamily(UserFixture.DEFAULT_FAMILY_ID, ACCESS_TTL_MS / 1000);
    }

    @Test
    @DisplayName("refresh: 이미 REVOKED 된 family 토큰 재제시도 reuse — family deny 재기록 후 USR-004")
    void refresh_revokedToken_reDeniesFamilyAndThrowsUSR004() {
        User user = UserFixture.userWithId();
        RefreshToken revoked = UserFixture.revokedRefreshToken(user.getId(), REFRESH_TOKEN_VALUE);
        given(refreshTokenRepository.findByTokenHash(REFRESH_TOKEN_HASH)).willReturn(Optional.of(revoked));

        assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN_VALUE))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_004);

        // REVOKED 재제시도 reuse 경로 — revoke(idempotent) + deny 재기록으로 family access token 차단 보장
        then(refreshTokenRepository).should().revokeFamily(UserFixture.DEFAULT_FAMILY_ID);
        then(tokenBlacklistPort).should().denyFamily(UserFixture.DEFAULT_FAMILY_ID, ACCESS_TTL_MS / 1000);
    }

    @Test
    @DisplayName("refresh: reuse 감지 시 Redis deny write 가 실패해도 DB family 무효화는 유지된다")
    void refresh_reuseWithRedisFailure_stillRevokesFamily() {
        User user = UserFixture.userWithId();
        RefreshToken rotated = UserFixture.rotatedRefreshToken(
                user.getId(), REFRESH_TOKEN_VALUE, 55L, LocalDateTime.now().minusSeconds(5));
        given(refreshTokenRepository.findByTokenHash(REFRESH_TOKEN_HASH)).willReturn(Optional.of(rotated));
        given(refreshTokenRepository.consumeGraceOnce(eq(REFRESH_TOKEN_HASH), any())).willReturn(0);
        org.mockito.BDDMockito.willThrow(new RuntimeException("redis down"))
                .given(tokenBlacklistPort).denyFamily(anyString(), anyLong());

        assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN_VALUE))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_004);

        // Redis 실패가 전파돼 revoke 를 롤백시키지 않아야 한다(예외 격리)
        then(refreshTokenRepository).should().revokeFamily(UserFixture.DEFAULT_FAMILY_ID);
    }
}
