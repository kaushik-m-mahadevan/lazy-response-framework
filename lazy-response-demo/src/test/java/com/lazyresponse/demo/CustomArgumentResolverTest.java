package com.lazyresponse.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.annotation.LazyResponse;
import com.lazyresponse.autoconfigure.LazyResponseAutoConfiguration;
import com.lazyresponse.context.ExecutionContext;
import com.lazyresponse.exception.ApplicationStartupException;
import com.lazyresponse.spi.DownstreamArgumentResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for Fix 2: {@link DownstreamArgumentResolver} SPI.
 *
 * <p>Verifies:
 * <ol>
 *   <li>A custom resolver enables {@code @Downstream} on methods with non-standard signatures
 *       (simulating a Feign client method that takes typed parameters instead of
 *       {@link ExecutionContext}).</li>
 *   <li>Startup fails with a clear error when a {@code @Downstream} method has no
 *       supporting resolver — catching misconfiguration early.</li>
 * </ol>
 */
@SpringBootTest(classes = {
        CustomArgumentResolverTest.TestController.class,
        CustomArgumentResolverTest.CustomResolverConfig.class,
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
class CustomArgumentResolverTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // 1. Custom resolver bridges typed parameters → downstream executes correctly
    // -------------------------------------------------------------------------

    @Test
    void customResolver_bridgesTypedParams_downstreamExecutesAndReturnsData() throws Exception {
        mockMvc.perform(post("/api/orders/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "request",  Map.of("orderId", "ORD-001", "accountId", "ACC-001"),
                                "template", Map.of("widget", new String[]{"label"})
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.widget.label").value("custom-resolved"));
    }

    // -------------------------------------------------------------------------
    // 2. Startup fails when no resolver supports the method
    // -------------------------------------------------------------------------

    @Test
    void failsToStart_whenNoResolverSupportsDownstreamMethod() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        LazyResponseAutoConfiguration.class
                ))
                .withUserConfiguration(UnsupportedSignatureConfig.class)
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasMessageContaining("DownstreamArgumentResolver"));
    }

    @RestController
    @RequestMapping("/api/orders")
    static class TestController {
        @LazyResponse
        @PostMapping("/detail")
        public ResponseEntity<?> getDetail(Object request) { return null; }
    }

    // -------------------------------------------------------------------------
    // Config: custom resolver + non-standard @Downstream method signature
    // -------------------------------------------------------------------------

    @Configuration
    static class CustomResolverConfig {

        @Bean
        WidgetService widgetService() {
            return new WidgetService();
        }

        /**
         * Custom resolver for {@link WidgetService} — extracts a string argument from the
         * ExecutionContext's request and passes it as a typed parameter, simulating how a
         * Feign client resolver would work.
         */
        @Bean
        DownstreamArgumentResolver widgetResolver() {
            return new DownstreamArgumentResolver() {
                @Override
                public boolean supports(Method method, Class<?> targetClass) {
                    return WidgetService.class.isAssignableFrom(targetClass);
                }

                @Override
                public Object[] resolve(Method method, ExecutionContext ctx) {
                    // The method takes a String parameter — extract it from the context
                    return new Object[]{"custom-resolved"};
                }
            };
        }

        /** Simulates a service whose method takes typed parameters, not ExecutionContext. */
        public static class WidgetService {
            @Downstream(id = "widget", fields = {"label"})
            public Map<String, Object> fetchWidget(String label) {
                return Map.of("label", label);
            }
        }
    }

    /** @Downstream method takes no args — no built-in or custom resolver supports it. */
    @Configuration
    static class UnsupportedSignatureConfig {
        @Bean
        UnsupportedService unsupportedService() { return new UnsupportedService(); }

        public static class UnsupportedService {
            @Downstream(id = "broken", fields = {"x"})
            public Map<String, Object> fetch() {          // zero params — no resolver supports this
                return Map.of("x", "value");
            }
        }
    }
}
