package com.peekcart.gateway.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내부 토큰 서명 지연 baseline.
 *
 * <p>서명은 Reactor 이벤트 루프에서 <b>동기 실행</b>된다. 그래서 "얼마나 걸리는가" 를 코드가 아니라
 * 숫자로 고정해 둔다 — 나중에 키 크기를 올리거나 서명 경로를 바꿨을 때 회귀가 조용히 통과하지 않도록.
 *
 * <p><b>예산(측정 전 확정)</b>: RSA-2048 p95 &lt; {@value #BUDGET_MS_2048}ms · RSA-3072 p95 &lt;
 * {@value #BUDGET_MS_3072}ms. CI 러너 편차를 흡수할 만큼 느슨하되, 한 자릿수 배수의 회귀는 잡는 값이다.
 * 결과값은 stdout 에 남겨 PR 본문/증적에 인용한다.
 *
 * <p><b>이 테스트가 다루지 않는 것</b>: 동시 부하 하의 <i>전체 요청</i> p95/p99 와
 * event-loop lag 는 단일 JVM 마이크로벤치로 측정할 수 없다 — 실 클러스터 부하 세션에서 측정하고,
 * 예산 초과 시 서명 전용 bounded scheduler + 포화 시 503 fail-closed 로 격리한다.
 */
@DisplayName("InternalTokenIssuer — 서명 지연 baseline (RSA 2048/3072)")
class InternalTokenSigningBudgetTest {

    private static final double BUDGET_MS_2048 = 10.0;
    private static final double BUDGET_MS_3072 = 25.0;

    private static final int WARMUP = 50;
    private static final int ITERATIONS = 300;

    @ParameterizedTest(name = "RSA-{0} 서명 p95 가 예산 이내")
    @ValueSource(ints = {2048, 3072})
    void signingLatencyWithinBudget(int keySize) throws Exception {
        InternalTokenIssuer issuer = new InternalTokenIssuer(
                new InternalTokenProperties("bench-kid", privateKeyPem(keySize), 30, true),
                Clock.systemUTC());
        GatewayClaims claims = new GatewayClaims(42L, "USER", "fam-bench", Instant.now().plusSeconds(300));

        for (int i = 0; i < WARMUP; i++) {
            issuer.issue(claims);
        }
        List<Long> nanos = new ArrayList<>(ITERATIONS);
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            issuer.issue(claims);
            nanos.add(System.nanoTime() - start);
        }
        Collections.sort(nanos);

        double p50 = nanos.get((int) (ITERATIONS * 0.50)) / 1_000_000.0;
        double p95 = nanos.get((int) (ITERATIONS * 0.95)) / 1_000_000.0;
        double p99 = nanos.get((int) (ITERATIONS * 0.99)) / 1_000_000.0;
        System.out.printf("[internal-token sign] rsa=%d p50=%.3fms p95=%.3fms p99=%.3fms (n=%d)%n",
                keySize, p50, p95, p99, ITERATIONS);

        double budget = keySize == 2048 ? BUDGET_MS_2048 : BUDGET_MS_3072;
        assertThat(p95)
                .as("RSA-%d 서명 p95 예산 %.1fms 초과 — 이벤트 루프 동기 서명이 위험해진다"
                        + " (계획 P2 (b): bounded scheduler 격리 검토)", keySize, budget)
                .isLessThan(budget);
    }

    /** 벤치 전용 키쌍 — 저장소에 커밋하지 않는다(ADR-0013 D2). */
    private static ByteArrayResource privateKeyPem(int keySize) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(keySize);
        KeyPair pair = generator.generateKeyPair();
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(pair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
        return new ByteArrayResource(pem.getBytes(StandardCharsets.UTF_8));
    }
}
