package com.peekcart.notification.application;

import com.peekcart.notification.application.dto.NotificationDetailDto;
import com.peekcart.notification.domain.model.Notification;
import com.peekcart.notification.domain.model.NotificationType;
import com.peekcart.notification.domain.repository.NotificationRepository;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.NotificationFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ServiceTest
@DisplayName("NotificationQueryService 단위 테스트")
class NotificationQueryServiceTest {

    @InjectMocks NotificationQueryService notificationQueryService;
    @Mock NotificationRepository notificationRepository;

    @Test
    @DisplayName("getNotifications: 사용자별 알림 목록을 페이징으로 반환한다")
    void getNotifications_returnsPaged() {
        Pageable pageable = PageRequest.of(0, 10);
        Notification n1 = NotificationFixture.notificationWithId(1L, NotificationType.ORDER_CREATED, "주문 생성");
        Notification n2 = NotificationFixture.notificationWithId(2L, NotificationType.PAYMENT_COMPLETED, "결제 완료");
        Page<Notification> page = new PageImpl<>(List.of(n1, n2), pageable, 2);

        given(notificationRepository.findByUserId(NotificationFixture.DEFAULT_USER_ID, pageable)).willReturn(page);

        Page<NotificationDetailDto> result = notificationQueryService.getNotifications(
                NotificationFixture.DEFAULT_USER_ID, pageable);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).type()).isEqualTo("ORDER_CREATED");
        assertThat(result.getContent().get(1).type()).isEqualTo("PAYMENT_COMPLETED");
    }

    @Test
    @DisplayName("getNotifications: 알림이 없으면 빈 페이지를 반환한다")
    void getNotifications_empty_returnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Notification> emptyPage = new PageImpl<>(List.of(), pageable, 0);

        given(notificationRepository.findByUserId(NotificationFixture.DEFAULT_USER_ID, pageable)).willReturn(emptyPage);

        Page<NotificationDetailDto> result = notificationQueryService.getNotifications(
                NotificationFixture.DEFAULT_USER_ID, pageable);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }
}
