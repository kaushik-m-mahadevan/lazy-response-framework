package com.lazyresponse.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that the framework's DAG-driven parallel execution is significantly
 * faster than calling the same downstreams sequentially.
 *
 * <p>Dependency graph and simulated latencies for the order detail endpoint:
 * <pre>
 *   account (60ms)  -> loyalty   (120ms)
 *   order   (80ms)  -> payment   (400ms)
 *                   -> shipment  (200ms)
 *                   -> inventory (150ms)
 * </pre>
 *
 * <p>Sequential total  : 60 + 80 + 400 + 200 + 150 + 120 = 1010ms
 * <br>Lazy parallel    : max(60, 80) + max(400, 200, 150, 120) = 80 + 400 = 480ms
 * <br>Expected speedup : ~2x
 *
 * <p>Uses the real {@link DemoApplication} context with production downstreams
 * (including their Thread.sleep latency simulation) so the measurement reflects
 * genuine parallel execution, not mocked behavior.
 */
@SpringBootTest(classes = {DemoApplication.class, TestSecurityConfig.class})
@AutoConfigureMockMvc
@WithMockUser
class LatencyComparisonTest {

    @Autowired
    private MockMvc mockMvc;

    // Simulated latencies from OrderDetailDownstreams (Thread.sleep values)
    private static final long ORDER_MS      = 80;
    private static final long ACCOUNT_MS    = 60;
    private static final long PAYMENT_MS    = 400;
    private static final long SHIPMENT_MS   = 200;
    private static final long INVENTORY_MS  = 150;
    private static final long LOYALTY_MS    = 120;

    // Sequential: all downstreams called one after another
    private static final long SEQUENTIAL_MS =
            ORDER_MS + ACCOUNT_MS + PAYMENT_MS + SHIPMENT_MS + INVENTORY_MS + LOYALTY_MS;

    // Theoretical lazy: stage 1 = max(roots), stage 2 = max(dependents)
    private static final long THEORETICAL_LAZY_MS =
            Math.max(ORDER_MS, ACCOUNT_MS) +
            Math.max(PAYMENT_MS, Math.max(SHIPMENT_MS, Math.max(INVENTORY_MS, LOYALTY_MS)));

    @Test
    void parallelExecutionIsFasterThanSequential() throws Exception {
        String requestBody = """
                {
                  "request": { "orderId": "ORD-BENCH-001", "accountId": "ACC-BENCH-001" },
                  "template": {
                    "order":     null,
                    "account":   null,
                    "payment":   null,
                    "shipment":  null,
                    "inventory": null,
                    "loyalty":   null
                  }
                }
                """;

        // Warm-up: ensure JIT compilation and Spring context are fully ready
        performRequest(requestBody);

        // Timed run
        long start = System.currentTimeMillis();
        performRequest(requestBody);
        long actualLazyMs = System.currentTimeMillis() - start;

        double speedup = (double) SEQUENTIAL_MS / actualLazyMs;

        printComparison(actualLazyMs, speedup);

        assertThat(actualLazyMs)
                .as("Framework should complete all 6 downstreams in under 700ms "
                        + "(sequential would take ~" + SEQUENTIAL_MS + "ms)")
                .isLessThan(700);

        assertThat(speedup)
                .as("Framework should deliver at least 1.3x speedup over sequential execution")
                .isGreaterThan(1.3);
    }

    private void performRequest(String body) throws Exception {
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void printComparison(long actualLazyMs, double speedup) {
        System.out.println();
        System.out.println("  +-------------------------------------------------+");
        System.out.println("  |            LATENCY COMPARISON                   |");
        System.out.println("  +-------------------------------------------------+");
        System.out.printf( "  |  Sequential (sum of all downstreams) : %4dms   |%n", SEQUENTIAL_MS);
        System.out.printf( "  |  Theoretical lazy (DAG parallel)     : %4dms   |%n", THEORETICAL_LAZY_MS);
        System.out.printf( "  |  Actual lazy (measured)              : %4dms   |%n", actualLazyMs);
        System.out.printf( "  |  Speedup vs sequential               :  %.2fx    |%n", speedup);
        System.out.println("  +-------------------------------------------------+");
        System.out.println();
    }
}
