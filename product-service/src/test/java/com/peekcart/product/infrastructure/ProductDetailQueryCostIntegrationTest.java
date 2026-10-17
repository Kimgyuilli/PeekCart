package com.peekcart.product.infrastructure;

import com.peekcart.product.application.ProductCommandService;
import com.peekcart.product.application.ProductQueryService;
import com.peekcart.product.application.dto.CreateProductCommand;
import com.peekcart.product.domain.model.Category;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.sql.DriverManager;
import java.sql.Connection;
import org.springframework.aop.framework.Advised;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.peekcart.product.application.ProductCacheService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-026 — 상품 상세/목록의 <b>캐시 적중 시 DB 왕복 수</b>를 고정하는 계약 테스트.
 *
 * <p>Hibernate Statistics는 prepared statement만 세며 세션의 SET/COMMIT 명령은 보지 못한다.
 * D-072는 서버 general_log를 추가해 cache hit의 모든 MySQL 명령이 0인지 확인한다.
 * 기존 GKE 배속 차이의 인과는 이 로컬 계약으로 확정하지 않는다(ADR-0037).
 *
 * <p><b>false-green 차단.</b> 기존 {@code ProductCacheIntegrationTest.getProduct_cacheHit} 은
 * 캐시 엔트리 존재와 {@code id}/{@code name} 일치만 보므로 <b>재고가 어디서 왔는지 묻지 않는다</b> —
 * 재고를 캐시로 옮겨도 그 테스트는 그대로 그린이다(계획서 V11). 그래서 여기서는 검증 도구를
 * 구현과 다른 출처인 <b>Hibernate {@link Statistics}</b> 로 잡았다. 캐시 어노테이션을 지우면
 * 즉시 red 가 된다.
 *
 * <p>ADR-0026.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // Statistics 는 기본 off 다. 이 테스트의 검증 수단이므로 여기서만 켠다.
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Import(SharedContainers.class)
@DisplayName("D-026 상품 조회 DB 왕복 수 계약")
class ProductDetailQueryCostIntegrationTest extends AbstractIntegrationTest {

    private static final PageRequest DEFAULT_PAGE = PageRequest.of(0, 10);

    @Autowired ProductQueryService queryService;
    @Autowired ProductCacheService cacheService;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("productReadBulkheadAdvisor")
    org.springframework.aop.Advisor bulkheadAdvisor;
    @Autowired ProductCommandService commandService;
    @Autowired CacheManager cacheManager;

    private Long productId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        cleanCaches(cacheManager);

        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Category category = Category.create("전자기기", null);
        em.persist(category);
        em.flush();
        Long categoryId = category.getId();
        em.getTransaction().commit();
        em.close();

        productId = commandService.create(
                new CreateProductCommand(categoryId, "스마트폰", "설명", 1_000_000L, null, 100)).id();

        // create 가 목록 캐시를 evict 하므로 측정 전 캐시를 초기화한다.
        cleanCaches(cacheManager);
    }

    @Test
    @DisplayName("상세 조회: 캐시가 전부 적중하면 DB 왕복이 0이다 (재고까지 캐시 뒤에 있다)")
    void getProduct_cacheHit_issuesNoStatement() {
        // 워밍업 — 상품 캐시와 재고 캐시를 모두 채운다.
        queryService.getProduct(productId);

        long issued = countStatements(() -> queryService.getProduct(productId));

        assertThat(issued)
                .as("""
                        상세 조회는 캐시 적중 시 DB 를 건드리지 않아야 한다 (ADR-0026 D2).
                        1 이 나오면 재고를 여전히 매 호출 DB 에서 읽고 있다는 뜻이고,
                        수정 전 기준선은 detail=1 / list=0 이었다.""")
                .isZero();
    }

    @Test
    @DisplayName("목록 조회: 캐시 적중 시 DB 왕복이 0이다 (대조군 — 재고를 담지 않아 원래 0이었다)")
    void getProducts_cacheHit_issuesNoStatement() {
        queryService.getProducts(null, DEFAULT_PAGE);

        long issued = countStatements(() -> queryService.getProducts(null, DEFAULT_PAGE));

        assertThat(issued)
                .as("목록은 재고를 담지 않아(ProductListDto) 적중 시 DB 무접촉이다 — 상세의 비교 기준선")
                .isZero();
    }

    /**
     * {@code action} 이 발행한 JDBC PreparedStatement 수를 센다.
     *
     * <p>{@link Statistics#getPrepareStatementCount()} 는 JVM 전역 누적이라 <b>차분</b>으로 읽는다.
     * {@code clear()} 로 리셋하지 않는 이유는 병렬 실행 시 다른 테스트의 리셋과 섞이면
     * 음수/과소 계수가 나올 수 있어서다 — 차분은 그 경우에도 방향이 무너지지 않는다.
     */
    private long countStatements(Runnable action) {
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        long before = statistics.getPrepareStatementCount();
        action.run();
        return statistics.getPrepareStatementCount() - before;
    }
    @Test
    void cacheAdviceWrapsTransactionAdvice() {
        var advisors = ((Advised) cacheService).getAdvisors();
        int cacheIndex = -1;
        int transactionIndex = -1;
        int bulkheadIndex = -1;
        for (int i = 0; i < advisors.length; i++) {
            if (advisors[i].getAdvice() instanceof CacheInterceptor) cacheIndex = i;
            if (advisors[i] == bulkheadAdvisor) bulkheadIndex = i;
            if (advisors[i].getAdvice() instanceof TransactionInterceptor) transactionIndex = i;
        }
        assertThat(cacheIndex).isNotNegative().isLessThan(bulkheadIndex);
        assertThat(bulkheadIndex).isLessThan(transactionIndex);
    }

    @Test
    void serverCommandsAreZeroForHitsAndPresentForMisses() throws Exception {
        queryService.getProduct(productId);
        queryService.getProducts(null, DEFAULT_PAGE);
        assertThat(countServerCommands(() -> {
            queryService.getProduct(productId);
            queryService.getProducts(null, DEFAULT_PAGE);
        })).as("cache hit must send no SELECT, SET, COMMIT or other server command").isZero();
        cleanCaches(cacheManager);
        assertThat(countServerCommands(() -> queryService.getProduct(productId)))
                .as("positive control: cache miss must execute real DB commands").isPositive();
    }

    private long countServerCommands(Runnable action) throws Exception {
        try (Connection observer = DriverManager.getConnection(SharedContainers.MYSQL.getJdbcUrl(),
                "root", SharedContainers.MYSQL.getPassword());
             var statement = observer.createStatement()) {
            String originalOutput;
            boolean originalEnabled;
            try (var settings = statement.executeQuery("SELECT @@GLOBAL.log_output, @@GLOBAL.general_log")) {
                settings.next();
                originalOutput = settings.getString(1);
                originalEnabled = settings.getBoolean(2);
            }
            try {
                statement.execute("SET GLOBAL general_log = OFF");
                statement.execute("SET GLOBAL log_output = 'TABLE'");
                statement.execute("TRUNCATE TABLE mysql.general_log");
                statement.execute("SET GLOBAL general_log = ON");
                action.run();
                statement.execute("SET GLOBAL general_log = OFF");
                try (var count = statement.executeQuery("SELECT COUNT(*) FROM mysql.general_log "
                        + "WHERE thread_id <> CONNECTION_ID()")) {
                    count.next();
                    return count.getLong(1);
                }
            } finally {
                statement.execute("SET GLOBAL general_log = OFF");
                statement.execute("SET GLOBAL log_output = '" + originalOutput + "'");
                statement.execute("SET GLOBAL general_log = " + (originalEnabled ? "ON" : "OFF"));
            }
        }
    }
}
