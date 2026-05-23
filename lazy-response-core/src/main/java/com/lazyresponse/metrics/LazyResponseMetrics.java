package com.lazyresponse.metrics;

import com.lazyresponse.spi.DownstreamMetricsRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer-backed {@link DownstreamMetricsRecorder} registered by the auto-configuration
 * when {@code micrometer-core} is on the classpath.
 *
 * <h3>Metrics emitted:</h3>
 * <ul>
 *   <li>{@code lazy.response.downstream.duration}  -  {@link Timer} recording per-downstream
 *       execution time. Tagged with {@code downstream.id} and {@code outcome}
 *       ({@code success} or {@code failure}). Useful for p99 latency dashboards and
 *       downstream-level SLO tracking.</li>
 *   <li>{@code lazy.response.semaphore.rejected}  -  {@link Counter} incremented each time a
 *       request is rejected because no semaphore permits are available (HTTP 503). A rising
 *       rate indicates the thread pool is undersized for the current load.</li>
 *   <li>{@code lazy.response.semaphore.available}  -  Gauge tracking available semaphore
 *       permits in real time. Useful for capacity dashboards and alerting when approaching
 *       exhaustion.</li>
 * </ul>
 *
 * <p>All metric names follow the Micrometer naming convention (dot-separated, lowercase).
 * When exported to Prometheus, dots are converted to underscores automatically by the
 * Prometheus {@link io.micrometer.prometheusmetrics.PrometheusMeterRegistry}.
 */
public class LazyResponseMetrics implements DownstreamMetricsRecorder {

    private static final String TIMER_NAME = "lazy.response.downstream.duration";
    private static final String REJECTED_NAME = "lazy.response.semaphore.rejected";
    private static final String AVAILABLE_NAME = "lazy.response.semaphore.available";

    private final MeterRegistry registry;
    private final Counter rejectedCounter;

    // Cache timers by (downstreamId + outcome) to avoid repeated registry lookups
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    public LazyResponseMetrics(MeterRegistry registry, Semaphore semaphore) {
        this.registry = registry;
        this.rejectedCounter = Counter.builder(REJECTED_NAME)
                .description("Number of requests rejected due to semaphore exhaustion (HTTP 503)")
                .register(registry);
        registry.gauge(AVAILABLE_NAME, semaphore, Semaphore::availablePermits);
    }

    @Override
    public void recordSuccess(String downstreamId, long elapsedMs) {
        timerFor(downstreamId, "success").record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordFailure(String downstreamId, String reason, long elapsedMs) {
        timerFor(downstreamId, "failure").record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordSemaphoreExhausted() {
        rejectedCounter.increment();
    }

    private Timer timerFor(String downstreamId, String outcome) {
        String key = downstreamId + ":" + outcome;
        return timerCache.computeIfAbsent(key, k ->
                Timer.builder(TIMER_NAME)
                        .description("Execution time of @Downstream methods")
                        .tag("downstream.id", downstreamId)
                        .tag("outcome", outcome)
                        .register(registry));
    }
}
