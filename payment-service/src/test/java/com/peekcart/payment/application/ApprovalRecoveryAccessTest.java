package com.peekcart.payment.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ApprovalRecoveryAccessTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void overlappingRolesFailStartup() {
        assertThatThrownBy(() -> new ApprovalRecoveryAccess("101,202", "202"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void emptyDefaultsAndUnauthenticatedActorsCannotOperate() {
        var roles = new ApprovalRecoveryAccess("", "");
        assertThatThrownBy(roles::approver).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("101", "none",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertThatThrownBy(roles::executor).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
