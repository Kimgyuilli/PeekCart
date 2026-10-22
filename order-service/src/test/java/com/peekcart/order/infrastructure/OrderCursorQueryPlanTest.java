package com.peekcart.order.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderItemData;
import com.peekcart.order.domain.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 커서 질의가 인덱스 범위 스캔으로 실행되는지 실행계획으로 검증한다.
 *
 * <p>"인덱스를 만들었다"는 검증이 아니다 — 옵티마이저가 그것을 <b>선택했는지</b>를 본다.
 * 그래서 EXPLAIN 대상은 손으로 쓴 동등 SQL 이 아니라 Hibernate 가 실제로 발행한 SQL 이고,
 * 파서가 항상 통과하지 않는다는 것은 IGNORE INDEX 양성 대조군으로 확인한다.
 *
 * <p>실행계획은 옵티마이저 버전에 종속이므로 이미지를 패치 버전까지 핀 고정한다.
 * 슬라이스가 {@code @DataJpaTest} 인 이유는 이 검증에 Kafka/Redis 가 필요 없기 때문이다.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OrderRepositoryImpl.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.peekcart.order.infrastructure.OrderCursorQueryPlanTest$CapturingInspector"
})
@DisplayName("커서 질의 실행계획 검증")
class OrderCursorQueryPlanTest {

    private static final String INDEX = "idx_orders_user_id_ordered_at";
    private static final Long USER_ID = 777L;
    private static final int ROWS = 5_000;
    /** USER_ID 가 전체의 1/5 만 갖도록 분산한다 — 단일 사용자면 user_id 가 비선택적이라
     *  옵티마이저가 ref 대신 full index scan 을 고른다(실측). 운영 분포에도 이쪽이 가깝다. */
    private static final int USERS = 5;

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.46").withDatabaseName("peekcart_test");

    @Autowired OrderRepository orderRepository;
    @PersistenceContext EntityManager em;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Hibernate 는 SQL 문자열만 준다(String inspect(String)) — 바인드 값은 테스트가 직접 넣는다. */
    public static class CapturingInspector implements StatementInspector {
        static final AtomicReference<String> LAST = new AtomicReference<>();

        @Override
        public String inspect(String sql) {
            LAST.set(sql);
            return sql;
        }
    }

    @BeforeEach
    void seed() {
        em.createNativeQuery("DELETE FROM order_items").executeUpdate();
        em.createNativeQuery("DELETE FROM orders").executeUpdate();
        // AUTO_INCREMENT 를 되돌린다 — 아래에서 id 로 ordered_at 을 만들므로,
        // 값이 테스트 간 누적되면 시각 범위가 매번 달라진다.
        em.createNativeQuery("ALTER TABLE orders AUTO_INCREMENT = 1").executeUpdate();

        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 0);
        for (int i = 0; i < ROWS; i++) {
            Order order = Order.create(i % USERS == 0 ? USER_ID : USER_ID + 1 + (i % USERS),
                    "ORD-" + UUID.randomUUID(),
                    "받는이", "01000000000", "12345", "주소",
                    List.of(new OrderItemData(100L, 1, 1_000L)));
            em.persist(order);
            if (i % 200 == 0) {
                em.flush();
                em.clear();
            }
        }
        em.flush();
        // ordered_at 을 분산시켜 범위 스캔이 의미를 갖게 한다.
        em.createNativeQuery("UPDATE orders SET ordered_at = DATE_ADD(?1, INTERVAL id SECOND)")
                .setParameter(1, base)
                .executeUpdate();
        em.flush();
        // 옵티마이저가 최신 통계로 판단하게 한다 — 통계가 비면 인덱스를 안 고를 수 있다.
        em.createNativeQuery("ANALYZE TABLE orders").getResultList();
    }

    @Test
    @DisplayName("T9: V7 인덱스가 적용됐고 기존 인덱스도 남아있다")
    void migrationAppliedIndexes() {
        List<String> names = indexNames();

        assertThat(names).contains(INDEX);
        // 기존 인덱스가 유지됨을 함께 고정한다 — 커서 전환이 상태 필터 조회를 대체하지 않는다.
        // 보존 계약만 본다 — 총목록을 고정하면 이후 정당한 인덱스 추가가 이 테스트를 깨뜨린다.
        assertThat(names).contains(
                "PRIMARY", "uk_orders_order_number",
                "idx_orders_user_id_status", "idx_orders_status_ordered_at",
                "idx_orders_reservation_expiry");
    }

    @Test
    @DisplayName("T2/T3: 첫 페이지 질의가 filesort 없이 인덱스를 탄다")
    void firstPageUsesIndex() {
        assertThat(indexNames()).contains(INDEX);

        Captured c = capture(() -> orderRepository.findFirstPage(USER_ID, PageRequest.of(0, 21)));
        List<Object> binds = List.of(USER_ID, 21);

        // 첫 페이지는 등가 조건뿐이라 user_id 하나만 쓴다.
        assertPlanUsesIndex(c, binds, List.of("user_id"), "ref");
        assertControlGroupSorts(c.sql(), binds);
    }

    @Test
    @DisplayName("T2/T3: 커서 질의가 인덱스에 명시하지 않은 id 까지 key part 로 쓴다")
    void cursorPageUsesIndex() {
        assertThat(indexNames()).contains(INDEX);

        // 앵커를 하드코딩하지 않는다 — 실제 데이터에서 뽑아야 EXPLAIN 이 빈 결과를 보지 않는다.
        LocalDateTime at = anchorAtDepth(100);
        Captured c = capture(() ->
                orderRepository.findPageAfterCursor(USER_ID, at, Long.MAX_VALUE, PageRequest.of(0, 21)));
        List<Object> binds = List.of(USER_ID, Timestamp.valueOf(at), Timestamp.valueOf(at), Long.MAX_VALUE, 21);

        // 인덱스는 (user_id, ordered_at) 인데 key part 가 3개다 — InnoDB 가 PK 를 암묵 부착한다는
        // D3 전제의 직접 증거이자, 커서 predicate 가 회귀로 빠지면 user_id 하나로 줄어드는 게이트다.
        assertPlanUsesIndex(c, binds, List.of("user_id", "ordered_at", "id"), "range");
        assertControlGroupSorts(c.sql(), binds);
    }

    @Test
    @DisplayName("T8: 커서는 페이지 깊이와 무관하게 검사 행 수가 일정하다 (offset 은 증가한다)")
    void examinedRowsStayFlatForCursor() {
        // 커서 쪽은 리포지터리가 실제 발행한 SQL 을 쓴다 — 수기 SQL 이면 운영 질의가 회귀해도
        // 이 게이트가 계속 통과한다. offset 쪽은 대응 리포지터리 메서드가 없으므로(전환으로 삭제됨)
        // 비교 기준선만 수기로 만든다.
        LocalDateTime probe = anchorAtDepth(20);
        String cursorSql = captureSql(() ->
                orderRepository.findPageAfterCursor(USER_ID, probe, Long.MAX_VALUE, PageRequest.of(0, 20)));
        String offsetSql = "SELECT o1_0.id FROM orders o1_0 WHERE o1_0.user_id = ? "
                + "ORDER BY o1_0.ordered_at DESC, o1_0.id DESC LIMIT 20 OFFSET ?";

        List<Long> cursorRows = new ArrayList<>();
        List<Long> offsetRows = new ArrayList<>();
        // 깊이는 USER_ID 가 실제로 가진 행 수(ROWS/USERS) 안에 있어야 한다.
        for (int depth : new int[]{20, 400, 900}) {
            LocalDateTime at = anchorAtDepth(depth);
            cursorRows.add(examinedRows(cursorSql,
                    List.of(USER_ID, Timestamp.valueOf(at), Timestamp.valueOf(at), Long.MAX_VALUE, 20)));
            offsetRows.add(examinedRows(offsetSql, List.of(USER_ID, depth)));
        }

        long cursorMax = cursorRows.stream().mapToLong(Long::longValue).max().orElseThrow();
        long cursorMin = cursorRows.stream().mapToLong(Long::longValue).min().orElseThrow();
        assertThat(cursorMax)
                .as("cursor examined rows %s 는 깊이와 무관하게 평탄해야 한다", cursorRows)
                .isLessThan(cursorMin * 2);
        assertThat(offsetRows.get(2))
                .as("offset examined rows %s 는 깊이에 비례해 증가해야 한다", offsetRows)
                .isGreaterThan(offsetRows.get(0));
        assertThat(offsetRows.get(2))
                .as("깊은 offset %s 은 같은 깊이의 cursor %s 보다 많은 행을 읽는다", offsetRows, cursorRows)
                .isGreaterThan(cursorRows.get(2));

        System.out.printf("[evidence] depth=20/400/900 cursor=%s offset=%s%n", cursorRows, offsetRows);
    }

    // ── EXPLAIN 파싱 ──────────────────────────────────────────────────────

    private void assertPlanUsesIndex(Captured captured, List<Object> binds,
                                     List<String> expectedKeyParts, String expectedAccessType) {
        JsonNode explain = explainJson(captured.sql(), binds);
        JsonNode table = tableNode(explain);

        assertThat(table.path("key").asText()).isEqualTo(INDEX);
        assertThat(table.path("access_type").asText()).isEqualTo(expectedAccessType);
        assertThat(keyParts(table))
                .as("used_key_parts 가 줄면 predicate 가 인덱스 밖으로 빠진 것이다")
                .isEqualTo(expectedKeyParts);
        assertThat(usesFilesort(explain))
                .as("정상 경로에서 filesort 가 발생하면 인덱스 정렬을 못 쓰고 있다")
                .isFalse();

        // EXPLAIN 대상이 리포지터리가 실제로 실행한 그 질의인지 — 바인드 순서가 틀리거나
        // StatementInspector 가 인접 SELECT 를 잡았으면 결과가 어긋난다.
        assertThat(runForIds(captured.sql(), binds))
                .as("EXPLAIN 대상 SQL 의 직접 실행 결과가 리포지터리 결과와 달라졌다")
                .isNotEmpty()
                .isEqualTo(captured.ids());
    }

    private List<String> keyParts(JsonNode table) {
        List<String> parts = new ArrayList<>();
        table.path("used_key_parts").forEach(n -> parts.add(n.asText()));
        return parts;
    }

    /**
     * 양성 대조군. 파서가 무엇이든 통과시키는 것이 아님을 보인다 —
     * 같은 파서가 IGNORE INDEX 질의에서는 filesort 를 읽어내야 한다.
     */
    private void assertControlGroupSorts(String sql, List<Object> binds) {
        String ignored = sql.replaceFirst("(?i)(from\\s+orders\\s+\\S+)", "$1 IGNORE INDEX (" + INDEX + ")");
        if (ignored.equals(sql)) {
            fail("IGNORE INDEX 힌트를 주입하지 못했다 — 대조군이 성립하지 않는다: " + sql);
        }
        assertThat(usesFilesort(explainJson(ignored, binds)))
                .as("대조군에서 filesort 를 못 읽으면 파서가 고장난 것이다")
                .isTrue();
    }

    /**
     * filesort 가 생기면 table 노드가 ordering_operation 아래로 내려간다.
     * 두 경로 중 정확히 하나만 있어야 한다 — 없으면 파싱 실패이지 성공이 아니다.
     */
    private JsonNode tableNode(JsonNode explain) {
        JsonNode block = explain.path("query_block");
        JsonNode direct = block.path("table");
        JsonNode nested = block.path("ordering_operation").path("table");
        if (direct.isObject() == nested.isObject()) {
            fail("table 노드를 정규화할 수 없다 (둘 다 있거나 둘 다 없음): " + explain);
        }
        return direct.isObject() ? direct : nested;
    }

    private boolean usesFilesort(JsonNode explain) {
        return explain.path("query_block").path("ordering_operation").path("using_filesort").asBoolean(false);
    }

    private JsonNode explainJson(String sql, List<Object> binds) {
        String raw = single("EXPLAIN FORMAT=JSON " + sql, binds);
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("EXPLAIN JSON 파싱 실패: " + raw, e);
        }
    }

    /**
     * EXPLAIN ANALYZE(TREE) 에서 <b>orders 인덱스 스캔 iterator 정확히 하나</b>를 지목해
     * {@code actual rows × loops} 를 읽는다.
     *
     * <p>트리 전체를 합산하면 안 된다 — orders 스캔 노드가 아예 없어도 다른 iterator 하나만 있으면
     * 통과하고, 값도 examined rows 가 아니다.
     */
    private long examinedRows(String sql, List<Object> binds) {
        String tree = single("EXPLAIN ANALYZE " + sql, binds);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("using " + INDEX + "[^\\n]*?actual time=[^)]*?rows=([0-9.e+]+) loops=([0-9.e+]+)")
                .matcher(tree);

        List<Long> matches = new ArrayList<>();
        while (m.find()) {
            matches.add(Math.round(Double.parseDouble(m.group(1)) * Double.parseDouble(m.group(2))));
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "'" + INDEX + "' 스캔 iterator 를 정확히 1개 지목하지 못했다 (" + matches.size() + "개): " + tree);
        }
        return matches.get(0);
    }

    private LocalDateTime anchorAtDepth(int offset) {
        String sql = "SELECT o.ordered_at FROM orders o WHERE o.user_id = ? "
                + "ORDER BY o.ordered_at DESC, o.id DESC LIMIT 1 OFFSET ?";
        AtomicReference<LocalDateTime> found = new AtomicReference<>();
        em.unwrap(Session.class).doWork(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, USER_ID);
                ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        found.set(rs.getTimestamp(1).toLocalDateTime());
                    }
                }
            }
        });
        return Optional.ofNullable(found.get())
                .orElseThrow(() -> new IllegalStateException("깊이 " + offset + " 의 기준 행이 없다"));
    }

    // ── JDBC 실행 (fixture 와 같은 물리 연결) ────────────────────────────────

    private String single(String sql, List<Object> binds) {
        AtomicReference<String> out = new AtomicReference<>();
        em.unwrap(Session.class).doWork(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, binds);
                try (ResultSet rs = ps.executeQuery()) {
                    StringBuilder sb = new StringBuilder();
                    while (rs.next()) {
                        sb.append(rs.getString(1));
                    }
                    out.set(sb.toString());
                }
            }
        });
        return out.get();
    }

    /**
     * 캡처한 엔티티 SQL 을 그대로 실행하되, id 는 컬럼 <b>순서</b>가 아니라 메타데이터로 찾는다.
     * JPQL 은 {@code SELECT o} 라 SELECT 목록의 순서는 리포지터리 계약이 아니다 —
     * Hibernate 가 순서를 바꿔도 정상 구현이 실패하면 안 된다.
     */
    private List<Long> runForIds(String sql, List<Object> binds) {
        List<Long> ids = new ArrayList<>();
        em.unwrap(Session.class).doWork(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, binds);
                try (ResultSet rs = ps.executeQuery()) {
                    int idColumn = idColumnIndex(rs.getMetaData());
                    while (rs.next()) {
                        ids.add(rs.getLong(idColumn));
                    }
                }
            }
        });
        return ids;
    }

    private int idColumnIndex(java.sql.ResultSetMetaData meta) throws java.sql.SQLException {
        int found = -1;
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if ("id".equalsIgnoreCase(meta.getColumnLabel(i))) {
                if (found != -1) {
                    fail("결과셋에 id 컬럼이 여러 개다 — 어느 것을 읽을지 정할 수 없다");
                }
                found = i;
            }
        }
        if (found == -1) {
            fail("결과셋에 id 컬럼이 없다: 캡처한 SQL 이 기대와 다르다");
        }
        return found;
    }

    private void bind(PreparedStatement ps, List<Object> binds) throws java.sql.SQLException {
        for (int i = 0; i < binds.size(); i++) {
            ps.setObject(i + 1, binds.get(i));
        }
    }

    /** 캡처한 SQL 과 그 호출이 실제로 돌려준 id — 둘을 함께 들고 있어야 동등성을 비교할 수 있다. */
    private record Captured(String sql, List<Long> ids) {
    }

    private Captured capture(java.util.function.Supplier<List<Order>> call) {
        CapturingInspector.LAST.set(null);
        List<Order> result = call.get();
        String sql = CapturingInspector.LAST.get();
        if (sql == null) {
            fail("Hibernate SQL 을 캡처하지 못했다 — StatementInspector 가 배선되지 않았다");
        }
        return new Captured(sql, result.stream().map(Order::getId).toList());
    }

    private String captureSql(Runnable call) {
        CapturingInspector.LAST.set(null);
        call.run();
        String sql = CapturingInspector.LAST.get();
        if (sql == null) {
            fail("Hibernate SQL 을 캡처하지 못했다 — StatementInspector 가 배선되지 않았다");
        }
        return sql;
    }

    private List<String> indexNames() {
        @SuppressWarnings("unchecked")
        List<String> names = em.createNativeQuery(
                        "SELECT DISTINCT index_name FROM information_schema.statistics "
                                + "WHERE table_schema = DATABASE() AND table_name = 'orders'")
                .getResultList();
        return names;
    }
}
