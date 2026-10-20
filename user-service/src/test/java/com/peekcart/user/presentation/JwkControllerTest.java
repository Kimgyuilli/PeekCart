package com.peekcart.user.presentation;

import com.peekcart.global.jwt.RsaPublicKeyRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * JWKS 응답 스키마 단위 테스트 (ADR-0013 D1).
 * kid → 공개키를 RFC 7517 JWK(kty/use/alg/kid/n/e)로 노출하는지 회귀한다.
 */
@DisplayName("JwkController JWKS 스키마 단위 테스트")
class JwkControllerTest {

    @Test
    @DisplayName("등록된 공개키를 JWK(kty=RSA·use=sig·alg=RS256·kid·n·e)로 노출한다")
    void jwks_exposesRegisteredKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey publicKey = (RSAPublicKey) generator.generateKeyPair().getPublic();

        RsaPublicKeyRegistry registry = mock(RsaPublicKeyRegistry.class);
        given(registry.all()).willReturn(Map.of("kid-1", publicKey));

        Map<String, List<Map<String, String>>> body = new JwkController(registry).jwks();

        assertThat(body).containsKey("keys");
        assertThat(body.get("keys")).hasSize(1);
        Map<String, String> jwk = body.get("keys").get(0);
        assertThat(jwk).containsEntry("kty", "RSA")
                .containsEntry("use", "sig")
                .containsEntry("alg", "RS256")
                .containsEntry("kid", "kid-1");
        assertThat(jwk.get("n")).isNotBlank();
        assertThat(jwk.get("e")).isNotBlank();
        // base64url: no padding/'+'/'/'
        assertThat(jwk.get("n")).doesNotContain("=", "+", "/");

        // 2048bit 모듈러스는 최상위 비트가 켜져 BigInteger.toByteArray() 가 선행 0x00 부호바이트(257B)를 붙인다.
        // JWK 는 부호 없는 값이어야 하므로 선행 0 이 제거돼 정확히 256B(2048bit) 여야 한다.
        byte[] modulus = Base64.getUrlDecoder().decode(jwk.get("n"));
        assertThat(modulus).hasSize(256);
        assertThat(modulus[0]).isNotEqualTo((byte) 0);
    }
}
