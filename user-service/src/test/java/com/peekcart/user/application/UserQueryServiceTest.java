package com.peekcart.user.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.UserFixture;
import com.peekcart.user.domain.exception.UserException;
import com.peekcart.user.domain.model.User;
import com.peekcart.user.domain.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ServiceTest
@DisplayName("UserQueryService 단위 테스트")
class UserQueryServiceTest {

    @InjectMocks UserQueryService userQueryService;
    @Mock UserRepository userRepository;

    @Test
    @DisplayName("getMe: 회원이 존재하면 User 도메인 객체를 반환한다")
    void getMe_success() {
        given(userRepository.findById(UserFixture.DEFAULT_ID))
                .willReturn(Optional.of(UserFixture.userWithId()));

        User result = userQueryService.getMe(UserFixture.DEFAULT_ID);

        assertThat(result.getId()).isEqualTo(UserFixture.DEFAULT_ID);
        assertThat(result.getEmail()).isEqualTo(UserFixture.DEFAULT_EMAIL);
        assertThat(result.getName()).isEqualTo(UserFixture.DEFAULT_NAME);
    }

    @Test
    @DisplayName("getMe: 회원이 없으면 USR-003 예외가 발생한다")
    void getMe_notFound_throwsUSR003() {
        given(userRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userQueryService.getMe(99L))
                .isInstanceOf(UserException.class)
                .extracting(e -> ((UserException) e).getErrorCode())
                .isEqualTo(ErrorCode.USR_003);
    }
}
