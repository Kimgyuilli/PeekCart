package com.peekcart.user.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("User 도메인 단위 테스트")
class UserTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("create: 기본 역할이 USER로 설정된다")
    void create_setsDefaultRoleToUser() {
        User user = User.create("a@b.com", "hash", "이름");

        assertThat(user.getRole()).isEqualTo(UserRole.USER);
        assertThat(user.getEmail()).isEqualTo("a@b.com");
        assertThat(user.getName()).isEqualTo("이름");
        assertThat(user.getId()).isNull();
    }

    @Test
    @DisplayName("matchesPassword: 올바른 비밀번호이면 true를 반환한다")
    void matchesPassword_returnsTrue_whenPasswordIsCorrect() {
        String hash = encoder.encode("password123");
        User user = User.create("a@b.com", hash, "이름");

        assertThat(user.matchesPassword("password123", encoder)).isTrue();
    }

    @Test
    @DisplayName("matchesPassword: 틀린 비밀번호이면 false를 반환한다")
    void matchesPassword_returnsFalse_whenPasswordIsWrong() {
        String hash = encoder.encode("password123");
        User user = User.create("a@b.com", hash, "이름");

        assertThat(user.matchesPassword("wrongpassword", encoder)).isFalse();
    }

    @Test
    @DisplayName("updateProfile: 이름이 변경된다")
    void updateProfile_updatesName() {
        User user = User.create("a@b.com", "hash", "기존이름");

        user.updateProfile("새이름");

        assertThat(user.getName()).isEqualTo("새이름");
    }
}
