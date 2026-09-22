package com.peekcart.order.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 목록 API 의 OpenAPI 계약 회귀 테스트 (계획 P11 · T7).
 *
 * <p>파괴적 계약 변경이라 문서가 구현과 갈라지면 안 된다. 특히 {@code size} 는 런타임 타입이
 * {@code String} 이라(타입 변환 500 회피) {@code @Schema} 선언이 없으면 문서에 임의 문자열로
 * 노출된다 — 구현은 1~100 정수만 받는데.
 *
 * <p>{@code @WebMvcTest} 를 쓰지 않는다. 제3자 springdoc 자동 구성이 보장되지 않아 {@code /api-docs}
 * 가 404 가 될 수 있고, 필요한 것만 골라 import 하면 실제 앱과 다른 컨텍스트를 검사하게 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("주문 API OpenAPI 계약")
class OrderApiDocsContractTest {

    @Autowired MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode getOrdersOperation() throws Exception {
        String body = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode op = mapper.readTree(body).path("paths").path("/api/v1/orders").path("get");
        // 404 나 빈 문서를 조용히 통과시키지 않는다 — 여기서 멈춰야 아래 단언이 의미를 갖는다.
        assertThat(op.isObject()).as("GET /api/v1/orders operation 이 문서에 없다").isTrue();
        return op;
    }

    private List<String> paramNames(JsonNode op) {
        List<String> names = new ArrayList<>();
        op.path("parameters").forEach(p -> names.add(p.path("name").asText()));
        return names;
    }

    /** springdoc 은 required=false 를 문서에서 생략한다 — 부재와 false 를 같게 본다. */
    private void assertOptional(JsonNode param) {
        assertThat(param.path("required").asBoolean(false)).isFalse();
    }

    private JsonNode param(JsonNode op, String name) {
        for (JsonNode p : op.path("parameters")) {
            if (name.equals(p.path("name").asText())) {
                return p;
            }
        }
        throw new AssertionError("파라미터 없음: " + name);
    }

    @Test
    @DisplayName("파라미터는 cursor·size 뿐이고 offset 계열은 사라졌다")
    void parameters_areCursorAndSizeOnly() throws Exception {
        List<String> names = paramNames(getOrdersOperation());

        assertThat(names).containsExactlyInAnyOrder("cursor", "size");
        assertThat(names).doesNotContain("page", "sort", "offset");
    }

    @Test
    @DisplayName("cursor 는 query 위치의 선택적 문자열이다")
    void cursorParameter_documented() throws Exception {
        JsonNode cursor = param(getOrdersOperation(), "cursor");

        assertThat(cursor.path("in").asText()).isEqualTo("query");
        assertOptional(cursor);
        assertThat(cursor.path("schema").path("type").asText()).isEqualTo("string");
    }

    @Test
    @DisplayName("size 는 문자열이 아니라 1~100 정수로 문서화된다 (런타임 타입은 String)")
    void sizeParameter_documentedAsBoundedInteger() throws Exception {
        JsonNode size = param(getOrdersOperation(), "size");

        assertThat(size.path("in").asText()).isEqualTo("query");
        assertOptional(size);
        JsonNode schema = size.path("schema");
        assertThat(schema.path("type").asText()).isEqualTo("integer");
        assertThat(schema.path("format").asText()).isEqualTo("int32");
        assertThat(schema.path("default").asText()).isEqualTo("20");
        assertThat(schema.path("minimum").asInt()).isEqualTo(1);
        assertThat(schema.path("maximum").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("응답 스키마에 totalElements 는 없고 nextCursor/hasNext 가 있다")
    void responseSchema_isCursorShaped() throws Exception {
        String body = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode root = mapper.readTree(body);

        JsonNode ref = root.path("paths").path("/api/v1/orders").path("get")
                .path("responses").path("200").path("content")
                .path("*/*").path("schema").path("$ref");
        assertThat(ref.isMissingNode()).isFalse();

        JsonNode wrapper = resolve(root, ref.asText());
        JsonNode dataRef = wrapper.path("properties").path("data").path("$ref");
        JsonNode page = resolve(root, dataRef.asText());

        assertThat(page.path("properties").fieldNames()).toIterable()
                .contains("content", "nextCursor", "hasNext")
                .doesNotContain("totalElements", "totalPages", "number", "size", "pageable");
    }

    private JsonNode resolve(JsonNode root, String ref) {
        JsonNode node = root;
        for (String part : ref.replace("#/", "").split("/")) {
            node = node.path(part);
        }
        assertThat(node.isObject()).as("$ref 를 해석하지 못했다: %s", ref).isTrue();
        return node;
    }
}
