package com.peekcart.global.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.util.List;

/**
 * RS256 비대칭키 설정 계약 (ADR-0013 D1/D2).
 * <p>User(발급) 서버만 {@code privateKeyLocation}(개인키)을 로드하고, 모든 서비스/Gateway 는
 * {@code publicKeys}(kid→PEM 공개키)로 검증한다. 키 자료는 파일/클래스패스 마운트로 주입하며
 * 환경변수 직접 주입은 금지한다(ADR-0013 D2). 프로파일별 위치 오버라이드는 ADR-0007 을 따른다.
 * <p><b>PR4</b>: 전환기 HS256 fallback 스위치를 제거했다 — 그 스위치를 읽던 servlet 측 verifier 는
 * PR3d-a 에서 삭제됐고(ADR-0014 D2-c exit) 발급도 RS256 단독이라, 남겨두면 설정 표면에만 존재하는
 * 죽은 스위치가 된다.
 *
 * @param activeKid           발급 시 사용할 현재 개인키의 kid (JWT 헤더 kid 로 기록)
 * @param privateKeyLocation  개인키(PKCS#8 PEM) 위치 — User 서명 전속, 그 외 서비스는 null
 * @param publicKeys          검증/JWKS 용 공개키 목록 (kid → SPKI PEM)
 */
@ConfigurationProperties(prefix = "app.jwt.rs256")
public record JwtKeyProperties(
        String activeKid,
        Resource privateKeyLocation,
        List<PublicKeyEntry> publicKeys
) {

    public JwtKeyProperties {
        publicKeys = publicKeys == null ? List.of() : List.copyOf(publicKeys);
    }

    /**
     * 공개키 1개 항목.
     *
     * @param kid      키 식별자 (토큰 헤더 kid 와 매칭)
     * @param location 공개키(SPKI PEM) 위치
     */
    public record PublicKeyEntry(String kid, Resource location) {
    }
}
