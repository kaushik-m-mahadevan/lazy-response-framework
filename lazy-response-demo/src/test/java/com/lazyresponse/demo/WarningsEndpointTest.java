package com.lazyresponse.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for {@code meta.warnings}  -  the soft-threshold slow-downstream signal (spec §7.5).
 *
 * <p>Uses {@code lazy-response.timeout.warning-threshold=100} so any downstream taking
 * longer than 100ms generates a warning. The stub {@code slowOrder} sleeps 200ms,
 * which exceeds the soft threshold but is well under the hard timeout (2000ms),
 * so the request succeeds with a 200 and a warning entry.
 *
 * <p>A second test confirms no warnings when all downstreams finish under the threshold.
 */
@SpringBootTest(classes = {
        WarningsEndpointTest.TestController.class,
        WarningsEndpointTest.TestDownstreams.class,
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
@TestPropertySource(properties = "lazy-response.timeout.warning-threshold=100")
class WarningsEndpointTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // 1. Slow downstream (200ms) exceeds soft threshold (100ms) → warning emitted
    // -------------------------------------------------------------------------

    @Test
    void slowDownstream_exceedsSoftThreshold_warningInMeta() throws Exception {
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("order", new String[]{"id", "status"}))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.id").value("ORD-001"))
                .andExpect(jsonPath("$.meta.errors").isEmpty())
                .andExpect(jsonPath("$.meta.warnings", hasSize(1)))
                .andExpect(jsonPath("$.meta.warnings[0]").value(
                        containsString("order")));
    }

    // -------------------------------------------------------------------------
    // 2. Fast downstream finishes under threshold → no warning
    // -------------------------------------------------------------------------

    @Test
    void fastDownstream_underSoftThreshold_noWarning() throws Exception {
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("account", new String[]{"name"}))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account.name").value("Test User"))
                .andExpect(jsonPath("$.meta.warnings").isEmpty());
    }

    // -------------------------------------------------------------------------
    // 3. Warning threshold disabled (0)  -  no warnings even for slow downstream
    // -------------------------------------------------------------------------

    // This scenario is tested via the other endpoint test classes which do not set
    // warning-threshold (defaults to 0 = disabled). The presence of meta.warnings=[]
    // in those tests implicitly covers the disabled case.

    // -------------------------------------------------------------------------
    // Request helper
    // -------------------------------------------------------------------------

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
    // Stubs
    // -------------------------------------------------------------------------

    @Configuration
    static class TestDownstreams {

        @Bean
        WarningsTestDownstreamService warningsTestDownstreamService() {
            return new WarningsTestDownstreamService();
        }

        public static class WarningsTestDownstreamService {

            /** Sleeps 200ms  -  exceeds the 100ms soft threshold, not the 2000ms hard timeout. */
            @Downstream(id = "order", fields = {"id", "status"}, chainTimeout = 2000)
            public Map<String, Object> fetchOrder(ExecutionContext ctx) {
                sleep(200);
                return Map.of("id", "ORD-001", "status", "CONFIRMED");
            }

            /** Returns immediately  -  under any reasonable soft threshold. */
            @Downstream(id = "account", fields = {"name"})
            public Map<String, Object> fetchAccount(ExecutionContext ctx) {
                return Map.of("name", "Test User");
            }

            @Downstream(id = "payment", fields = {"status"}, dependsOn = {"order"}, timeout = 500)
            public Map<String, Object> fetchPayment(ExecutionContext ctx) {
                return Map.of("status", "PAID");
            }

            private void sleep(long ms) {
                try { Thread.sleep(ms); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
