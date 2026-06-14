package com.peekcart.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * 통합 테스트 공통 베이스 클래스.
 *
 * <p>컨테이너 선언은 각 자식 클래스에서 per-class 수명으로 유지한다.
 * 이 클래스는 DB/캐시 cleanup 유틸리티와 규약만 제공한다.</p>
 *
 * <h3>사용 규약</h3>
 * <ul>
 *   <li>데이터에 의존하는 통합 테스트는 {@code @BeforeEach}에서 {@link #cleanDatabase()}를 호출한다.</li>
 *   <li>cleanup이 불필요한 테스트(ShedLock 레코드 검증, 메트릭 노출 검증 등)는 호출하지 않는다.</li>
 *   <li>캐시 동작을 검증하는 테스트는 {@code @BeforeEach}에서 {@link #cleanCaches(CacheManager)}를 호출한다.</li>
 * </ul>
 */
public abstract class AbstractIntegrationTest {

    @Autowired
    protected EntityManagerFactory emf;

    /**
     * FK 의존 역순으로 전체 비즈니스 테이블 DELETE.
     * shedlock 테이블은 제외 (Flyway 관리, 비즈니스 데이터 아님).
     *
     * <p>데이터에 의존하는 통합 테스트는 반드시 {@code @BeforeEach}에서 이 메서드를 호출해야 한다.
     * cleanup이 불필요한 테스트(ShedLock 레코드 검증, 메트릭 노출 검증 등)는 호출하지 않는다.</p>
     */
    protected void cleanDatabase() {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            em.createNativeQuery("DELETE FROM outbox_events").executeUpdate();
            em.createNativeQuery("DELETE FROM processed_events").executeUpdate();
            em.createNativeQuery("DELETE FROM notifications").executeUpdate();
            em.createNativeQuery("DELETE FROM webhook_logs").executeUpdate();
            em.createNativeQuery("DELETE FROM payments").executeUpdate();
            em.createNativeQuery("DELETE FROM order_items").executeUpdate();
            em.createNativeQuery("DELETE FROM orders").executeUpdate();
            em.createNativeQuery("DELETE FROM cart_items").executeUpdate();
            em.createNativeQuery("DELETE FROM carts").executeUpdate();
            em.createNativeQuery("DELETE FROM inventories").executeUpdate();
            em.createNativeQuery("DELETE FROM products").executeUpdate();
            em.createNativeQuery("DELETE FROM categories").executeUpdate();
            em.createNativeQuery("DELETE FROM refresh_tokens").executeUpdate();
            em.createNativeQuery("DELETE FROM addresses").executeUpdate();
            em.createNativeQuery("DELETE FROM users").executeUpdate();
            em.getTransaction().commit();
        } catch (Exception e) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            throw e;
        } finally {
            em.close();
        }
    }

    /**
     * Spring CacheManager 가 관리하는 캐시 엔트리를 모두 비움.
     * Redis keyspace 전체(Redisson 락 키 등)를 정리하지는 않는다.
     * 캐시 동작을 검증하는 테스트에서 {@code @BeforeEach}에 호출.
     */
    protected void cleanCaches(CacheManager cacheManager) {
        cacheManager.getCacheNames().forEach(name -> {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
    }
}
