package com.peekcart.global.kafka;

/**
 * DLQ 원장 행이 어떤 좌표를 물리 식별자로 쓰는지 구분한다.
 *
 * <p>DLQ 레코드는 {@code kafka_dlt-original-*} 헤더로 원본 좌표를 싣지만,
 * 그 헤더 자체가 없거나 깨진 레코드가 존재할 수 있다. 그때 좌표 컬럼을 NULL 로 두면
 * <b>MySQL UNIQUE 가 중복을 막지 못해</b> 같은 poison record 가 여러 행이 된다
 * (nullable 컬럼이 포함된 UNIQUE 는 NULL 끼리 충돌하지 않는다).
 *
 * <p>그래서 좌표 6컬럼은 항상 NOT NULL 이고, 무엇을 담았는지를 본 enum 이 밝힌다.
 */
public enum DlqOriginKind {

    /** {@code DLT_ORIGINAL_TOPIC}/{@code PARTITION}/{@code OFFSET} 를 전부 판독했다. 좌표 = 원본 토픽의 좌표. */
    RESOLVED_ORIGIN,

    /**
     * origin 헤더가 없거나 판독 불가라 원본 좌표를 알 수 없다.
     * 좌표 = <b>DLQ 레코드 자신의</b> 좌표. 원본으로의 replay 는 불가하며 운영 판단 대상이다.
     */
    DLQ_ORIGIN
}
