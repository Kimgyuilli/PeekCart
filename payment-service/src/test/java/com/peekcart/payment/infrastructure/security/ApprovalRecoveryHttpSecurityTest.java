package com.peekcart.payment.infrastructure.security;

import com.peekcart.internaltoken.InternalTokenContract;
import com.peekcart.internaltoken.InternalTokenFixtures;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SharedContainers.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration",
        "app.internal-token.public-keys[0].kid=gw-test-2026",
        "app.internal-token.public-keys[0].location=classpath:internal-token/gateway-test-public.pem",
        "app.approval-recovery.approvers=101", "app.approval-recovery.executors=202"
})
class ApprovalRecoveryHttpSecurityTest extends AbstractIntegrationTest {
    @Autowired TestRestTemplate rest;
    @BeforeEach void clean() { cleanDatabase(); }
    private HttpStatusCode request(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Spoofed actor is ignored: only signed principal can choose the operation role.
        headers.set("X-User-Id", "101"); headers.set("X-User-Role", "ADMIN");
        if (token != null) headers.set(InternalTokenContract.HEADER, token);
        return rest.exchange("/api/v1/payments/approval-recoveries/" + path, HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "HTTP authority contract", "resolvedBy", "101"), headers), String.class).getStatusCode();
    }
    @Test void unsignedAndForgedActorsCannotCreateRecovery() {
        assertThat(request(null, "orders/9999")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(request(InternalTokenFixtures.mintForged(101L, "ADMIN"), "orders/9999")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    @Test void roleAndAssignedIdentityAreBothRequired() {
        assertThat(request(InternalTokenFixtures.mint(101L, "USER", "family"), "orders/9999")).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(request(InternalTokenFixtures.mint(303L, "ADMIN", "family"), "orders/9999")).isEqualTo(HttpStatus.FORBIDDEN);
        // Assigned signer reaches domain validation (missing order), rather than authentication rejection.
        assertThat(request(InternalTokenFixtures.mint(101L, "ADMIN", "family"), "orders/9999")).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void approverCannotExecuteAndExecutorCannotApprove() {
        assertThat(request(InternalTokenFixtures.mint(101L, "ADMIN", "family"), "missing/execute")).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(request(InternalTokenFixtures.mint(202L, "ADMIN", "family"), "missing/approve")).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
