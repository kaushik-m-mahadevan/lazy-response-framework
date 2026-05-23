package com.lazyresponse.annotation;

import java.lang.annotation.*;

/**
 * Marks a Spring MVC controller method as a lazy-template-driven endpoint.
 *
 * <p>The framework intercepts this method via AOP and owns request handling entirely.
 * The method body is never executed. The method's first parameter type is read via
 * reflection to determine the inner request POJO type expected inside the
 * {@code {"request": ..., "template": ...}} body wrapper.
 *
 * <h3>Controller method contract:</h3>
 * <ul>
 *   <li>Must be a {@code POST} endpoint ({@code @PostMapping})</li>
 *   <li>Must declare return type {@code ResponseEntity<?>}</li>
 *   <li>Must declare exactly one parameter whose type matches the {@code request} POJO type</li>
 *   <li>The parameter must NOT carry {@code @RequestBody}  -  the framework reads the body directly</li>
 * </ul>
 *
 * <p>All constraints are validated at startup. Violations throw
 * {@link com.lazyresponse.exception.ApplicationStartupException}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LazyResponse {

    /**
     * The bean classes whose {@code @Downstream} methods are in scope for this endpoint.
     *
     * <p>If left empty (the default), all registered downstreams are available  -  suitable for
     * single-endpoint applications or during initial development. For applications with multiple
     * {@code @LazyResponse} endpoints, declaring this attribute prevents downstream ID collisions
     * and ensures each endpoint executes only its own graph.
     *
     * <pre>{@code
     * @LazyResponse(downstreams = {OrderDetailDownstreams.class})
     * @PostMapping("/api/orders/detail")
     * public ResponseEntity<?> getOrderDetail(OrderDetailRequest request) { ... }
     * }</pre>
     */
    Class<?>[] downstreams() default {};
}
