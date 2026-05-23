package com.lazyresponse.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazyresponse.config.LazyResponseProperties;
import com.lazyresponse.executor.ExecutionPlanner;
import com.lazyresponse.executor.LazyResponseOrchestrator;
import com.lazyresponse.executor.MdcDelegatingExecutor;
import com.lazyresponse.filter.RequestBodyCachingFilter;
import com.lazyresponse.graph.LazyGraphController;
import com.lazyresponse.health.LazyResponseHealthIndicator;
import com.lazyresponse.interceptor.LazyResponseAspect;
import com.lazyresponse.metrics.LazyResponseMetrics;
import com.lazyresponse.registry.DownstreamRegistry;
import com.lazyresponse.registry.DownstreamRegistryBeanPostProcessor;
import com.lazyresponse.spi.DownstreamArgumentResolver;
import com.lazyresponse.spi.DownstreamMetricsRecorder;
import com.lazyresponse.spi.ExecutionContextArgumentResolver;
import com.lazyresponse.swagger.LazyResponseSwaggerContributor;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/**
 * Spring Boot auto-configuration for the Lazy Response Framework.
 *
 * <p>Activated automatically via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * Adding the framework dependency to {@code pom.xml} is sufficient to activate the entire
 * framework  -  no additional configuration required.
 *
 * <h3>Beans registered:</h3>
 * <ul>
 *   <li>{@link DownstreamRegistry}  -  central registry of all discovered downstreams</li>
 *   <li>{@link DownstreamRegistryBeanPostProcessor}  -  scans beans for {@code @Downstream} methods</li>
 *   <li>{@link ThreadPoolTaskExecutor}  -  thread pool for concurrent downstream execution</li>
 *   <li>{@link Semaphore}  -  backpressure mechanism; sized to the pool's thread count</li>
 *   <li>{@link ExecutionPlanner}  -  builds per-request execution plans from the graph + template</li>
 *   <li>{@link LazyResponseOrchestrator}  -  executes plans, manages concurrency, assembles responses</li>
 *   <li>{@link LazyResponseAspect}  -  AOP interceptor for {@code @LazyResponse} controller methods</li>
 *   <li>{@link RequestBodyCachingFilter}  -  ensures the request body can be read by the aspect</li>
 *   <li>{@link LazyGraphController}  -  serves the {@code /lazy/graph} visualisation endpoint</li>
 *   <li>{@link LazyResponseSwaggerContributor}  -  enriches OpenAPI docs (conditional on springdoc)</li>
 *   <li>{@link ExecutionContextArgumentResolver}  -  built-in resolver for {@code (ExecutionContext)} methods</li>
 *   <li>{@link LazyResponseMetrics}  -  Micrometer metrics (conditional on {@code micrometer-core})</li>
 *   <li>{@link LazyResponseHealthIndicator}  -  Actuator health (conditional on {@code spring-boot-actuate})</li>
 * </ul>
 *
 * <p>All beans use {@link ConditionalOnMissingBean} so consumer applications can override
 * any component by declaring their own bean of the same type.
 *
 * <h3>Context propagation:</h3>
 * <ul>
 *   <li><b>MDC</b>  -  always propagated. {@link MdcDelegatingExecutor} captures the SLF4J
 *       MDC map on the request thread and restores it before each downstream task runs,
 *       ensuring log correlation IDs flow through to downstream log lines.</li>
 *   <li><b>Security context</b>  -  propagated when {@code spring-security-core} is on the
 *       classpath. {@code DelegatingSecurityContextExecutorService} wraps the raw thread pool
 *       so downstream methods can call {@code SecurityContextHolder.getContext()} safely.</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(LazyResponseProperties.class)
public class LazyResponseAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DownstreamRegistry downstreamRegistry() {
        return new DownstreamRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public static DownstreamRegistryBeanPostProcessor downstreamRegistryBeanPostProcessor() {
        return new DownstreamRegistryBeanPostProcessor();
    }

    /**
     * Thread pool for downstream task execution.
     * Bean name {@code lazyResponseExecutor} is the spec-published name for consumer overrides.
     */
    @Bean(name = "lazyResponseExecutor")
    @ConditionalOnMissingBean(name = "lazyResponseExecutor")
    public ThreadPoolTaskExecutor lazyResponseExecutor(LazyResponseProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int poolSize = properties.getExecutor().getPoolSize();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(0); // No queue  -  the semaphore is the backpressure gate
        executor.setThreadNamePrefix("lazy-response-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    /**
     * Semaphore sized to the thread pool. Atomically gates new requests to prevent the pool
     * from being oversubscribed.
     */
    @Bean
    @ConditionalOnMissingBean
    public Semaphore lazyResponseSemaphore(LazyResponseProperties properties) {
        return new Semaphore(properties.getExecutor().getPoolSize(), true);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExecutionPlanner executionPlanner(DownstreamRegistry registry) {
        return new ExecutionPlanner(registry);
    }

    /**
     * Default argument resolver for {@code @Downstream} methods with the standard
     * {@code (ExecutionContext)} signature. Always registered. Custom resolvers declared
     * as beans are collected first in the injected {@code List<DownstreamArgumentResolver>}.
     */
    @Bean
    @ConditionalOnMissingBean(ExecutionContextArgumentResolver.class)
    public ExecutionContextArgumentResolver executionContextArgumentResolver() {
        return new ExecutionContextArgumentResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public LazyResponseOrchestrator lazyResponseOrchestrator(
            DownstreamRegistry registry,
            ExecutionPlanner planner,
            @Qualifier("lazyResponseTaskExecutor") Executor lazyResponseTaskExecutor,
            Semaphore lazyResponseSemaphore,
            LazyResponseProperties properties,
            ObjectMapper objectMapper,
            List<DownstreamArgumentResolver> argumentResolvers,
            DownstreamMetricsRecorder metricsRecorder) {
        return new LazyResponseOrchestrator(
                registry, planner, lazyResponseTaskExecutor,
                lazyResponseSemaphore, properties, objectMapper,
                argumentResolvers, metricsRecorder);
    }

    /** Bean name {@code lazyResponseInterceptor} matches the spec-published name. */
    @Bean(name = "lazyResponseInterceptor")
    @ConditionalOnMissingBean(name = "lazyResponseInterceptor")
    public LazyResponseAspect lazyResponseInterceptor(
            LazyResponseOrchestrator orchestrator,
            DownstreamRegistry registry,
            ObjectMapper objectMapper) {
        return new LazyResponseAspect(orchestrator, registry, objectMapper);
    }

    /**
     * Registers the body caching filter at the highest precedence so the request body is
     * always available to the AOP aspect, regardless of what other filters run first.
     */
    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<RequestBodyCachingFilter> requestBodyCachingFilter() {
        FilterRegistrationBean<RequestBodyCachingFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestBodyCachingFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * The dependency graph visualisation controller, registered only when
     * {@code lazy-response.graph.enabled=true} (the default).
     *
     * <p>Set {@code lazy-response.graph.enabled=false} to suppress the {@code /lazy/graph}
     * endpoint in environments where the downstream topology should not be externally visible.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lazy-response.graph", name = "enabled", havingValue = "true", matchIfMissing = true)
    public LazyGraphController lazyGraphController(DownstreamRegistry registry) {
        return new LazyGraphController(registry);
    }

    /** Only registered when springdoc-openapi is on the classpath. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(OpenAPI.class)
    public LazyResponseSwaggerContributor lazyResponseSwaggerContributor(DownstreamRegistry registry) {
        return new LazyResponseSwaggerContributor(registry);
    }

    // -------------------------------------------------------------------------
    // Task executor  -  MDC always applied; security context applied when present
    // -------------------------------------------------------------------------

    /**
     * Plain executor  -  no Spring Security on classpath.
     * Wraps the raw thread pool with MDC propagation only.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.springframework.security.core.context.SecurityContextHolder")
    static class PlainExecutorConfiguration {

        @Bean(name = "lazyResponseTaskExecutor")
        @ConditionalOnMissingBean(name = "lazyResponseTaskExecutor")
        public Executor lazyResponseTaskExecutor(
                @Qualifier("lazyResponseExecutor") ThreadPoolTaskExecutor lazyResponseExecutor) {
            return new MdcDelegatingExecutor(lazyResponseExecutor.getThreadPoolExecutor());
        }
    }

    /**
     * Security-aware executor  -  activated when {@code spring-security-core} is present.
     * Stacks MDC propagation on top of security context propagation:
     * <pre>
     *   MdcDelegatingExecutor → DelegatingSecurityContextExecutorService → ThreadPoolExecutor
     * </pre>
     * MDC is the outermost wrapper so it is captured closest to the call site and restored
     * first on the worker thread, after the security context is already in place.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.concurrent.DelegatingSecurityContextExecutorService")
    static class SecurityAwareExecutorConfiguration {

        @Bean(name = "lazyResponseTaskExecutor")
        @ConditionalOnMissingBean(name = "lazyResponseTaskExecutor")
        public Executor lazyResponseTaskExecutor(
                @Qualifier("lazyResponseExecutor") ThreadPoolTaskExecutor lazyResponseExecutor) {
            // Loaded via reflection to avoid a hard compile-time dependency in this source file.
            // The @ConditionalOnClass guarantees the class is present when this method runs.
            try {
                Class<?> delegatingClass = Class.forName(
                        "org.springframework.security.concurrent.DelegatingSecurityContextExecutorService");
                Executor withSecurity = (Executor) delegatingClass
                        .getConstructor(java.util.concurrent.ExecutorService.class)
                        .newInstance(lazyResponseExecutor.getThreadPoolExecutor());
                return new MdcDelegatingExecutor(withSecurity);
            } catch (ReflectiveOperationException e) {
                // Should never happen  -  @ConditionalOnClass guarantees the class exists
                return new MdcDelegatingExecutor(lazyResponseExecutor.getThreadPoolExecutor());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Metrics  -  Micrometer (conditional)
    // -------------------------------------------------------------------------

    /**
     * Activated when {@code micrometer-core} is on the classpath.
     * Registers per-downstream timers, a semaphore rejection counter, and a semaphore
     * availability gauge. All metrics are prefixed {@code lazy.response.*}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(DownstreamMetricsRecorder.class)
        @ConditionalOnBean(MeterRegistry.class)
        public LazyResponseMetrics lazyResponseMetrics(
                MeterRegistry meterRegistry,
                Semaphore lazyResponseSemaphore) {
            return new LazyResponseMetrics(meterRegistry, lazyResponseSemaphore);
        }
    }

    /**
     * NOOP recorder  -  registered when Micrometer is absent so the orchestrator always has
     * a non-null {@link DownstreamMetricsRecorder} to call without null-checks.
     */
    @Bean
    @ConditionalOnMissingBean(DownstreamMetricsRecorder.class)
    public DownstreamMetricsRecorder noopMetricsRecorder() {
        return DownstreamMetricsRecorder.NOOP;
    }

    // -------------------------------------------------------------------------
    // Health  -  Spring Boot Actuator (conditional)
    // -------------------------------------------------------------------------

    /**
     * Activated when {@code spring-boot-actuate} is on the classpath.
     * Reports semaphore availability at {@code /actuator/health/lazyResponse}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    static class HealthConfiguration {

        @Bean
        @ConditionalOnMissingBean(LazyResponseHealthIndicator.class)
        public LazyResponseHealthIndicator lazyResponseHealthIndicator(
                Semaphore lazyResponseSemaphore,
                LazyResponseProperties properties) {
            return new LazyResponseHealthIndicator(
                    lazyResponseSemaphore,
                    properties.getExecutor().getPoolSize());
        }
    }
}
