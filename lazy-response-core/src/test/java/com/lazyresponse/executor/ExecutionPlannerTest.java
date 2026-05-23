package com.lazyresponse.executor;

import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.context.ExecutionContext;
import com.lazyresponse.model.DownstreamRegistration;
import com.lazyresponse.model.ExecutionPlan;
import com.lazyresponse.registry.DownstreamRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExecutionPlanner}  -  template filtering, transitive dependency
 * resolution, unknown key handling, and empty plan production.
 * No Spring context required.
 */
class ExecutionPlannerTest {

    private DownstreamRegistry registry;
    private ExecutionPlanner planner;

    // -------------------------------------------------------------------------
    // Test stubs
    // -------------------------------------------------------------------------

    public static class Stubs {
        @Downstream(id = "order", fields = {"id", "status"}, chainTimeout = 2000)
        public String order(ExecutionContext ctx) { return "order"; }

        @Downstream(id = "account", fields = {"name"})
        public String account(ExecutionContext ctx) { return "account"; }

        @Downstream(id = "payment", fields = {"status"}, dependsOn = {"order"}, timeout = 500)
        public String payment(ExecutionContext ctx) { return "payment"; }

        @Downstream(id = "loyalty", fields = {"points"}, dependsOn = {"account"})
        public String loyalty(ExecutionContext ctx) { return "loyalty"; }
    }

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception {
        registry = new DownstreamRegistry();
        Stubs stubs = new Stubs();
        for (Method m : stubs.getClass().getMethods()) {
            Downstream ann = m.getAnnotation(Downstream.class);
            if (ann != null) {
                registry.register(stubs, m, ann, stubs.getClass());
            }
        }
        registry.seal(3000L);
        planner = new ExecutionPlanner(registry);
    }

    // -------------------------------------------------------------------------
    // 1. Empty template
    // -------------------------------------------------------------------------

    @Test
    void emptyTemplate_producesEmptyPlan() {
        ExecutionPlan plan = planner.plan(Map.of());
        assertThat(plan.isEmpty()).isTrue();
        assertThat(plan.getMaxWidth()).isZero();
    }

    // -------------------------------------------------------------------------
    // 2. Unknown keys silently ignored
    // -------------------------------------------------------------------------

    @Test
    void unknownTemplateKey_ignored_planContainsOnlyKnown() {
        ExecutionPlan plan = planner.plan(Map.of(
                "order", List.of("id"),
                "nonexistent", List.of("foo")
        ));
        assertThat(plan.isEmpty()).isFalse();
        List<String> includedIds = plan.getStages().stream()
                .flatMap(s -> s.getNodes().stream())
                .map(DownstreamRegistration::getId)
                .toList();
        assertThat(includedIds).contains("order").doesNotContain("nonexistent");
    }

    // -------------------------------------------------------------------------
    // 3. Only requested downstreams included (no unrequested)
    // -------------------------------------------------------------------------

    @Test
    void onlyRequestedDownstreams_inPlan() {
        ExecutionPlan plan = planner.plan(Map.of("account", List.of("name")));
        List<String> ids = plan.getStages().stream()
                .flatMap(s -> s.getNodes().stream())
                .map(DownstreamRegistration::getId)
                .toList();
        assertThat(ids).containsExactly("account");
    }

    // -------------------------------------------------------------------------
    // 4. Transitive dependency auto-included
    // -------------------------------------------------------------------------

    @Test
    void payment_inTemplate_autoIncludesOrder() {
        // payment dependsOn order; order not in template → must still execute
        ExecutionPlan plan = planner.plan(Map.of("payment", List.of("status")));
        List<String> ids = plan.getStages().stream()
                .flatMap(s -> s.getNodes().stream())
                .map(DownstreamRegistration::getId)
                .toList();
        assertThat(ids).containsExactlyInAnyOrder("order", "payment");
    }

    // -------------------------------------------------------------------------
    // 5. Max width
    // -------------------------------------------------------------------------

    @Test
    void maxWidth_reflectsWidestStage() {
        // order + account are independent roots → stage 0 width = 2
        // payment + loyalty depend on them → stage 1 width = 2
        ExecutionPlan plan = planner.plan(Map.of(
                "order",   List.of("id"),
                "account", List.of("name"),
                "payment", List.of("status"),
                "loyalty", List.of("points")
        ));
        assertThat(plan.getMaxWidth()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // 6. Stage ordering respected
    // -------------------------------------------------------------------------

    @Test
    void stageOrdering_parentsBeforeChildren() {
        ExecutionPlan plan = planner.plan(Map.of(
                "order",   List.of("id"),
                "payment", List.of("status")
        ));
        List<String> stage0 = plan.getStages().get(0).getNodes().stream()
                .map(DownstreamRegistration::getId).toList();
        List<String> stage1 = plan.getStages().get(1).getNodes().stream()
                .map(DownstreamRegistration::getId).toList();
        assertThat(stage0).containsExactly("order");
        assertThat(stage1).containsExactly("payment");
    }
}
