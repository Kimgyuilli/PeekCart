package com.peekcart.notification.application;

import com.peekcart.global.port.SlackPort;
import com.peekcart.notification.domain.model.Notification;
import com.peekcart.notification.domain.model.NotificationType;
import com.peekcart.notification.domain.repository.NotificationRepository;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.NotificationFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ServiceTest
@DisplayName("NotificationCommandService 단위 테스트")
class NotificationCommandServiceTest {

    @InjectMocks NotificationCommandService notificationCommandService;
    @Mock NotificationRepository notificationRepository;
    @Mock SlackPort slackPort;

    @Test
    @DisplayName("createNotification: 알림이 저장된다")
    void createNotification_savesNotification() {
        given(notificationRepository.save(any(Notification.class))).willAnswer(inv -> inv.getArgument(0));

        notificationCommandService.createNotification(
                NotificationFixture.DEFAULT_USER_ID, NotificationType.ORDER_CREATED, NotificationFixture.DEFAULT_MESSAGE);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        then(notificationRepository).should().save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(NotificationFixture.DEFAULT_USER_ID);
        assertThat(saved.getType()).isEqualTo(NotificationType.ORDER_CREATED);
        assertThat(saved.getMessage()).isEqualTo(NotificationFixture.DEFAULT_MESSAGE);
    }

    @Test
    @DisplayName("createNotification: Slack 메시지가 발송된다")
    void createNotification_sendsSlackMessage() {
        given(notificationRepository.save(any(Notification.class))).willAnswer(inv -> inv.getArgument(0));

        notificationCommandService.createNotification(
                NotificationFixture.DEFAULT_USER_ID, NotificationType.ORDER_CREATED, NotificationFixture.DEFAULT_MESSAGE);

        then(slackPort).should().send(NotificationFixture.DEFAULT_MESSAGE);
    }
}
