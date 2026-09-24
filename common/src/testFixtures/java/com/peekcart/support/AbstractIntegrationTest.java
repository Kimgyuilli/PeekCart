package com.peekcart.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 통합 테스트 공통 베이스 클래스.
 *
 * <p><b>컨테이너는 모듈 싱글톤이다</b>({@link SharedContainers}, ADR-0028 · D-032).
 * 자식 클래스가 {@code @Container} 를 선언하지 않는다 — 그 어노테이션의 수명이 곧 per-class 라,
 * 클래스마다 컨테이너·Flyway·Spring context 를 다시 만들었다(실측 클래스당 약 31초).
 * 이 클래스는 공유 자원의 cleanup 유틸리티와 규약을 제공한다.</p>
 *
 * <h3>사용 규약</h3>
 * <ul>
 *   <li>{@code @SpringBootTest} 에 {@code @Import(SharedContainers.class)} 를 붙인다.
 *       직접 선언은 {@code scripts/integration-test-container-lint.sh} 가 막는다.</li>
 *   <li>데이터에 의존하는 통합 테스트는 {@code @BeforeEach}에서 {@link #cleanDatabase()}를 호출한다.</li>
 *   <li>애플리케이션 토픽({@code order.created} 등 <b>고정 이름</b>)을 읽는 테스트는
 *       {@link #cleanKafkaTopics(String, String...)} 도 호출한다. 브로커를 공유하므로 앞 클래스가
 *       발행한 레코드가 {@code seekToBeginning} 에 그대로 읽힌다.</li>
 *   <li>cleanup이 불필요한 테스트(빈 배선 검증, 메트릭 노출 검증 등)는 호출하지 않는다.</li>
 *   <li>캐시 동작을 검증하는 테스트는 {@code @BeforeEach}에서 {@link #cleanCaches(CacheManager)}를 호출한다.</li>
 *   <li><b>배경 스케줄링은 기본 off</b> 다({@code app.scheduling.enabled}). 타이머 발화 자체를
 *       검증하는 테스트만 켜고, 그 경우 {@code @DirtiesContext(AFTER_CLASS)} 로 수명을 자기 클래스로
 *       가둔다 — context 캐시 때문에 타이머가 계속 돌면 공유 DB 를 고친다.</li>
 * </ul>
 */
public abstract class AbstractIntegrationTest {

    @Autowired
    protected EntityManagerFactory emf;

    /**
     * 현재 스키마의 전체 비즈니스 테이블 DELETE (DB-per-service: 자기 스키마 테이블만 존재).
     * 테이블 목록을 information_schema 에서 동적으로 조회하므로, 각 서비스 Testcontainer 는
     * 자기 모듈 마이그레이션으로 생성한 테이블만 비운다(공유 스키마 가정 제거 — 구현 ② PR2/B10).
     * flyway_schema_history(Flyway 관리)·shedlock(락 레코드, 비즈니스 데이터 아님)은 제외.
     * FK 의존 순서를 신경 쓰지 않도록 cleanup 동안 FOREIGN_KEY_CHECKS 를 끈다.
     *
     * <p>데이터에 의존하는 통합 테스트는 반드시 {@code @BeforeEach}에서 이 메서드를 호출해야 한다.
     * cleanup이 불필요한 테스트(ShedLock 레코드 검증, 메트릭 노출 검증 등)는 호출하지 않는다.</p>
     */
    protected void cleanDatabase() {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            @SuppressWarnings("unchecked")
            List<String> tables = em.createNativeQuery(
                    "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() "
                            + "AND table_name NOT IN ('flyway_schema_history', 'shedlock')")
                    .getResultList();
            em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
            for (String table : tables) {
                em.createNativeQuery("DELETE FROM `" + table + "`").executeUpdate();
            }
            em.getTransaction().commit();
        } catch (Exception e) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            throw e;
        } finally {
            // FOREIGN_KEY_CHECKS 는 세션 변수라 rollback 으로 복구되지 않는다 — FK 체크가 꺼진 커넥션이
            // 풀에 반환돼 후속 테스트를 오염시키지 않도록 close 전에 best-effort 로 복구한다.
            try {
                if (!em.getTransaction().isActive()) em.getTransaction().begin();
                em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
                em.getTransaction().commit();
            } catch (Exception ignored) {
                if (em.getTransaction().isActive()) em.getTransaction().rollback();
            } finally {
                em.close();
            }
        }
    }

    /**
     * 지정 토픽의 기존 레코드를 잘라낸다 (D-032 · 공유 브로커 규약).
     *
     * <p><b>왜 필요한가</b>: 컨테이너 싱글톤(ADR-0028) 이후 Kafka 브로커를 클래스 간에 공유한다.
     * 애플리케이션 토픽은 {@code order.created} 처럼 <b>이름이 고정</b>이라, 앞 클래스가 발행한
     * 레코드가 뒤 클래스의 {@code seekToBeginning} 에 그대로 읽힌다. {@link #cleanDatabase()} 가
     * MySQL 을 비우는 것과 같은 자리이고, 같은 이유로 필요하다.
     *
     * <p><b>왜 토픽을 지우지 않는가</b>: {@code deleteTopics} 는 비동기라 메타데이터 전파가 끝나기
     * 전에 다음 send 가 나가면 타임아웃한다 — D-021·D-028 이 실제로 겪은 실패다. 대신
     * {@code deleteRecords} 로 로그를 end offset 까지 잘라 low watermark 를 올린다. 토픽과 파티션
     * 메타데이터는 그대로 남으므로 전파 지연이 생기지 않고, {@code seekToBeginning} 은 잘린
     * 지점부터 읽어 다시 올바르게 동작한다.
     *
     * <p>토픽이 아직 없으면 조용히 건너뛴다. 최초 실행에서는 비울 것이 없는 것이 정상이다.
     *
     * @param bootstrapServers {@code SharedContainers.KAFKA.getBootstrapServers()}
     * @param topics           비울 토픽 이름
     */
    protected void cleanKafkaTopics(String bootstrapServers, String... topics) {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrapServers))) {
            Map<TopicPartition, RecordsToDelete> toDelete = new HashMap<>();
            for (String topic : topics) {
                var description = admin.describeTopics(List.of(topic)).topicNameValues().get(topic);
                try {
                    description.get().partitions().forEach(p ->
                            toDelete.put(new TopicPartition(topic, p.partition()), null));
                } catch (Exception missing) {
                    continue;   // 아직 생성 전인 토픽 — 비울 것이 없다
                }
            }
            if (toDelete.isEmpty()) {
                return;
            }
            Map<TopicPartition, OffsetSpec> query = new HashMap<>();
            toDelete.keySet().forEach(tp -> query.put(tp, OffsetSpec.latest()));

            Map<TopicPartition, RecordsToDelete> cut = new HashMap<>();
            admin.listOffsets(query).all().get()
                    .forEach((tp, info) -> cut.put(tp, RecordsToDelete.beforeOffset(info.offset())));
            admin.deleteRecords(cut).all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka 토픽 cleanup 실패", e);
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
