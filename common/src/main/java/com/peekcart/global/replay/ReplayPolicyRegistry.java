package com.peekcart.global.replay;

import com.peekcart.global.kafka.DlqTopology;
import com.peekcart.global.kafka.PeekcartService;
import com.peekcart.global.kafka.TopicGroup;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * replay 정책 레지스트리 — <b>default-deny</b> (ADR-0020 §D5-2 축 5 · 구현 ④-c-2b-4a P19).
 *
 * <h2>왜 토픽이 아니라 {@code (소유 서비스, 토픽)} 인가 (계획 리뷰 2R #3)</h2>
 * {@code stock.reservation.result} 는 <b>order 와 payment 두 group 이 소비</b>하고 각자 다른 로컬 상태를
 * 만진다. DB-per-service 라 payment 의 원장 replay 는 Order 상태를 조회할 수 없다 — 토픽 단일 키로 두면
 * payment replay 를 통째로 거부하거나 <b>원격 DB 결합</b>을 들여야 한다.
 *
 * <h2>완전성의 정본은 {@link DlqTopology} 다 (계획 리뷰 3R #5)</h2>
 * 엔트리는 토픽 10종이 아니라 <b>소비쌍 21개</b>다. 토픽만 채우면 공유 토픽의 소유자별 누락이 검사되지
 * 않는다. {@code ReplayPolicyRegistryTest} 가 topology 의 모든 소비쌍에 정책이 <b>정확히 하나</b> 있고
 * <b>여분 정책이 없음</b>을 대조한다 — 한쪽만 보면 오래된 엔트리가 남아도 통과한다.
 *
 * <h2>eventType 축은 이 코드베이스에서 토픽과 같은 값이다</h2>
 * ADR §D5-2 축 5 는 "토픽/eventType 별 정책" 을 요구하지만, 발행 측이 {@code KafkaEventEnvelope.eventType}
 * 에 <b>토픽 이름을 그대로</b> 싣는다({@code saveOutboxEvent(eventType=토픽)} → {@code buildDomainRecord}
 * 가 {@code event_type} 을 토픽으로 쓴다). 따라서 별도 축으로 분기할 값이 없다 — 대신
 * {@link #eventTypeMatchesTopic} 로 <b>봉투의 eventType 이 목적지 토픽과 일치하는지</b>를 강제한다.
 * 불일치는 원장 행과 원본 레코드가 어긋났다는 신호이므로 거부 사유가 된다.
 */
public final class ReplayPolicyRegistry {

    /** 정책표 버전. 판정을 감사 기록에 남길 때 함께 적는다 — 표가 바뀌면 과거 판정의 근거도 바뀐다. */
    public static final String VERSION = "v1";

    private static final Map<Key, ReplayPolicy> POLICIES = new LinkedHashMap<>();

    /** {@code (소유 서비스, 업무 토픽)}. */
    private record Key(PeekcartService owner, String topic) {
    }

    private static void put(PeekcartService owner, String topic, ReplayPolicy policy) {
        POLICIES.put(new Key(owner, topic), policy);
    }

    private static ReplayPolicy deny(String id) {
        return new ReplayPolicy(id, ReplayPolicy.Decision.DENY, false);
    }

    private static ReplayPolicy allow(String id) {
        return new ReplayPolicy(id, ReplayPolicy.Decision.ALLOW, false);
    }

    private static ReplayPolicy allowWithPrecondition(String id) {
        return new ReplayPolicy(id, ReplayPolicy.Decision.ALLOW, true);
    }

    static {
        // --- order 원장 (6쌍) ---
        // 결제 요청은 외부 PG 승인을 유발할 수 있다 — 늦은 재적용이 이중 과금 경로다.
        put(PeekcartService.ORDER, "payment.requested", deny("order/payment.requested"));
        put(PeekcartService.ORDER, "payment.completed", allow("order/payment.completed"));
        put(PeekcartService.ORDER, "payment.failed", allow("order/payment.failed"));
        put(PeekcartService.ORDER, "payment.refunded", allow("order/payment.refunded"));
        put(PeekcartService.ORDER, "stock.reservation.result", allowWithPrecondition("order/stock.reservation.result"));
        put(PeekcartService.ORDER, "product.updated", allow("order/product.updated"));

        // --- product 원장 (5쌍) ---
        // 주문 생성은 하류 예약·결제를 연쇄 개시한다 — 늦은 재적용이 이미 취소된 주문의 재고를 다시 잡는다.
        put(PeekcartService.PRODUCT, "order.created", deny("product/order.created"));
        put(PeekcartService.PRODUCT, "order.cancelled", allow("product/order.cancelled"));
        put(PeekcartService.PRODUCT, "payment.completed", allow("product/payment.completed"));
        put(PeekcartService.PRODUCT, "payment.failed", allow("product/payment.failed"));
        put(PeekcartService.PRODUCT, "payment.refunded", allow("product/payment.refunded"));

        // --- payment 원장 (5쌍) ---
        put(PeekcartService.PAYMENT, "order.created", deny("payment/order.created"));
        put(PeekcartService.PAYMENT, "order.cancelled", allow("payment/order.cancelled"));
        put(PeekcartService.PAYMENT, "stock.reservation.result",
                allowWithPrecondition("payment/stock.reservation.result"));
        put(PeekcartService.PAYMENT, "stock.compensation.requested", allow("payment/stock.compensation.requested"));
        put(PeekcartService.PAYMENT, "order.compensation.requested", allow("payment/order.compensation.requested"));

        // --- notification 원장 (5쌍) ---
        // 알림은 전부 통지 소비다. 늦은 재전달은 중복 알림이지 상태 변경이 아니다.
        put(PeekcartService.NOTIFICATION, "order.created", allow("notification/order.created"));
        put(PeekcartService.NOTIFICATION, "order.cancelled", allow("notification/order.cancelled"));
        put(PeekcartService.NOTIFICATION, "payment.completed", allow("notification/payment.completed"));
        put(PeekcartService.NOTIFICATION, "payment.failed", allow("notification/payment.failed"));
        put(PeekcartService.NOTIFICATION, "payment.refunded", allow("notification/payment.refunded"));
    }

    private ReplayPolicyRegistry() {
    }

    /**
     * 정책을 찾는다. <b>미등록 쌍은 {@code null} 이며 호출자는 거부해야 한다</b>(default-deny).
     *
     * <p>여기서 {@code deny} 정책 객체를 대신 돌려주지 않는 이유: "명시적으로 금지한 것" 과
     * "선언을 빠뜨린 것" 은 감사 기록에서 구분돼야 한다.
     */
    public static ReplayPolicy find(PeekcartService owner, String topic) {
        return POLICIES.get(new Key(owner, topic));
    }

    /** 봉투의 {@code eventType} 이 목적지 토픽과 같은가. 이 코드베이스에서 둘은 같은 값이다. */
    public static boolean eventTypeMatchesTopic(String eventType, String topic) {
        return eventType != null && eventType.equals(topic);
    }

    /** 완전성 대조용 — 선언된 모든 {@code (소유자, 토픽)} 쌍. */
    public static Set<String> declaredPairs() {
        return POLICIES.keySet().stream()
                .map(k -> k.owner().prefix() + "/" + k.topic())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** {@link DlqTopology} 가 정의한 실제 소비쌍 — 레지스트리가 덮어야 할 집합의 정본. */
    public static Set<String> topologyPairs() {
        Set<String> pairs = new java.util.LinkedHashSet<>();
        for (PeekcartService service : PeekcartService.values()) {
            for (TopicGroup subscription : DlqTopology.businessSubscriptions(service)) {
                pairs.add(service.prefix() + "/" + subscription.topic());
            }
        }
        return pairs;
    }
}
