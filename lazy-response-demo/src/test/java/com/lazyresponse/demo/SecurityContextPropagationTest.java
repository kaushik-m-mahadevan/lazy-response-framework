package com.lazyresponse.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.annotation.LazyResponse;
import com.lazyresponse.autoconfigure.LazyResponseAutoConfiguration;
import com.lazyresponse.context.ExecutionContext;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies Spring Security context propagation to downstream threads.
 *
 * <p>When {@code spring-security-core} is on the classpath, the framework wraps the thread
 * pool executor with {@code DelegatingSecurityContextExecutorService}. This test verifies
 * that a {@code SecurityContext} set on the request thread is available inside a
 * {@code @Downstream} method running on a pool thread.
 *
 * <p>A thread-safe {@link AtomicReference} captures the authentication principal name
 * observed inside the downstream method. The test asserts it matches the principal set
 * on the request.
 */
@SpringBootTest(classes = {
        SecurityContextPropagationTest.TestController.class,
        SecurityContextPropagationTest.SecurityTestConfig.class,
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
class SecurityContextPropagationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SecurityTestConfig.SecurityCapturingDownstreams downstreams;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void securityContext_propagatedToDownstreamThread() throws Exception {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "kaushik", "credentials",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);

        mockMvc.perform(post("/api/orders/detail")
                        .with(securityContext(ctx))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "request",  Map.of("orderId", "ORD-001", "accountId", "ACC-001"),
                                "template", Map.of("secured", new String[]{"principal"})
                        ))))
                .andExpect(status().isOk());

        assertThat(downstreams.capturedPrincipal.get())
                .as("SecurityContext must be propagated to the downstream worker thread")
                .isEqualTo("kaushik");
    }

    @RestController
    @RequestMapping("/api/orders")
    static class TestController {
        @LazyResponse
        @PostMapping("/detail")
        public ResponseEntity<?> getDetail(Object request) { return null; }
    }

    @Configuration
    static class SecurityTestConfig {

        @Bean
        SecurityCapturingDownstreams securityCapturingDownstreams() {
            return new SecurityCapturingDownstreams();
        }

        public static class SecurityCapturingDownstreams {

            final AtomicReference<String> capturedPrincipal = new AtomicReference<>();

            @Downstream(id = "secured", fields = {"principal"})
            public Map<String, Object> fetchSecured(ExecutionContext ctx) {
                // This runs on a pool thread  -  verifies SecurityContext propagation
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                String principal = (auth != null && auth.getPrincipal() != null)
                        ? auth.getName()
                        : "<none>";
                capturedPrincipal.set(principal);
                return Map.of("principal", principal);
            }
        }
    }
}
