package com.peekcart.order.application;

import com.peekcart.order.application.dto.CartDetailDto;
import com.peekcart.order.domain.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장바구니 조회를 담당하는 애플리케이션 서비스.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CartQueryService {

    private final CartRepository cartRepository;

    /**
     * 사용자의 장바구니를 조회한다. 장바구니가 없으면 빈 DTO를 반환한다.
     */
    public CartDetailDto getCart(Long userId) {
        return cartRepository.findByUserId(userId)
                .map(CartDetailDto::from)
                .orElse(CartDetailDto.empty());
    }
}
