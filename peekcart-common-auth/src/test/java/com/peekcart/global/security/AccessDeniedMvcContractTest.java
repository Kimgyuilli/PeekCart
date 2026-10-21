package com.peekcart.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccessDeniedMvcContractTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ExceptionTranslationFilter filter = new ExceptionTranslationFilter(new JwtAuthenticationEntryPoint(mapper));
        filter.setAccessDeniedHandler(new JwtAccessDeniedHandler(mapper));
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedMvcDenialUsesExistingForbiddenHandler() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("fixture-user", null, AuthorityUtils.createAuthorityList("ROLE_USER")));
        mvc.perform(get("/fixture/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYS-004"));
    }

    @Test
    void anonymousMvcDenialUsesExistingAuthenticationEntryPoint() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("fixture-key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        mvc.perform(get("/fixture/denied"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USR-004"));
    }

    @Test
    void unrelatedMvcExceptionStillUsesGenericServerErrorHandler() throws Exception {
        mvc.perform(get("/fixture/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SYS-001"));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/fixture/denied")
        String denied() {
            throw new AccessDeniedException("fixture authorization failure");
        }

        @GetMapping("/fixture/unexpected")
        String unexpected() {
            throw new IllegalStateException("fixture unexpected failure");
        }
    }
}
