package com.peekcart.notification.domain.model;

import com.peekcart.support.fixture.NotificationFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Notification 도메인 단위 테스트")
class NotificationTest {

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("userId, type, message가 설정된다")
        void setsFields() {
            Notification notification = NotificationFixture.notification();

            assertThat(notification.getUserId()).isEqualTo(NotificationFixture.DEFAULT_USER_ID);
            assertThat(notification.getType()).isEqualTo(NotificationType.ORDER_CREATED);
            assertThat(notification.getMessage()).isEqualTo(NotificationFixture.DEFAULT_MESSAGE);
        }

        @Test
        @DisplayName("초기 isRead는 false이다")
        void initialIsReadIsFalse() {
            Notification notification = NotificationFixture.notification();
            assertThat(notification.isRead()).isFalse();
        }

        @Test
        @DisplayName("createdAt이 설정된다")
        void setsCreatedAt() {
            Notification notification = NotificationFixture.notification();
            assertThat(notification.getCreatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("NotificationType")
    class TypeValues {

        @Test
        @DisplayName("4가지 알림 유형이 존재한다")
        void hasFourTypes() {
            assertThat(NotificationType.values()).containsExactly(
                    NotificationType.ORDER_CREATED,
                    NotificationType.PAYMENT_COMPLETED,
                    NotificationType.PAYMENT_FAILED,
                    NotificationType.ORDER_CANCELLED
            );
        }
    }
}
