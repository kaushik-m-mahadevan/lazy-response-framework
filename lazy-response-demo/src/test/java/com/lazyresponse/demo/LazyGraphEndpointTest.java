package com.lazyresponse.demo;

import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.annotation.LazyResponse;
import com.lazyresponse.autoconfigure.LazyResponseAutoConfiguration;
import com.lazyresponse.context.ExecutionContext;
import com.lazyresponse.demo.model.request.OrderDetailRequest;
import com.lazyresponse.exception.ApplicationStartupException;
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
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-level tests for the {@code GET /lazy/graph} endpoint and @LazyResponse
 * controller method contract validation at startup.
 */
@SpringBootTest(classes = {
        LazyGraphEndpointTest.TestController.class,
        LazyGraphEndpointTest.MinimalDownstreams.class,
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
class LazyGraphEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // 1. /lazy/graph  -  200 with HTML containing Mermaid markup
    // -------------------------------------------------------------------------

    @Test
    void lazyGraphEndpoint_returns200_withMermaidHtml() throws Exception {
        mockMvc.perform(get("/lazy/graph"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("mermaid")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("flowchart TD")));
    }

    @Test
    void lazyGraphEndpoint_includesRegisteredDownstreamIds() throws Exception {
        mockMvc.perform(get("/lazy/graph"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("order")));
    }

    // -------------------------------------------------------------------------
    // 2. Startup validation  -  @LazyResponse method contract violations
    //    (tested via WebApplicationContextRunner  -  no server start required)
    // -------------------------------------------------------------------------

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    LazyResponseAutoConfiguration.class
            ));

    @Test
    void failsToStart_whenLazyResponseMethodHasRequestBodyAnnotation() {
        runner.withUserConfiguration(RequestBodyOnParamConfig.class)
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasMessageContaining("@RequestBody"));
    }

    @Test
    void failsToStart_whenLazyResponseMethodHasNoParameters() {
        runner.withUserConfiguration(NoParamConfig.class)
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasMessageContaining("exactly one parameter"));
    }

    @Test
    void failsToStart_whenLazyResponseMethodHasWrongReturnType() {
        runner.withUserConfiguration(WrongReturnTypeConfig.class)
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasMessageContaining("ResponseEntity"));
    }

    @RestController
    @RequestMapping("/api/orders")
    static class TestController {
        @LazyResponse
        @PostMapping("/detail")
        public ResponseEntity<?> getDetail(OrderDetailRequest request) { return null; }
    }

    // -------------------------------------------------------------------------
    // Minimal downstream stubs for the context
    // -------------------------------------------------------------------------

    @Configuration
    static class MinimalDownstreams {
        @Bean
        MinimalDownstreamService minimalDownstreamService() {
            return new MinimalDownstreamService();
        }

        public static class MinimalDownstreamService {
            @Downstream(id = "order", fields = {"id", "status"}, chainTimeout = 2000)
            public Map<String, Object> fetchOrder(ExecutionContext ctx) {
                return Map.of("id", "ORD-001", "status", "CONFIRMED");
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
    }

    // -------------------------------------------------------------------------
    // Invalid controller configurations for startup validation tests
    // -------------------------------------------------------------------------

    @Configuration
    @RestController
    @RequestMapping("/bad1")
    static class RequestBodyOnParamConfig {
        @LazyResponse
        @PostMapping("/detail")
        public ResponseEntity<?> endpoint(@RequestBody OrderDetailRequest req) { return null; }
    }

    @Configuration
    @RestController
    @RequestMapping("/bad2")
    static class NoParamConfig {
        @LazyResponse
        @PostMapping("/detail")
        public ResponseEntity<?> endpoint() { return null; }
    }

    @Configuration
    @RestController
    @RequestMapping("/bad3")
    static class WrongReturnTypeConfig {
        @LazyResponse
        @PostMapping("/detail")
        public String endpoint(OrderDetailRequest req) { return null; }
    }
}
