package com.lazyresponse.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazyresponse.annotation.Default;
import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.config.LazyResponseProperties;
import com.lazyresponse.context.ExecutionContext;
import com.lazyresponse.model.LazyApiResponse;
import com.lazyresponse.registry.DownstreamRegistry;
import com.lazyresponse.spi.DownstreamMetricsRecorder;
import com.lazyresponse.spi.ExecutionContextArgumentResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LazyResponseOrchestrator}  -  happy path, silent failure,
 * defaults, chain blocking, timeout, empty template, and semaphore exhaustion.
 * No Spring context required.
 */
class LazyResponseOrchestratorTest {

    private DownstreamRegistry registry;
    private ThreadPoolTaskExecutor executor;
    private Semaphore semaphore;
    private LazyResponseOrchestrator orchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Test stubs
    // -------------------------------------------------------------------------

    public static class HappyStubs {
        @Downstream(id = "order", fields = {"id", "status"}, chainTimeout = 2000)
        public Map<String, Object> order(ExecutionContext ctx) {
            return Map.of("id", "ORD-1", "status", "CONFIRMED");
        }

        @Downstream(id = "account", fields = {"name"})
        public Map<String, Object> account(ExecutionContext ctx) {
            return Map.of("name", "Alice");
        }

        @Downstream(id = "payment", fields = {"status", "method", "txnId"},
                dependsOn = {"order"}, timeout = 500,
                defaults = {
                        @Default(field = "status", value = "unknown"),
                        @Default(field = "method", value = "—")
                        // txnId has no default
                })
        public Map<String, Object> payment(ExecutionContext ctx) {
            return Map.of("status", "PAID", "method", "CARD", "txnId", "TXN-001");
        }
    }

    public static class FailingOrderStubs {
        @Downstream(id = "order", fields = {"id"}, chainTimeout = 2000)
        public Map<String, Object> order(ExecutionContext ctx) {
            throw new RuntimeException("Order service down");
        }

        @Downstream(id = "payment", fields = {"status", "txnId"},
                dependsOn = {"order"}, timeout = 500,
                defaults = { @Default(field = "status", value = "unknown") })
        public Map<String, Object> payment(ExecutionContext ctx) {
            return Map.of("status", "PAID", "txnId", "TXN-001");
        }

        @Downstream(id = "account", fields = {"name"})
        public Map<String, Object> account(ExecutionContext ctx) {
            return Map.of("name", "Bob");
        }
    }

    public static class SlowStubs {
        @Downstream(id = "account", fields = {"name"}, timeout = 100)
        public Map<String, Object> account(ExecutionContext ctx) throws InterruptedException {
            Thread.sleep(2000); // far exceeds timeout=100ms
            return Map.of("name", "Charlie");
        }
    }

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    private void buildOrchestrator(Object stubBean, int poolSize) throws Exception {
        registry = new DownstreamRegistry();
        for (Method m : stubBean.getClass().getMethods()) {
            Downstream ann = m.getAnnotation(Downstream.class);
            if (ann != null) {
                registry.register(stubBean, m, ann, stubBean.getClass());
            }
        }
        registry.seal(3000L);

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("test-lazy-");
        executor.initialize();

        semaphore = new Semaphore(poolSize, true);

        LazyResponseProperties props = new LazyResponseProperties();
        props.setFailureStrategy("silent");

        orchestrator = new LazyResponseOrchestrator(
                registry, new ExecutionPlanner(registry),
                executor.getThreadPoolExecutor(), semaphore, props, objectMapper,
                List.of(new ExecutionContextArgumentResolver()),
                DownstreamMetricsRecorder.NOOP);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) executor.shutdown();
    }

    // -------------------------------------------------------------------------
    // 1. Empty template → empty data, no errors
    // -------------------------------------------------------------------------

    @Test
    void emptyTemplate_returnsEmptyResponse() throws Exception {
        buildOrchestrator(new HappyStubs(), 10);
        LazyResponseOrchestrator.OrchestratorResult result =
                orchestrator.execute(new Object(), Map.of());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse().getData()).isEmpty();
        assertThat(result.getResponse().getMeta().getErrors()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // 2. Happy path  -  fields filtered to template
    // -------------------------------------------------------------------------

    @Test
    void happyPath_dataFilteredToTemplate() throws Exception {
        buildOrchestrator(new HappyStubs(), 10);
        LazyResponseOrchestrator.OrchestratorResult result = orchestrator.execute(
                new Object(),
                Map.of("order", List.of("id", "status")));

        assertThat(result.isSuccess()).isTrue();
        LazyApiResponse response = result.getResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) response.getData().get("order");
        assertThat(order).containsEntry("id", "ORD-1").containsEntry("status", "CONFIRMED");
        assertThat(response.getData()).doesNotContainKey("account"); // not in template
    }

    // -------------------------------------------------------------------------
    // 3. Silent failure  -  @Default values applied, no-default fields null + error
    // -------------------------------------------------------------------------

    @Test
    void silentFailure_defaultsApplied_missingFieldNullWithError() throws Exception {
        buildOrchestrator(new FailingOrderStubs(), 10);
        // order fails → payment blocked; txnId has no default → FieldError
        LazyResponseOrchestrator.OrchestratorResult result = orchestrator.execute(
                new Object(),
                Map.of("payment", List.of("status", "txnId")));

        assertThat(result.isSuccess()).isTrue();
        LazyApiResponse response = result.getResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> payment = (Map<String, Object>) response.getData().get("payment");
        assertThat(payment.get("status")).isEqualTo("unknown");
        assertThat(payment.get("txnId")).isNull();
        assertThat(response.getMeta().getErrors()).hasSize(1);
        assertThat(response.getMeta().getErrors().get(0).getDownstream()).isEqualTo("payment");
        assertThat(response.getMeta().getErrors().get(0).getField()).isEqualTo("txnId");
    }

    // -------------------------------------------------------------------------
    // 4. Chain failure  -  independent downstream unaffected
    // -------------------------------------------------------------------------

    @Test
    void chainFailure_independentDownstreamSucceeds() throws Exception {
        buildOrchestrator(new FailingOrderStubs(), 10);
        LazyResponseOrchestrator.OrchestratorResult result = orchestrator.execute(
                new Object(),
                Map.of("account", List.of("name"), "payment", List.of("status", "txnId")));

        assertThat(result.isSuccess()).isTrue();
        LazyApiResponse response = result.getResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> account = (Map<String, Object>) response.getData().get("account");
        assertThat(account.get("name")).isEqualTo("Bob");
    }

    // -------------------------------------------------------------------------
    // 5. Timeout  -  reason=timeout in meta.errors
    // -------------------------------------------------------------------------

    @Test
    void timeout_reasonIsTimeout_inMetaErrors() throws Exception {
        buildOrchestrator(new SlowStubs(), 10);
        LazyResponseOrchestrator.OrchestratorResult result = orchestrator.execute(
                new Object(),
                Map.of("account", List.of("name")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse().getMeta().getErrors()).hasSize(1);
        assertThat(result.getResponse().getMeta().getErrors().get(0).getReason())
                .isEqualTo("timeout");
    }

    // -------------------------------------------------------------------------
    // 6. Semaphore exhaustion
    // -------------------------------------------------------------------------

    @Test
    void semaphoreExhausted_returnsSemaphoreExhaustedStatus() throws Exception {
        buildOrchestrator(new HappyStubs(), 10);
        semaphore.drainPermits();
        LazyResponseOrchestrator.OrchestratorResult result = orchestrator.execute(
                new Object(),
                Map.of("order", List.of("id")));
        assertThat(result.getStatus())
                .isEqualTo(LazyResponseOrchestrator.OrchestratorResult.Status.SEMAPHORE_EXHAUSTED);
    }

    // -------------------------------------------------------------------------
    // 7. Transitive dependency  -  parent executed, absent from template data
    // -------------------------------------------------------------------------

    @Test
    void transitiveDep_parentExecutesButAbsentFromResponseData() throws Exception {
        buildOrchestrator(new HappyStubs(), 10);
        // payment depends on order; order NOT in template
        LazyResponseOrchestrator.OrchestratorResult result = orchestrator.execute(
                new Object(),
                Map.of("payment", List.of("status", "method")));

        assertThat(result.isSuccess()).isTrue();
        LazyApiResponse response = result.getResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> payment = (Map<String, Object>) response.getData().get("payment");
        assertThat(payment.get("status")).isEqualTo("PAID");
        assertThat(response.getData()).doesNotContainKey("order");
    }

    // -------------------------------------------------------------------------
    // 8. Fail-fast  -  downstream error returns DOWNSTREAM_ERROR
    // -------------------------------------------------------------------------

    @Test
    void failFast_downstreamError_returnsDownstreamErrorStatus() throws Exception {
        FailingOrderStubs stubs = new FailingOrderStubs();
        registry = new DownstreamRegistry();
        for (Method m : stubs.getClass().getMethods()) {
            Downstream ann = m.getAnnotation(Downstream.class);
            if (ann != null) registry.register(stubs, m, ann, stubs.getClass());
        }
        registry.seal(3000L);

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(0);
        executor.initialize();
        semaphore = new Semaphore(10, true);

        LazyResponseProperties props = new LazyResponseProperties();
        props.setFailureStrategy("fail-fast");
        orchestrator = new LazyResponseOrchestrator(
                registry, new ExecutionPlanner(registry),
                executor.getThreadPoolExecutor(), semaphore, props, objectMapper,
                List.of(new ExecutionContextArgumentResolver()),
                DownstreamMetricsRecorder.NOOP);

        LazyResponseOrchestrator.OrchestratorResult result = orchestrator.execute(
                new Object(),
                Map.of("order", List.of("id")));

        assertThat(result.getStatus())
                .isEqualTo(LazyResponseOrchestrator.OrchestratorResult.Status.DOWNSTREAM_ERROR);
    }
}
