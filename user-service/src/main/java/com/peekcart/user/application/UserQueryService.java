package com.peekcart.user.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.user.domain.exception.UserException;
import com.peekcart.user.domain.model.User;
import com.peekcart.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 조회를 담당하는 애플리케이션 서비스.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserQueryService {

    private final UserRepository userRepository;

    /**
     * 현재 로그인한 회원 정보를 조회한다.
     *
     * @param userId 조회할 회원 PK
     * @return 회원 도메인 객체
     * @throws UserException 회원이 없으면 {@code USR-003}
     */
    public User getMe(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserException(ErrorCode.USR_003));
    }
}
