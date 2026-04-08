package com.lazyresponse.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazyresponse.annotation.LazyResponse;
import com.lazyresponse.executor.LazyResponseOrchestrator;
import com.lazyresponse.executor.LazyResponseOrchestrator.OrchestratorResult;
import com.lazyresponse.filter.CachedBodyHttpServletRequest;
import com.lazyresponse.model.LazyApiRequest;
import com.lazyresponse.registry.DownstreamRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AOP {@code @Around} advice that intercepts all {@link LazyResponse}-annotated controller
 * methods and delegates request handling entirely to the {@link LazyResponseOrchestrator}.
 *
 * <p>The annotated controller method body is never executed. The method's declared parameter
 * type is read via reflection to determine the inner request POJO type expected inside the
 * {@code {"request": ..., "template": ...}} body wrapper.
 *
 * <h3>Interception flow:</h3>
 * <ol>
 *   <li>Read the raw request body from the {@link CachedBodyHttpServletRequest} (installed by
 *       the framework's {@link com.lazyresponse.filter.RequestBodyCachingFilter})</li>
 *   <li>Deserialize the body as {@link LazyApiRequest}</li>
 *   <li>Re-deserialize the {@code request} value into the controller method's declared
 *       parameter type</li>
 *   <li>Resolve the endpoint's downstream scope from
 *       {@link LazyResponse#downstreams()} via the registry</li>
 *   <li>Execute the orchestrator with the resolved scope</li>
 *   <li>Map the {@link OrchestratorResult} to an appropriate {@link ResponseEntity}</li>
 * </ol>
 *
 * <p>The controller method is never called ({@code proceed()} is not invoked).
 */
@Aspect
public class LazyResponseAspect {

    private final LazyResponseOrchestrator orchestrator;
    private final DownstreamRegistry registry;
    private final ObjectMapper objectMapper;

    public LazyResponseAspect(
            LazyResponseOrchestrator orchestrator,
            DownstreamRegistry registry,
            ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(lazyResponse)")
    public Object intercept(ProceedingJoinPoint joinPoint, LazyResponse lazyResponse) throws Throwable {
        HttpServletRequest httpRequest = resolveHttpRequest();
        byte[] rawBody = readCachedBody(httpRequest);

        LazyApiRequest apiRequest = objectMapper.readValue(rawBody, LazyApiRequest.class);

        Class<?> requestPojoType = resolveRequestPojoType(joinPoint);
        Object requestPojo = objectMapper.convertValue(apiRequest.getRequest(), requestPojoType);

        Map<String, Object> template = apiRequest.getTemplate() != null
                ? apiRequest.getTemplate()
                : Collections.emptyMap();

        Set<String> scopedIds = resolveScopedIds(lazyResponse);

        OrchestratorResult result = orchestrator.execute(requestPojo, template, scopedIds);
        return toResponseEntity(result);
    }

    /**
     * Resolves the set of downstream IDs that are in scope for this endpoint.
     *
     * <p>When {@link LazyResponse#downstreams()} is non-empty, only the downstreams declared
     * by the listed bean classes are eligible. An empty array means no restriction — all
     * registered downstreams are in scope (equivalent to a global graph).
     */
    private Set<String> resolveScopedIds(LazyResponse lazyResponse) {
        Class<?>[] declaredTypes = lazyResponse.downstreams();
        if (declaredTypes.length == 0) {
            return Collections.emptySet();
        }
        Set<Class<?>> beanTypes = Arrays.stream(declaredTypes).collect(Collectors.toSet());
        return registry.getDownstreamIdsForBeanTypes(beanTypes);
    }

    private HttpServletRequest resolveHttpRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return attributes.getRequest();
    }

    /**
     * Reads the cached body bytes from the request. The request must have been wrapped by
     * {@link CachedBodyHttpServletRequest}. If it has not been wrapped (e.g., the framework
     * filter was bypassed), falls back to reading the input stream directly — which will fail
     * if the stream was already consumed by Spring MVC.
     */
    private byte[] readCachedBody(HttpServletRequest request) throws Exception {
        if (request instanceof CachedBodyHttpServletRequest cached) {
            return cached.getCachedBody();
        }
        return request.getInputStream().readAllBytes();
    }

    /**
     * Resolves the inner request POJO type from the controller method's sole declared parameter.
     * The parameter type tells the framework how to deserialize the {@code "request"} value
     * from the body wrapper.
     */
    private Class<?> resolveRequestPojoType(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            throw new IllegalStateException(
                    "@LazyResponse method '" + method.getName() + "' has no parameters. "
                            + "Declare one parameter matching the inner request POJO type.");
        }
        return parameters[0].getType();
    }

    /**
     * Maps the orchestrator result to an HTTP response.
     *
     * <ul>
     *   <li>{@code SUCCESS} → 200 with the assembled {@link com.lazyresponse.model.LazyApiResponse}</li>
     *   <li>{@code SEMAPHORE_EXHAUSTED} → 503 (thread pool at capacity, request rejected)</li>
     *   <li>{@code TIMEOUT} → 503 (fail-fast mode: at least one downstream timed out)</li>
     *   <li>{@code DOWNSTREAM_ERROR} → 502 (fail-fast mode: at least one downstream errored)</li>
     * </ul>
     */
    private ResponseEntity<?> toResponseEntity(OrchestratorResult result) {
        return switch (result.getStatus()) {
            case SUCCESS -> ResponseEntity.ok(result.getResponse());
            case SEMAPHORE_EXHAUSTED -> ResponseEntity.status(503).build();
            case TIMEOUT -> ResponseEntity.status(503).build();
            case DOWNSTREAM_ERROR -> ResponseEntity.status(502).build();
        };
    }
}
