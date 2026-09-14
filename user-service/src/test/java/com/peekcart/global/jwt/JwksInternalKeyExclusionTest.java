package com.peekcart.global.jwt;

import com.peekcart.internaltoken.InternalTokenFixtures;
import com.peekcart.user.presentation.JwkController;
import com.peekcart.support.InternalKeyFingerprint;
import com.peekcart.support.TestRsaKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWKS 에 Gateway 내부 토큰 공개키가 실리지 않음을 고정한다 (계획 P9 · ADR-0017 D3).
 *
 * <p><b>왜 kid 만으로는 부족한가</b>: {@code app.jwt.rs256.public-keys} 에 Gateway 공개키를 <i>다른 kid</i> 로
 * 끼워 넣으면 kid 대조는 통과하면서 키 자체는 공개된다. 그래서 kid 가 아니라 <b>SPKI DER SHA-256
 * fingerprint</b> 로 비교한다 — 같은 키를 어떤 이름으로 넣든 걸린다.
 *
 * <p>내부 신뢰 앵커가 JWKS 로 새면 누구나 Gateway 공개키를 얻지만, 그것만으로 위조가 되지는 않는다.
 * 다만 키 도메인 분리(ADR-0017 D3)가 깨졌다는 신호이며, 회전·폐기 경로가 User JWKS 에 묶여버린다.
 *
 * <p>패키지가 {@code com.peekcart.global.jwt} 인 이유: {@code RsaPublicKeyRegistry.load()} 가 package-private
 * 이라 스프링 컨텍스트 없이 레지스트리를 직접 구성하려면 같은 패키지여야 한다.
 */
@DisplayName("JWKS — Gateway 내부 토큰 공개키 배제 (fingerprint 기준)")
class JwksInternalKeyExclusionTest {

    private static final String USER_KID = TestRsaKeys.KID;

    /** User 서명 키만 담은 정상 배선. */
    private JwkController controller(List<JwtKeyProperties.PublicKeyEntry> publicKeys) {
        JwtKeyProperties props = new JwtKeyProperties(
                USER_KID, new FileSystemResource(TestRsaKeys.privateKeyFile()), publicKeys);
        RsaPublicKeyRegistry registry = new RsaPublicKeyRegistry(props);
        registry.load();
        return new JwkController(registry);
    }

    private static JwtKeyProperties.PublicKeyEntry userKey() {
        return new JwtKeyProperties.PublicKeyEntry(USER_KID, new FileSystemResource(TestRsaKeys.publicKeyFile()));
    }

    /** SPKI DER SHA-256 — PEM 문자열 hash 가 아니라 키 자체의 정규화된 지문(재인코딩·개행 차이에 불변). */
    private static String fingerprint(RSAPublicKey key) {
        return InternalKeyFingerprint.of(key);
    }

    private static String fingerprintOfJwk(Map<String, String> jwk) {
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("n")));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("e")));
        try {
            var spec = new java.security.spec.RSAPublicKeySpec(modulus, exponent);
            RSAPublicKey key = (RSAPublicKey) java.security.KeyFactory.getInstance("RSA").generatePublic(spec);
            return fingerprint(key);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("정상 배선: JWKS 는 User 서명 키만 게시하고 Gateway 키 fingerprint 는 없다")
    void jwksExcludesGatewayKey() {
        List<Map<String, String>> keys = controller(List.of(userKey())).jwks().get("keys");

        String gatewayFingerprint = fingerprint(InternalTokenFixtures.gatewayPublicKey());
        assertThat(keys).hasSize(1);
        assertThat(keys).extracting(JwksInternalKeyExclusionTest::fingerprintOfJwk)
                .as("Gateway 내부 토큰 공개키가 JWKS 로 새면 안 된다")
                .doesNotContain(gatewayFingerprint);
    }

    @Test
    @DisplayName("음성 대조군: Gateway 키를 '다른 kid' 로 끼워 넣으면 fingerprint 검사가 잡아낸다")
    void wrongKidSmugglingIsDetected() {
        // kid 만 대조했다면 이 배선은 통과한다 — 검사 자체가 vacuous 하지 않음을 보인다.
        JwtKeyProperties.PublicKeyEntry smuggled = new JwtKeyProperties.PublicKeyEntry(
                "looks-like-a-user-key",
                new ByteArrayResource(InternalTokenFixtures.gatewayPublicKeyPem().getBytes(StandardCharsets.UTF_8)));

        List<Map<String, String>> keys = controller(List.of(userKey(), smuggled)).jwks().get("keys");

        assertThat(keys).extracting(jwk -> jwk.get("kid"))
                .as("kid 대조로는 우회된다(그래서 kid 만 보면 안 된다)")
                .doesNotContain("gw-test-2026");
        assertThat(keys).extracting(JwksInternalKeyExclusionTest::fingerprintOfJwk)
                .as("fingerprint 로는 같은 키임이 드러나야 한다")
                .contains(fingerprint(InternalTokenFixtures.gatewayPublicKey()));
    }
}
