package com.peekcart.global.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * retention/floor/cleanup 정책키 base-only 배치 가드 (ADR-0007).
 * <p>동작 정책 키는 base {@code application.yml} 소유이고 환경별 프로파일
 * ({@code application-local.yml}/{@code application-k8s.yml})에는 등장하면 안 된다(정책 override 금지).
 * product 를 대표로 검증한다(전 서비스 스윕은 CI/verify grep 이 담당).
 */
@DisplayName("retention 정책키 base-only 배치 가드 (ADR-0007)")
class RetentionPolicyPlacementTest {

    private static final String[] POLICY_KEYS = {"idempotency:", "retention:", "floor:", "cleanup:"};

    private String read(String resource) throws IOException {
        ClassPathResource r = new ClassPathResource(resource);
        try (var in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("정책키는 base application.yml 에만 있고 프로파일 yml 에는 없다")
    void policyKeysOnlyInBase() throws IOException {
        String base = read("application.yml");
        assertThat(base).contains("idempotency:");

        for (String profile : new String[]{"application-local.yml", "application-k8s.yml"}) {
            String content = read(profile);
            for (String key : POLICY_KEYS) {
                assertThat(content)
                        .as("%s 는 정책키 '%s' 를 override 하면 안 됨 (ADR-0007 정책=base)", profile, key)
                        .doesNotContain(key);
            }
        }
    }
}
