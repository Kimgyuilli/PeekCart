package com.peekcart.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내부 토큰 공개키 설정이 <b>k8s ConfigMap 의 인덱스 env</b> 로 덮어써지는지 검증한다
 * (ADR-0017 D2).
 *
 * <p><b>왜 이 테스트가 필요한가</b>: 이미지 재빌드 없이 키를 회전하기 위해 공개키 목록을
 * `internal-token-binding` ConfigMap 의 인덱스 env(`APP_INTERNALTOKEN_PUBLICKEYS_<i>_*`)로 주입한다.
 * 이 설계는 "env 가 이미지에 베이크된 application.yml 리스트를 인덱스 단위로 덮어쓴다" 는 Spring
 * relaxed binding 동작에 의존하는데, 이건 렌더나 매니페스트 lint 로는 증명되지 않는다 —
 * 실제로 바인딩된 값을 보아야 한다. 여기서 틀리면 클러스터에서 서비스가 엉뚱한
 * kid 를 신뢰하거나 부팅에 실패하는데, 실제 배포 뒤에야 발견하게 된다.
 *
 * <p><b>확인된 동작</b>: 리스트는 인덱스 단위로 병합되지 않고 <b>가장 높은 우선순위 소스가 통째로
 * 대체</b>한다. 따라서 env(ConfigMap)가 하나라도 항목을 주면 베이크된 YAML 목록은 전부 무시되고,
 * ConfigMap 이 곧 <b>신뢰하는 kid 집합의 단일 출처</b>가 된다 — 키 회전 중 old kid 제거가 실제로
 * 폐기로 이어지는 근거다. (구현 전에는 인덱스 병합이라 구 키가 살아남는다고 가정했으나, 이 테스트가
 * 반증했다. 병합 동작으로 회귀하면 마지막 케이스가 먼저 깨진다.)
 */
class InternalTokenPropertiesBindingTest {

    /** 이미지에 베이크된 application.yml 상당 — 항상 dev 키 1개. */
    private static Map<String, Object> bakedYaml() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app.internal-token.mode", "SIGNED_ONLY");
        m.put("app.internal-token.public-keys[0].kid", "peekcart-gateway-dev-2026");
        m.put("app.internal-token.public-keys[0].location", "classpath:keys/dev-gateway-internal-public.pem");
        return m;
    }

    /** k8s ConfigMap(internal-token-binding) 이 컨테이너에 넣는 env 상당. */
    private static Binder binderWith(Map<String, Object> env) {
        MutablePropertySources sources = new MutablePropertySources();
        // 실제 컨테이너와 같은 우선순위: env 가 application.yml 보다 앞선다.
        sources.addFirst(new SystemEnvironmentPropertySource("systemEnvironment", env));
        sources.addLast(new MapPropertySource("application.yml", bakedYaml()));
        return new Binder(org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources));
    }

    private static InternalTokenProperties bind(Map<String, Object> env) {
        return binderWith(env)
                .bind("app.internal-token", InternalTokenProperties.class)
                .orElseThrow(() -> new AssertionError("app.internal-token 바인딩 실패"));
    }

    @Test
    @DisplayName("운영 kid ConfigMap 이 베이크된 dev 키를 대체한다 (항목 1개 유지)")
    void configMapEnvReplacesBakedKey() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("APP_INTERNALTOKEN_MODE", "SIGNED_ONLY");
        env.put("APP_INTERNALTOKEN_PUBLICKEYS_0_KID", "peekcart-gateway-prod-2026");
        env.put("APP_INTERNALTOKEN_PUBLICKEYS_0_LOCATION",
                "file:/etc/peekcart/internal-token-keys/peekcart-gateway-prod-2026.pem");

        InternalTokenProperties props = bind(env);

        assertThat(props.mode()).isEqualTo(InternalTokenProperties.Mode.SIGNED_ONLY);
        assertThat(props.publicKeys()).hasSize(1);
        assertThat(props.publicKeys().get(0).kid()).isEqualTo("peekcart-gateway-prod-2026");
        // dev 키가 살아남으면 폐기된 키를 계속 신뢰하게 된다 — 회전의 ⑤ 단계가 무의미해진다.
        assertThat(props.publicKeys())
                .noneSatisfy(e -> assertThat(e.kid()).isEqualTo("peekcart-gateway-dev-2026"));
    }

    @Test
    @DisplayName("회전 overlap: 인덱스 1 을 추가하면 두 kid 를 동시에 수용한다")
    void rotationOverlapAddsSecondKey() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("APP_INTERNALTOKEN_PUBLICKEYS_0_KID", "peekcart-gateway-prod-2026");
        env.put("APP_INTERNALTOKEN_PUBLICKEYS_0_LOCATION",
                "file:/etc/peekcart/internal-token-keys/peekcart-gateway-prod-2026.pem");
        env.put("APP_INTERNALTOKEN_PUBLICKEYS_1_KID", "peekcart-gateway-prod-2027");
        env.put("APP_INTERNALTOKEN_PUBLICKEYS_1_LOCATION",
                "file:/etc/peekcart/internal-token-keys/peekcart-gateway-prod-2027.pem");

        InternalTokenProperties props = bind(env);

        // 공개키 overlap 구간: 공개키가 먼저 둘 다 배포돼야 gateway 가 새 kid 로 서명해도 안전하다.
        assertThat(props.publicKeys()).extracting(InternalTokenProperties.PublicKeyEntry::kid)
                .containsExactly("peekcart-gateway-prod-2026", "peekcart-gateway-prod-2027");
    }

    @Test
    @DisplayName("DUAL_ACCEPT 는 env 로만 켜진다 — 이미지 기본값은 SIGNED_ONLY 다")
    void modeIsOverriddenByEnvOnly() {
        // dual-accept 전환과 SIGNED_ONLY 복귀는 이 한 줄(env)로 제어한다 —
        // 이미지 교체 없이 되돌릴 수 있다는 rollback 비용 주장의 근거다.
        assertThat(bind(Map.of()).mode()).isEqualTo(InternalTokenProperties.Mode.SIGNED_ONLY);
        assertThat(bind(Map.of("APP_INTERNALTOKEN_MODE", "DUAL_ACCEPT")).mode())
                .isEqualTo(InternalTokenProperties.Mode.DUAL_ACCEPT);
    }

    @Test
    @DisplayName("리스트는 소스 단위로 통째 교체된다 — 베이크가 2개여도 env 1개면 결과는 1개")
    void higherPrecedenceSourceReplacesWholeList() {
        // 설계 검증 지점: 만약 인덱스 단위 병합이었다면, 베이크에 남은 구 kid 가 키 회전 중 old kid 제거
        // 이후에도 계속 신뢰돼 폐기가 성립하지 않는다. 실제로는 env 가 리스트를 통째로 대체하므로
        // ConfigMap 이 곧 신뢰 집합의 단일 출처다. 이 동작이 바뀌면 old kid 제거 후에도 구 키를 신뢰하게 된다.
        Map<String, Object> bakedTwo = new LinkedHashMap<>(bakedYaml());
        bakedTwo.put("app.internal-token.public-keys[1].kid", "stale-kid");
        bakedTwo.put("app.internal-token.public-keys[1].location", "classpath:keys/dev-gateway-internal-public.pem");

        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                "APP_INTERNALTOKEN_PUBLICKEYS_0_KID", "prod-kid",
                "APP_INTERNALTOKEN_PUBLICKEYS_0_LOCATION", "file:/etc/peekcart/internal-token-keys/prod.pem")));
        sources.addLast(new MapPropertySource("application.yml", bakedTwo));

        InternalTokenProperties props = new Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources))
                .bind("app.internal-token", InternalTokenProperties.class)
                .orElseThrow(() -> new AssertionError("app.internal-token 바인딩 실패"));

        assertThat(props.publicKeys()).extracting(InternalTokenProperties.PublicKeyEntry::kid)
                .containsExactly("prod-kid");
    }
}
