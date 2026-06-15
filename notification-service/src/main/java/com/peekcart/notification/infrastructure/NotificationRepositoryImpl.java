package com.peekcart.notification.infrastructure;

import com.peekcart.notification.domain.model.Notification;
import com.peekcart.notification.domain.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationRepositoryImpl implements NotificationRepository {

    private final NotificationJpaRepository notificationJpaRepository;

    @Override
    public Notification save(Notification notification) {
        return notificationJpaRepository.save(notification);
    }

    @Override
    public Page<Notification> findByUserId(Long userId, Pageable pageable) {
        return notificationJpaRepository.findByUserId(userId, pageable);
    }
}
