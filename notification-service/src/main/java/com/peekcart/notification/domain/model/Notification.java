package com.peekcart.notification.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 알림 엔티티. 도메인 이벤트 수신 시 생성되어 사용자에게 전달된다.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "is_read", nullable = false)
    private boolean isRead;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private Notification(Long userId, NotificationType type, String message) {
        this.userId = userId;
        this.type = type;
        this.message = message;
        this.isRead = false;
        this.createdAt = LocalDateTime.now();
    }

    public static Notification create(Long userId, NotificationType type, String message) {
        return new Notification(userId, type, message);
    }
}
