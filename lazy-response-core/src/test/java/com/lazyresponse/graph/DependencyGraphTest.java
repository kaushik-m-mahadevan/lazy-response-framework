package com.lazyresponse.graph;

import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.context.ExecutionContext;
import com.lazyresponse.exception.ApplicationStartupException;
import com.lazyresponse.model.DownstreamRegistration;
import com.lazyresponse.model.ExecutionStage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DependencyGraph}  -  reference validation, cycle detection,
 * stage construction, chain timeout resolution, and Mermaid generation.
 * No Spring context required.
 */
class DependencyGraphTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Builds a DownstreamRegistration from a @Downstream annotated method by name. */
    private DownstreamRegistration reg(Object bean, String methodName) throws Exception {
        for (Method m : bean.getClass().getMethods()) {
            Downstream ann = m.getAnnotation(Downstream.class);
            if (ann != null && m.getName().equals(methodName)) {
                return new DownstreamRegistration(bean, m, ann, bean.getClass());
            }
        }
        throw new IllegalArgumentException("No @Downstream method: " + methodName);
    }

    private Map<String, DownstreamRegistration> graph(DownstreamRegistration... regs) {
        Map<String, DownstreamRegistration> map = new LinkedHashMap<>();
        for (DownstreamRegistration r : regs) {
            map.put(r.getId(), r);
        }
        return map;
    }

    // -------------------------------------------------------------------------
    // Test stubs
    // -------------------------------------------------------------------------

    public static class Roots {
        @Downstream(id = "a", fields = {"x"}, chainTimeout = 1000)
        public String a(ExecutionContext ctx) { return "a"; }

        @Downstream(id = "b", fields = {"y"}, chainTimeout = 2000)
        public String b(ExecutionContext ctx) { return "b"; }
    }

    public static class WithDeps {
        @Downstream(id = "a", fields = {"x"}, chainTimeout = 1000)
        public String a(ExecutionContext ctx) { return "a"; }

        @Downstream(id = "b", fields = {"y"})
        public String b(ExecutionContext ctx) { return "b"; }

        @Downstream(id = "c", fields = {"z"}, dependsOn = {"a", "b"}, timeout = 500)
        public String c(ExecutionContext ctx) { return "c"; }

        @Downstream(id = "d", fields = {"w"}, dependsOn = {"a"})
        public String d(ExecutionContext ctx) { return "d"; }
    }

    public static class Cyclic {
        @Downstream(id = "x", fields = {"f"}, dependsOn = {"y"})
        public String x(ExecutionContext ctx) { return "x"; }

        @Downstream(id = "y", fields = {"f"}, dependsOn = {"x"})
        public String y(ExecutionContext ctx) { return "y"; }
    }

    public static class BrokenRef {
        @Downstream(id = "orphan", fields = {"f"}, dependsOn = {"ghost"})
        public String orphan(ExecutionContext ctx) { return "orphan"; }
    }

    public static class Linear {
        @Downstream(id = "a", fields = {"x"}, chainTimeout = 3000)
        public String a(ExecutionContext ctx) { return "a"; }

        @Downstream(id = "b", fields = {"y"}, dependsOn = {"a"})
        public String b(ExecutionContext ctx) { return "b"; }

        @Downstream(id = "c", fields = {"z"}, dependsOn = {"b"}, timeout = 400)
        public String c(ExecutionContext ctx) { return "c"; }
    }

    public static class Diamond {
        // a → b, a → c, b → d, c → d  (diamond)
        @Downstream(id = "a", fields = {"f"}, chainTimeout = 2000)
        public String a(ExecutionContext ctx) { return "a"; }

        @Downstream(id = "b", fields = {"f"}, dependsOn = {"a"}, chainTimeout = 1500)
        public String b(ExecutionContext ctx) { return "b"; }

        @Downstream(id = "c", fields = {"f"}, dependsOn = {"a"})
        public String c(ExecutionContext ctx) { return "c"; }

        @Downstream(id = "d", fields = {"f"}, dependsOn = {"b", "c"})
        public String d(ExecutionContext ctx) { return "d"; }
    }

    // -------------------------------------------------------------------------
    // 1. validateReferences
    // -------------------------------------------------------------------------

    @Test
    void validateReferences_passes_whenAllDepsRegistered() throws Exception {
        WithDeps stubs = new WithDeps();
        Map<String, DownstreamRegistration> nodes = graph(
                reg(stubs, "a"), reg(stubs, "b"), reg(stubs, "c"), reg(stubs, "d"));
        new DependencyGraph(nodes).validateReferences(); // should not throw
    }

    @Test
    void validateReferences_throws_whenDepNotRegistered() throws Exception {
        BrokenRef stubs = new BrokenRef();
        Map<String, DownstreamRegistration> nodes = graph(reg(stubs, "orphan"));
        assertThatThrownBy(() -> new DependencyGraph(nodes).validateReferences())
                .isInstanceOf(ApplicationStartupException.class)
                .hasMessageContaining("ghost")
                .hasMessageContaining("no @Downstream with that id is registered");
    }

    // -------------------------------------------------------------------------
    // 2. topologicalSort  -  correct levels
    // -------------------------------------------------------------------------

    @Test
    void topologicalSort_twoIndependentRoots_singleLevel() throws Exception {
        Roots stubs = new Roots();
        DependencyGraph g = new DependencyGraph(graph(reg(stubs, "a"), reg(stubs, "b")));
        List<List<String>> levels = g.topologicalSort();
        assertThat(levels).hasSize(1);
        assertThat(levels.get(0)).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void topologicalSort_linearChain_threeLevels() throws Exception {
        Linear stubs = new Linear();
        DependencyGraph g = new DependencyGraph(
                graph(reg(stubs, "a"), reg(stubs, "b"), reg(stubs, "c")));
        List<List<String>> levels = g.topologicalSort();
        assertThat(levels).hasSize(3);
        assertThat(levels.get(0)).containsExactly("a");
        assertThat(levels.get(1)).containsExactly("b");
        assertThat(levels.get(2)).containsExactly("c");
    }

    @Test
    void topologicalSort_diamond_correctLevels() throws Exception {
        Diamond stubs = new Diamond();
        DependencyGraph g = new DependencyGraph(
                graph(reg(stubs, "a"), reg(stubs, "b"), reg(stubs, "c"), reg(stubs, "d")));
        List<List<String>> levels = g.topologicalSort();
        // a → level 0, b+c → level 1, d → level 2
        assertThat(levels).hasSize(3);
        assertThat(levels.get(0)).containsExactly("a");
        assertThat(levels.get(1)).containsExactlyInAnyOrder("b", "c");
        assertThat(levels.get(2)).containsExactly("d");
    }

    @Test
    void topologicalSort_cycle_throwsWithOffendingNodes() throws Exception {
        Cyclic stubs = new Cyclic();
        DependencyGraph g = new DependencyGraph(graph(reg(stubs, "x"), reg(stubs, "y")));
        assertThatThrownBy(g::topologicalSort)
                .isInstanceOf(ApplicationStartupException.class)
                .hasMessageContaining("Cycle detected");
    }

    // -------------------------------------------------------------------------
    // 3. buildStages
    // -------------------------------------------------------------------------

    @Test
    void buildStages_widthMatchesLevelSizes() throws Exception {
        WithDeps stubs = new WithDeps();
        DependencyGraph g = new DependencyGraph(
                graph(reg(stubs, "a"), reg(stubs, "b"), reg(stubs, "c"), reg(stubs, "d")));
        List<ExecutionStage> stages = g.buildStages();
        // a+b in stage 0, c+d in stage 1
        assertThat(stages).hasSize(2);
        assertThat(stages.get(0).width()).isEqualTo(2);
        assertThat(stages.get(1).width()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // 4. resolveChainTimeouts
    // -------------------------------------------------------------------------

    @Test
    void resolveChainTimeouts_perNodeTimeoutWins() throws Exception {
        Linear stubs = new Linear();
        DownstreamRegistration rA = reg(stubs, "a");
        DownstreamRegistration rB = reg(stubs, "b");
        DownstreamRegistration rC = reg(stubs, "c"); // timeout=400
        DependencyGraph g = new DependencyGraph(graph(rA, rB, rC));
        g.topologicalSort();
        g.resolveChainTimeouts(5000);
        // c has per-node timeout=400; overrides chain
        assertThat(rC.getEffectiveTimeout()).isEqualTo(400);
    }

    @Test
    void resolveChainTimeouts_chainPropagatesFromRoot() throws Exception {
        Linear stubs = new Linear();
        DownstreamRegistration rA = reg(stubs, "a"); // chainTimeout=3000
        DownstreamRegistration rB = reg(stubs, "b"); // no timeout, inherits chain
        DownstreamRegistration rC = reg(stubs, "c"); // timeout=400  -  overrides
        DependencyGraph g = new DependencyGraph(graph(rA, rB, rC));
        g.topologicalSort();
        g.resolveChainTimeouts(5000);
        assertThat(rA.getEffectiveTimeout()).isEqualTo(3000); // chain = own chainTimeout
        assertThat(rB.getEffectiveTimeout()).isEqualTo(3000); // inherits from a
    }

    @Test
    void resolveChainTimeouts_globalFallsBack_whenNoChainOrPerNode() throws Exception {
        // b has no chainTimeout, no timeout  -  should fall back to global
        WithDeps stubs = new WithDeps();
        DownstreamRegistration rA = reg(stubs, "a");
        DownstreamRegistration rB = reg(stubs, "b"); // no timeout, no chain
        DependencyGraph g = new DependencyGraph(graph(rA, rB));
        g.topologicalSort();
        g.resolveChainTimeouts(9999);
        assertThat(rB.getEffectiveTimeout()).isEqualTo(9999);
    }

    @Test
    void resolveChainTimeouts_diamond_mostConservativeChainWins() throws Exception {
        // a chain=2000, b declares chainTimeout=1500 (non-root, ignored per spec),
        // c inherits 2000 from a, d has parents b(2000) and c(2000) → 2000
        Diamond stubs = new Diamond();
        DownstreamRegistration rA = reg(stubs, "a");
        DownstreamRegistration rB = reg(stubs, "b");
        DownstreamRegistration rC = reg(stubs, "c");
        DownstreamRegistration rD = reg(stubs, "d");
        DependencyGraph g = new DependencyGraph(graph(rA, rB, rC, rD));
        g.topologicalSort();
        g.resolveChainTimeouts(5000);
        // d's parents both propagate 2000 from a → d gets 2000
        assertThat(rD.getEffectiveTimeout()).isEqualTo(2000);
    }

    // -------------------------------------------------------------------------
    // 5. toMermaidFlowchart
    // -------------------------------------------------------------------------

    @Test
    void toMermaidFlowchart_containsEdgesForDeps() throws Exception {
        WithDeps stubs = new WithDeps();
        DependencyGraph g = new DependencyGraph(
                graph(reg(stubs, "a"), reg(stubs, "b"), reg(stubs, "c"), reg(stubs, "d")));
        String chart = g.toMermaidFlowchart();
        assertThat(chart).startsWith("flowchart TD\n");
        assertThat(chart).contains("a --> c");
        assertThat(chart).contains("b --> c");
        assertThat(chart).contains("a --> d");
    }

    @Test
    void toMermaidFlowchart_isolatedNode_declaredExplicitly() throws Exception {
        Roots stubs = new Roots();
        DependencyGraph g = new DependencyGraph(graph(reg(stubs, "a"), reg(stubs, "b")));
        String chart = g.toMermaidFlowchart();
        // a and b are isolated (no edges)  -  must appear as standalone nodes
        assertThat(chart).contains("    a\n");
        assertThat(chart).contains("    b\n");
    }
}
