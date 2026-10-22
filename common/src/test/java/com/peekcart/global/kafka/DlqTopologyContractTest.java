package com.peekcart.global.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLQ 소유권 계약.
 *
 * <p>여기서 증명하는 것은 <b>매핑의 무모순</b>이다 — "4서비스를 동시에 띄워 1곳만 적재함" 은
 * cross-service 검증이다. 본 테스트 × 서비스별 실제 Kafka 왕복 테스트가
 * 그 전역 불변식의 근거를 나눠 갖는다.
 */
@DisplayName("DlqTopology — 소유권 계약")
class DlqTopologyContractTest {

    @ParameterizedTest
    @EnumSource(PeekcartService.class)
    @DisplayName("소비 소유권과 quarantine 소유권은 교집합이 없다 — 한 레코드를 두 listener 가 적재하면 안 된다")
    void consumptionAndQuarantineAreDisjoint(PeekcartService service) {
        // 교집합을 직접 구한다 — doesNotContainAnyElementsOf 는 빈 기대집합에서 예외를 던지고,
        // notification 의 quarantine 은 정상적으로 비어 있다.
        Set<String> intersection = new HashSet<>(DlqTopology.consumptionTopics(service));
        intersection.retainAll(DlqTopology.quarantineTopics(service));

        assertThat(intersection).isEmpty();
    }

    @Test
    @DisplayName("(dlqTopic, group) 조합을 두 서비스가 동시에 소유하지 않는다")
    void consumptionOwnershipIsUnique() {
        List<DlqSubscription> all = new ArrayList<>();
        for (PeekcartService service : PeekcartService.values()) {
            all.addAll(DlqTopology.consumptionSubscriptions(service));
        }

        assertThat(all).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("한 DLQ 토픽의 quarantine 소유자는 정확히 1곳이다")
    void quarantineOwnershipIsUnique() {
        List<String> all = new ArrayList<>();
        for (PeekcartService service : PeekcartService.values()) {
            all.addAll(DlqTopology.quarantineTopics(service));
        }

        assertThat(all).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("소비 구독 총 27개 — 운영 코드의 @KafkaListener 수와 같다")
    void consumptionSubscriptionCountMatchesListeners() {
        int total = 0;
        for (PeekcartService service : PeekcartService.values()) {
            total += DlqTopology.consumptionSubscriptions(service).size();
        }

        assertThat(total).isEqualTo(27);
        assertThat(DlqTopology.consumptionSubscriptions(PeekcartService.ORDER)).hasSize(6);
        assertThat(DlqTopology.consumptionSubscriptions(PeekcartService.PRODUCT)).hasSize(8);
        assertThat(DlqTopology.consumptionSubscriptions(PeekcartService.PAYMENT)).hasSize(8);
        assertThat(DlqTopology.consumptionSubscriptions(PeekcartService.NOTIFICATION)).hasSize(5);
    }

    @Test
    @DisplayName("consumer group 은 소유 서비스의 접두사로 시작한다 — 남의 group 을 소유로 적으면 검출된다")
    void consumerGroupsCarryOwnerPrefix() {
        for (PeekcartService service : PeekcartService.values()) {
            for (DlqSubscription subscription : DlqTopology.consumptionSubscriptions(service)) {
                assertThat(subscription.consumerGroup())
                        .as("%s 의 구독 %s", service, subscription)
                        .startsWith(service.prefix() + "-svc-");
            }
        }
    }

    @Test
    @DisplayName("모든 구독 토픽은 .dlq 로 끝난다")
    void allTopicsAreDlqTopics() {
        Set<String> all = new HashSet<>();
        for (PeekcartService service : PeekcartService.values()) {
            all.addAll(DlqTopology.consumptionTopics(service));
            all.addAll(DlqTopology.quarantineTopics(service));
        }

        assertThat(all).allSatisfy(topic -> assertThat(topic).endsWith(DlqTopology.DLQ_SUFFIX));
    }

    @Test
    @DisplayName("quarantine 대상은 자기가 발행하는 토픽뿐 — notification 은 발행 토픽이 0개다")
    void notificationHasNoQuarantineTopics() {
        assertThat(DlqTopology.quarantineTopics(PeekcartService.NOTIFICATION)).isEmpty();
    }

    @Test
    @DisplayName("ADR-0018 신설 토픽 3종의 .dlq 가 소유 매핑에 있다")
    void adr0018TopicsAreCovered() {
        Set<String> paymentConsumption = DlqTopology.consumptionTopics(PeekcartService.PAYMENT);
        assertThat(paymentConsumption).contains(
                "stock.compensation.requested.dlq",
                "order.compensation.requested.dlq");

        assertThat(DlqTopology.quarantineTopics(PeekcartService.PAYMENT)).contains("payment.refunded.dlq");
        assertThat(DlqTopology.consumptionTopics(PeekcartService.ORDER)).contains("payment.refunded.dlq");
        assertThat(DlqTopology.consumptionTopics(PeekcartService.PRODUCT)).contains("payment.refunded.dlq");
        assertThat(DlqTopology.consumptionTopics(PeekcartService.NOTIFICATION)).contains("payment.refunded.dlq");
    }

    @Test
    @DisplayName("ownsConsumption — group 이 sentinel 이면 소비 경로가 아니다")
    void unknownGroupIsNotConsumptionOwned() {
        assertThat(DlqTopology.ownsConsumption(PeekcartService.ORDER,
                "payment.completed.dlq", "order-svc-payment-completed-group")).isTrue();

        assertThat(DlqTopology.ownsConsumption(PeekcartService.ORDER,
                "payment.completed.dlq", DlqOrigin.UNKNOWN_CONSUMER_GROUP)).isFalse();

        assertThat(DlqTopology.ownsConsumption(PeekcartService.ORDER,
                "payment.completed.dlq", "product-svc-payment-completed-group")).isFalse();
    }

    @Test
    @DisplayName("ownsQuarantine — group 이 판독됐으면 quarantine 대상이 아니다")
    void resolvedGroupIsNotQuarantineOwned() {
        assertThat(DlqTopology.ownsQuarantine(PeekcartService.PAYMENT,
                "payment.completed.dlq", DlqOrigin.UNKNOWN_CONSUMER_GROUP)).isTrue();

        assertThat(DlqTopology.ownsQuarantine(PeekcartService.PAYMENT,
                "payment.completed.dlq", "order-svc-payment-completed-group")).isFalse();

        // 발행자가 아닌 서비스는 quarantine 소유자가 아니다
        assertThat(DlqTopology.ownsQuarantine(PeekcartService.ORDER,
                "payment.completed.dlq", DlqOrigin.UNKNOWN_CONSUMER_GROUP)).isFalse();
    }
}
