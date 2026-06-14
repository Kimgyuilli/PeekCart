package com.peekcart.support;

import org.springframework.security.test.context.support.WithSecurityContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@code @WebMvcTest}에서 {@code @CurrentUser LoginUser}가 주입되도록
 * SecurityContext에 인증 정보를 설정하는 테스트 어노테이션.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithMockLoginUserSecurityContextFactory.class)
public @interface WithMockLoginUser {
    long userId() default 1L;
    String accessToken() default "mock-access-token";
}
