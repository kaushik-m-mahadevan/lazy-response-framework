package com.lazyresponse.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazyresponse.config.LazyResponseProperties;
import com.lazyresponse.context.ExecutionContext;
import com.lazyresponse.model.*;
import com.lazyresponse.registry.DownstreamRegistry;
import com.lazyresponse.spi.DownstreamArgumentResolver;
import com.lazyresponse.spi.DownstreamMetricsRecorder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes a per-request {@link ExecutionPlan} against the downstream dependency graph.
 *
 * <h3>Execution flow per request:</h3>
 * <ol>
 *   <li>Plan is built by {@link ExecutionPlanner} — stages filtered to the template and scope</li>
 *   <li>Max-width semaphore permits are atomically reserved; 503 if unavailable</li>
 *   <li>Stages execute in order; within each stage, all nodes run concurrently</li>
 *   <li>Before firing each node, its declared parents are checked in the
 *       {@link ExecutionContext}. A node is blocked if any parent is absent or failed</li>
 *   <li>Excess permits are released after each stage to free capacity for other requests</li>
 *   <li>Response is assembled from context results, applying defaults and building the
 *       {@code meta.errors} list for fields that could not be populated</li>
 * </ol>
 *
 * <h3>Failure strategies:</h3>
 * <ul>
 *   <li>{@code silent} — failures are absorbed; affected fields get defaults or null;
 *       meta.errors is populated; 200 is always returned</li>
 *   <li>{@code fail-fast} — any failure aborts immediately; 502 for downstream error,
 *       503 for timeout; no partial data is returned</li>
 * </ul>
 *
 * <h3>Security context:</h3>
 * <p>The {@link Executor} injected at construction time is expected to propagate the
 * Spring Security context when {@code spring-security-core} is on the classpath. The
 * auto-configuration wraps the thread pool with {@code DelegatingSecurityContextExecutorService}
 * automatically — downstream methods can safely call {@code SecurityContextHolder.getContext()}.
 */
public class LazyResponseOrchestrator {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final DownstreamRegistry registry;
    private final ExecutionPlanner planner;
    private final Executor executor;
    private final Semaphore semaphore;
    private final LazyResponseProperties properties;
    private final ObjectMapper objectMapper;
    private final List<DownstreamArgumentResolver> argumentResolvers;
    private final DownstreamMetricsRecorder metricsRecorder;

    public LazyResponseOrchestrator(
            DownstreamRegistry registry,
            ExecutionPlanner planner,
            Executor executor,
            Semaphore semaphore,
            LazyResponseProperties properties,
            ObjectMapper objectMapper,
            List<DownstreamArgumentResolver> argumentResolvers,
            DownstreamMetricsRecorder metricsRecorder) {
        this.registry = registry;
        this.planner = planner;
        this.executor = executor;
        this.semaphore = semaphore;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.argumentResolvers = List.copyOf(argumentResolvers);
        this.metricsRecorder = metricsRecorder;
    }

    /**
     * Executes the full lazy resolution pipeline for a single request with no scope restriction.
     * All registered downstreams are eligible. Equivalent to {@code execute(requestObject, template, Set.of())}.
     */
    public OrchestratorResult execute(Object requestObject, Map<String, Object> template) {
        return execute(requestObject, template, Collections.emptySet());
    }

    /**
     * Executes the full lazy resolution pipeline for a single request.
     *
     * @param requestObject the typed request POJO to populate the {@link ExecutionContext}
     * @param template      the field selection template from the request body
     * @param scopedIds     the downstream IDs in scope for this endpoint; empty means no restriction
     * @return an {@link OrchestratorResult} describing the outcome and carrying the
     *         assembled response on success
     */
    public OrchestratorResult execute(Object requestObject, Map<String, Object> template, Set<String> scopedIds) {
        ExecutionPlan plan = planner.plan(template, scopedIds);

        if (plan.isEmpty()) {
            return OrchestratorResult.success(emptyResponse());
        }

        int permitsToAcquire = plan.getMaxWidth();
        if (!semaphore.tryAcquire(permitsToAcquire)) {
            metricsRecorder.recordSemaphoreExhausted();
            return OrchestratorResult.semaphoreExhausted();
        }

        ExecutionContext context = new ExecutionContext(requestObject);
        Map<String, Throwable> failures = new ConcurrentHashMap<>();
        Map<String, Long> elapsedMs = new ConcurrentHashMap<>();
        boolean failFast = "fail-fast".equals(properties.getFailureStrategy());
        AtomicBoolean hardFailed = new AtomicBoolean(false);

        int permitsHeld = permitsToAcquire;
        List<ExecutionStage> stages = plan.getStages();

        try {
            for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
                if (hardFailed.get()) {
                    break;
                }

                ExecutionStage stage = stages.get(stageIndex);
                executeStage(stage, context, failures, elapsedMs, failFast, hardFailed);

                // Release permits we no longer need. We must retain at least as many permits as the
                // widest remaining stage requires — using only nextWidth would under-retain when a
                // later stage is wider than the next (e.g. stage widths [5, 2, 5]).
                int maxFutureWidth = 0;
                for (int f = stageIndex + 1; f < stages.size(); f++) {
                    maxFutureWidth = Math.max(maxFutureWidth, stages.get(f).width());
                }
                int toRelease = Math.max(0, permitsHeld - maxFutureWidth);
                if (toRelease > 0) {
                    semaphore.release(toRelease);
                    permitsHeld -= toRelease;
                }
            }
        } finally {
            // Always release whatever permits remain, including early-exit paths
            if (permitsHeld > 0) {
                semaphore.release(permitsHeld);
            }
        }

        if (hardFailed.get()) {
            boolean isTimeout = failures.values().stream().anyMatch(t -> t instanceof TimeoutException);
            return OrchestratorResult.hardFailure(isTimeout);
        }

        return OrchestratorResult.success(assembleResponse(template, scopedIds, context, failures, elapsedMs));
    }

    /**
     * Executes all nodes in a stage concurrently. Nodes whose parents are absent or failed
     * are blocked (skipped) immediately without submitting a task.
     */
    private void executeStage(
            ExecutionStage stage,
            ExecutionContext context,
            Map<String, Throwable> failures,
            Map<String, Long> elapsedMs,
            boolean failFast,
            AtomicBoolean hardFailed) {

        List<CompletableFuture<Void>> stageFutures = new ArrayList<>();

        for (DownstreamRegistration node : stage.getNodes()) {
            if (hardFailed.get()) {
                continue;
            }
            if (!allParentsSucceeded(node, context, failures)) {
                continue;
            }

            long taskStart = System.currentTimeMillis();
            CompletableFuture<Void> future = CompletableFuture
                    .runAsync(() -> invokeDownstream(node, context, failures, elapsedMs, failFast, hardFailed),
                            executor)
                    .orTimeout(node.getEffectiveTimeout(), TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        Throwable cause = unwrap(ex);
                        long taskElapsed = System.currentTimeMillis() - taskStart;
                        // putIfAbsent ensures only the first failure (e.g. timeout vs exception) is recorded
                        if (failures.putIfAbsent(node.getId(), cause) == null) {
                            metricsRecorder.recordFailure(node.getId(),
                                    cause instanceof TimeoutException ? "timeout" : "error",
                                    taskElapsed);
                        }
                        if (failFast) {
                            hardFailed.set(true);
                        }
                        return null;
                    });

            stageFutures.add(future);
        }

        CompletableFuture.allOf(stageFutures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * Returns {@code true} only if every declared parent of {@code node} has written a result
     * to the context and has NOT failed. A partial set of parent results is never accepted.
     */
    private boolean allParentsSucceeded(
            DownstreamRegistration node,
            ExecutionContext context,
            Map<String, Throwable> failures) {
        for (String parentId : node.getDependsOn()) {
            if (!context.hasResult(parentId) || failures.containsKey(parentId)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reflectively invokes the downstream method using the first matching
     * {@link DownstreamArgumentResolver}. On success, writes the result to the context.
     * On unhandled exception, records the failure and optionally triggers fail-fast.
     *
     * <p>Developers who catch exceptions inside their downstream method and return a fallback
     * result bypass this failure path entirely. The framework treats such methods as successful.
     * This is intentional and documented in the spec.
     */
    private void invokeDownstream(
            DownstreamRegistration node,
            ExecutionContext context,
            Map<String, Throwable> failures,
            Map<String, Long> elapsedMs,
            boolean failFast,
            AtomicBoolean hardFailed) {
        long start = System.currentTimeMillis();
        try {
            Method method = node.getMethod();
            method.setAccessible(true);
            Object[] args = resolveArguments(method, node.getBeanType(), context);
            Object result = method.invoke(node.getBean(), args);
            long elapsed = System.currentTimeMillis() - start;
            // Guard against recording success after a concurrent timeout has already fired.
            // The orTimeout exceptionally-handler uses putIfAbsent, so if it ran first the
            // node will already be in failures and we should not overwrite it.
            if (!failures.containsKey(node.getId())) {
                elapsedMs.put(node.getId(), elapsed);
                context.put(node.getId(), result);
                metricsRecorder.recordSuccess(node.getId(), elapsed);
            }
        } catch (InvocationTargetException e) {
            long elapsed = System.currentTimeMillis() - start;
            // putIfAbsent: a concurrent timeout may have already claimed the failure slot
            if (failures.putIfAbsent(node.getId(), e.getCause()) == null) {
                metricsRecorder.recordFailure(node.getId(), "error", elapsed);
            }
            if (failFast) {
                hardFailed.set(true);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot invoke @Downstream method '" + node.getMethod().getName()
                            + "'. Ensure the method is public.", e);
        }
    }

    /**
     * Finds the first {@link DownstreamArgumentResolver} that supports the method and
     * resolves its arguments. Throws {@link IllegalStateException} if none matches —
     * this is a configuration error that should have been caught at startup by
     * {@link com.lazyresponse.registry.DownstreamRegistryBeanPostProcessor}.
     */
    private Object[] resolveArguments(Method method, Class<?> beanType, ExecutionContext ctx) {
        return argumentResolvers.stream()
                .filter(r -> r.supports(method, beanType))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No DownstreamArgumentResolver supports @Downstream method '"
                                + method.toGenericString() + "' on '" + beanType.getSimpleName()
                                + "'. This should have been caught at startup."))
                .resolve(method, ctx);
    }

    /**
     * Assembles the final {@link LazyApiResponse} from the execution context and failure map.
     *
     * <p>For each downstream referenced in the template (within scope):
     * <ul>
     *   <li>If the downstream succeeded: filter its result to only the requested fields</li>
     *   <li>If it failed or was blocked: apply {@code @Default} values for configured fields,
     *       {@code null} for unconfigured fields; emit a {@link FieldError} for each null field</li>
     * </ul>
     */
    private LazyApiResponse assembleResponse(
            Map<String, Object> template,
            Set<String> scopedIds,
            ExecutionContext context,
            Map<String, Throwable> failures,
            Map<String, Long> elapsedMs) {

        Map<String, Object> data = new LinkedHashMap<>();
        List<FieldError> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean anyFailed = false;

        long warningThreshold = properties.getTimeout().getWarningThreshold();

        for (Map.Entry<String, Object> templateEntry : template.entrySet()) {
            String downstreamId = templateEntry.getKey();
            if (!registry.isRegistered(downstreamId)) {
                continue;
            }
            // Honour the endpoint scope — skip out-of-scope downstreams
            if (!scopedIds.isEmpty() && !scopedIds.contains(downstreamId)) {
                continue;
            }

            DownstreamRegistration reg = registry.getGraph().getNodes().get(downstreamId);
            boolean failed = isFailedOrBlocked(downstreamId, reg, context, failures);

            // A downstream that ran and returned null is treated as failed for the purposes of
            // default-value application. The caller declared @Default for a reason — null results
            // should not silently suppress those defaults.
            if (!failed && context.hasResult(downstreamId)
                    && context.get(downstreamId, Object.class) == null) {
                failed = true;
                failures.putIfAbsent(downstreamId, new NullPointerException("downstream returned null"));
            }

            if (failed) {
                anyFailed = true;
                String reason = resolveFailureReason(downstreamId, reg, failures);
                if (!failures.containsKey(downstreamId)) {
                    // blocked/dependency-failed — downstream never ran, elapsed time is 0
                    metricsRecorder.recordFailure(downstreamId, reason, 0L);
                }
                Map<String, Object> downstreamData = assembleFailedDownstream(
                        downstreamId, reg, templateEntry.getValue(), failures, errors);
                data.put(downstreamId, downstreamData);
            } else {
                Object result = context.get(downstreamId, Object.class);
                Map<String, Object> resultMap = objectMapper.convertValue(result, MAP_TYPE);
                data.put(downstreamId, filterByTemplate(resultMap, templateEntry.getValue()));

                if (warningThreshold > 0) {
                    Long elapsed = elapsedMs.get(downstreamId);
                    if (elapsed != null && elapsed > warningThreshold) {
                        warnings.add("downstream '" + downstreamId + "' responded in "
                                + elapsed + "ms, exceeding the soft threshold of "
                                + warningThreshold + "ms");
                    }
                }
            }
        }

        return new LazyApiResponse(data, new LazyMeta(errors, warnings, anyFailed));
    }

    /**
     * Returns {@code true} if this downstream failed directly, timed out, or had a parent
     * that failed (making it blocked).
     */
    private boolean isFailedOrBlocked(
            String downstreamId,
            DownstreamRegistration reg,
            ExecutionContext context,
            Map<String, Throwable> failures) {
        if (failures.containsKey(downstreamId)) {
            return true;
        }
        if (!context.hasResult(downstreamId)) {
            return true;
        }
        for (String parentId : reg.getDependsOn()) {
            if (failures.containsKey(parentId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the data map for a failed downstream. Fields with a configured {@code @Default}
     * receive that value coerced to the field's declared Java type. Fields without a default
     * receive {@code null} and generate a {@link FieldError}.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> assembleFailedDownstream(
            String downstreamId,
            DownstreamRegistration reg,
            Object templateSpec,
            Map<String, Throwable> failures,
            List<FieldError> errors) {

        List<String> requestedFields = flattenRequestedFields(templateSpec);
        String reason = resolveFailureReason(downstreamId, reg, failures);
        Map<String, Object> downstreamData = new LinkedHashMap<>();

        for (String field : requestedFields) {
            String defaultValue = reg.getDefaults().get(field);
            if (defaultValue == null) {
                downstreamData.put(field, null);
                errors.add(new FieldError(downstreamId, field, reason));
            } else {
                downstreamData.put(field, coerceDefault(defaultValue, reg.getFieldTypes().get(field)));
            }
        }
        return downstreamData;
    }

    /**
     * Coerces a {@code @Default} string value to the field's declared Java type using
     * Jackson's {@link ObjectMapper#convertValue}. Falls back to the raw string if the
     * target type is unknown or conversion fails.
     *
     * <p>This ensures that {@code @Default(field = "active", value = "true")} produces
     * JSON {@code true} (boolean) rather than {@code "true"} (string), and that numeric
     * defaults round-trip correctly.
     */
    private Object coerceDefault(String value, Class<?> targetType) {
        if (targetType == null || targetType == String.class) {
            return value;
        }
        try {
            return objectMapper.convertValue(value, targetType);
        } catch (IllegalArgumentException e) {
            // Declared type doesn't accept the string — return as-is and let the caller decide
            return value;
        }
    }

    /**
     * Recursively filters a downstream result map to include only the fields specified in
     * the template value.
     *
     * <ul>
     *   <li>If {@code templateSpec} is {@code null}: return the entire result map (all fields)</li>
     *   <li>If {@code templateSpec} is a {@code List<String>}: filter to those field names</li>
     *   <li>If {@code templateSpec} is a {@code Map}: recurse into nested structures</li>
     * </ul>
     *
     * <p>A {@code null} template spec — produced when the client sends
     * {@code "template": {"order": null}} — is treated as "give me all fields" for that
     * downstream, rather than "give me nothing". This matches the principle of least surprise.
     */
    @SuppressWarnings("unchecked")
    private Object filterByTemplate(Map<String, Object> resultMap, Object templateSpec) {
        if (templateSpec == null) {
            // null means "all fields" — return the full result map unfiltered
            return resultMap != null ? resultMap : Collections.emptyMap();
        }

        if (templateSpec instanceof List<?> fieldList) {
            Map<String, Object> filtered = new LinkedHashMap<>();
            for (String field : (List<String>) fieldList) {
                filtered.put(field, resultMap != null ? resultMap.get(field) : null);
            }
            return filtered;
        }

        if (templateSpec instanceof Map<?, ?> nestedTemplate) {
            Map<String, Object> filtered = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) nestedTemplate).entrySet()) {
                Object subResult = resultMap != null ? resultMap.get(entry.getKey()) : null;
                if (subResult instanceof Map<?, ?> subMap) {
                    filtered.put(entry.getKey(), filterByTemplate((Map<String, Object>) subMap, entry.getValue()));
                } else {
                    filtered.put(entry.getKey(), filterByTemplate(null, entry.getValue()));
                }
            }
            return filtered;
        }

        return Collections.emptyMap();
    }

    /**
     * Flattens the template spec into a list of leaf field names for error reporting and
     * default value application in the failure path.
     */
    @SuppressWarnings("unchecked")
    private List<String> flattenRequestedFields(Object templateSpec) {
        if (templateSpec instanceof List<?> list) {
            return (List<String>) list;
        }
        if (templateSpec instanceof Map<?, ?> map) {
            List<String> fields = new ArrayList<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) map).entrySet()) {
                fields.addAll(flattenRequestedFields(entry.getValue()));
            }
            return fields;
        }
        return Collections.emptyList();
    }

    private String resolveFailureReason(
            String downstreamId,
            DownstreamRegistration reg,
            Map<String, Throwable> failures) {
        Throwable direct = failures.get(downstreamId);
        if (direct != null) {
            return direct instanceof TimeoutException ? "timeout" : "error";
        }
        for (String parentId : reg.getDependsOn()) {
            if (failures.containsKey(parentId)) {
                return "dependency-failed";
            }
        }
        return "blocked";
    }

    private Throwable unwrap(Throwable ex) {
        return ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
    }

    private LazyApiResponse emptyResponse() {
        return new LazyApiResponse(
                Collections.emptyMap(),
                new LazyMeta(Collections.emptyList(), Collections.emptyList(), false));
    }

    /**
     * Outcome of a single orchestrator execution. Distinguishes between success, semaphore
     * exhaustion (503), downstream error in fail-fast mode (502), and timeout in fail-fast
     * mode (503). Only the {@code SUCCESS} status carries a populated response.
     */
    public static final class OrchestratorResult {

        public enum Status {
            SUCCESS,
            SEMAPHORE_EXHAUSTED,
            DOWNSTREAM_ERROR,
            TIMEOUT
        }

        private final Status status;
        private final LazyApiResponse response;

        private OrchestratorResult(Status status, LazyApiResponse response) {
            this.status = status;
            this.response = response;
        }

        public static OrchestratorResult success(LazyApiResponse response) {
            return new OrchestratorResult(Status.SUCCESS, response);
        }

        public static OrchestratorResult semaphoreExhausted() {
            return new OrchestratorResult(Status.SEMAPHORE_EXHAUSTED, null);
        }

        public static OrchestratorResult hardFailure(boolean isTimeout) {
            return new OrchestratorResult(isTimeout ? Status.TIMEOUT : Status.DOWNSTREAM_ERROR, null);
        }

        public Status getStatus() {
            return status;
        }

        public LazyApiResponse getResponse() {
            return response;
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }
    }
}
