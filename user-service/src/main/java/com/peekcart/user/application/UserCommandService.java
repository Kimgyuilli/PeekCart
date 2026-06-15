package com.peekcart.user.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.user.domain.exception.UserException;
import com.peekcart.user.domain.model.User;
import com.peekcart.user.domain.repository.UserRepository;
import com.peekcart.user.presentation.dto.request.UpdateProfileRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 정보 변경을 담당하는 애플리케이션 서비스.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class UserCommandService {

    private final UserRepository userRepository;

    /**
     * 현재 로그인한 회원의 프로필을 수정한다.
     *
     * @param userId  수정할 회원 PK
     * @param request 변경 요청 (이름)
     * @return 수정된 회원 도메인 객체
     * @throws UserException 회원이 없으면 {@code USR-003}
     */
    public User updateMe(Long userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserException(ErrorCode.USR_003));
        user.updateProfile(request.name());
        return user;
    }
}
