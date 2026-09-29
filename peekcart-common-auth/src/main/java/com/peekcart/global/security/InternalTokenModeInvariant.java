package com.peekcart.global.security;

import jakarta.servlet.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 부팅 시 인증 필터 구성이 선언된 모드와 일치하는지 강제한다 (ADR-0017).
 *
 * <p>모드는 설정이지만 그 설정이 실제 필터 체인과 어긋나면 조용히 위험해진다 — 예컨대 사용자 토큰
 * 검증 필터가 되살아나 있으면, 서비스가 직접 경로의 {@code Authorization: Bearer} 를 그대로 인증해
 * <b>내부 서명 없이 Gateway 를 우회</b>할 수 있는데도 겉보기엔 signed-only 로 보인다.
 * 그래서 "설정값" 이 아니라 <b>구성된 필터 집합</b>을 직접 확인하고, 위반이면 기동을 거부한다.
 *
 * <p>사용자 토큰 검증 필터는 존재하지 않는다(ADR-0014 D2-c exit). 이 검사는 그 상태를 <b>고정</b>하기 위한
 * 회귀 가드다(클래스명 기반이라 삭제된 클래스를 참조하지 않는다).
 */
@Component
public class InternalTokenModeInvariant implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(InternalTokenModeInvariant.class);

    /** 되살아나면 안 되는 사용자 토큰 검증 필터(ADR-0014 D2-c exit). */
    private static final Set<String> FORBIDDEN_FILTERS = Set.of("JwtFilter");

    private final ObjectProvider<SecurityFilterChain> chains;
    private final InternalTokenProperties properties;

    public InternalTokenModeInvariant(ObjectProvider<SecurityFilterChain> chains,
                                      InternalTokenProperties properties) {
        this.chains = chains;
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<SecurityFilterChain> all = chains.stream().toList();
        List<String> violations = new ArrayList<>();

        // 체인 0개는 "검사할 게 없음" 이 아니라 그 자체로 위반이다 — 보안 설정이 컴포넌트 스캔에서
        // 빠지거나 자동설정이 어긋나면 전 요청이 무인증 통과하는데, 조기 return 하면 그 컨텍스트가
        // 조용히 기동한다(fail-open). 이 빈은 슬라이스 테스트에서는 생성되지 않으므로
        // (@WebMvcTest 는 @Component 를 스캔하지 않는다) 완화할 이유가 없다.
        if (all.isEmpty()) {
            violations.add("SecurityFilterChain 이 0개 — 인증 필터가 배선되지 않아 모든 요청이 무인증 통과한다");
        }
        for (SecurityFilterChain chain : all) {
            List<String> names = chain.getFilters().stream()
                    .map(Filter::getClass)
                    .map(Class::getSimpleName)
                    .toList();

            long internalFilters = names.stream().filter(InternalTokenAuthenticationFilter.class.getSimpleName()::equals).count();
            if (internalFilters != 1) {
                violations.add("내부 토큰 필터가 %d 개(정확히 1개여야 함): %s".formatted(internalFilters, names));
            }
            names.stream().filter(FORBIDDEN_FILTERS::contains).forEach(name ->
                    violations.add("사용자 토큰 검증 필터 %s 가 체인에 살아 있다 — 직접 경로 Bearer 우회 가능".formatted(name)));
        }

        if (properties.mode() == InternalTokenProperties.Mode.SIGNED_ONLY && !properties.familyIdRequired()) {
            violations.add("SIGNED_ONLY 인데 familyIdRequired=false — fid 없는 신원이 통과한다");
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException("내부 토큰 인증 구성 위반:\n  " + String.join("\n  ", violations));
        }
        log.info("내부 토큰 인증 구성 확인: mode={} chains={}", properties.mode(), all.size());
    }
}
