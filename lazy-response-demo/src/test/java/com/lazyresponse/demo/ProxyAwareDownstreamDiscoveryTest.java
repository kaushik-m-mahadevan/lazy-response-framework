package com.lazyresponse.demo;

import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.annotation.LazyResponse;
import com.lazyresponse.autoconfigure.LazyResponseAutoConfiguration;
import com.lazyresponse.context.ExecutionContext;
import com.lazyresponse.demo.model.request.OrderDetailRequest;
import com.lazyresponse.registry.DownstreamRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies spec success criterion 6: {@code @Downstream} methods are discovered correctly
 * even when the declaring bean is wrapped in an AOP proxy.
 *
 * <p>A custom {@link TimingAspect} wraps all public methods of {@link ProxiedDownstreamService}.
 * Spring therefore hands the framework a CGLIB proxy, not the raw bean. The framework must
 * use {@link org.springframework.aop.support.AopUtils#getTargetClass(Object)} to unwrap the
 * proxy and find the real {@code @Downstream} annotations.
 *
 * <p>Two assertions are made:
 * <ol>
 *   <li>The registry correctly registers the downstream from the proxied bean.</li>
 *   <li>An HTTP request returns the expected data — the downstream was actually invoked
 *       through the proxy, confirming end-to-end correctness.</li>
 * </ol>
 */
@SpringBootTest(classes = {
        ProxyAwareDownstreamDiscoveryTest.TestController.class,
        ProxyAwareDownstreamDiscoveryTest.ProxiedConfig.class,
        TestSecurityConfig.class
})
@AutoConfigureMockMvc
@WithMockUser
@ImportAutoConfiguration({
        AopAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        LazyResponseAutoConfiguration.class
})
class ProxyAwareDownstreamDiscoveryTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DownstreamRegistry registry;
    @Autowired private ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // 1. Registry correctly discovers @Downstream through proxy
    // -------------------------------------------------------------------------

    @Test
    void downstreamRegistered_despiteAopProxy() {
        assertThat(registry.isRegistered("order")).isTrue();
        assertThat(registry.isRegistered("account")).isTrue();
        assertThat(registry.isRegistered("payment")).isTrue();
    }

    // -------------------------------------------------------------------------
    // 2. Downstream actually invoked through proxy — aspect increments counter
    // -------------------------------------------------------------------------

    @Test
    void downstreamInvokedThroughProxy_aspectInterceptsCall() throws Exception {
        ProxiedConfig.TimingAspect.callCount.set(0);

        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "request", Map.of("orderId", "ORD-001", "accountId", "ACC-001"),
                                "template", Map.of("order", new String[]{"id", "status"})
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.id").value("ORD-001"));

        // The timing aspect must have intercepted at least the order downstream call
        assertThat(ProxiedConfig.TimingAspect.callCount.get()).isGreaterThanOrEqualTo(1);
    }

    @RestController
    @RequestMapping("/api/orders")
    static class TestController {
        @LazyResponse
        @PostMapping("/detail")
        public ResponseEntity<?> getDetail(OrderDetailRequest request) { return null; }
    }

    // -------------------------------------------------------------------------
    // Configuration: a @Downstream bean wrapped by a custom AOP aspect
    // -------------------------------------------------------------------------

    @Configuration
    @EnableAspectJAutoProxy
    static class ProxiedConfig {

        @Bean
        ProxiedDownstreamService proxiedDownstreamService() {
            return new ProxiedDownstreamService();
        }

        @Bean
        TimingAspect timingAspect() {
            return new TimingAspect();
        }

        /** A CGLIB-proxied Spring bean with @Downstream methods. */
        public static class ProxiedDownstreamService {

            @Downstream(id = "order", fields = {"id", "status"}, chainTimeout = 2000)
            public Map<String, Object> fetchOrder(ExecutionContext ctx) {
                OrderDetailRequest req = ctx.getRequest(OrderDetailRequest.class);
                return Map.of("id", req.getOrderId(), "status", "CONFIRMED");
            }

            @Downstream(id = "account", fields = {"name"})
            public Map<String, Object> fetchAccount(ExecutionContext ctx) {
                return Map.of("name", "Test User");
            }

            @Downstream(id = "payment", fields = {"status"}, dependsOn = {"order"}, timeout = 500)
            public Map<String, Object> fetchPayment(ExecutionContext ctx) {
                return Map.of("status", "PAID");
            }
        }

        /**
         * A custom AOP aspect that wraps all public methods of {@link ProxiedDownstreamService}.
         * Its presence forces Spring to create a CGLIB proxy for that bean.
         * {@code callCount} lets tests assert the proxy is actually in the call chain.
         */
        @Aspect
        public static class TimingAspect {
            static final AtomicInteger callCount = new AtomicInteger(0);

            @Around("execution(public * com.lazyresponse.demo.ProxyAwareDownstreamDiscoveryTest.ProxiedConfig.ProxiedDownstreamService.*(..))")
            public Object time(ProceedingJoinPoint pjp) throws Throwable {
                callCount.incrementAndGet();
                return pjp.proceed();
            }
        }
    }
}
