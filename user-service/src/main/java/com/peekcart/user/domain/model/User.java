package com.peekcart.user.domain.model;

import com.peekcart.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 회원 도메인 엔티티.
 * 비즈니스 로직(비밀번호 검증, 프로필 수정)을 직접 보유한다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    private User(String email, String passwordHash, String name) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = UserRole.USER;
    }

    /**
     * 새 회원 인스턴스를 생성한다. 기본 역할은 {@link UserRole#USER}.
     *
     * @param email        이메일 (고유)
     * @param passwordHash 암호화된 비밀번호
     * @param name         이름
     */
    public static User create(String email, String passwordHash, String name) {
        return new User(email, passwordHash, name);
    }

    /**
     * 입력된 평문 비밀번호가 저장된 해시와 일치하는지 검증한다.
     *
     * @param rawPassword 평문 비밀번호
     * @param encoder     {@link PasswordEncoder}
     * @return 일치하면 {@code true}
     */
    public boolean matchesPassword(String rawPassword, PasswordEncoder encoder) {
        return encoder.matches(rawPassword, this.passwordHash);
    }

    /**
     * 회원 이름을 변경한다.
     *
     * @param name 새 이름
     */
    public void updateProfile(String name) {
        this.name = name;
    }
}
