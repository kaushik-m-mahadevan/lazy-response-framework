package com.lazyresponse.spi;

/**
 * SPI for recording lazy-response execution metrics.
 *
 * <p>The framework calls these methods at key execution points. The default implementation
 * registered by the auto-configuration is a no-op. When {@code micrometer-core} is on the
 * classpath, the auto-configuration registers a Micrometer-backed implementation instead.
 *
 * <p>Consumer applications can register a custom implementation as a Spring bean to send
 * metrics to any backend (Prometheus, CloudWatch, Datadog, etc.) without depending on
 * Micrometer. The framework uses the first bean of this type it finds.
 *
 * @see com.lazyresponse.metrics.LazyResponseMetrics
 */
public interface DownstreamMetricsRecorder {

    /**
     * Called when a downstream method completes successfully.
     *
     * @param downstreamId the {@code @Downstream} id
     * @param elapsedMs    execution time in milliseconds
     */
    void recordSuccess(String downstreamId, long elapsedMs);

    /**
     * Called when a downstream method fails  -  either by throwing an exception or timing out.
     *
     * @param downstreamId the {@code @Downstream} id
     * @param reason       failure reason: {@code "error"}, {@code "timeout"},
     *                     {@code "dependency-failed"}, or {@code "blocked"}
     * @param elapsedMs    time elapsed before the failure in milliseconds; {@code 0} for
     *                     downstreams that were blocked and never started
     */
    void recordFailure(String downstreamId, String reason, long elapsedMs);

    /**
     * Called when a request is rejected because no semaphore permits are available.
     * Maps to HTTP 503.
     */
    void recordSemaphoreExhausted();

    /**
     * No-op implementation used when no metrics backend is configured.
     * All methods are empty and produce no side effects.
     */
    DownstreamMetricsRecorder NOOP = new DownstreamMetricsRecorder() {
        @Override public void recordSuccess(String id, long ms) {}
        @Override public void recordFailure(String id, String reason, long elapsedMs) {}
        @Override public void recordSemaphoreExhausted() {}
    };
}
