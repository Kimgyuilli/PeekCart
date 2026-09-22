package com.peekcart.order.infrastructure;

import com.peekcart.order.application.OrderQueryService;
import com.peekcart.order.application.dto.CursorSlice;
import com.peekcart.order.application.dto.OrderSummaryDto;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderCursor;
import com.peekcart.order.domain.model.OrderItemData;
import com.peekcart.order.domain.repository.OrderRepository;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 커서 페이지네이션의 정확성·격리를 실제 MySQL 로 검증한다 (계획 T1·T4·T11·T12).
 *
 * <p>단위 테스트로는 닫히지 않는 것들이다 — 동률 {@code ordered_at} 의 페이지 경계는 DB 정렬이
 * 결정하고, {@code DATETIME(6)} 의 마이크로초·표현 범위는 JDBC 바인딩 경계에서만 드러난다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("주문 커서 페이지네이션 통합 테스트")
class OrderCursorPaginationIntegrationTest extends AbstractIntegrationTest {

    private static final Long USER_A = 100L;
    private static final Long USER_B = 200L;

    @Autowired OrderQueryService orderQueryService;
    @Autowired OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    /**
     * orderedAt 은 Order.create 가 현재 시각으로 채우므로, 시나리오가 요구하는 값으로 덮어쓴다.
     * 기존 통합 테스트와 같이 독립 트랜잭션으로 커밋한다 — 조회 경로가 자기 트랜잭션에서 보게 하려면
     * 시드가 먼저 커밋돼 있어야 한다.
     */
    private Long seed(Long userId, LocalDateTime orderedAt) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Order order = Order.create(userId, "ORD-" + UUID.randomUUID(),
                "받는이", "01000000000", "12345", "주소",
                List.of(new OrderItemData(100L, 1, 1_000L)));
        em.persist(order);
        em.flush();
        Long id = order.getId();
        em.createNativeQuery("UPDATE orders SET ordered_at = ?1 WHERE id = ?2")
                .setParameter(1, orderedAt)
                .setParameter(2, id)
                .executeUpdate();
        em.getTransaction().commit();
        em.close();
        return id;
    }

    /** 커서를 따라 끝까지 순회하며 만난 id 를 순서대로 모은다. */
    private List<Long> drain(Long userId, int size) {
        List<Long> ids = new ArrayList<>();
        String cursor = null;
        // 무한 루프 방어 — 커서가 전진하지 않으면 여기서 멈추고 아래 단언이 실패한다.
        for (int guard = 0; guard < 100; guard++) {
            CursorSlice<OrderSummaryDto> slice = orderQueryService.getOrders(
                    userId, cursor == null ? null : OrderCursor.decode(cursor), size);
            slice.content().forEach(dto -> ids.add(dto.id()));
            if (!slice.hasNext()) {
                return ids;
            }
            cursor = slice.nextCursor();
        }
        throw new IllegalStateException("커서가 종료되지 않았다");
    }

    @Test
    @DisplayName("T1: ordered_at 이 전부 동일해도 페이지 경계에서 누락·중복이 없다")
    void tiedOrderedAt_noGapNoDuplicate() {
        LocalDateTime tied = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_000);
        List<Long> seeded = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            seeded.add(seed(USER_A, tied));
        }

        List<Long> drained = drain(USER_A, 2);

        assertThat(drained).doesNotHaveDuplicates();
        assertThat(drained).containsExactlyInAnyOrderElementsOf(seeded);
        // tie-break 가 id DESC 이므로 순서까지 결정적이다.
        assertThat(drained).isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }

    @Test
    @DisplayName("T1: ordered_at 이 섞여 있어도 (ordered_at, id) 내림차순이 유지된다")
    void mixedOrderedAt_stableDescending() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 0);
        Long older = seed(USER_A, base.minusMinutes(1));
        Long tiedLow = seed(USER_A, base);
        Long tiedHigh = seed(USER_A, base);
        Long newest = seed(USER_A, base.plusMinutes(1));

        List<Long> drained = drain(USER_A, 1);

        assertThat(drained).containsExactly(newest, Math.max(tiedHigh, tiedLow), Math.min(tiedHigh, tiedLow), older);
    }

    @Test
    @DisplayName("T4: 타 사용자 커서는 위치일 뿐 권한이 아니다 — 인증 주체의 주문만 나온다")
    void cursorGrantsNoAccess() {
        // B_new > A > B_old 로 교차 배치하고 size 를 A 건수보다 크게 잡는다.
        // 이 배치라야 userId 조건이 빠졌을 때 B_old 가 결과에 실제로 섞인다.
        LocalDateTime base = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 0);
        Long bNew = seed(USER_B, base.plusMinutes(10));
        Long a = seed(USER_A, base);
        Long bOld = seed(USER_B, base.minusMinutes(10));

        Order bNewOrder = orderRepository.findById(bNew).orElseThrow();
        OrderCursor foreignCursor = new OrderCursor(bNewOrder.getOrderedAt(), bNewOrder.getId());

        CursorSlice<OrderSummaryDto> slice = orderQueryService.getOrders(USER_A, foreignCursor, 2);

        assertThat(slice.content()).extracting(OrderSummaryDto::id).containsExactly(a);
        assertThat(slice.content()).extracting(OrderSummaryDto::id).doesNotContain(bNew, bOld);
    }

    @Test
    @DisplayName("T11: 마이크로초 경계(0 / 1 / 999999)가 커서 왕복에서 보존된다")
    void microsecondBoundaries_roundTrip() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 0);
        Long zero = seed(USER_A, base.withNano(0));
        Long one = seed(USER_A, base.withNano(1_000));
        Long max = seed(USER_A, base.withNano(999_999_000));

        List<Long> drained = drain(USER_A, 1);

        assertThat(drained).containsExactly(max, one, zero);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "9999-12-31T23:59:59.499999",
            "9999-12-31T23:59:59.500000",
            "9999-12-31T23:59:59.999999",
            "0001-01-01T00:00:00",
    })
    @DisplayName("T12: DATETIME(6) 저장 경계값이 저장·재조회·커서 재요청까지 통과한다")
    void storageBoundaries_survive(String raw) {
        LocalDateTime boundary = LocalDateTime.parse(raw);
        Long anchorId = seed(USER_A, boundary);
        // 경계 행보다 확실히 오래된/새로운 짝. 하한(0001-01-01)에서는 경계 행이 가장 오래된 행이
        // 되므로 "경계 행이 첫 페이지"를 전제할 수 없다 — 그래서 아래는 순서에 의존하지 않는다.
        Long companion = seed(USER_A, LocalDateTime.of(2000, 1, 1, 0, 0, 0, 0));

        // 1. JDBC 왕복에서 값이 그대로인가 — 상한을 잘못 좁히면 애초에 저장이 안 된다.
        Order stored = orderRepository.findById(anchorId).orElseThrow();
        assertThat(stored.getOrderedAt()).isEqualTo(boundary);

        // 2. 경계 행에서 만든 커서가 decode 와 바인딩을 통과하는가.
        //    상한을 .499999 로 잘못 잡으면 여기서 ORD-010 이 난다.
        OrderCursor atBoundary = new OrderCursor(stored.getOrderedAt(), stored.getId());
        CursorSlice<OrderSummaryDto> after =
                orderQueryService.getOrders(USER_A, OrderCursor.decode(atBoundary.encode()), 10);

        // 3. 커서 뒤에는 경계 행보다 엄격히 오래된 것만 온다.
        boolean companionIsOlder = LocalDateTime.of(2000, 1, 1, 0, 0, 0, 0).isBefore(boundary);
        assertThat(after.content()).extracting(OrderSummaryDto::id)
                .containsExactlyElementsOf(companionIsOlder ? List.of(companion) : List.of());

        // 4. 전체 순회에는 두 행이 모두, ordered_at 내림차순으로 들어간다.
        assertThat(drain(USER_A, 1)).containsExactly(
                companionIsOlder ? anchorId : companion,
                companionIsOlder ? companion : anchorId);
    }
}
