package com.peekcart.product.infrastructure.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

/**
 * {@link ProductOutboxEventPublisher#publishProductUpdated} 단위 테스트 (strangler-2).
 * ADR-0012:48 7필드 + version 직렬화, status 매핑, aggregateId=productId 를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductOutboxEventPublisher 단위 테스트")
class ProductOutboxEventPublisherTest {

    @InjectMocks ProductOutboxEventPublisher publisher;
    @Mock OutboxEventRepository outboxEventRepository;
    @org.mockito.Spy ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("publishProductUpdated: ADR-0012:48 7필드+version 발행, status 매핑, aggregateId=productId")
    void publishProductUpdated_serializesContract() throws Exception {
        Category category = setId(Category.create("카테고리", null), 7L);
        Product product = Product.create(category, "상품", "설명", 50_000L, "img.png");
        setId(product, 42L);
        setField(product, "version", 3L);

        publisher.publishProductUpdated(product, 17);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        then(outboxEventRepository).should().save(captor.capture());
        OutboxEvent event = captor.getValue();

        assertThat(event.getAggregateType()).isEqualTo("PRODUCT");
        assertThat(event.getAggregateId()).isEqualTo("42");          // 파티션 키 = productId
        assertThat(event.getEventType()).isEqualTo("product.updated");

        JsonNode envelope = objectMapper.readTree(event.getPayload());
        assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("productId").asLong()).isEqualTo(42L);
        assertThat(payload.get("name").asText()).isEqualTo("상품");
        assertThat(payload.get("price").asLong()).isEqualTo(50_000L);
        assertThat(payload.get("availableStock").asInt()).isEqualTo(17);
        assertThat(payload.get("status").asText()).isEqualTo("ACTIVE");   // ON_SALE → ACTIVE
        assertThat(payload.get("categoryId").asLong()).isEqualTo(7L);
        assertThat(payload.get("version").asLong()).isEqualTo(3L);
        assertThat(payload.get("updatedAt")).isNotNull();
    }

    @Test
    @DisplayName("publishProductUpdated: 판매중단 상품은 status=INACTIVE 로 매핑된다")
    void publishProductUpdated_discontinued_mapsInactive() throws Exception {
        Category category = setId(Category.create("카테고리", null), 1L);
        Product product = Product.create(category, "상품", "설명", 1_000L, null);
        setId(product, 9L);
        setField(product, "version", 2L);
        product.discontinue();

        publisher.publishProductUpdated(product, 0);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        then(outboxEventRepository).should().save(captor.capture());
        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload()).get("payload");
        assertThat(payload.get("status").asText()).isEqualTo("INACTIVE");  // DISCONTINUED → INACTIVE
    }

    private static <T> T setId(T entity, Long id) throws Exception {
        return setField(entity, "id", id);
    }

    @SuppressWarnings("unchecked")
    private static <T> T setField(T target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
        return target;
    }
}
