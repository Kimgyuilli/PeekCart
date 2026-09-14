package com.peekcart.global.jwt;

import com.peekcart.global.auth.TokenIssuer;
import com.peekcart.support.TestRsaKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RS256 발급 서명 + kid 헤더 + 왕복 검증 + 서명 latency 측정 (ADR-0013 D1/D2, 구현 ③ PR1 P2/P5).
 * 키 자료를 커밋하지 않기 위해 런타임 생성 키쌍({@link TestRsaKeys})으로 발급한다.
 *
 * <p>PR3d 에서 servlet 측 {@code JwtTokenVerifier} 가 삭제됐다(ADR-0014 D2-c exit) — 사용자 토큰을 검증하는
 * 주체는 이제 Gateway 뿐이다. 그래서 왕복 검증은 공개키로 직접 파싱해 <b>발급 산출물의 형태</b>만 고정한다.
 * latency 는 KMS 격상(D2 후속) 판단 근거로 p50/p95 를 기록한다(측정만, 전환 미결정).
 */
@DisplayName("JwtTokenSigner RS256 발급 + latency 측정")
class JwtTokenSignerTest {

    private static final String KID = TestRsaKeys.KID;

    private JwtTokenSigner signer;
    private JwtKeyProperties keyProps;

    @BeforeEach
    void setUp() {
        JwtAuthProperties authProps = new JwtAuthProperties(1_800_000, 604_800_000);
        // 런타임 생성 키쌍(임시 파일) — 개인키를 저장소에 커밋하지 않는다(ADR-0013 D2)
        keyProps = new JwtKeyProperties(
                KID,
                new FileSystemResource(TestRsaKeys.privateKeyFile()),
                List.of(new JwtKeyProperties.PublicKeyEntry(KID, new FileSystemResource(TestRsaKeys.publicKeyFile()))));

        signer = new JwtTokenSigner(authProps, keyProps);
        signer.init();
    }

    @Test
    @DisplayName("발급 토큰은 RS256(kid) 서명이며 공개키로 클레임이 왕복된다")
    void issue_rs256_verifiedByPublicKey() {
        TokenIssuer.IssuedTokens tokens = signer.issue(42L, "USER", "family-42");

        // JWT 헤더(첫 세그먼트)에 kid 가 실려야 한다
        String headerJson = new String(java.util.Base64.getUrlDecoder()
                .decode(tokens.accessToken().split("\\.")[0]));
        assertThat(headerJson).contains("\"kid\":\"" + KID + "\"").contains("RS256");

        RsaPublicKeyRegistry registry = new RsaPublicKeyRegistry(keyProps);
        registry.load();
        Claims claims = Jwts.parser()
                .verifyWith(registry.find(KID))
                .build()
                .parseSignedClaims(tokens.accessToken())
                .getPayload();
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
        assertThat(claims.get("family_id", String.class)).isEqualTo("family-42");
    }

    @Test
    @DisplayName("RS256 서명 latency p50/p95 측정 (ADR-0013 D2 후속 근거)")
    void signLatency_p50_p95() {
        int warmup = 20;
        int iterations = 200;
        for (int i = 0; i < warmup; i++) {
            signer.issue(1L, "USER", "family-1");
        }
        List<Long> nanos = new ArrayList<>(iterations);
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            signer.issue(1L, "USER", "family-1");
            nanos.add(System.nanoTime() - start);
        }
        Collections.sort(nanos);
        double p50 = nanos.get((int) (iterations * 0.50)) / 1_000_000.0;
        double p95 = nanos.get((int) (iterations * 0.95)) / 1_000_000.0;
        System.out.printf("[RS256 sign latency] p50=%.3fms p95=%.3fms (n=%d, dev key)%n", p50, p95, iterations);

        // 회귀 방어(느슨) — CI 환경 편차 흡수. KMS 격상 판단은 별도 ADR 후보(측정만).
        assertThat(p95).isLessThan(200.0);
    }
}
