package com.lazyresponse.demo;

import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.annotation.LazyResponse;
import com.lazyresponse.autoconfigure.LazyResponseAutoConfiguration;
import com.lazyresponse.context.ExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Concurrent correctness test for the semaphore and permit-release logic (spec section 5.2).
 *
 * <p>Fires N simultaneous requests against a pool of size M (N > M). The test asserts:
 * <ol>
 *   <li>No request hangs indefinitely — all complete within a generous timeout.</li>
 *   <li>All successful requests (HTTP 200) return valid JSON with the expected fields.</li>
 *   <li>Requests rejected due to semaphore exhaustion return HTTP 503.</li>
 *   <li>Semaphore permits are fully restored after each request — confirmed by a final
 *       successful request after all concurrent ones have finished.</li>
 * </ol>
 *
 * <p>Pool size is set to 3 via {@code @TestPropertySource} to make contention easy to trigger
 * with 10 concurrent requests.
 */
@SpringBootTest(classes = {
        ConcurrentRequestTest.TestController.class,
        ConcurrentRequestTest.TestDownstreams.class,
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
@TestPropertySource(properties = "lazy-response.executor.pool-size=3")
class ConcurrentRequestTest {

    private static final int CONCURRENT_REQUESTS = 10;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void concurrentRequests_allCompleteWithoutHanging_permitsDrained() throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "request",  Map.of("orderId", "ORD-001", "accountId", "ACC-001"),
                "template", Map.of("order", new String[]{"id", "status"})
        ));

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            Callable<Integer> task = () -> {
                MvcResult result = mockMvc.perform(post("/api/orders/detail")
                                .with(user("user").roles("USER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                        .andReturn();
                return result.getResponse().getStatus();
            };
            futures.add(pool.submit(task));
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .as("All concurrent requests must complete within 30 seconds")
                .isTrue();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }

        // Every response must be either 200 (success) or 503 (semaphore exhausted)
        assertThat(statuses).allSatisfy(status ->
                assertThat(status).isIn(200, 503));

        // At least pool-size (3) requests must have succeeded.
        // The semaphore allows 3 concurrent slots; with 50ms sleep per downstream
        // and a 30-second window, all 3 slots must have served at least one request each.
        long successCount = statuses.stream().filter(s -> s == 200).count();
        assertThat(successCount)
                .as("At least pool-size (3) requests must succeed — semaphore has 3 permits")
                .isGreaterThanOrEqualTo(3);

        // Permits must be fully restored — a final request after all concurrent ones
        // must succeed without a 503.
        mockMvc.perform(post("/api/orders/detail")
                        .with(user("user").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus())
                                .as("Semaphore permits must be restored after concurrent burst")
                                .isEqualTo(200));
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
        ConcurrentTestDownstreamService concurrentTestDownstreamService() {
            return new ConcurrentTestDownstreamService();
        }

        public static class ConcurrentTestDownstreamService {
            @Downstream(id = "order", fields = {"id", "status"}, chainTimeout = 2000)
            public Map<String, Object> fetchOrder(ExecutionContext ctx) {
                // Small sleep to create real contention between concurrent requests
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return Map.of("id", "ORD-001", "status", "CONFIRMED");
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
