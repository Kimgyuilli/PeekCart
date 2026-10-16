package com.peekcart.payment.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 기본 allowlist는 비어 있다. 내부 JWT로 인증된 ADMIN principal만 운영 역할을 가진다. */
@Component
public class ApprovalRecoveryAccess {
    private final Set<String> approvers;
    private final Set<String> executors;
    public ApprovalRecoveryAccess(@Value("${app.approval-recovery.approvers:}") String approvers,
                                  @Value("${app.approval-recovery.executors:}") String executors) {
        this.approvers = split(approvers); this.executors = split(executors);
        if (this.approvers.stream().anyMatch(this.executors::contains)) {
            throw new IllegalArgumentException("Recovery approvers and executors must be disjoint");
        }
    }
    private Set<String> split(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isEmpty()).collect(Collectors.toSet());
    }
    public String reader() {
        java.util.HashSet<String> readers = new java.util.HashSet<>(approvers);
        readers.addAll(executors);
        return require(readers);
    }
    public String approver() { return require(approvers); }
    public String executor() { return require(executors); }
    private String require(Set<String> allowed) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))
                || !allowed.contains(auth.getName())) throw new AccessDeniedException("Recovery role required");
        return auth.getName();
    }
}
