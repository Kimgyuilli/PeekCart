package com.peekcart.support.fixture;

import com.peekcart.user.domain.model.RefreshToken;
import com.peekcart.user.domain.model.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * User 도메인 테스트 픽스처 팩토리.
 * 도메인 단위 테스트 및 Application 레이어 Mockito 테스트에서 공통으로 사용한다.
 */
public class UserFixture {

    public static final Long DEFAULT_ID = 1L;
    public static final String DEFAULT_EMAIL = "user@example.com";
    public static final String DEFAULT_NAME = "테스트유저";
    /** BCrypt hash of "password123" */
    public static final String DEFAULT_PASSWORD_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private UserFixture() {}

    /**
     * ID가 없는 User를 생성한다. 도메인 단위 테스트용.
     */
    public static User user() {
        return User.create(DEFAULT_EMAIL, DEFAULT_PASSWORD_HASH, DEFAULT_NAME);
    }

    /**
     * ID가 설정된 User를 생성한다. Application 레이어 Mockito 테스트용.
     */
    public static User userWithId() {
        return userWithId(DEFAULT_ID);
    }

    /**
     * 지정한 ID가 설정된 User를 생성한다.
     */
    public static User userWithId(Long id) {
        User user = User.create(DEFAULT_EMAIL, DEFAULT_PASSWORD_HASH, DEFAULT_NAME);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /**
     * 지정한 이메일로 User를 생성한다.
     */
    public static User userWithEmail(String email) {
        User user = User.create(email, DEFAULT_PASSWORD_HASH, DEFAULT_NAME);
        ReflectionTestUtils.setField(user, "id", DEFAULT_ID);
        return user;
    }

    /**
     * 만료되지 않은 RefreshToken을 생성한다.
     */
    public static RefreshToken refreshToken(Long userId, String tokenValue) {
        return RefreshToken.create(userId, tokenValue, LocalDateTime.now().plusDays(7));
    }

    /**
     * 이미 만료된 RefreshToken을 생성한다.
     */
    public static RefreshToken expiredRefreshToken(Long userId, String tokenValue) {
        return RefreshToken.create(userId, tokenValue, LocalDateTime.now().minusDays(1));
    }
}
