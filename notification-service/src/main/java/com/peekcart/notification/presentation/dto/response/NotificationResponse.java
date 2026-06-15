package com.peekcart.notification.presentation.dto.response;

import com.peekcart.notification.application.dto.NotificationDetailDto;

import java.time.LocalDateTime;

public record NotificationResponse(
        Long id,
        String type,
        String message,
        boolean isRead,
        LocalDateTime createdAt
) {

    public static NotificationResponse from(NotificationDetailDto dto) {
        return new NotificationResponse(
                dto.id(),
                dto.type(),
                dto.message(),
                dto.isRead(),
                dto.createdAt()
        );
    }
}
