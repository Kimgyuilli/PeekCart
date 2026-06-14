package com.peekcart.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 생성 시각과 수정 시각({@code updated_at})을 함께 갖는 엔티티의 공통 상위 클래스.
 */
@MappedSuperclass
@Getter
public abstract class BaseEntity extends BaseTimeEntity {

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
