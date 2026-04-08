package com.lazyresponse.demo;

import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.annotation.LazyResponse;
import com.lazyresponse.autoconfigure.LazyResponseAutoConfiguration;
import com.lazyresponse.context.ExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.Semaphore;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level test verifying that the framework returns HTTP 503 with no body
 * when the semaphore is exhausted (spec section 5.1).
 *
 * <p>Drains all semaphore permits before the request, then restores them afterwards
 * to avoid polluting the shared context for other tests in the same JVM run.
 */
@SpringBootTest(classes = {
        SemaphoreExhaustionEndpointTest.TestController.class,
        SemaphoreExhaustionEndpointTest.TestDownstreams.class,
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
class SemaphoreExhaustionEndpointTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private Semaphore lazyResponseSemaphore;

    private int drainedPermits;

    @BeforeEach
    void drainSemaphore() {
        drainedPermits = lazyResponseSemaphore.drainPermits();
    }

    @AfterEach
    void restoreSemaphore() {
        lazyResponseSemaphore.release(drainedPermits);
    }

    @Test
    void semaphoreExhausted_returns503_withEmptyBody() throws Exception {
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":{\"orderId\":\"ORD-1\",\"accountId\":\"ACC-1\"},"
                                + "\"template\":{\"order\":[\"id\"]}}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(""));
    }

    @RestController
    @RequestMapping("/api/orders")
    static class TestController {
        @LazyResponse
        @PostMapping("/detail")
        public ResponseEntity<?> getDetail(Object request) { return null; }
    }

    @Configuration
    static class TestDownstreams {
        @Bean
        SemaphoreTestDownstreamService semaphoreTestDownstreamService() {
            return new SemaphoreTestDownstreamService();
        }

        public static class SemaphoreTestDownstreamService {
            @Downstream(id = "order", fields = {"id", "status"}, chainTimeout = 2000)
            public Map<String, Object> fetchOrder(ExecutionContext ctx) {
                return Map.of("id", "ORD-1", "status", "CONFIRMED");
            }

            @Downstream(id = "account", fields = {"name"})
            public Map<String, Object> fetchAccount(ExecutionContext ctx) {
                return Map.of("name", "Test User");
            }

            @Downstream(id = "payment", fields = {"status"}, dependsOn = {"order"}, timeout = 500)
            public Map<String, Object> fetchPayment(ExecutionContext ctx) {
                return Map.of("status", "PAID");
            }
        }
    }
}
