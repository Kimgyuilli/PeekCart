package com.peekcart.global.cache;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

/**
 * {@link Page} Redis 직렬화를 위한 래퍼 record.
 * <p>{@code PageImpl}은 기본 Jackson 역직렬화가 불가하므로,
 * 캐시 저장 시 이 record로 변환하고 조회 시 {@link #toPage()}로 복원한다.
 *
 * @param <T> 페이지 요소 타입
 */
public record CachedPage<T>(
        List<T> content,
        long totalElements,
        int pageNumber,
        int pageSize
) {
    public static <T> CachedPage<T> of(Page<T> page) {
        return new CachedPage<>(
                page.getContent(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize());
    }

    public Page<T> toPage() {
        return new PageImpl<>(content, PageRequest.of(pageNumber, pageSize), totalElements);
    }
}
