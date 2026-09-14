package com.peekcart.global.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자 토큰 공개키 설정이 <b>k8s ConfigMap 의 인덱스 env</b> 로 덮어써지는지 검증한다
 * (ADR-0013 D1/D2). {@code InternalTokenPropertiesBindingTest} 의 User 키 도메인 판이다.
 *
 * <p><b>왜 이 테스트가 필요한가</b>: 구현 ③ PR3d-b-2 클러스터 세션에서 user-service 는 Secret
 * Manager 로 <b>새 개인키</b>를 받았는데 공개키는 이미지에 베이크된 dev 키 그대로였다. 발급은 새 키로
 * 하고 JWKS 는 옛 키를 게시해 모듈러스가 어긋났고, 그 결과 자기가 발급한 토큰을 gateway 가 검증하지
 * 못했다. 내부 토큰 도메인에는 ConfigMap 인덱스 env seam 이 있었는데 User 도메인에는 없었던 탓이다.
 * {@code user-jwt-binding} ConfigMap 이 그 대칭을 만들고, 이 테스트가 <b>실제로 바인딩되는지</b>를
 * 고정한다 — 렌더나 매니페스트 lint 로는 증명되지 않는 축이다.
 *
 * <p><b>키쌍은 함께 교체해야 한다</b>: 개인키(Secret Manager CSI)와 공개키(이 ConfigMap)는 같은
 * 키쌍의 양면이다. 한쪽만 바꾸면 위 실패가 그대로 재현된다.
 */
class JwtKeyPropertiesBindingTest {

    /** 이미지에 베이크된 application.yml 상당 — 항상 dev 키 1개. */
    private static Map<String, Object> bakedYaml() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app.jwt.rs256.active-kid", "peekcart-dev-2026");
        m.put("app.jwt.rs256.private-key-location", "file:./local-keys/dev-jwt-private.pem");
        m.put("app.jwt.rs256.public-keys[0].kid", "peekcart-dev-2026");
        m.put("app.jwt.rs256.public-keys[0].location", "classpath:keys/dev-jwt-public.pem");
        return m;
    }

    /** k8s ConfigMap(user-jwt-binding) 이 컨테이너에 넣는 env 상당. */
    private static JwtKeyProperties bind(Map<String, Object> env) {
        MutablePropertySources sources = new MutablePropertySources();
        // 실제 컨테이너와 같은 우선순위: env 가 application.yml 보다 앞선다.
        sources.addFirst(new SystemEnvironmentPropertySource("systemEnvironment", env));
        sources.addLast(new MapPropertySource("application.yml", bakedYaml()));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("app.jwt.rs256", JwtKeyProperties.class)
                .orElseThrow(() -> new AssertionError("app.jwt.rs256 바인딩 실패"));
    }

    @Test
    @DisplayName("운영 kid ConfigMap 이 베이크된 dev 공개키를 대체한다")
    void configMapEnvReplacesBakedPublicKey() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("APP_JWT_RS256_PUBLICKEYS_0_KID", "peekcart-prod-2026");
        env.put("APP_JWT_RS256_PUBLICKEYS_0_LOCATION",
                "file:/etc/peekcart/user-jwt-public-keys/peekcart-prod-2026.pem");

        JwtKeyProperties props = bind(env);

        assertThat(props.publicKeys()).hasSize(1);
        assertThat(props.publicKeys().get(0).kid()).isEqualTo("peekcart-prod-2026");
        // dev 키가 살아남으면 JWKS 가 폐기된 키를 계속 게시한다.
        assertThat(props.publicKeys())
                .noneSatisfy(e -> assertThat(e.kid()).isEqualTo("peekcart-dev-2026"));
    }

    @Test
    @DisplayName("회전 overlap: 인덱스 1 을 추가하면 두 kid 를 동시에 게시한다")
    void rotationOverlapPublishesBothKeys() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("APP_JWT_RS256_PUBLICKEYS_0_KID", "peekcart-prod-2026");
        env.put("APP_JWT_RS256_PUBLICKEYS_0_LOCATION",
                "file:/etc/peekcart/user-jwt-public-keys/peekcart-prod-2026.pem");
        env.put("APP_JWT_RS256_PUBLICKEYS_1_KID", "peekcart-prod-2027");
        env.put("APP_JWT_RS256_PUBLICKEYS_1_LOCATION",
                "file:/etc/peekcart/user-jwt-public-keys/peekcart-prod-2027.pem");

        JwtKeyProperties props = bind(env);

        // 회전 구간: 새 공개키가 JWKS 에 먼저 실려야 새 kid 로 서명한 토큰이 검증된다.
        // 소비자가 JWKS 를 캐시하고 있으므로 구 키를 즉시 빼면 진행 중인 토큰이 전부 깨진다.
        assertThat(props.publicKeys()).extracting(JwtKeyProperties.PublicKeyEntry::kid)
                .containsExactly("peekcart-prod-2026", "peekcart-prod-2027");
    }

    @Test
    @DisplayName("리스트는 소스 단위로 통째 교체된다 — 베이크가 남아 JWKS 로 새지 않는다")
    void higherPrecedenceSourceReplacesWholeList() {
        // 인덱스 단위 병합이었다면 베이크된 dev 키가 운영에서도 JWKS 에 계속 실린다.
        // 실제로는 env 가 리스트를 통째 대체하므로 ConfigMap 이 곧 게시 kid 집합의 단일 출처다.
        JwtKeyProperties props = bind(Map.of(
                "APP_JWT_RS256_PUBLICKEYS_0_KID", "peekcart-prod-2026",
                "APP_JWT_RS256_PUBLICKEYS_0_LOCATION",
                "file:/etc/peekcart/user-jwt-public-keys/peekcart-prod-2026.pem"));

        assertThat(props.publicKeys()).hasSize(1);
    }

    @Test
    @DisplayName("env 가 없으면 베이크된 dev 키가 그대로 쓰인다 — 로컬/테스트 경로 보존")
    void withoutEnvBakedDefaultSurvives() {
        // ConfigMap 이 없는 환경(로컬 dev, 컨테이너 스모크)에서 부팅이 깨지면 안 된다.
        JwtKeyProperties props = bind(Map.of());

        assertThat(props.activeKid()).isEqualTo("peekcart-dev-2026");
        assertThat(props.publicKeys()).extracting(JwtKeyProperties.PublicKeyEntry::kid)
                .containsExactly("peekcart-dev-2026");
    }

    @Test
    @DisplayName("active-kid 는 ConfigMap 이 아니라 base 가 소유한다 — 발급 정책이다 (ADR-0007)")
    void activeKidIsNotOwnedByBindingConfigMap() {
        // user-jwt-binding 에는 active-kid 를 두지 않는다. 회전 시 operator 가 한시적으로 override 하며,
        // 그때도 env 가 이긴다는 것만 여기서 고정한다.
        assertThat(bind(Map.of()).activeKid()).isEqualTo("peekcart-dev-2026");
        assertThat(bind(Map.of("APP_JWT_RS256_ACTIVEKID", "peekcart-prod-2026")).activeKid())
                .isEqualTo("peekcart-prod-2026");
    }
}
