package com.lazyresponse.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazyresponse.annotation.Default;
import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.annotation.LazyResponse;
import com.lazyresponse.autoconfigure.LazyResponseAutoConfiguration;
import com.lazyresponse.context.ExecutionContext;
import com.lazyresponse.demo.model.request.OrderDetailRequest;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Runtime endpoint tests for {@code POST /api/orders/detail} in {@code silent} failure mode.
 *
 * <p>Uses {@link SpringBootTest} with an explicit {@code classes} list — no component scan.
 * This means exactly what we specify is in the context: the controller, the test stubs,
 * and the framework auto-configurations. No production downstreams, no collision.
 *
 * <p>Fail-fast tests are in {@link OrderDetailFailFastEndpointTest} — separate context
 * with {@code lazy-response.failure-strategy=fail-fast}.
 */
@SpringBootTest(classes = {
        OrderDetailEndpointTest.TestController.class,
        OrderDetailEndpointTest.TestDownstreams.class,
        TestSecurityConfig.class
})
@AutoConfigureMockMvc
@WithMockUser
@ImportAutoConfiguration({AopAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class, WebMvcAutoConfiguration.class, LazyResponseAutoConfiguration.class})
class OrderDetailEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // 1. Happy path
    // -------------------------------------------------------------------------

    @Test
    void happyPath_allDownstreamsSucceed_responseFilteredToTemplate() throws Exception {
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ORD-001", "ACC-001", Map.of(
                                "order",   new String[]{"id", "status"},
                                "account", new String[]{"name", "tier"}
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.id").value("ORD-001"))
                .andExpect(jsonPath("$.data.order.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.account.name").value("Test User"))
                .andExpect(jsonPath("$.data.account.tier").value("GOLD"))
                .andExpect(jsonPath("$.meta.errors").isEmpty())
                .andExpect(jsonPath("$.meta.warnings").isEmpty());
    }

    // -------------------------------------------------------------------------
    // 2. Partial template — unrequested downstreams absent from response
    // -------------------------------------------------------------------------

    @Test
    void partialTemplate_onlyRequestedDownstreamsInResponse() throws Exception {
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ORD-001", "ACC-001", Map.of(
                                "order", new String[]{"id", "status"}
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order").exists())
                .andExpect(jsonPath("$.data.account").doesNotExist())
                .andExpect(jsonPath("$.data.payment").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // 3. Unknown template keys silently dropped
    // -------------------------------------------------------------------------

    @Test
    void unknownTemplateKeys_silentlyIgnored() throws Exception {
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ORD-001", "ACC-001", Map.of(
                                "order",       new String[]{"id"},
                                "nonexistent", new String[]{"foo"}
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.id").value("ORD-001"))
                .andExpect(jsonPath("$.data.nonexistent").doesNotExist())
                .andExpect(jsonPath("$.meta.errors").isEmpty());
    }

    // -------------------------------------------------------------------------
    // 4. Downstream failure — silent mode, @Default values applied
    // -------------------------------------------------------------------------

    @Test
    void downstreamFailure_silentMode_defaultsApplied() throws Exception {
        // order fails for ORD-FAIL; payment depends on order and is blocked;
        // payment has @Default on status and method
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ORD-FAIL", "ACC-001", Map.of(
                                "payment", new String[]{"status", "method"}
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payment.status").value("unknown"))
                .andExpect(jsonPath("$.data.payment.method").value("—"));
    }

    // -------------------------------------------------------------------------
    // 5. Downstream failure — fields with no @Default are null + meta.errors entry
    // -------------------------------------------------------------------------

    @Test
    void downstreamFailure_fieldsWithNoDefault_areNullAndGenerateErrors() throws Exception {
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ORD-FAIL", "ACC-001", Map.of(
                                "payment", new String[]{"status", "method", "transactionId"}
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payment.transactionId").value(nullValue()))
                .andExpect(jsonPath("$.meta.errors", hasSize(1)))
                .andExpect(jsonPath("$.meta.errors[0].downstream").value("payment"))
                .andExpect(jsonPath("$.meta.errors[0].field").value("transactionId"));
    }

    // -------------------------------------------------------------------------
    // 6. Chain failure — child blocked, independent chain unaffected
    // -------------------------------------------------------------------------

    @Test
    void chainFailure_childBlockedWhenParentFails_independentChainUnaffected() throws Exception {
        // order fails → payment blocked. account is independent → must succeed.
        // transactionId has no @Default → FieldError added for payment.
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ORD-FAIL", "ACC-001", Map.of(
                                "account", new String[]{"name"},
                                "payment", new String[]{"status", "transactionId"}
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account.name").value("Test User"))
                .andExpect(jsonPath("$.meta.errors[*].downstream", hasItem("payment")));
    }

    // -------------------------------------------------------------------------
    // 7. Timeout — downstream exceeds timeout, reason=timeout in meta.errors
    // -------------------------------------------------------------------------

    @Test
    void timeout_downstreamExceedsTimeout_reasonIsTimeout() throws Exception {
        // ORD-SLOW causes account to sleep 2000ms but account timeout=300ms
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ORD-SLOW", "ACC-001", Map.of(
                                "account", new String[]{"name"}
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.errors[0].downstream").value("account"))
                .andExpect(jsonPath("$.meta.errors[0].reason").value("timeout"));
    }

    // -------------------------------------------------------------------------
    // 8. Transitive dependency — parent auto-included even when absent from template
    // -------------------------------------------------------------------------

    @Test
    void transitiveDependency_parentNotInTemplate_stillExecuted() throws Exception {
        // payment depends on order. order is not in the template.
        // order must execute silently; must NOT appear in response data.
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ORD-001", "ACC-001", Map.of(
                                "payment", new String[]{"status", "method"}
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payment.status").value("PAID"))
                .andExpect(jsonPath("$.data.order").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // 9. Empty template — 200 with empty data block
    // -------------------------------------------------------------------------

    @Test
    void emptyTemplate_returns200WithEmptyData() throws Exception {
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ORD-001", "ACC-001", Map.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.errors").isEmpty())
                .andExpect(jsonPath("$.meta.warnings").isEmpty());
    }

    // -------------------------------------------------------------------------
    // 10. Meta envelope always present
    // -------------------------------------------------------------------------

    @Test
    void metaEnvelope_alwaysPresent_bothListsNonNull() throws Exception {
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ORD-001", "ACC-001", Map.of(
                                "order", new String[]{"id"}
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta").exists())
                .andExpect(jsonPath("$.meta.errors").exists())
                .andExpect(jsonPath("$.meta.warnings").exists());
    }

    // -------------------------------------------------------------------------
    // Stub controller — no downstreams scope so all test stubs are eligible
    // -------------------------------------------------------------------------

    @RestController
    @RequestMapping("/api/orders")
    static class TestController {
        @LazyResponse
        @PostMapping("/detail")
        public ResponseEntity<?> getDetail(OrderDetailRequest request) { return null; }
    }

    // -------------------------------------------------------------------------
    // Request body helper
    // -------------------------------------------------------------------------

    private String body(String orderId, String accountId, Map<String, Object> template)
            throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "request", Map.of("orderId", orderId, "accountId", accountId),
                "template", template
        ));
    }

    // -------------------------------------------------------------------------
    // Test downstream stubs
    // -------------------------------------------------------------------------

    @Configuration
    static class TestDownstreams {

        @Bean
        TestDownstreamService testDownstreamService() {
            return new TestDownstreamService();
        }

        public static class TestDownstreamService {

            @Downstream(id = "order", fields = {"id", "status", "total"}, chainTimeout = 2000)
            public Map<String, Object> fetchOrder(ExecutionContext ctx) {
                OrderDetailRequest req = ctx.getRequest(OrderDetailRequest.class);
                if (req.getOrderId().startsWith("ORD-FAIL")) {
                    throw new RuntimeException("Simulated order failure");
                }
                return Map.of("id", req.getOrderId(), "status", "CONFIRMED", "total", 1000);
            }

            @Downstream(id = "account", fields = {"name", "tier"}, chainTimeout = 1500, timeout = 300)
            public Map<String, Object> fetchAccount(ExecutionContext ctx) {
                OrderDetailRequest req = ctx.getRequest(OrderDetailRequest.class);
                if (req.getOrderId().startsWith("ORD-SLOW")) {
                    sleep(2000); // exceeds timeout=300ms → TimeoutException
                }
                return Map.of("name", "Test User", "tier", "GOLD");
            }

            @Downstream(
                id        = "payment",
                fields    = {"status", "method", "transactionId"},
                dependsOn = {"order"},
                timeout   = 500,
                defaults  = {
                    @Default(field = "status", value = "unknown"),
                    @Default(field = "method", value = "—")
                    // transactionId: no @Default → null + meta.errors on failure
                }
            )
            public Map<String, Object> fetchPayment(ExecutionContext ctx) {
                return Map.of("status", "PAID", "method", "CARD", "transactionId", "TXN-001");
            }

            private void sleep(long ms) {
                try { Thread.sleep(ms); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
