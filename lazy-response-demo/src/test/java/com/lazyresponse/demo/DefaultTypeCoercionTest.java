package com.lazyresponse.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazyresponse.annotation.Default;
import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.annotation.LazyResponse;
import com.lazyresponse.autoconfigure.LazyResponseAutoConfiguration;
import com.lazyresponse.context.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for Fix 4: {@code @Default} string values are coerced to the field's declared Java type.
 *
 * <p>Before this fix, {@code @Default(field = "active", value = "true")} would inject the
 * Java {@code String} {@code "true"} into the response map, which Jackson serialises as the
 * JSON string {@code "true"}  -  not the JSON boolean {@code true}. This breaks API contracts
 * for boolean and numeric fields.
 *
 * <p>This test verifies:
 * <ul>
 *   <li>{@code @Default} on a {@code boolean} field produces a JSON boolean ({@code true/false}),
 *       not a JSON string.</li>
 *   <li>{@code @Default} on an {@code int} field produces a JSON number ({@code 42}),
 *       not a JSON string ({@code "42"}).</li>
 *   <li>{@code @Default} on a {@code String} field still works as before.</li>
 * </ul>
 */
@SpringBootTest(classes = {
        DefaultTypeCoercionTest.TestController.class,
        DefaultTypeCoercionTest.TypedDefaultsConfig.class,
        TestSecurityConfig.class
})
@AutoConfigureMockMvc
@WithMockUser
@ImportAutoConfiguration({
        AopAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        LazyResponseAutoConfiguration.class
})
class DefaultTypeCoercionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void booleanDefault_serialisesAsJsonBoolean_notString() throws Exception {
        // product always throws → defaults applied. "active" defaults to "true" (string in annotation)
        // After Fix 4: should arrive as boolean true in JSON, not string "true"
        MvcResult result = mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("product", new String[]{"active"}))))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> product = (Map<String, Object>) data.get("product");

        // Must be Boolean true, not String "true"
        assertThat(product.get("active"))
                .as("@Default boolean value must be coerced to Boolean, not remain a String")
                .isInstanceOf(Boolean.class)
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    void intDefault_serialisesAsJsonNumber_notString() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("product", new String[]{"stockCount"}))))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> product = (Map<String, Object>) data.get("product");

        // Must be Integer 0, not String "0"
        assertThat(product.get("stockCount"))
                .as("@Default int value must be coerced to Integer, not remain a String")
                .isInstanceOf(Integer.class)
                .isEqualTo(0);
    }

    @Test
    void stringDefault_stillWorksAsExpected() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("product", new String[]{"category"}))))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = objectMapper.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> product = (Map<String, Object>) data.get("product");

        assertThat(product.get("category"))
                .as("@Default String value must remain a String")
                .isInstanceOf(String.class)
                .isEqualTo("UNKNOWN");
    }

    private String body(Map<String, Object> template) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "request",  Map.of("orderId", "ORD-001", "accountId", "ACC-001"),
                "template", template
        ));
    }

    @RestController
    @RequestMapping("/api/orders")
    static class TestController {
        @LazyResponse
        @PostMapping("/detail")
        public ResponseEntity<?> getDetail(Object request) { return null; }
    }

    // -------------------------------------------------------------------------
    // Config: a typed response class with boolean + int fields, always-failing downstream
    // -------------------------------------------------------------------------

    @Configuration
    static class TypedDefaultsConfig {

        @Bean
        TypedDefaultsService typedDefaultsService() {
            return new TypedDefaultsService();
        }

        public static class ProductStatus {
            private boolean active;
            private int stockCount;
            private String category;

            public boolean isActive() { return active; }
            public void setActive(boolean active) { this.active = active; }

            public int getStockCount() { return stockCount; }
            public void setStockCount(int stockCount) { this.stockCount = stockCount; }

            public String getCategory() { return category; }
            public void setCategory(String category) { this.category = category; }
        }

        public static class TypedDefaultsService {

            /**
             * Always throws  -  forcing the framework to apply @Default values.
             * Field types are resolved from ProductStatus's getters.
             */
            @Downstream(
                id      = "product",
                fields  = {"active", "stockCount", "category"},
                timeout = 500,
                defaults = {
                    @Default(field = "active",     value = "true"),   // boolean  -  must become Boolean.TRUE
                    @Default(field = "stockCount",  value = "0"),     // int     -  must become Integer 0
                    @Default(field = "category",    value = "UNKNOWN") // String  -  stays as-is
                }
            )
            public ProductStatus fetchProduct(ExecutionContext ctx) {
                throw new RuntimeException("Simulated product failure  -  defaults must apply");
            }
        }
    }
}
