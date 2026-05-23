package com.lazyresponse.demo;

import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.autoconfigure.LazyResponseAutoConfiguration;
import com.lazyresponse.context.ExecutionContext;
import com.lazyresponse.executor.ExecutionPlanner;
import com.lazyresponse.executor.LazyResponseOrchestrator;
import com.lazyresponse.graph.LazyGraphController;
import com.lazyresponse.interceptor.LazyResponseAspect;
import com.lazyresponse.registry.DownstreamRegistry;
import com.lazyresponse.swagger.LazyResponseSwaggerContributor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import com.lazyresponse.executor.LazyResponseOrchestrator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Framework-level auto-configuration tests using {@link WebApplicationContextRunner}.
 *
 * <p>Boots a minimal Spring context per test  -  no server starts. Tests bean registration,
 * conditional behaviour, YAML property binding, and startup validation (cycle detection,
 * duplicate ids, unresolved references). This is the correct tool for testing framework
 * internals: fast, isolated, and gives clean assertions on startup failures.
 */
class LazyResponseAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    LazyResponseAutoConfiguration.class
            ));

    // -------------------------------------------------------------------------
    // Bean registration
    // -------------------------------------------------------------------------

    @Test
    void registersAllCoreBeans() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(DownstreamRegistry.class);
            assertThat(ctx).hasSingleBean(ExecutionPlanner.class);
            assertThat(ctx).hasSingleBean(LazyResponseOrchestrator.class);
            assertThat(ctx).hasSingleBean(LazyResponseAspect.class);
            assertThat(ctx).hasSingleBean(LazyGraphController.class);
            assertThat(ctx).hasSingleBean(ThreadPoolTaskExecutor.class);
            assertThat(ctx).hasSingleBean(Semaphore.class);
        });
    }

    @Test
    void registersRequestBodyCachingFilterAsFilterRegistrationBean() {
        // The framework registers a FilterRegistrationBean<RequestBodyCachingFilter>,
        // not a bare RequestBodyCachingFilter  -  assert on the wrapper type.
        contextRunner.run(ctx ->
                assertThat(ctx).hasSingleBean(FilterRegistrationBean.class));
    }

    @Test
    void registersSwaggerContributorWhenSpringdocPresent() {
        contextRunner.run(ctx ->
                assertThat(ctx).hasSingleBean(LazyResponseSwaggerContributor.class));
    }

    @Test
    void doesNotRegisterSwaggerContributorWhenSpringdocAbsent() {
        contextRunner
                .withClassLoader(new FilteredClassLoader("io.swagger.v3.oas.models.OpenAPI"))
                .run(ctx ->
                        assertThat(ctx).doesNotHaveBean(LazyResponseSwaggerContributor.class));
    }

    // -------------------------------------------------------------------------
    // YAML property defaults
    // -------------------------------------------------------------------------

    @Test
    void defaultFailureStrategyIsSilent() {
        contextRunner.run(ctx -> {
            var props = ctx.getBean(com.lazyresponse.config.LazyResponseProperties.class);
            assertThat(props.getFailureStrategy()).isEqualTo("silent");
        });
    }

    @Test
    void defaultGlobalTimeoutIs3000ms() {
        contextRunner.run(ctx -> {
            var props = ctx.getBean(com.lazyresponse.config.LazyResponseProperties.class);
            assertThat(props.getTimeout().getGlobal()).isEqualTo(3000L);
        });
    }

    @Test
    void defaultPoolSizeIs20() {
        contextRunner.run(ctx -> {
            var props = ctx.getBean(com.lazyresponse.config.LazyResponseProperties.class);
            assertThat(props.getExecutor().getPoolSize()).isEqualTo(20);
        });
    }

    @Test
    void yamlOverridesAreApplied() {
        contextRunner
                .withPropertyValues(
                        "lazy-response.failure-strategy=fail-fast",
                        "lazy-response.timeout.global=1000",
                        "lazy-response.executor.pool-size=5"
                )
                .run(ctx -> {
                    var props = ctx.getBean(com.lazyresponse.config.LazyResponseProperties.class);
                    assertThat(props.getFailureStrategy()).isEqualTo("fail-fast");
                    assertThat(props.getTimeout().getGlobal()).isEqualTo(1000L);
                    assertThat(props.getExecutor().getPoolSize()).isEqualTo(5);
                });
    }

    @Test
    void semaphoreIsInitialisedToPoolSize() {
        contextRunner
                .withPropertyValues("lazy-response.executor.pool-size=7")
                .run(ctx -> {
                    Semaphore semaphore = ctx.getBean(Semaphore.class);
                    assertThat(semaphore.availablePermits()).isEqualTo(7);
                });
    }

    @Test
    void semaphoreExhausted_orchestratorReturnsSemaphoreExhaustedStatus() {
        // Verifies spec section 5.1: when permits are unavailable the request is
        // rejected immediately with SEMAPHORE_EXHAUSTED  -  no downstream is invoked.
        contextRunner
                .withUserConfiguration(ValidDownstreamsConfig.class)
                .run(ctx -> {
                    Semaphore semaphore = ctx.getBean(Semaphore.class);
                    semaphore.drainPermits();

                    LazyResponseOrchestrator orchestrator = ctx.getBean(LazyResponseOrchestrator.class);
                    LazyResponseOrchestrator.OrchestratorResult result =
                            orchestrator.execute(new Object(), Map.of("alpha", List.of("x")));

                    assertThat(result.getStatus())
                            .isEqualTo(LazyResponseOrchestrator.OrchestratorResult.Status.SEMAPHORE_EXHAUSTED);
                });
    }

    // -------------------------------------------------------------------------
    // @ConditionalOnMissingBean  -  consumer override
    // -------------------------------------------------------------------------

    @Test
    void consumerCanOverrideDownstreamRegistry() {
        contextRunner
                .withUserConfiguration(CustomRegistryConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DownstreamRegistry.class);
                    assertThat(ctx.getBean(DownstreamRegistry.class))
                            .isSameAs(ctx.getBean("customRegistry"));
                });
    }

    // -------------------------------------------------------------------------
    // Startup validation  -  cycle detection
    // -------------------------------------------------------------------------

    @Test
    void failsToStartWhenCycleDetected() {
        contextRunner
                .withUserConfiguration(CyclicDownstreamsConfig.class)
                .run(ctx ->
                        assertThat(ctx).hasFailed()
                                .getFailure()
                                .hasMessageContaining("Cycle detected"));
    }

    // -------------------------------------------------------------------------
    // Startup validation  -  duplicate downstream id
    // -------------------------------------------------------------------------

    @Test
    void failsToStartWhenDuplicateDownstreamIdRegistered() {
        contextRunner
                .withUserConfiguration(DuplicateIdDownstreamsConfig.class)
                .run(ctx ->
                        assertThat(ctx).hasFailed()
                                .getFailure()
                                .hasMessageContaining("Duplicate @Downstream id"));
    }

    // -------------------------------------------------------------------------
    // Startup validation  -  unresolved dependsOn reference
    // -------------------------------------------------------------------------

    @Test
    void failsToStartWhenDependsOnReferencesUnknownId() {
        contextRunner
                .withUserConfiguration(BrokenDependencyConfig.class)
                .run(ctx ->
                        assertThat(ctx).hasFailed()
                                .getFailure()
                                .hasMessageContaining("no @Downstream with that id is registered"));
    }

    // -------------------------------------------------------------------------
    // Downstream registration
    // -------------------------------------------------------------------------

    @Test
    void downstreamsAreRegisteredFromSpringBeans() {
        contextRunner
                .withUserConfiguration(ValidDownstreamsConfig.class)
                .run(ctx -> {
                    DownstreamRegistry registry = ctx.getBean(DownstreamRegistry.class);
                    assertThat(registry.isRegistered("alpha")).isTrue();
                    assertThat(registry.isRegistered("beta")).isTrue();
                    assertThat(registry.isRegistered("gamma")).isFalse();
                });
    }

    // -------------------------------------------------------------------------
    // Supporting inner configurations
    // -------------------------------------------------------------------------

    @Configuration
    static class CustomRegistryConfig {
        @Bean(name = "customRegistry")
        DownstreamRegistry customRegistry() {
            return new DownstreamRegistry();
        }
    }

    @Configuration
    static class CyclicDownstreamsConfig {
        @Downstream(id = "x", fields = {"f"}, dependsOn = {"y"})
        public String x(ExecutionContext ctx) { return "x"; }

        @Downstream(id = "y", fields = {"f"}, dependsOn = {"x"})
        public String y(ExecutionContext ctx) { return "y"; }
    }

    @Configuration
    static class DuplicateIdDownstreamsConfig {
        @Downstream(id = "dupe", fields = {"a"})
        public String first(ExecutionContext ctx) { return "first"; }

        @Downstream(id = "dupe", fields = {"b"})
        public String second(ExecutionContext ctx) { return "second"; }
    }

    @Configuration
    static class BrokenDependencyConfig {
        @Downstream(id = "orphan", fields = {"a"}, dependsOn = {"ghost"})
        public String orphan(ExecutionContext ctx) { return "orphan"; }
    }

    @Configuration
    static class ValidDownstreamsConfig {
        @Downstream(id = "alpha", fields = {"x", "y"})
        public String alpha(ExecutionContext ctx) { return "alpha"; }

        @Downstream(id = "beta", fields = {"z"}, dependsOn = {"alpha"})
        public String beta(ExecutionContext ctx) { return "beta"; }
    }
}
