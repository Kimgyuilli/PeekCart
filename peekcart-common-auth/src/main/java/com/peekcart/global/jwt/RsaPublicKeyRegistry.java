package com.peekcart.global.jwt;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * kid → RSA 공개키 레지스트리 (ADR-0013 D1). 모든 서비스/Gateway 검증기가 토큰 헤더 {@code kid}
 * 로 공개키를 선택하는 단일 소스이며, User 는 이를 JWKS 응답의 원본으로 사용한다.
 * <p>부팅 시 {@link JwtKeyProperties#publicKeys()} 를 로드한다. 공개키가 하나도 없어도
 * 부팅은 허용하고, RS256 토큰 도착 시 unknown kid 로 거부된다.
 */
@Component
public class RsaPublicKeyRegistry {

    private final JwtKeyProperties properties;
    private Map<String, RSAPublicKey> keysByKid = Map.of();

    public RsaPublicKeyRegistry(JwtKeyProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void load() {
        Map<String, RSAPublicKey> loaded = new LinkedHashMap<>();
        for (JwtKeyProperties.PublicKeyEntry entry : properties.publicKeys()) {
            if (entry.kid() == null || entry.location() == null) {
                throw new IllegalStateException("app.jwt.rs256.public-keys 항목에 kid/location 누락");
            }
            loaded.put(entry.kid(), PemKeyLoader.loadPublicKey(entry.location()));
        }
        this.keysByKid = Map.copyOf(loaded);
    }

    /** kid 에 해당하는 공개키. 없으면 {@code null}(unknown kid → 검증 거부). */
    public RSAPublicKey find(String kid) {
        return kid == null ? null : keysByKid.get(kid);
    }

    /** JWKS 노출용 (kid → 공개키) 전체 뷰. */
    public Map<String, RSAPublicKey> all() {
        return keysByKid;
    }
}
