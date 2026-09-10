package com.peekcart.global.kafka;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * replay 대조 축 9 의 payload digest (ADR-0021 §D2, 계획 ④-c-2b-3b P15-b).
 *
 * <p><b>절단 전 전문</b>의 SHA-256 hex 다. 원장의 {@code payload} 컬럼은 {@code maxLength} 로 잘려
 * 저장되므로 그 값으로 대조하면 <b>상한 밖 변조를 통과</b>시킨다 — "byte-for-byte 동일"(ADR-0020 §D8-3)을
 * 주장할 수 없다. 그래서 digest 를 따로 계산해 앵커로 쓴다.
 *
 * <p><b>인증이 아니라 오상관 방지다</b>(ADR-0021 §D3). 공격자가 앵커를 아는 상황을 막지는 못하고,
 * 같은 좌표·같은 eventId 를 실은 <b>다른 payload</b> 가 남의 사건에 붙는 것을 막는다.
 */
public final class PayloadDigest {

    private PayloadDigest() {
    }

    /**
     * payload 전문의 SHA-256 hex (소문자 64자).
     *
     * <p><b>{@code null} 은 {@code null} 로 돌려준다</b> — tombstone 은 digest 도 없고, ADR-0021 §D2 가
     * "양쪽 null 이면 일치" 로 결정했다. 빈 문자열을 digest 한 값과 구분되어야 하므로 sentinel 을 쓰지 않는다.
     */
    public static String sha256Hex(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JRE 가 제공한다 (JLS 보장). 여기 오면 런타임이 깨진 것이다.
            throw new IllegalStateException("SHA-256 을 사용할 수 없다", e);
        }
    }
}
