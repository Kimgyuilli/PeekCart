package com.peekcart.internaltoken;

/**
 * Gateway 서명 내부 토큰의 <b>이름 계약</b> (ADR-0017 D1/D3).
 *
 * <p>Gateway 가 사용자 토큰을 검증한 뒤, 자기 개인키로 서명한 짧은 수명 JWT 를 {@link #HEADER} 로
 * 주입한다. 리소스 서비스는 Gateway 공개키로 서명·{@code iss}·{@code kid}·{@code exp} 를 검증한 뒤
 * claims 에서 신원을 추출한다. 평문 {@code X-User-*} 신뢰는 폐기된다.
 *
 * <p><b>설정 불가(의도)</b>: 여기 값들은 프로퍼티로 노출하지 않는다. issuer 이름이 환경마다 달라지면
 * "이 토큰을 누가 발행했는가" 라는 신뢰 앵커가 환경 설정으로 우회 가능해지고, claim 이름이 달라지면
 * 발행/검증이 조용히 어긋난다. 발행측(gateway)과 검증측(common-auth)이 서로를 의존할 수 없으므로
 * 이 모듈이 <b>단일 출처</b>다.
 */
public final class InternalTokenContract {

    /** Gateway 가 주입하고 리소스 서비스가 소비하는 내부 토큰 헤더. */
    public static final String HEADER = "X-Internal-Auth";

    /** 내부 토큰의 유일한 발행자. 검증측은 이 값으로 iss 를 핀한다(사용자 access token 오용 차단). */
    public static final String ISSUER = "peekcart-gateway";

    /** 서명 알고리즘. 검증측은 이 값만 허용한다(alg 혼동/HS fallback 차단). */
    public static final String ALGORITHM = "RS256";

    /** 사용자 역할 claim ("USER"/"ADMIN" — {@code ROLE_} 접두사 없음). */
    public static final String CLAIM_ROLE = "role";

    /** refresh family 식별자 claim. */
    public static final String CLAIM_FAMILY_ID = "fid";

    private InternalTokenContract() {
    }
}
