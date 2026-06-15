package com.peekcart.global.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 토큰 블랙리스트 신키 식별자 해시 (ADR-0014 D1-c · PR2b/U5).
 * <p>블랙리스트 write owner(User)와 read(common-auth)가 <b>동일 해시</b>로 키를 생성/조회하도록
 * 단일 소유 유틸로 둔다(키스킴 드리프트 차단). 신키 = {@code auth:blacklist:<sha256hex(token)>}.
 * <p>ADR-0014 "토큰 원문 금지" 는 신키에 원문 대신 단방향 해시만 저장하여 충족한다.
 */
public final class TokenHasher {

    private TokenHasher() {
    }

    /** access token 원문의 SHA-256 hex 다이제스트. */
    public static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 표준 — 실제로 발생하지 않음
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }
}
