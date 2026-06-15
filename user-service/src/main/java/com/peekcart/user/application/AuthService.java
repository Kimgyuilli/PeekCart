package com.peekcart.user.application;

import com.peekcart.global.auth.TokenBlacklistPort;
import com.peekcart.global.auth.TokenClaims;
import com.peekcart.global.auth.TokenIssuer;
import com.peekcart.global.auth.TokenParseException;
import com.peekcart.global.jwt.JwtTokenVerifier;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.user.application.dto.TokenResult;
import com.peekcart.user.domain.exception.UserException;
import com.peekcart.user.domain.model.RefreshToken;
import com.peekcart.user.domain.model.User;
import com.peekcart.user.domain.repository.RefreshTokenRepository;
import com.peekcart.user.domain.repository.UserRepository;
import com.peekcart.user.presentation.dto.request.LoginRequest;
import com.peekcart.user.presentation.dto.request.SignupRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입 / 로그인 / 로그아웃 / 토큰 재발급을 처리하는 애플리케이션 서비스.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklistPort tokenBlacklistPort;
    private final TokenIssuer tokenIssuer;
    private final JwtTokenVerifier jwtTokenVerifier;
    private final PasswordEncoder passwordEncoder;

    /**
     * 신규 회원을 등록하고 토큰을 발급한다.
     *
     * @param request 회원가입 요청 (이메일, 비밀번호, 이름)
     * @return 발급된 액세스 토큰 및 리프레시 토큰
     * @throws UserException 이메일 중복 시 {@code USR-001}
     */
    public TokenResult signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new UserException(ErrorCode.USR_001);
        }
        String passwordHash = passwordEncoder.encode(request.password());
        User user = userRepository.save(User.create(request.email(), passwordHash, request.name()));
        return issueTokens(user);
    }

    /**
     * 이메일과 비밀번호로 로그인하고 토큰을 발급한다.
     * 기존 리프레시 토큰은 모두 삭제 후 재발급한다.
     *
     * @param request 로그인 요청 (이메일, 비밀번호)
     * @return 발급된 액세스 토큰 및 리프레시 토큰
     * @throws UserException 인증 실패 시 {@code USR-002}
     */
    public TokenResult login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UserException(ErrorCode.USR_002));
        if (!user.matchesPassword(request.password(), passwordEncoder)) {
            throw new UserException(ErrorCode.USR_002);
        }
        refreshTokenRepository.deleteByUserId(user.getId());
        return issueTokens(user);
    }

    /**
     * 액세스 토큰을 블랙리스트에 등록하고 모든 리프레시 토큰을 삭제한다.
     * 정상 흐름에서 이 메서드는 JwtFilter를 통과한 유효한 토큰만 받으나,
     * 방어적으로 파싱 실패 시 USR-004를 던진다.
     *
     * @param accessToken 로그아웃할 액세스 토큰
     * @throws UserException 유효하지 않은 토큰이면 {@code USR-004}
     */
    public void logout(String accessToken) {
        TokenClaims claims;
        try {
            claims = jwtTokenVerifier.parseToken(accessToken);
        } catch (TokenParseException e) {
            throw new UserException(ErrorCode.USR_004);
        }
        long ttlSeconds = (claims.expiration().toEpochMilli() - System.currentTimeMillis()) / 1000;
        if (ttlSeconds > 0) {
            tokenBlacklistPort.addToBlacklist(accessToken, ttlSeconds);
        }
        refreshTokenRepository.deleteByUserId(claims.userId());
    }

    /**
     * 리프레시 토큰으로 새 액세스/리프레시 토큰을 발급한다.
     * 기존 토큰이 DB에 없으면 그레이스 피리어드를 통해 재발급을 시도한다.
     *
     * @param oldRefreshToken 기존 리프레시 토큰
     * @return 새로 발급된 액세스 토큰 및 리프레시 토큰
     * @throws UserException 토큰이 유효하지 않으면 {@code USR-004}
     */
    public TokenResult refresh(String oldRefreshToken) {
        return refreshTokenRepository.findByToken(oldRefreshToken)
                .map(token -> rotateToken(token, oldRefreshToken))
                .orElseGet(() -> refreshViaGracePeriod(oldRefreshToken));
    }

    private TokenResult rotateToken(RefreshToken token, String oldRefreshToken) {
        if (token.isExpired()) {
            refreshTokenRepository.deleteByToken(oldRefreshToken);
            throw new UserException(ErrorCode.USR_005);
        }
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new UserException(ErrorCode.USR_003));
        tokenBlacklistPort.addGracePeriod(oldRefreshToken, user.getId(), 10);
        boolean deleted = refreshTokenRepository.deleteByToken(oldRefreshToken);
        if (!deleted) {
            // 동시 요청이 먼저 처리됨 — 이중 발급 방지
            throw new UserException(ErrorCode.USR_004);
        }
        return issueTokens(user);
    }

    private TokenResult refreshViaGracePeriod(String oldRefreshToken) {
        Long userId = tokenBlacklistPort.consumeGracePeriod(oldRefreshToken)
                .orElseThrow(() -> new UserException(ErrorCode.USR_004));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserException(ErrorCode.USR_003));
        // 첫 번째 로테이션이 저장한 고아 토큰을 정리하고 재발급
        refreshTokenRepository.deleteByUserId(userId);
        return issueTokens(user);
    }

    private TokenResult issueTokens(User user) {
        TokenIssuer.IssuedTokens issued = tokenIssuer.issue(user.getId(), user.getRole().name());
        refreshTokenRepository.save(RefreshToken.create(user.getId(), issued.refreshTokenValue(), issued.refreshTokenExpiresAt()));
        return new TokenResult(issued.accessToken(), issued.refreshTokenValue());
    }
}
