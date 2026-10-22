package com.peekcart.gateway.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.internaltoken.InternalTokenContract;
import com.peekcart.internaltoken.InternalTokenFixtures;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 내부 토큰 <b>발행</b> 계약 (ADR-0017 D1).
 *
 * <p>교차모듈 conformance 의 발행측 절반이다 — gateway 는 common-auth(servlet)를 의존할 수 없어 하나의
 * 테스트로 발행↔검증을 잇지 못하므로, 공유 fixture 의 <b>커밋된 계약 토큰</b>과 자기 출력을 대조한다.
 * 검증측({@code InternalTokenVerifierTest})은 같은 토큰을 소비한다. 한쪽만 바뀌면 반대편이 깨진다.
 */
@DisplayName("InternalTokenIssuer — 서명 발행 계약 · fail-fast · fail-closed")
class InternalTokenIssuerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(InternalTokenFixtures.ISSUED_AT, ZoneOffset.UTC);

    private static InternalTokenIssuer issuer(String kid, Resource key, int ttl, boolean requireFamilyId) {
        return new InternalTokenIssuer(new InternalTokenProperties(kid, key, ttl, requireFamilyId), FIXED_CLOCK);
    }

    private static Resource gatewayKey() {
        return new ByteArrayResource(InternalTokenFixtures.gatewayPrivateKeyPem().getBytes(StandardCharsets.UTF_8));
    }

    private static Jws<Claims> parse(String token) {
        return Jwts.parser()
                .verifyWith(InternalTokenFixtures.gatewayPublicKey())
                .clock(() -> Date.from(InternalTokenFixtures.ISSUED_AT))
                .build()
                .parseSignedClaims(token);
    }

    @Nested
    @DisplayName("계약 준수")
    class Contract {

        @Test
        @DisplayName("발행 토큰의 claims 가 공유 계약(conformance vector)과 일치한다")
        void issuedClaimsMatchSharedContract() throws Exception {
            String token = issuer(InternalTokenFixtures.KID, gatewayKey(), 30, true)
                    .issue(new GatewayClaims(InternalTokenFixtures.USER_ID, InternalTokenFixtures.ROLE,
                            InternalTokenFixtures.FAMILY_ID, Instant.now()));

            Jws<Claims> parsed = parse(token);
            JsonNode expected = new ObjectMapper().readTree(InternalTokenFixtures.conformanceClaimsJson());

            assertThat(parsed.getPayload().getIssuer()).isEqualTo(expected.get("iss").asText());
            assertThat(parsed.getPayload().getSubject()).isEqualTo(expected.get("sub").asText());
            assertThat(parsed.getPayload().get(InternalTokenContract.CLAIM_ROLE, String.class))
                    .isEqualTo(expected.get(InternalTokenContract.CLAIM_ROLE).asText());
            assertThat(parsed.getPayload().get(InternalTokenContract.CLAIM_FAMILY_ID, String.class))
                    .isEqualTo(expected.get(InternalTokenContract.CLAIM_FAMILY_ID).asText());
            assertThat(parsed.getPayload().getIssuedAt().toInstant().getEpochSecond())
                    .isEqualTo(expected.get("iat").asLong());
            assertThat(parsed.getPayload().getExpiration().toInstant().getEpochSecond())
                    .isEqualTo(expected.get("exp").asLong());
        }

        @Test
        @DisplayName("헤더에 alg=RS256 과 active kid 를 기록한다")
        void headerCarriesAlgAndKid() {
            String token = issuer(InternalTokenFixtures.KID, gatewayKey(), 30, true)
                    .issue(new GatewayClaims(7L, "ADMIN", "fam-7", Instant.now()));

            Jws<Claims> parsed = parse(token);
            assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(InternalTokenContract.ALGORITHM);
            assertThat(parsed.getHeader().getKeyId()).isEqualTo(InternalTokenFixtures.KID);
        }

        @Test
        @DisplayName("exp = iat + ttl-seconds")
        void expirationFollowsTtl() {
            String token = issuer(InternalTokenFixtures.KID, gatewayKey(), 15, true)
                    .issue(new GatewayClaims(7L, "USER", "fam-7", Instant.now()));

            Claims claims = parse(token).getPayload();
            assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(15_000L);
        }
    }

    @Nested
    @DisplayName("fail-closed / fail-fast")
    class Failures {

        @Test
        @DisplayName("fid 필수인데 family-less → 발행 거부 (신원을 통과시키지 않는다)")
        void familyLessRefusedWhenRequired() {
            InternalTokenIssuer issuer = issuer(InternalTokenFixtures.KID, gatewayKey(), 30, true);

            assertThatThrownBy(() -> issuer.issue(new GatewayClaims(42L, "USER", null, Instant.now())))
                    .isInstanceOf(InternalTokenIssuer.IssuanceRefusedException.class);
            assertThatThrownBy(() -> issuer.issue(new GatewayClaims(42L, "USER", "   ", Instant.now())))
                    .as("blank fid 도 미보유와 동일하게 취급해야 한다")
                    .isInstanceOf(InternalTokenIssuer.IssuanceRefusedException.class);
        }

        @Test
        @DisplayName("active-kid 누락 → 부팅 실패")
        void blankKidFailsFast() {
            assertThatThrownBy(() -> issuer("  ", gatewayKey(), 30, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active-kid");
        }

        @Test
        @DisplayName("개인키 위치 누락 → 부팅 실패")
        void missingKeyLocationFailsFast() {
            assertThatThrownBy(() -> issuer(InternalTokenFixtures.KID, null, 30, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("private-key-location");
        }

        @Test
        @DisplayName("존재하지 않는 개인키 파일 → 부팅 실패 (첫 요청까지 지연 금지)")
        void unreadableKeyFailsFast() {
            assertThatThrownBy(() -> issuer(InternalTokenFixtures.KID,
                    new ClassPathResource("internal-token/does-not-exist.pem"), 30, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("개인키");
        }

        @Test
        @DisplayName("PEM 이 아닌 자료 → 부팅 실패")
        void malformedKeyFailsFast() {
            Resource garbage = new ByteArrayResource("not a pem".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> issuer(InternalTokenFixtures.KID, garbage, 30, true))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("ttl 범위 위반(0 · 상한 초과) → 부팅 실패")
        void ttlOutOfRangeFailsFast() {
            assertThatThrownBy(() -> issuer(InternalTokenFixtures.KID, gatewayKey(), 0, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ttl-seconds");
            assertThatThrownBy(() -> issuer(InternalTokenFixtures.KID, gatewayKey(),
                    InternalTokenProperties.MAX_TTL_SECONDS + 1, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ttl-seconds");
        }
    }
}
