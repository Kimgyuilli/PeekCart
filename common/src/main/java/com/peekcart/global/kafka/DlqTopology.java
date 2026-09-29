package com.peekcart.global.kafka;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DLQ 소유권의 <b>단일 출처</b>.
 *
 * <p>DLQ 토픽은 <b>공유</b>다 — 한 원본 토픽을 여러 서비스가 각자의 group 으로 소비하고,
 * 실패는 전부 같은 {@code <topic>.dlq} 로 간다. 예를 들어 {@code payment.completed} 는
 * order·product·notification 셋이 소비한다. 따라서 원장이 "누가 미결인지" 에 답하려면
 * <b>실패한 consumer group 으로 소유권을 갈라야</b> 한다.
 *
 * <p>소유권은 두 종류다:
 * <ul>
 *   <li><b>소비 소유권</b>({@link #consumptionSubscriptions}) — 자기 group 의 실패분</li>
 *   <li><b>quarantine 소유권</b>({@link #quarantineTopics}) — group 헤더를 판독하지 못한 레코드.
 *       원본 토픽을 <b>발행</b>하는 서비스가 단일 소유자다(ADR-0011 producer-owns-topic).</li>
 * </ul>
 *
 * <p><b>두 집합은 교집합이 없다.</b> 서비스는 자기가 발행한 토픽을 소비하지 않기 때문이다.
 * 이 불변식은 계약 테스트로 강제한다 — 깨지면 한 레코드를 두 listener 가 적재하려 든다.
 *
 * <p>여기 적힌 group 문자열은 각 서비스 consumer 의 {@code groupId} 와 <b>정확히 일치해야</b> 하며,
 * 계약 테스트가 서비스별 실제 값과 대조한다.
 */
public final class DlqTopology {

    /** {@code DeadLetterPublishingRecoverer} 의 목적지 규칙 — {@code record.topic() + ".dlq"}. */
    public static final String DLQ_SUFFIX = ".dlq";

    // ------------------------------------------------------------------
    // DLQ listener 자신의 group.
    //
    // 위 CONSUMPTION 의 group 은 **실패한 업무 consumer** 의 group 이지 DLQ listener 의 group 이
    // 아니다. 둘을 섞으면 readiness 가 order-svc-dlq-group 계열을 통째로 놓친다.
    // annotation 이 literal 을 쓰면 여기와 갈라져도 아무 것도 실패하지 않으므로 상수로 참조시킨다.
    // (annotation 속성은 컴파일 상수여야 해서 메서드가 아니라 상수로 둔다.)
    // ------------------------------------------------------------------
    public static final String ORDER_DLQ_GROUP = "order-svc-dlq-group";
    public static final String PRODUCT_DLQ_GROUP = "product-svc-dlq-group";
    public static final String PAYMENT_DLQ_GROUP = "payment-svc-dlq-group";
    public static final String NOTIFICATION_DLQ_GROUP = "notification-svc-dlq-group";

    public static final String ORDER_DLQ_QUARANTINE_GROUP = "order-svc-dlq-quarantine-group";
    public static final String PRODUCT_DLQ_QUARANTINE_GROUP = "product-svc-dlq-quarantine-group";
    public static final String PAYMENT_DLQ_QUARANTINE_GROUP = "payment-svc-dlq-quarantine-group";

    private static final Map<PeekcartService, String> INTAKE_GROUP = new EnumMap<>(PeekcartService.class);
    private static final Map<PeekcartService, String> QUARANTINE_GROUP = new EnumMap<>(PeekcartService.class);

    static {
        INTAKE_GROUP.put(PeekcartService.ORDER, ORDER_DLQ_GROUP);
        INTAKE_GROUP.put(PeekcartService.PRODUCT, PRODUCT_DLQ_GROUP);
        INTAKE_GROUP.put(PeekcartService.PAYMENT, PAYMENT_DLQ_GROUP);
        INTAKE_GROUP.put(PeekcartService.NOTIFICATION, NOTIFICATION_DLQ_GROUP);

        QUARANTINE_GROUP.put(PeekcartService.ORDER, ORDER_DLQ_QUARANTINE_GROUP);
        QUARANTINE_GROUP.put(PeekcartService.PRODUCT, PRODUCT_DLQ_QUARANTINE_GROUP);
        QUARANTINE_GROUP.put(PeekcartService.PAYMENT, PAYMENT_DLQ_QUARANTINE_GROUP);
        // notification 은 발행 토픽이 0개라 quarantine 대상이 없다(QUARANTINE 과 동일한 이유).
    }

    private static final Map<PeekcartService, Set<DlqSubscription>> CONSUMPTION =
            new EnumMap<>(PeekcartService.class);
    private static final Map<PeekcartService, Set<String>> QUARANTINE =
            new EnumMap<>(PeekcartService.class);

    static {
        CONSUMPTION.put(PeekcartService.ORDER, Set.copyOf(List.of(
                sub("payment.requested", "order-svc-payment-requested-group"),
                sub("payment.completed", "order-svc-payment-completed-group"),
                sub("payment.failed", "order-svc-payment-failed-group"),
                sub("payment.refunded", "order-svc-payment-refunded-group"),
                sub("stock.reservation.result", "order-svc-stock-result-group"),
                sub("product.updated", "order-svc-product-updated-group")
        )));
        CONSUMPTION.put(PeekcartService.PRODUCT, Set.copyOf(List.of(
                sub("order.created", "product-svc-order-created-group"),
                sub("order.cancelled", "product-svc-order-cancelled-group"),
                sub("payment.completed", "product-svc-payment-completed-group"),
                sub("payment.failed", "product-svc-payment-failed-group"),
                sub("payment.refunded", "product-svc-payment-refunded-group")
        )));
        CONSUMPTION.put(PeekcartService.PAYMENT, Set.copyOf(List.of(
                sub("order.created", "payment-svc-order-created-group"),
                sub("order.cancelled", "payment-svc-order-cancelled-group"),
                sub("stock.reservation.result", "payment-svc-stock-result-group"),
                sub("stock.compensation.requested", "payment-svc-stock-compensation-requested-group"),
                sub("order.compensation.requested", "payment-svc-order-compensation-requested-group")
        )));
        CONSUMPTION.put(PeekcartService.NOTIFICATION, Set.copyOf(List.of(
                sub("order.created", "notification-svc-order-created-group"),
                sub("order.cancelled", "notification-svc-order-cancelled-group"),
                sub("payment.completed", "notification-svc-payment-completed-group"),
                sub("payment.failed", "notification-svc-payment-failed-group"),
                sub("payment.refunded", "notification-svc-payment-refunded-group")
        )));

        // 자기가 발행하는 토픽의 .dlq — NewTopic 선언 소유와 동일하다.
        QUARANTINE.put(PeekcartService.ORDER, Set.copyOf(List.of(
                dlq("order.created"), dlq("order.cancelled"), dlq("order.compensation.requested")
        )));
        QUARANTINE.put(PeekcartService.PRODUCT, Set.copyOf(List.of(
                dlq("product.updated"), dlq("stock.reservation.result"), dlq("stock.compensation.requested")
        )));
        QUARANTINE.put(PeekcartService.PAYMENT, Set.copyOf(List.of(
                dlq("payment.completed"), dlq("payment.failed"), dlq("payment.requested"), dlq("payment.refunded")
        )));
        // notification 은 발행 토픽이 0개라 quarantine 대상이 없다.
        QUARANTINE.put(PeekcartService.NOTIFICATION, Set.of());
    }

    private DlqTopology() {
    }

    private static DlqSubscription sub(String originTopic, String consumerGroup) {
        return new DlqSubscription(dlq(originTopic), consumerGroup);
    }

    /** 원본 토픽 이름에 {@code .dlq} 를 붙인다. */
    public static String dlq(String originTopic) {
        return originTopic + DLQ_SUFFIX;
    }

    /**
     * 이 서비스가 <b>업무 토픽</b>을 소비하는 (originTopic, group) 집합 (readiness 판정용).
     *
     * <p>{@link #consumptionSubscriptions} 에서 <b>유도</b>한다 — 같은 21쌍을 별도 상수로 복제하면
     * 정본이 둘이 되고, 양쪽을 함께 잘못 고치면 각자의 자기대조가 모두 통과한다.
     */
    public static Set<TopicGroup> businessSubscriptions(PeekcartService service) {
        return Set.copyOf(CONSUMPTION.get(service).stream()
                .map(s -> new TopicGroup(s.originTopic(), s.consumerGroup()))
                .toList());
    }

    /** 이 서비스의 DLQ 소비 listener 가 쓰는 group. */
    public static String dlqIntakeGroup(PeekcartService service) {
        return INTAKE_GROUP.get(service);
    }

    /** 이 서비스의 quarantine listener 가 쓰는 group. quarantine 대상이 없으면 {@code null}. */
    public static String quarantineGroup(PeekcartService service) {
        return QUARANTINE_GROUP.get(service);
    }

    /** 이 서비스가 소비 실패분을 소유하는 (dlqTopic, group) 집합. */
    public static Set<DlqSubscription> consumptionSubscriptions(PeekcartService service) {
        return CONSUMPTION.get(service);
    }

    /** 이 서비스가 구독해야 할 DLQ 토픽 집합 (소비 경로). */
    public static Set<String> consumptionTopics(PeekcartService service) {
        return Set.copyOf(CONSUMPTION.get(service).stream().map(DlqSubscription::dlqTopic).toList());
    }

    /** 이 서비스가 group 부재분을 소유하는 DLQ 토픽 집합 (quarantine 경로). */
    public static Set<String> quarantineTopics(PeekcartService service) {
        return QUARANTINE.get(service);
    }

    /**
     * 이 레코드를 <b>소비 경로</b>로 적재할 소유자인가.
     * group 이 판독되지 않은 레코드는 소비 경로가 아니라 quarantine 경로 소관이다.
     */
    public static boolean ownsConsumption(PeekcartService service, String dlqTopic, String consumerGroup) {
        if (dlqTopic == null || consumerGroup == null || DlqOrigin.UNKNOWN_CONSUMER_GROUP.equals(consumerGroup)) {
            return false;
        }
        return CONSUMPTION.get(service).contains(new DlqSubscription(dlqTopic, consumerGroup));
    }

    /** 이 레코드를 <b>quarantine 경로</b>로 적재할 소유자인가. group 이 판독됐다면 대상이 아니다. */
    public static boolean ownsQuarantine(PeekcartService service, String dlqTopic, String consumerGroup) {
        if (dlqTopic == null || !DlqOrigin.UNKNOWN_CONSUMER_GROUP.equals(consumerGroup)) {
            return false;
        }
        return QUARANTINE.get(service).contains(dlqTopic);
    }
}
