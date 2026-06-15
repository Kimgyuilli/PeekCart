package com.peekcart.notification.domain.repository;

import com.peekcart.notification.domain.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationRepository {

    Notification save(Notification notification);

    Page<Notification> findByUserId(Long userId, Pageable pageable);
}
