package com.peekcart.product.application;

import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sweeper 조회의 <b>실행계획</b> 증적 (부모 계획 `task-impl4-choreography-saga.md` §5 P13).
 *
 * <p>부모 P13 의 성공 기준은 "인덱스 적용 후 sweeper 조회 실행계획" 인데, 기존
 * {@code StockReservationLeaseSweepIntegrationTest} 는 <b>limit 과 경계 조건만</b> 검증하고
 * 인덱스가 실제로 쓰이는지는 보지 않았다. 인덱스는 존재하는데 조회가 풀스캔을 타는 회귀
 * (예: 조건식이 함수로 감싸지거나 컬럼 순서가 바뀌는 경우)를 그 테스트는 잡지 못한다.
 *
 * <p>그래서 여기서는 <b>실제 Flyway 가 적용된 MySQL</b> 에 EXPLAIN 을 던져 인덱스 사용을 확인한다.
 *
 * <p><b>정정 기록</b>: 부모 P13 은 인덱스를 {@code (status, reserved_at)} 으로 적었으나, 만료 판정
 * 기준이 고정 TTL 에서 lease 로 바뀌면서 실제 인덱스는 {@code (status, expires_at)} 이 됐다
 * ({@code V3__stock_reservation_lease.sql} 주석이 이 전환을 이미 기록). 검증 대상은 현행 인덱스다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("sweeper 조회 실행계획 (부모 P13 증적)")
class StockReservationSweeperExplainTest extends AbstractIntegrationTest {

    @Autowired EntityManager entityManager;

    private static final String INDEX = "idx_stock_reservations_lease";

    /**
     * EXPLAIN 은 빈 테이블 전제다. DB 를 공유하므로(ADR-0028) 앞 클래스가 남긴 행이 옵티마이저 판단을
     * 바꿀 수 있다 — 종전 per-class 컨테이너에서 늘 성립하던 전제를 여기서 되살린다.
     */
    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @Test
    @DisplayName("인덱스가 실제로 생성돼 있고 (status, expires_at) 순서다")
    void indexExistsWithExpectedColumns() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT COLUMN_NAME, SEQ_IN_INDEX
                  FROM INFORMATION_SCHEMA.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'stock_reservations'
                   AND INDEX_NAME = :indexName
                 ORDER BY SEQ_IN_INDEX
                """).setParameter("indexName", INDEX).getResultList();

        assertThat(rows).as("%s 인덱스가 없다", INDEX).hasSize(2);
        assertThat(String.valueOf(rows.get(0)[0])).isEqualTo("status");
        assertThat(String.valueOf(rows.get(1)[0])).isEqualTo("expires_at");
    }

    @Test
    @DisplayName("sweeper 조회가 풀스캔이 아니라 idx_stock_reservations_lease 를 탄다")
    void sweeperQueryUsesIndex() {
        // findExpiredReserved 와 같은 조건식 (JPQL 이 생성하는 SQL 과 동치)
        String explain = explain("""
                EXPLAIN FORMAT=JSON
                SELECT * FROM stock_reservations
                 WHERE status = 'RESERVED'
                   AND expires_at IS NOT NULL
                   AND expires_at < NOW(6)
                 ORDER BY expires_at ASC
                 LIMIT 100
                """);

        assertThat(explain)
                .as("실행계획에 인덱스가 없다 — 풀스캔이면 매분 도는 sweeper 가 테이블 전체를 읽는다:%n%s", explain)
                .contains(INDEX);
        assertThat(explain)
                .as("풀스캔(ALL)으로 판정됐다:%n%s", explain)
                .doesNotContain("\"access_type\": \"ALL\"");
    }

    private String explain(String sql) {
        Object result = entityManager.createNativeQuery(sql).getSingleResult();
        return String.valueOf(result);
    }
}
