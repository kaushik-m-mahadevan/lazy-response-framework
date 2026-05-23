package com.lazyresponse.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * YAML-bound configuration properties for the Lazy Response Framework.
 *
 * <p>All settings have sensible defaults so only overrides need to be declared.
 *
 * <pre>{@code
 * lazy-response:
 *   failure-strategy: silent     # silent | fail-fast
 *   timeout:
 *     global: 3000               # ms  -  floor timeout for all downstreams
 *     warning-threshold: 0       # ms  -  soft threshold for meta.warnings (0 = disabled)
 *   executor:
 *     pool-size: 20              # thread pool size; also sizes the semaphore
 *   graph:
 *     enabled: true              # set to false to disable /lazy/graph in production
 * }</pre>
 *
 * <p>Property values are validated at startup when {@code spring-boot-starter-validation}
 * (Hibernate Validator) is on the classpath. Invalid values cause a fast-fail with a
 * descriptive error before any requests are served.
 */
@ConfigurationProperties(prefix = "lazy-response")
@Validated
public class LazyResponseProperties {

    /**
     * Global failure strategy. {@code silent} absorbs failures and returns 200 with
     * defaults/nulls. {@code fail-fast} aborts on any failure and returns 502 or 503.
     */
    @NotNull
    @Pattern(regexp = "silent|fail-fast",
             message = "must be 'silent' or 'fail-fast'")
    private String failureStrategy = "silent";

    @Valid
    private Timeout timeout = new Timeout();

    @Valid
    private Executor executor = new Executor();

    @Valid
    private Graph graph = new Graph();

    public String getFailureStrategy() {
        return failureStrategy;
    }

    public void setFailureStrategy(String failureStrategy) {
        this.failureStrategy = failureStrategy;
    }

    public Timeout getTimeout() {
        return timeout;
    }

    public void setTimeout(Timeout timeout) {
        this.timeout = timeout;
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public Graph getGraph() {
        return graph;
    }

    public void setGraph(Graph graph) {
        this.graph = graph;
    }

    public static class Timeout {

        /** Global timeout floor in milliseconds. Applied when no chain or per-node timeout is set. */
        @Min(value = 1, message = "must be > 0 ms")
        private long global = 3000;

        /**
         * Soft warning threshold in milliseconds. When a downstream completes successfully but
         * took longer than this value, a warning entry is added to {@code meta.warnings}.
         * {@code 0} disables the soft threshold (no warnings emitted). Default: 0 (disabled).
         */
        @Min(value = 0, message = "must be >= 0")
        private long warningThreshold = 0;

        public long getGlobal() {
            return global;
        }

        public void setGlobal(long global) {
            this.global = global;
        }

        public long getWarningThreshold() {
            return warningThreshold;
        }

        public void setWarningThreshold(long warningThreshold) {
            this.warningThreshold = warningThreshold;
        }
    }

    public static class Executor {

        /**
         * Thread pool size. Also determines the total semaphore permit count, which caps the
         * maximum number of concurrent downstream tasks across all in-flight requests.
         */
        @Min(value = 1, message = "must be >= 1")
        private int poolSize = 20;

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }
    }

    public static class Graph {

        /**
         * Whether the {@code /lazy/graph} visualisation endpoint is registered.
         * Set to {@code false} in environments where the dependency topology should not be
         * publicly accessible (e.g., internet-facing production services).
         * Default: {@code true}.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
