package com.lazyresponse.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Fail-fast mode endpoint tests for {@code POST /api/orders/detail}.
 *
 * <p>Separate from {@link OrderDetailEndpointTest} because fail-fast requires a different
 * application context ({@code failure-strategy=fail-fast}). Spring caches contexts by
 * configuration key — mixing strategies in one class forces a context rebuild per test.
 */
@SpringBootTest(classes = {
        OrderDetailFailFastEndpointTest.TestController.class,
        OrderDetailFailFastEndpointTest.TestDownstreams.class,
        TestSecurityConfig.class
})
@AutoConfigureMockMvc
@WithMockUser
@ImportAutoConfiguration({AopAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class, WebMvcAutoConfiguration.class, LazyResponseAutoConfiguration.class})
@TestPropertySource(properties = "lazy-response.failure-strategy=fail-fast")
class OrderDetailFailFastEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void downstreamError_returns502_noDataBlock() throws Exception {
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ORD-FAIL", "ACC-001", Map.of(
                                "order", new String[]{"id", "status"}
                        ))))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(""));
    }

    @Test
    void downstreamTimeout_returns503_noDataBlock() throws Exception {
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ORD-SLOW", "ACC-001", Map.of(
                                "account", new String[]{"name"}
                        ))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(""));
    }

    @RestController
    @RequestMapping("/api/orders")
    static class TestController {
        @LazyResponse
        @PostMapping("/detail")
        public ResponseEntity<?> getDetail(OrderDetailRequest request) { return null; }
    }

    private String body(String orderId, String accountId, Map<String, Object> template)
            throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "request", Map.of("orderId", orderId, "accountId", accountId),
                "template", template
        ));
    }

    @Configuration
    static class TestDownstreams {

        @Bean
        FailFastTestDownstreamService failFastTestDownstreamService() {
            return new FailFastTestDownstreamService();
        }

        public static class FailFastTestDownstreamService {

            @Downstream(id = "order", fields = {"id", "status"}, chainTimeout = 2000)
            public Map<String, Object> fetchOrder(ExecutionContext ctx) {
                OrderDetailRequest req = ctx.getRequest(OrderDetailRequest.class);
                if (req.getOrderId().startsWith("ORD-FAIL")) {
                    throw new RuntimeException("Simulated failure");
                }
                return Map.of("id", req.getOrderId(), "status", "CONFIRMED");
            }

            @Downstream(id = "account", fields = {"name"}, timeout = 300)
            public Map<String, Object> fetchAccount(ExecutionContext ctx) {
                OrderDetailRequest req = ctx.getRequest(OrderDetailRequest.class);
                if (req.getOrderId().startsWith("ORD-SLOW")) {
                    sleep(2000);
                }
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
