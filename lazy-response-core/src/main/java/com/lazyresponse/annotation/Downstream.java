package com.lazyresponse.annotation;

import java.lang.annotation.*;

/**
 * Marks a Spring bean method as a downstream resolver in the Lazy Response Framework.
 *
 * <p>Every annotated method must declare exactly one parameter of type
 * {@link com.lazyresponse.context.ExecutionContext}. The framework invokes the method
 * when its {@link #id()} is referenced in the request's field selection template and all
 * declared {@link #dependsOn()} parents have completed successfully.
 *
 * <p>The framework scans all Spring beans for {@code @Downstream} methods at startup via
 * {@link org.springframework.aop.support.AopUtils} and
 * {@link org.springframework.core.annotation.AnnotationUtils} to correctly resolve
 * annotations through AOP proxies.
 *
 * <h3>Timeout priority (highest to lowest):</h3>
 * <ol>
 *   <li>{@link #timeout()} — per-node override</li>
 *   <li>{@link #chainTimeout()} — inherited from root ancestor(s); most conservative wins</li>
 *   <li>Global YAML {@code lazy-response.timeout.global}</li>
 * </ol>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Downstream {

    /**
     * Unique identifier for this downstream. Maps to the top-level key in the field selection
     * template. Must be unique across all registered downstreams in the application context.
     */
    String id();

    /**
     * The fields this downstream can populate. Drives the Swagger contract and is used to
     * filter the assembled response. Fields absent from the caller's template are never
     * included in the response, even if populated by this downstream.
     */
    String[] fields() default {};

    /**
     * IDs of downstreams that must all complete successfully before this downstream executes.
     * An empty array means this downstream is independent and fires in the first parallel stage.
     *
     * <p>If any declared parent fails or times out, this downstream is blocked entirely.
     * A partial set of parent results is never passed to a child.
     */
    String[] dependsOn() default {};

    /**
     * Chain timeout budget in milliseconds, rooted at this downstream. Meaningful only on
     * root nodes (those with empty {@link #dependsOn()}). Inherited by all descendants in
     * this node's chain. When a node has multiple parent chains, the most conservative
     * (lowest) {@code chainTimeout} applies.
     *
     * <p>{@code 0} means not set — descendants inherit from the global YAML default.
     */
    long chainTimeout() default 0;

    /**
     * Per-node timeout in milliseconds. Applies to this node only. Takes precedence over
     * both {@link #chainTimeout()} and the global YAML default. {@code 0} means not set.
     */
    long timeout() default 0;

    /**
     * Field-level fallback values applied when this downstream fails under the {@code silent}
     * failure strategy. Fields not listed here return {@code null} on failure and generate
     * an entry in {@code meta.errors}.
     */
    Default[] defaults() default {};
}
