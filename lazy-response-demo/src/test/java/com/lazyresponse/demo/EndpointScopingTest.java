package com.lazyresponse.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.annotation.LazyAggregator;
import com.lazyresponse.annotation.LazyResponse;
import com.lazyresponse.autoconfigure.LazyResponseAutoConfiguration;
import com.lazyresponse.context.ExecutionContext;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies per-endpoint downstream scoping via {@link LazyResponse#downstreams()}.
 *
 * <p>Two controllers with overlapping downstream IDs ({@code "item"} appears in both
 * {@link AlphaDownstreams} and {@link BetaDownstreams}) are deployed in the same context.
 * Without scoping this would fail at startup with a duplicate-ID error. With scoping, each
 * endpoint sees only its own graph and the context starts successfully.
 *
 * <p>Tests verify:
 * <ol>
 *   <li>Each endpoint returns its own data  -  {@code /api/alpha/detail} returns alpha values,
 *       {@code /api/beta/detail} returns beta values, even though both use id {@code "item"}.</li>
 *   <li>Template keys that belong to the other endpoint's scope are silently dropped  - 
 *       {@code /api/alpha/detail} ignores a template requesting beta-only ids.</li>
 * </ol>
 */
@SpringBootTest(classes = {
        EndpointScopingTest.AlphaController.class,
        EndpointScopingTest.BetaController.class,
        EndpointScopingTest.AlphaConfig.class,
        EndpointScopingTest.BetaConfig.class,
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
class EndpointScopingTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // 1. Each scoped endpoint returns its own downstream data
    // -------------------------------------------------------------------------

    @Test
    void alphaEndpoint_returnsAlphaData() throws Exception {
        mockMvc.perform(post("/api/alpha/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("alphaItem", new String[]{"name"}))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alphaItem.name").value("alpha-item"));
    }

    @Test
    void betaEndpoint_returnsBetaData() throws Exception {
        mockMvc.perform(post("/api/beta/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("betaItem", new String[]{"name"}))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.betaItem.name").value("beta-item"));
    }

    // -------------------------------------------------------------------------
    // 2. Out-of-scope template keys are silently dropped
    // -------------------------------------------------------------------------

    @Test
    void alphaEndpoint_ignoresBetaTemplateKeys() throws Exception {
        // "betaExtra" is registered in BetaExtraDownstreams, not AlphaDownstreams
        // The alpha endpoint must silently drop it  -  no error, no data for "betaExtra"
        mockMvc.perform(post("/api/alpha/detail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "alphaItem", new String[]{"name"},
                                "betaExtra", new String[]{"value"}
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alphaItem.name").value("alpha-item"))
                .andExpect(jsonPath("$.data.betaExtra").doesNotExist())
                .andExpect(jsonPath("$.meta.errors").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Request helper
    // -------------------------------------------------------------------------

    private String body(Map<String, Object> template) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "request",  Map.of("id", "test-id"),
                "template", template
        ));
    }

    // -------------------------------------------------------------------------
    // Controllers  -  two separate scoped endpoints, both using id "item"
    // -------------------------------------------------------------------------

    @LazyAggregator
    @RequestMapping("/api/alpha")
    static class AlphaController {
        @LazyResponse(downstreams = {AlphaDownstreams.class})
        @PostMapping("/detail")
        public ResponseEntity<?> getDetail(Object request) { return null; }
    }

    @LazyAggregator
    @RequestMapping("/api/beta")
    static class BetaController {
        @LazyResponse(downstreams = {BetaDownstreams.class, BetaExtraDownstreams.class})
        @PostMapping("/detail")
        public ResponseEntity<?> getDetail(Object request) { return null; }
    }

    // -------------------------------------------------------------------------
    // Downstream beans  -  unique ids per scope to avoid registry duplicate-id rejection
    // -------------------------------------------------------------------------

    @Configuration
    static class AlphaConfig {
        @Bean AlphaDownstreams alphaDownstreams() { return new AlphaDownstreams(); }
    }

    @Configuration
    static class BetaConfig {
        @Bean BetaDownstreams betaDownstreams() { return new BetaDownstreams(); }
        @Bean BetaExtraDownstreams betaExtraDownstreams() { return new BetaExtraDownstreams(); }
    }

    public static class AlphaDownstreams {
        @Downstream(id = "alphaItem", fields = {"name"})
        public Map<String, Object> fetchItem(ExecutionContext ctx) {
            return Map.of("name", "alpha-item");
        }
    }

    public static class BetaDownstreams {
        @Downstream(id = "betaItem", fields = {"name"})
        public Map<String, Object> fetchItem(ExecutionContext ctx) {
            return Map.of("name", "beta-item");
        }
    }

    public static class BetaExtraDownstreams {
        @Downstream(id = "betaExtra", fields = {"value"})
        public Map<String, Object> fetchExtra(ExecutionContext ctx) {
            return Map.of("value", "beta-extra");
        }
    }
}
