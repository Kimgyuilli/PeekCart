package com.peekcart.global.security;

import com.peekcart.global.auth.LoginUser;
import com.peekcart.internaltoken.InternalTokenContract;
import com.peekcart.internaltoken.InternalTokenFixtures;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 내부 토큰 <b>검증</b> 계약 + 음성 매트릭스 (ADR-0017 D3).
 *
 * <p>교차모듈 conformance 의 검증측 절반 — 발행측({@code InternalTokenIssuerTest})이 만드는 것과 동일한
 * <b>커밋된 계약 토큰</b>을 소비한다.
 *
 * <p>음성 케이스가 본체다: 서명만 맞으면 통과하는 검증기는 사용자 access token 을 그대로 받아들여
 * Gateway 우회를 허용한다. 그래서 iss·kid·alg·수명·claim 타입까지 전부 핀한다.
 */
@DisplayName("InternalTokenVerifier — 계약 + 음성 매트릭스")
class InternalTokenVerifierTest {

    private static final Instant NOW = InternalTokenFixtures.ISSUED_AT.plusSeconds(5);

    private InternalTokenVerifier verifier(InternalTokenProperties.Mode mode) {
        InternalTokenProperties properties = new InternalTokenProperties(
                mode,
                List.of(new InternalTokenProperties.PublicKeyEntry(InternalTokenFixtures.KID, pem(InternalTokenFixtures.gatewayPublicKeyPem()))),
                5,
                120);
        InternalGatewayPublicKeyRegistry registry = new InternalGatewayPublicKeyRegistry(properties);
        registry.load();
        return new InternalTokenVerifier(registry, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Resource pem(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    /** 승인 키로 서명한 토큰을 만든다. {@code customizer} 로 claim 을 변형해 음성 케이스를 구성한다. */
    private static String signed(Consumer<JwtBuilder> customizer) {
        return InternalTokenFixtures.sign(InternalTokenFixtures.gatewayPrivateKey(), InternalTokenFixtures.KID, customizer);
    }

    private void expectRejected(String token) {
        assertThatThrownBy(() -> verifier(InternalTokenProperties.Mode.SIGNED_ONLY).verify(token))
                .isInstanceOf(InternalTokenVerifier.InvalidInternalTokenException.class);
    }

    @Nested
    @DisplayName("정상 경로")
    class Valid {

        @Test
        @DisplayName("커밋된 계약 토큰을 발행측 의도대로 해석한다 (교차모듈 conformance)")
        void conformanceTokenIsAccepted() {
            LoginUser user = verifier(InternalTokenProperties.Mode.SIGNED_ONLY)
                    .verify(InternalTokenFixtures.conformanceToken());

            assertThat(user.userId()).isEqualTo(InternalTokenFixtures.USER_ID);
            assertThat(user.role()).isEqualTo(InternalTokenFixtures.ROLE);
            assertThat(user.familyId()).isEqualTo(InternalTokenFixtures.FAMILY_ID);
        }

        @Test
        @DisplayName("ADMIN role 도 허용된다")
        void adminRoleAccepted() {
            String token = signed(b -> b.claim(InternalTokenContract.CLAIM_ROLE, "ADMIN"));
            assertThat(verifier(InternalTokenProperties.Mode.SIGNED_ONLY).verify(token).role()).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("skew 이내의 근소한 미래 iat 은 허용된다")
        void slightFutureIatWithinSkewAccepted() {
            String token = signed(b -> b.issuedAt(Date.from(NOW.plusSeconds(3))));
            assertThat(verifier(InternalTokenProperties.Mode.SIGNED_ONLY).verify(token).userId())
                    .isEqualTo(InternalTokenFixtures.USER_ID);
        }
    }

    @Nested
    @DisplayName("서명 · 알고리즘 · 키")
    class Signature {

        @Test
        @DisplayName("미승인 키로 서명한 위조 토큰 → 거부")
        void foreignKeySignatureRejected() {
            expectRejected(InternalTokenFixtures.sign(
                    InternalTokenFixtures.foreignPrivateKey(), InternalTokenFixtures.KID, b -> {
                    }));
        }

        @Test
        @DisplayName("승인 집합 밖의 kid → 거부")
        void unknownKidRejected() {
            expectRejected(InternalTokenFixtures.sign(
                    InternalTokenFixtures.gatewayPrivateKey(), "unknown-kid", b -> {
                    }));
        }

        @Test
        @DisplayName("kid 부재 → 거부")
        void missingKidRejected() {
            expectRejected(InternalTokenFixtures.sign(
                    InternalTokenFixtures.gatewayPrivateKey(), null, b -> {
                    }));
        }

        @Test
        @DisplayName("HS512 대칭키 서명 → 거부 (alg 혼동 차단)")
        void hmacSignatureRejected() {
            String hmac = Jwts.builder()
                    .header().keyId(InternalTokenFixtures.KID).and()
                    .issuer(InternalTokenContract.ISSUER)
                    .subject("42")
                    .claim(InternalTokenContract.CLAIM_ROLE, "USER")
                    .claim(InternalTokenContract.CLAIM_FAMILY_ID, "fam-1")
                    .issuedAt(Date.from(InternalTokenFixtures.ISSUED_AT))
                    .expiration(Date.from(InternalTokenFixtures.EXPIRES_AT))
                    .signWith(Keys.hmacShaKeyFor(
                            "peekcart-secret-key-must-be-at-least-512-bits-long-xxxxxxxxxxxxxxxxxxxxxxxx".getBytes(StandardCharsets.UTF_8)))
                    .compact();
            expectRejected(hmac);
        }

        @Test
        @DisplayName("서명 없는(unsecured) 토큰 → 거부")
        void unsignedTokenRejected() {
            expectRejected(Jwts.builder()
                    .issuer(InternalTokenContract.ISSUER)
                    .subject("42")
                    .claim(InternalTokenContract.CLAIM_ROLE, "USER")
                    .expiration(Date.from(InternalTokenFixtures.EXPIRES_AT))
                    .compact());
        }

        @Test
        @DisplayName("JWT 형식이 아닌 문자열 → 거부")
        void malformedTokenRejected() {
            expectRejected("not-a-jwt");
        }

        @Test
        @DisplayName("승인 키의 RS384/RS512 서명 → 거부 (alg 를 RSA 계열로 뭉뚱그리지 않는다)")
        void otherRsaAlgorithmsRejected() {
            // HS/unsecured 만 막으면 "RSA 면 통과" 인 구현도 테스트를 통과한다 — RS256 정확 핀을 고정한다.
            for (io.jsonwebtoken.security.SecureDigestAlgorithm<java.security.PrivateKey, ?> alg :
                    java.util.List.of(Jwts.SIG.RS384, Jwts.SIG.RS512)) {
                String token = Jwts.builder()
                        .header().keyId(InternalTokenFixtures.KID).and()
                        .issuer(InternalTokenContract.ISSUER)
                        .subject(String.valueOf(InternalTokenFixtures.USER_ID))
                        .claim(InternalTokenContract.CLAIM_ROLE, InternalTokenFixtures.ROLE)
                        .claim(InternalTokenContract.CLAIM_FAMILY_ID, InternalTokenFixtures.FAMILY_ID)
                        .issuedAt(Date.from(InternalTokenFixtures.ISSUED_AT))
                        .expiration(Date.from(InternalTokenFixtures.EXPIRES_AT))
                        .signWith(InternalTokenFixtures.gatewayPrivateKey(), alg)
                        .compact();
                expectRejected(token);
            }
        }

        @Test
        @DisplayName("양성 대조군: 같은 claim 을 RS256 으로 서명하면 통과한다 (위 음성이 vacuous 하지 않음)")
        void rs256CounterpartAccepted() {
            assertThat(verifier(InternalTokenProperties.Mode.SIGNED_ONLY).verify(signed(b -> {
            })).userId()).isEqualTo(InternalTokenFixtures.USER_ID);
        }

        @Test
        @DisplayName("회전 overlap: active/previous 두 kid 가 모두 검증된다")
        void activeAndPreviousKidsBothAccepted() {
            InternalTokenProperties properties = new InternalTokenProperties(
                    InternalTokenProperties.Mode.SIGNED_ONLY,
                    List.of(new InternalTokenProperties.PublicKeyEntry("gw-previous",
                                    pem(InternalTokenFixtures.foreignPublicKeyPem())),
                            new InternalTokenProperties.PublicKeyEntry(InternalTokenFixtures.KID,
                                    pem(InternalTokenFixtures.gatewayPublicKeyPem()))),
                    5, 120);
            InternalGatewayPublicKeyRegistry registry = new InternalGatewayPublicKeyRegistry(properties);
            registry.load();
            InternalTokenVerifier rotating =
                    new InternalTokenVerifier(registry, properties, Clock.fixed(NOW, ZoneOffset.UTC));

            assertThat(rotating.verify(signed(b -> {
            })).userId()).isEqualTo(InternalTokenFixtures.USER_ID);
            assertThat(rotating.verify(InternalTokenFixtures.sign(
                    InternalTokenFixtures.foreignPrivateKey(), "gw-previous", b -> {
                    })).userId()).isEqualTo(InternalTokenFixtures.USER_ID);
        }
    }

    @Nested
    @DisplayName("발행자 · 수명")
    class IssuerAndLifetime {

        @Test
        @DisplayName("iss 가 Gateway 가 아닌 토큰(사용자 access token 오용) → 거부")
        void wrongIssuerRejected() {
            expectRejected(signed(b -> b.issuer("peekcart-user-service")));
        }

        @Test
        @DisplayName("iss 부재 → 거부")
        void missingIssuerRejected() {
            expectRejected(signed(b -> b.claims().delete("iss").and()));
        }

        @Test
        @DisplayName("만료된 토큰 → 거부")
        void expiredRejected() {
            expectRejected(signed(b -> b
                    .issuedAt(Date.from(InternalTokenFixtures.ISSUED_AT.minusSeconds(600)))
                    .expiration(Date.from(InternalTokenFixtures.ISSUED_AT.minusSeconds(570)))));
        }

        @Test
        @DisplayName("exp 부재 → 거부 (무기한 내부 토큰 금지)")
        void missingExpirationRejected() {
            expectRejected(signed(b -> b.claims().delete("exp").and()));
        }

        @Test
        @DisplayName("iat 부재 → 거부 (수명 상한을 계산할 수 없다)")
        void missingIssuedAtRejected() {
            expectRejected(signed(b -> b.claims().delete("iat").and()));
        }

        @Test
        @DisplayName("skew 를 넘는 미래 iat → 거부")
        void futureIssuedAtRejected() {
            expectRejected(signed(b -> b
                    .issuedAt(Date.from(NOW.plusSeconds(60)))
                    .expiration(Date.from(NOW.plusSeconds(90)))));
        }

        @Test
        @DisplayName("수명 상한 초과(exp-iat > max-ttl) → 거부 (서명만 맞는 장수명 토큰 차단)")
        void overlongLifetimeRejected() {
            expectRejected(signed(b -> b
                    .issuedAt(Date.from(InternalTokenFixtures.ISSUED_AT))
                    .expiration(Date.from(InternalTokenFixtures.ISSUED_AT.plusSeconds(3600)))));
        }

        @Test
        @DisplayName("exp 가 iat 이전 → 거부")
        void expirationBeforeIssuedAtRejected() {
            expectRejected(signed(b -> b
                    .issuedAt(Date.from(InternalTokenFixtures.ISSUED_AT))
                    .expiration(Date.from(InternalTokenFixtures.ISSUED_AT.minusSeconds(10)))));
        }

        @Test
        @DisplayName("exp == iat → 거부 (수명 0 은 유효 구간이 없다)")
        void zeroLifetimeRejected() {
            expectRejected(signed(b -> b
                    .issuedAt(Date.from(InternalTokenFixtures.ISSUED_AT))
                    .expiration(Date.from(InternalTokenFixtures.ISSUED_AT))));
        }

        @Test
        @DisplayName("수명 경계: max-ttl 정확히 = 허용 / +1초 = 거부")
        void maxTtlBoundary() {
            // 경계에서 한 칸씩 밀리면 "상한 검사가 있다" 는 사실만 확인하고 값은 고정되지 않는다.
            String atLimit = signed(b -> b
                    .issuedAt(Date.from(NOW))
                    .expiration(Date.from(NOW.plusSeconds(120))));
            assertThat(verifier(InternalTokenProperties.Mode.SIGNED_ONLY).verify(atLimit).userId())
                    .isEqualTo(InternalTokenFixtures.USER_ID);

            expectRejected(signed(b -> b
                    .issuedAt(Date.from(NOW))
                    .expiration(Date.from(NOW.plusSeconds(121)))));
        }

        @Test
        @DisplayName("skew 경계: iat = now+5s 허용 / now+6s 거부")
        void skewBoundary() {
            String atLimit = signed(b -> b
                    .issuedAt(Date.from(NOW.plusSeconds(5)))
                    .expiration(Date.from(NOW.plusSeconds(35))));
            assertThat(verifier(InternalTokenProperties.Mode.SIGNED_ONLY).verify(atLimit).userId())
                    .isEqualTo(InternalTokenFixtures.USER_ID);

            expectRejected(signed(b -> b
                    .issuedAt(Date.from(NOW.plusSeconds(6)))
                    .expiration(Date.from(NOW.plusSeconds(36)))));
        }
    }

    @Nested
    @DisplayName("claim 형식")
    class ClaimShape {

        @Test
        @DisplayName("sub 부재 → 거부")
        void missingSubjectRejected() {
            expectRejected(signed(b -> b.claims().delete("sub").and()));
        }

        @Test
        @DisplayName("sub 가 숫자 형식이 아님 → 거부")
        void nonNumericSubjectRejected() {
            expectRejected(signed(b -> b.subject("not-a-number")));
        }

        @Test
        @DisplayName("sub 가 양수가 아님 → 거부")
        void nonPositiveSubjectRejected() {
            expectRejected(signed(b -> b.subject("0")));
            expectRejected(signed(b -> b.subject("-1")));
        }

        @Test
        @DisplayName("role 부재 / 미허용 role → 거부")
        void invalidRoleRejected() {
            expectRejected(signed(b -> b.claims().delete(InternalTokenContract.CLAIM_ROLE).and()));
            expectRejected(signed(b -> b.claim(InternalTokenContract.CLAIM_ROLE, "SUPERADMIN")));
        }

        @Test
        @DisplayName("role 타입 오류(숫자) → 거부")
        void wrongRoleTypeRejected() {
            expectRejected(signed(b -> b.claim(InternalTokenContract.CLAIM_ROLE, 7)));
        }

        @Test
        @DisplayName("fid 타입 오류(숫자) → 거부")
        void wrongFamilyIdTypeRejected() {
            expectRejected(signed(b -> b.claim(InternalTokenContract.CLAIM_FAMILY_ID, 7)));
        }
    }

    @Nested
    @DisplayName("모드별 fid 정책")
    class FamilyIdPolicy {

        @Test
        @DisplayName("SIGNED_ONLY: fid 부재 → 거부")
        void signedOnlyRequiresFamilyId() {
            expectRejected(signed(b -> b.claims().delete(InternalTokenContract.CLAIM_FAMILY_ID).and()));
        }

        @Test
        @DisplayName("SIGNED_ONLY: blank fid → 거부 (존재하지만 비어 있는 값도 미보유)")
        void signedOnlyRejectsBlankFamilyId() {
            expectRejected(signed(b -> b.claim(InternalTokenContract.CLAIM_FAMILY_ID, "   ")));
        }

        @Test
        @DisplayName("DUAL_ACCEPT: fid 부재 허용 (전환기 레거시 신원)")
        void dualAcceptAllowsMissingFamilyId() {
            String token = signed(b -> b.claims().delete(InternalTokenContract.CLAIM_FAMILY_ID).and());
            LoginUser user = verifier(InternalTokenProperties.Mode.DUAL_ACCEPT).verify(token);
            assertThat(user.familyId()).isNull();
            assertThat(user.userId()).isEqualTo(InternalTokenFixtures.USER_ID);
        }
    }

    @Nested
    @DisplayName("레지스트리 fail-fast")
    class RegistryFailFast {

        @Test
        @DisplayName("공개키가 하나도 없으면 부팅 실패 (전 요청 401 로 발현되기 전에 잡는다)")
        void emptyKeySetFailsFast() {
            InternalTokenProperties properties = new InternalTokenProperties(
                    InternalTokenProperties.Mode.SIGNED_ONLY, List.of(), 5, 120);
            assertThatThrownBy(() -> new InternalGatewayPublicKeyRegistry(properties).load())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("public-keys");
        }

        @Test
        @DisplayName("PEM 이 깨졌으면 부팅 실패")
        void malformedPemFailsFast() {
            InternalTokenProperties properties = new InternalTokenProperties(
                    InternalTokenProperties.Mode.SIGNED_ONLY,
                    List.of(new InternalTokenProperties.PublicKeyEntry("k1", pem("garbage"))), 5, 120);
            assertThatThrownBy(() -> new InternalGatewayPublicKeyRegistry(properties).load())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("읽을 수 없는 키 위치 → 부팅 실패")
        void unreadableKeyFailsFast() {
            InternalTokenProperties properties = new InternalTokenProperties(
                    InternalTokenProperties.Mode.SIGNED_ONLY,
                    List.of(new InternalTokenProperties.PublicKeyEntry("k1",
                            new ClassPathResource("internal-token/missing.pem"))), 5, 120);
            assertThatThrownBy(() -> new InternalGatewayPublicKeyRegistry(properties).load())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("kid 중복 → 부팅 실패 (어느 키가 이기는지 모호해진다)")
        void duplicateKidFailsFast() {
            Resource key = pem(InternalTokenFixtures.gatewayPublicKeyPem());
            InternalTokenProperties properties = new InternalTokenProperties(
                    InternalTokenProperties.Mode.SIGNED_ONLY,
                    List.of(new InternalTokenProperties.PublicKeyEntry("dup", key),
                            new InternalTokenProperties.PublicKeyEntry("dup", key)), 5, 120);
            assertThatThrownBy(() -> new InternalGatewayPublicKeyRegistry(properties).load())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("중복 kid");
        }

        @Test
        @DisplayName("skew / max-ttl 범위 위반 → 부팅 실패")
        void rangeViolationFailsFast() {
            assertThatThrownBy(() -> new InternalTokenProperties(
                    InternalTokenProperties.Mode.SIGNED_ONLY,
                    List.of(new InternalTokenProperties.PublicKeyEntry("k1", pem(InternalTokenFixtures.gatewayPublicKeyPem()))),
                    InternalTokenProperties.MAX_SKEW_SECONDS + 1, 120).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("skew-seconds");
            assertThatThrownBy(() -> new InternalTokenProperties(
                    InternalTokenProperties.Mode.SIGNED_ONLY,
                    List.of(new InternalTokenProperties.PublicKeyEntry("k1", pem(InternalTokenFixtures.gatewayPublicKeyPem()))),
                    5, InternalTokenProperties.TTL_CEILING_SECONDS + 1).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("max-ttl-seconds");
        }
    }
}
