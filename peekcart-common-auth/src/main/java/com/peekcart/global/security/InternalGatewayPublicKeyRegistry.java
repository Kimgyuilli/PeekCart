package com.peekcart.global.security;

import com.peekcart.global.jwt.PemKeyLoader;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Gateway 내부 토큰 <b>전용</b> kid → 공개키 레지스트리 (ADR-0017 D3).
 *
 * <p>{@code RsaPublicKeyRegistry}(User access token 검증 + JWKS 게시)와 <b>의도적으로 분리</b>한다.
 * 같은 레지스트리를 쓰면 {@code JwkController} 가 {@code all()} 을 JWKS 로 전량 게시하므로,
 * 내부 신뢰 앵커인 Gateway 공개키가 외부에 노출된다.
 *
 * <p>{@code RsaPublicKeyRegistry} 와 달리 <b>빈 키셋을 허용하지 않는다</b> — 내부 토큰은 유일한 인증
 * 수단이라 키가 없으면 서비스가 아무도 인증하지 못하는 상태로 조용히 뜬다.
 */
@Component
public class InternalGatewayPublicKeyRegistry {

    private final InternalTokenProperties properties;
    private Map<String, RSAPublicKey> keysByKid = Map.of();

    public InternalGatewayPublicKeyRegistry(InternalTokenProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void load() {
        properties.validate();
        Map<String, RSAPublicKey> loaded = new LinkedHashMap<>();
        for (InternalTokenProperties.PublicKeyEntry entry : properties.publicKeys()) {
            if (entry.kid() == null || entry.kid().isBlank() || entry.location() == null) {
                throw new IllegalStateException("app.internal-token.public-keys 항목에 kid/location 누락");
            }
            if (loaded.containsKey(entry.kid())) {
                throw new IllegalStateException("app.internal-token.public-keys 에 중복 kid: " + entry.kid());
            }
            // PEM 파싱 실패는 여기서 터진다 → 부팅 거부(fail-fast)
            loaded.put(entry.kid(), PemKeyLoader.loadPublicKey(entry.location()));
        }
        this.keysByKid = Map.copyOf(loaded);
    }

    /** kid 에 해당하는 Gateway 공개키. 없으면 {@code null}(unknown kid → 검증 거부). */
    public RSAPublicKey find(String kid) {
        return kid == null ? null : keysByKid.get(kid);
    }

    /** 허용 kid 집합 — 진단/테스트용. JWKS 로 게시하지 않는다. */
    public Set<String> kids() {
        return keysByKid.keySet();
    }
}
