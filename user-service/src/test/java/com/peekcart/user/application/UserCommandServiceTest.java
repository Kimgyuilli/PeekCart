package com.peekcart.user.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.UserFixture;
import com.peekcart.user.domain.exception.UserException;
import com.peekcart.user.domain.model.User;
import com.peekcart.user.domain.repository.UserRepository;
import com.peekcart.user.presentation.dto.request.UpdateProfileRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ServiceTest
@DisplayName("UserCommandService 단위 테스트")
class UserCommandServiceTest {

    @InjectMocks UserCommandService userCommandService;
    @Mock UserRepository userRepository;

    @Test
    @DisplayName("updateMe: 회원이 존재하면 이름을 변경하고 User 도메인 객체를 반환한다")
    void updateMe_success() {
        User user = UserFixture.userWithId();
        given(userRepository.findById(UserFixture.DEFAULT_ID)).willReturn(Optional.of(user));

        User result = userCommandService.updateMe(UserFixture.DEFAULT_ID, new UpdateProfileRequest("새이름"));

        assertThat(result.getName()).isEqualTo("새이름");
        assertThat(result.getId()).isEqualTo(UserFixture.DEFAULT_ID);
    }

    @Test
    @DisplayName("updateMe: 회원이 없으면 USR-003 예외가 발생한다")
    void updateMe_notFound_throwsUSR003() {
        given(userRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userCommandService.updateMe(99L, new UpdateProfileRequest("이름")))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_003);
    }
}
