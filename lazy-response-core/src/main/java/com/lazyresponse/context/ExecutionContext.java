package com.lazyresponse.context;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-request context scoped to a single {@code @LazyResponse} invocation.
 *
 * <p>Holds the original request POJO and a thread-safe map of downstream results keyed
 * by downstream id. Constructed by the framework before any downstream executes and
 * garbage-collected when the request completes. Never shared across requests.
 *
 * <p>{@link ConcurrentHashMap} is used because multiple stages write results and read
 * parent results concurrently.
 *
 * <p>Developers access this context as the sole parameter of every {@code @Downstream} method.
 * Pre-processing, validation, and transformation of the request or parent results happen
 * inside the downstream method before any downstream call is made.
 */
public class ExecutionContext {

    /**
     * Sentinel stored in place of a {@code null} downstream result.
     * {@link ConcurrentHashMap} does not accept null values, so a sentinel is required to
     * distinguish "downstream ran and returned null" from "downstream has not run yet".
     */
    private static final Object NULL_RESULT = new Object();

    private final Object request;
    private final ConcurrentHashMap<String, Object> results;

    public ExecutionContext(Object request) {
        this.request = request;
        this.results = new ConcurrentHashMap<>();
    }

    /**
     * Returns the original request POJO cast to the given type.
     *
     * @param type the expected type of the request POJO
     * @throws ClassCastException if the actual request is not assignable to {@code type}
     */
    public <T> T getRequest(Class<T> type) {
        return type.cast(request);
    }

    /**
     * Returns the result of a previously completed downstream, cast to the given type.
     *
     * <p>Returns {@code null} if the downstream has not yet written a result (which should
     * never happen when called from a properly declared {@code dependsOn} chain) or if the
     * downstream wrote {@code null} as its result.
     *
     * @param downstreamId the id declared on the target {@code @Downstream} method
     * @param type         the expected return type of that downstream
     */
    public <T> T get(String downstreamId, Class<T> type) {
        Object result = results.get(downstreamId);
        if (result == null || result == NULL_RESULT) {
            return null;
        }
        return type.cast(result);
    }

    /**
     * Writes a downstream's result into the context. Called by the framework when a
     * downstream method returns. Not intended for developer use.
     *
     * <p>{@code null} results are stored as {@link #NULL_RESULT} because
     * {@link ConcurrentHashMap} does not permit null values.
     */
    public void put(String downstreamId, Object result) {
        results.put(downstreamId, result != null ? result : NULL_RESULT);
    }

    /**
     * Returns {@code true} if a result (including {@code null}) has been written for the
     * given downstream id. Used by the framework to gate dependent downstreams.
     */
    public boolean hasResult(String downstreamId) {
        return results.containsKey(downstreamId);
    }
}
