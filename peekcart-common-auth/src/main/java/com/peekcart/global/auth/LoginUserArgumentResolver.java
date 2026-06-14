package com.peekcart.global.auth;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link CurrentUser} 어노테이션이 붙은 {@link LoginUser} 파라미터를
 * {@code SecurityContext}의 인증 정보로부터 해석해 주입하는 ArgumentResolver.
 */
@Component
public class LoginUserArgumentResolver implements HandlerMethodArgumentResolver {

    /**
     * {@link CurrentUser}가 붙은 {@link LoginUser} 타입 파라미터일 때만 처리한다.
     */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && parameter.getParameterType().equals(LoginUser.class);
    }

    /**
     * {@code SecurityContext}에서 userId와 accessToken을 꺼내 {@link LoginUser}를 생성한다.
     */
    @Override
    public LoginUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                     NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("인증 정보가 없습니다.");
        }
        Long userId = (Long) authentication.getPrincipal();
        String accessToken = (String) authentication.getDetails();
        return new LoginUser(userId, accessToken);
    }
}
