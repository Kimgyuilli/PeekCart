package com.peekcart.global.replay;

import com.peekcart.global.kafka.PeekcartService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V-28f — 정책 레지스트리 completeness (계획 리뷰 3R #5).
 *
 * <p>정본은 {@code DlqTopology} 의 소비쌍이다. 토픽 10종만 세면 <b>공유 토픽의 소유자별 누락</b>이
 * 검사되지 않는다 — {@code stock.reservation.result} 는 order·payment 둘 다 소비한다.
 */
class ReplayPolicyRegistryTest {

    @Test
    @DisplayName("topology 의 모든 소비쌍에 정책이 정확히 하나 있고, 여분 정책이 없다")
    void completeness() {
        Set<String> topology = new TreeSet<>(ReplayPolicyRegistry.topologyPairs());
        Set<String> declared = new TreeSet<>(ReplayPolicyRegistry.declaredPairs());

        // 한쪽만 보면 안 된다 — 누락만 보면 오래된 엔트리가 남아도 통과하고,
        // 여분만 보면 새 구독이 정책 없이 도입돼도 통과한다.
        assertThat(declared)
                .as("정책이 없는 소비쌍 = default-deny 로 조용히 막히는 토픽")
                .containsAll(topology);
        assertThat(topology)
                .as("topology 에 없는 여분 정책 = 구독이 사라졌는데 남은 엔트리")
                .containsAll(declared);
    }

    @Test
    @DisplayName("소비쌍은 21개다 — 토픽 10종이 아니다")
    void pairCountIsNotTopicCount() {
        assertThat(ReplayPolicyRegistry.topologyPairs()).hasSize(21);
        assertThat(ReplayPolicyRegistry.declaredPairs()).hasSize(21);
    }

    @Test
    @DisplayName("공유 토픽은 소유자별로 갈린다 — stock.reservation.result 는 order·payment 둘 다 사전조건")
    void sharedTopicIsKeyedByOwner() {
        ReplayPolicy order = ReplayPolicyRegistry.find(PeekcartService.ORDER, "stock.reservation.result");
        ReplayPolicy payment = ReplayPolicyRegistry.find(PeekcartService.PAYMENT, "stock.reservation.result");

        assertThat(order).isNotNull();
        assertThat(payment).isNotNull();
        assertThat(order.preconditionRequired()).isTrue();
        assertThat(payment.preconditionRequired()).isTrue();
        // 같은 토픽이지만 판정기가 다르다 — id 가 갈려 있어야 감사 기록에서 구분된다.
        assertThat(order.id()).isNotEqualTo(payment.id());
    }

    @Test
    @DisplayName("미등록 쌍은 null 이다 — deny 정책 객체로 대체하지 않는다(선언 누락과 명시적 금지는 다르다)")
    void unregisteredIsNull() {
        assertThat(ReplayPolicyRegistry.find(PeekcartService.NOTIFICATION, "stock.reservation.result")).isNull();
        assertThat(ReplayPolicyRegistry.find(PeekcartService.ORDER, "존재하지-않는-토픽")).isNull();
    }

    @Test
    @DisplayName("연쇄 개시 토픽은 deny 다 — order.created / payment.requested")
    void chainStartersAreDenied() {
        assertThat(ReplayPolicyRegistry.find(PeekcartService.PRODUCT, "order.created").allowed()).isFalse();
        assertThat(ReplayPolicyRegistry.find(PeekcartService.PAYMENT, "order.created").allowed()).isFalse();
        assertThat(ReplayPolicyRegistry.find(PeekcartService.ORDER, "payment.requested").allowed()).isFalse();
        // notification 의 order.created 는 통지 소비라 allow — 같은 토픽이어도 소유자별로 갈린다.
        assertThat(ReplayPolicyRegistry.find(PeekcartService.NOTIFICATION, "order.created").allowed()).isTrue();
    }

    @Test
    @DisplayName("eventType 은 토픽과 같은 값이어야 한다 — 불일치는 원장/원본 어긋남 신호다")
    void eventTypeMustMatchTopic() {
        assertThat(ReplayPolicyRegistry.eventTypeMatchesTopic("order.created", "order.created")).isTrue();
        assertThat(ReplayPolicyRegistry.eventTypeMatchesTopic("order.cancelled", "order.created")).isFalse();
        assertThat(ReplayPolicyRegistry.eventTypeMatchesTopic(null, "order.created")).isFalse();
    }
}
