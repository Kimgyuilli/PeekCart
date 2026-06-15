package com.peekcart.notification.application.dto;

import com.peekcart.notification.domain.model.Notification;

import java.time.LocalDateTime;

public record NotificationDetailDto(
        Long id,
        String type,
        String message,
        boolean isRead,
        LocalDateTime createdAt
) {

    public static NotificationDetailDto from(Notification notification) {
        return new NotificationDetailDto(
                notification.getId(),
                notification.getType().name(),
                notification.getMessage(),
                notification.isRead(),
                notification.getCreatedAt()
        );
    }
}
