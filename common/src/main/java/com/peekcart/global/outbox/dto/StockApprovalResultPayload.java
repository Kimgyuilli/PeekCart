package com.peekcart.global.outbox.dto;

/** Product의 영속 예약 보호 결과. key=orderId, orderId당 승인 시도 하나 (ADR-0034). */
public record StockApprovalResultPayload(Long orderId, boolean granted) {}
