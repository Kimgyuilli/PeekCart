package com.peekcart.user.application;

import com.peekcart.global.auth.TokenBlacklistPort;
import com.peekcart.global.auth.TokenHasher;
import com.peekcart.global.auth.TokenIssuer;
import com.peekcart.global.jwt.JwtAuthProperties;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.user.application.dto.TokenResult;
import com.peekcart.user.domain.exception.RefreshTokenReuseException;
import com.peekcart.user.domain.exception.UserException;
import com.peekcart.user.domain.model.RefreshToken;
import com.peekcart.user.domain.model.User;
import com.peekcart.user.domain.repository.RefreshTokenRepository;
import com.peekcart.user.domain.repository.UserRepository;
import com.peekcart.user.infrastructure.metrics.UserAuthMetrics;
import com.peekcart.user.presentation.dto.request.LoginRequest;
import com.peekcart.user.presentation.dto.request.SignupRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 회원가입 / 로그인 / 로그아웃 / 토큰 재발급을 처리하는 애플리케이션 서비스.
 * <p>토큰 재발급은 {@code family_id}/{@code status} 상태전이 기반 reuse detection 을 수행한다
 * (ADR-0013 D4): 정상 로테이션은 ACTIVE→ROTATED(grace) 전이, grace 내 동시 재제시는 1회 허용,
 * grace 초과/이미 무효 재제시는 reuse 로 판정해 family 전체 무효화 + Redis family deny 를 기록한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    /** 정상 동시요청을 흡수하는 grace 창(초). */
    private static final long GRACE_SECONDS = 10;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklistPort tokenBlacklistPort;
    private final TokenIssuer tokenIssuer;
    private final PasswordEncoder passwordEncoder;
    private final JwtAuthProperties jwtAuthProperties;
    private final UserAuthMetrics authMetrics;

    /**
     * 신규 회원을 등록하고 새 family 로 토큰을 발급한다.
     *
     * @throws UserException 이메일 중복 시 {@code USR-001}
     */
    public TokenResult signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new UserException(ErrorCode.USR_001);
        }
        String passwordHash = passwordEncoder.encode(request.password());
        User user = userRepository.save(User.create(request.email(), passwordHash, request.name()));
        return issueNewFamily(user).tokenResult();
    }

    /**
     * 이메일과 비밀번호로 로그인하고 새 family 로 토큰을 발급한다.
     * 기존 리프레시 토큰은 모두 REVOKED 로 무효화한다.
     *
     * @throws UserException 인증 실패 시 {@code USR-002}
     */
    public TokenResult login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UserException(ErrorCode.USR_002));
        if (!user.matchesPassword(request.password(), passwordEncoder)) {
            throw new UserException(ErrorCode.USR_002);
        }
        refreshTokenRepository.revokeAllByUserId(user.getId());
        return issueNewFamily(user).tokenResult();
    }

    /**
     * 회원의 리프레시 토큰 family 를 무효화한다(header-trust, ADR-0013 D3 · PR3c).
     * <p>header-trust 전환 후 리소스 서비스는 raw access token 을 보유하지 않으므로 특정 토큰 blacklist 대신
     * <b>family deny + 전체 리프레시 무효화</b>로 재정의한다. familyId 가 있으면 이미 발급된 access token 을
     * family deny 로 즉시 차단한다. 전환기 레거시 토큰(familyId {@code null})은 family deny 를 기록할 수 없어
     * 기존 access token 이 access TTL 까지 유효하고(bounded risk), refresh 만 {@code revokeAllByUserId} 로 차단된다.
     */
    public void logout(Long userId, String familyId) {
        if (familyId != null && !familyId.isBlank()) {
            tokenBlacklistPort.denyFamily(familyId, jwtAuthProperties.accessTokenExpiry() / 1000);
        }
        refreshTokenRepository.revokeAllByUserId(userId);
        authMetrics.loggedOut();
    }

    /**
     * 리프레시 토큰으로 새 액세스/리프레시 토큰을 발급한다(reuse detection).
     * <p>reuse 감지 시 family 무효화는 이 트랜잭션에서 커밋되어야 하므로
     * {@link RefreshTokenReuseException} 에 대해서는 롤백하지 않는다(다른 거부 경로의 롤백은 유지).
     *
     * @throws UserException 토큰이 유효하지 않으면 {@code USR-004}, 만료 시 {@code USR-005}
     */
    @Transactional(noRollbackFor = RefreshTokenReuseException.class)
    public TokenResult refresh(String oldRefreshToken) {
        String tokenHash = TokenHasher.sha256Hex(oldRefreshToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new UserException(ErrorCode.USR_004));

        return switch (token.getStatus()) {
            case ACTIVE -> rotateActive(token);
            case ROTATED -> handleRotatedPresentation(token);
            // 이미 무효화된 family 재제시(로그아웃/재로그인 또는 reuse 여파) 도 reuse 로 판정한다
            // (plan P8 정의). revoke 는 idempotent, deny 는 재기록되어 family access token 차단을 보장한다.
            case REVOKED -> throw detectReuse(token.getFamilyId());
        };
    }

    /** 정상 로테이션: ACTIVE 토큰을 원자적으로 ROTATED(grace) 로 전이하고 새 토큰을 발급한다. */
    private TokenResult rotateActive(RefreshToken token) {
        if (token.isExpired()) {
            throw new UserException(ErrorCode.USR_005);
        }
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new UserException(ErrorCode.USR_003));
        IssueResult issued = issueInFamily(user, token.getFamilyId());
        LocalDateTime now = LocalDateTime.now();
        int rotated = refreshTokenRepository.rotateActive(
                token.getTokenHash(), issued.newTokenId(), now, now.plusSeconds(GRACE_SECONDS));
        if (rotated == 0) {
            // 동시 요청이 먼저 로테이션함 — 새 토큰 INSERT 는 트랜잭션 롤백으로 되돌아간다(이중 발급 방지)
            throw new UserException(ErrorCode.USR_004);
        }
        return issued.tokenResult();
    }

    /** ROTATED 토큰 재제시: grace 내 1회는 정상 동시요청으로 허용, 그 외는 reuse 로 판정한다. */
    private TokenResult handleRotatedPresentation(RefreshToken token) {
        LocalDateTime now = LocalDateTime.now();
        int consumed = refreshTokenRepository.consumeGraceOnce(token.getTokenHash(), now);
        if (consumed == 0) {
            // grace 만료/이미 소비 → reuse(탈취)
            throw detectReuse(token.getFamilyId());
        }
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new UserException(ErrorCode.USR_003));
        IssueResult issued = issueInFamily(user, token.getFamilyId());
        // grace 성공: 기존 replacement 를 grace 없이 강제 로테이션 → family 내 ACTIVE 정확히 1개.
        // forceRotate 가 1행을 못 바꾸면(=replacement 가 이미 다른 refresh 로 전이됨) ACTIVE 2개가 될 수
        // 있으므로 보수적으로 family 를 무효화한다(경쟁 이상 → reuse 취급). 방금 INSERT 한 새 토큰도 함께 REVOKED.
        if (token.getReplacedByTokenId() != null
                && refreshTokenRepository.forceRotate(token.getReplacedByTokenId(), issued.newTokenId(), now) != 1) {
            throw detectReuse(token.getFamilyId());
        }
        return issued.tokenResult();
    }

    /**
     * family 를 무효화(REVOKED)하고 Redis family deny 를 기록한 뒤 reuse 예외를 반환한다.
     * DB revoke 는 {@code noRollbackFor} 로 커밋되어야 하므로, Redis deny write 실패가 예외로 전파되어
     * 트랜잭션을 롤백시키지 않도록 격리(로깅)한다 — deny 미기록은 access token TTL 까지 bounded risk 이고,
     * blacklist read 는 Redis 장애 시 fail-closed 라 최종 안전하다.
     */
    private RefreshTokenReuseException detectReuse(String familyId) {
        refreshTokenRepository.revokeFamily(familyId);
        try {
            tokenBlacklistPort.denyFamily(familyId, jwtAuthProperties.accessTokenExpiry() / 1000);
        } catch (RuntimeException e) {
            log.warn("family deny write 실패 — DB 무효화는 유지, deny 는 access TTL 까지 bounded (familyId={})",
                    familyId, e);
        }
        authMetrics.reuseDetected();
        return new RefreshTokenReuseException();
    }

    private IssueResult issueNewFamily(User user) {
        return issueInFamily(user, UUID.randomUUID().toString());
    }

    private IssueResult issueInFamily(User user, String familyId) {
        TokenIssuer.IssuedTokens issued = tokenIssuer.issue(user.getId(), user.getRole().name(), familyId);
        RefreshToken saved = refreshTokenRepository.save(RefreshToken.issue(
                user.getId(), familyId,
                TokenHasher.sha256Hex(issued.refreshTokenValue()), issued.refreshTokenExpiresAt()));
        return new IssueResult(new TokenResult(issued.accessToken(), issued.refreshTokenValue()), saved.getId());
    }

    private record IssueResult(TokenResult tokenResult, Long newTokenId) {}
}
