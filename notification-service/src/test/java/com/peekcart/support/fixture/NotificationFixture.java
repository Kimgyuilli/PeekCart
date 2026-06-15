package com.peekcart.support.fixture;

import com.peekcart.notification.application.dto.NotificationDetailDto;
import com.peekcart.notification.domain.model.Notification;
import com.peekcart.notification.domain.model.NotificationType;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * Notification 도메인 테스트 픽스처 팩토리.
 */
public class NotificationFixture {

    public static final Long DEFAULT_NOTIFICATION_ID = 1L;
    public static final Long DEFAULT_USER_ID = 1L;
    public static final NotificationType DEFAULT_TYPE = NotificationType.ORDER_CREATED;
    public static final String DEFAULT_MESSAGE = "주문이 생성되었습니다. [주문번호: ORD-20260326-0001, 금액: 59,000원]";
    public static final LocalDateTime DEFAULT_CREATED_AT = LocalDateTime.of(2026, 3, 26, 10, 0);

    private NotificationFixture() {}

    // ── Domain 객체 ──

    public static Notification notification() {
        return Notification.create(DEFAULT_USER_ID, DEFAULT_TYPE, DEFAULT_MESSAGE);
    }

    public static Notification notificationWithId() {
        Notification notification = notification();
        ReflectionTestUtils.setField(notification, "id", DEFAULT_NOTIFICATION_ID);
        return notification;
    }

    public static Notification notificationWithId(Long id, NotificationType type, String message) {
        Notification notification = Notification.create(DEFAULT_USER_ID, type, message);
        ReflectionTestUtils.setField(notification, "id", id);
        return notification;
    }

    // ── Application DTO ──

    public static NotificationDetailDto notificationDetailDto() {
        return new NotificationDetailDto(
                DEFAULT_NOTIFICATION_ID,
                DEFAULT_TYPE.name(),
                DEFAULT_MESSAGE,
                false,
                DEFAULT_CREATED_AT
        );
    }

    public static NotificationDetailDto notificationDetailDto(Long id, NotificationType type, String message) {
        return new NotificationDetailDto(id, type.name(), message, false, DEFAULT_CREATED_AT);
    }
}
