package com.peekcart.user.application;

import com.peekcart.global.auth.TokenBlacklistPort;
import com.peekcart.global.auth.TokenClaims;
import com.peekcart.global.auth.TokenIssuer;
import com.peekcart.global.auth.TokenParseException;
import com.peekcart.global.jwt.JwtTokenVerifier;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
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

@ServiceTest
@DisplayName("AuthService 단위 테스트")
class AuthServiceTest {

    @InjectMocks AuthService authService;

    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock TokenBlacklistPort tokenBlacklistPort;
    @Mock TokenIssuer tokenIssuer;
    @Mock JwtTokenVerifier jwtTokenVerifier;
    @Mock PasswordEncoder passwordEncoder;

    private static final String ACCESS_TOKEN = "access.token.value";
    private static final String REFRESH_TOKEN_VALUE = "refresh-token-uuid";

    private TokenIssuer.IssuedTokens issuedTokens() {
        return new TokenIssuer.IssuedTokens(ACCESS_TOKEN, REFRESH_TOKEN_VALUE, LocalDateTime.now().plusDays(7));
    }

    private TokenClaims tokenClaims(long userId, Instant expiration) {
        return new TokenClaims(userId, "USER", expiration);
    }

    // ── signup ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("signup: 정상 가입 시 TokenResult를 반환한다")
    void signup_success() {
        SignupRequest request = new SignupRequest("new@example.com", "password123", "홍길동");
        User savedUser = UserFixture.userWithId();
        given(userRepository.existsByEmail(request.email())).willReturn(false);
        given(passwordEncoder.encode(request.password())).willReturn(UserFixture.DEFAULT_PASSWORD_HASH);
        given(userRepository.save(any(User.class))).willReturn(savedUser);
        given(tokenIssuer.issue(anyLong(), anyString())).willReturn(issuedTokens());

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
    @DisplayName("login: 올바른 인증 정보면 기존 토큰을 삭제하고 TokenResult를 반환한다")
    void login_success() {
        User user = UserFixture.userWithId();
        LoginRequest request = new LoginRequest(UserFixture.DEFAULT_EMAIL, "password123");
        given(userRepository.findByEmail(request.email())).willReturn(Optional.of(user));
        given(passwordEncoder.matches(anyString(), anyString())).willReturn(true);
        given(tokenIssuer.issue(anyLong(), anyString())).willReturn(issuedTokens());

        TokenResult result = authService.login(request);

        assertThat(result.accessToken()).isEqualTo(ACCESS_TOKEN);
        then(refreshTokenRepository).should().deleteByUserId(user.getId());
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
    @DisplayName("logout: 유효한 토큰이면 블랙리스트 등록 후 리프레시 토큰을 삭제한다")
    void logout_success() {
        Instant expiration = Instant.now().plusSeconds(3600);
        given(jwtTokenVerifier.parseToken(ACCESS_TOKEN)).willReturn(tokenClaims(1L, expiration));

        authService.logout(ACCESS_TOKEN);

        then(tokenBlacklistPort).should().addToBlacklist(eq(ACCESS_TOKEN), anyLong());
        then(refreshTokenRepository).should().deleteByUserId(1L);
    }

    @Test
    @DisplayName("logout: 유효하지 않은 토큰이면 USR-004 예외가 발생한다")
    void logout_invalidToken_throwsUSR004() {
        given(jwtTokenVerifier.parseToken(ACCESS_TOKEN)).willThrow(new TokenParseException(new RuntimeException()));

        assertThatThrownBy(() -> authService.logout(ACCESS_TOKEN))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_004);
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("refresh: DB에 토큰이 있으면 로테이션하고 TokenResult를 반환한다")
    void refresh_tokenInDb_rotatesToken() {
        User user = UserFixture.userWithId();
        RefreshToken storedToken = UserFixture.refreshToken(user.getId(), REFRESH_TOKEN_VALUE);
        given(refreshTokenRepository.findByToken(REFRESH_TOKEN_VALUE)).willReturn(Optional.of(storedToken));
        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
        given(refreshTokenRepository.deleteByToken(REFRESH_TOKEN_VALUE)).willReturn(true);
        given(tokenIssuer.issue(anyLong(), anyString())).willReturn(issuedTokens());

        TokenResult result = authService.refresh(REFRESH_TOKEN_VALUE);

        assertThat(result.accessToken()).isEqualTo(ACCESS_TOKEN);
        then(tokenBlacklistPort).should().addGracePeriod(eq(REFRESH_TOKEN_VALUE), eq(user.getId()), eq(10L));
        then(refreshTokenRepository).should().deleteByToken(REFRESH_TOKEN_VALUE);
    }

    @Test
    @DisplayName("refresh: 만료된 토큰이면 USR-005 예외가 발생한다")
    void refresh_expiredToken_throwsUSR005() {
        User user = UserFixture.userWithId();
        RefreshToken expiredToken = UserFixture.expiredRefreshToken(user.getId(), REFRESH_TOKEN_VALUE);
        given(refreshTokenRepository.findByToken(REFRESH_TOKEN_VALUE)).willReturn(Optional.of(expiredToken));
        given(refreshTokenRepository.deleteByToken(REFRESH_TOKEN_VALUE)).willReturn(true);

        assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN_VALUE))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_005);
    }

    @Test
    @DisplayName("refresh: 동시 요청으로 토큰이 이미 삭제된 경우 USR-004 예외가 발생한다")
    void refresh_concurrentRotation_throwsUSR004() {
        User user = UserFixture.userWithId();
        RefreshToken storedToken = UserFixture.refreshToken(user.getId(), REFRESH_TOKEN_VALUE);
        given(refreshTokenRepository.findByToken(REFRESH_TOKEN_VALUE)).willReturn(Optional.of(storedToken));
        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
        given(refreshTokenRepository.deleteByToken(REFRESH_TOKEN_VALUE)).willReturn(false);

        assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN_VALUE))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_004);
    }

    @Test
    @DisplayName("refresh: DB에 없지만 그레이스 피리어드가 유효하면 고아 토큰을 정리하고 새 토큰을 발급한다")
    void refresh_gracePeriodActive_cleansUpAndIssuesNewToken() {
        User user = UserFixture.userWithId();
        given(refreshTokenRepository.findByToken(REFRESH_TOKEN_VALUE)).willReturn(Optional.empty());
        given(tokenBlacklistPort.consumeGracePeriod(REFRESH_TOKEN_VALUE)).willReturn(Optional.of(user.getId()));
        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
        given(tokenIssuer.issue(anyLong(), anyString())).willReturn(issuedTokens());

        TokenResult result = authService.refresh(REFRESH_TOKEN_VALUE);

        assertThat(result.accessToken()).isEqualTo(ACCESS_TOKEN);
        then(refreshTokenRepository).should().deleteByUserId(user.getId());
    }

    @Test
    @DisplayName("refresh: DB에 없고 그레이스 피리어드도 만료되면 USR-004 예외가 발생한다")
    void refresh_gracePeriodExpired_throwsUSR004() {
        given(refreshTokenRepository.findByToken(REFRESH_TOKEN_VALUE)).willReturn(Optional.empty());
        given(tokenBlacklistPort.consumeGracePeriod(REFRESH_TOKEN_VALUE)).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN_VALUE))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_004);
    }
}
