package com.lazyresponse.graph;

import com.lazyresponse.exception.ApplicationStartupException;
import com.lazyresponse.model.DownstreamRegistration;
import com.lazyresponse.model.ExecutionStage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The directed acyclic graph of all registered downstreams.
 *
 * <p>Built from the sealed {@link com.lazyresponse.registry.DownstreamRegistry} at startup.
 * Responsible for:
 * <ul>
 *   <li>Reference validation — all {@code dependsOn} ids must be registered</li>
 *   <li>Cycle detection — via Kahn's topological sort algorithm; fatal if a cycle is found</li>
 *   <li>Stage construction — BFS level-grouping produces concurrent execution stages</li>
 *   <li>Chain timeout resolution — propagates {@code chainTimeout} from root nodes to
 *       descendants, taking the most conservative value at nodes with multiple parents</li>
 *   <li>Mermaid.js graph generation — for the {@code /lazy/graph} visualisation endpoint</li>
 * </ul>
 *
 * <p>All operations on this class are startup-time concerns. No runtime computation is
 * performed here — the graph is built once and remains immutable for the application lifetime.
 */
public class DependencyGraph {

    private final Map<String, DownstreamRegistration> nodes;

    public DependencyGraph(Map<String, DownstreamRegistration> nodes) {
        this.nodes = Collections.unmodifiableMap(nodes);
    }

    /**
     * Validates that every id in every {@code dependsOn} array refers to a registered downstream.
     *
     * @throws ApplicationStartupException if any reference is unresolved
     */
    public void validateReferences() {
        for (DownstreamRegistration reg : nodes.values()) {
            for (String depId : reg.getDependsOn()) {
                if (!nodes.containsKey(depId)) {
                    throw new ApplicationStartupException(
                            "@Downstream '" + reg.getId() + "' declares dependsOn = \"" + depId
                                    + "\" but no @Downstream with that id is registered.");
                }
            }
        }
    }

    /**
     * Executes Kahn's algorithm to produce a topological ordering grouped into BFS levels.
     * Each level becomes one {@link ExecutionStage}. Nodes within a level share no ordering
     * dependency and can execute concurrently.
     *
     * @return ordered list of node-id groups, from independent roots to deepest leaves
     * @throws ApplicationStartupException if a cycle is detected; the exception message
     *                                     identifies the offending node ids
     */
    public List<List<String>> topologicalSort() {
        // Build in-degree map and reverse adjacency (parent → children)
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> children = new HashMap<>();

        for (String id : nodes.keySet()) {
            inDegree.put(id, 0);
            children.put(id, new ArrayList<>());
        }
        for (DownstreamRegistration reg : nodes.values()) {
            for (String parentId : reg.getDependsOn()) {
                inDegree.merge(reg.getId(), 1, Integer::sum);
                children.get(parentId).add(reg.getId());
            }
        }

        // BFS — process all zero-in-degree nodes level by level
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        List<List<String>> levels = new ArrayList<>();
        int processed = 0;

        while (!queue.isEmpty()) {
            int levelSize = queue.size();
            List<String> currentLevel = new ArrayList<>(levelSize);

            for (int i = 0; i < levelSize; i++) {
                String nodeId = queue.poll();
                currentLevel.add(nodeId);
                processed++;

                for (String childId : children.get(nodeId)) {
                    inDegree.merge(childId, -1, Integer::sum);
                    if (inDegree.get(childId) == 0) {
                        queue.offer(childId);
                    }
                }
            }
            levels.add(currentLevel);
        }

        if (processed != nodes.size()) {
            Set<String> cycleNodes = nodes.keySet().stream()
                    .filter(id -> inDegree.get(id) > 0)
                    .collect(Collectors.toSet());
            // Surface the specific edges between cycle participants to aid diagnosis
            List<String> cycleEdges = new ArrayList<>();
            for (String nodeId : cycleNodes) {
                for (String dep : nodes.get(nodeId).getDependsOn()) {
                    if (cycleNodes.contains(dep)) {
                        cycleEdges.add(nodeId + " -> " + dep);
                    }
                }
            }
            throw new ApplicationStartupException(
                    "Cycle detected in @Downstream dependency graph. Application cannot start. "
                            + "Nodes involved: " + cycleNodes + ". "
                            + "Edges forming the cycle: " + cycleEdges);
        }

        return levels;
    }

    /**
     * Builds {@link ExecutionStage}s from the topological sort output.
     * Called by the {@link com.lazyresponse.executor.ExecutionPlanner} to produce the
     * base stage plan that is then filtered per request.
     */
    public List<ExecutionStage> buildStages() {
        List<List<String>> levels = topologicalSort();
        List<ExecutionStage> stages = new ArrayList<>(levels.size());
        for (List<String> level : levels) {
            List<DownstreamRegistration> stageNodes = level.stream()
                    .map(nodes::get)
                    .collect(Collectors.toList());
            stages.add(new ExecutionStage(stageNodes));
        }
        return stages;
    }

    /**
     * Resolves and sets the {@code effectiveTimeout} on every {@link DownstreamRegistration}.
     *
     * <p>Priority (highest to lowest):
     * <ol>
     *   <li>Per-node {@code timeout} annotation attribute — applies to this node only</li>
     *   <li>Chain timeout — {@code chainTimeout} declared on root ancestors, propagated via BFS;
     *       when a node has multiple parent chains, the most conservative (lowest) value applies</li>
     *   <li>{@code globalTimeoutMs} from YAML — floor for all nodes</li>
     * </ol>
     *
     * <p>Chain timeouts are meaningful only on root nodes. Non-root nodes that declare
     * {@code chainTimeout} have it silently ignored in favour of their inherited chain value.
     *
     * @param globalTimeoutMs the global timeout floor from YAML configuration
     */
    public void resolveChainTimeouts(long globalTimeoutMs) {
        // Phase 1: seed chain timeouts from root nodes only
        Map<String, Long> chainTimeoutByNode = new HashMap<>();
        for (DownstreamRegistration reg : nodes.values()) {
            if (reg.isRootNode() && reg.getChainTimeout() > 0) {
                chainTimeoutByNode.put(reg.getId(), reg.getChainTimeout());
            }
        }

        // Phase 2: propagate via iterative relaxation (handles arbitrary graph depth).
        // At each node with multiple parents, take the minimum (most conservative) chain timeout.
        // In a DAG with n nodes the longest path is at most n-1 edges, so n iterations suffices.
        boolean changed = true;
        int maxIterations = nodes.size();
        int iteration = 0;
        while (changed && iteration++ < maxIterations) {
            changed = false;
            for (DownstreamRegistration reg : nodes.values()) {
                if (reg.getDependsOn().isEmpty()) {
                    continue;
                }
                long minParentChain = Long.MAX_VALUE;
                boolean hasParentWithChain = false;
                for (String parentId : reg.getDependsOn()) {
                    Long parentChain = chainTimeoutByNode.get(parentId);
                    if (parentChain != null) {
                        hasParentWithChain = true;
                        minParentChain = Math.min(minParentChain, parentChain);
                    }
                }
                if (hasParentWithChain) {
                    Long current = chainTimeoutByNode.get(reg.getId());
                    if (current == null || current > minParentChain) {
                        chainTimeoutByNode.put(reg.getId(), minParentChain);
                        changed = true;
                    }
                }
            }
        }

        // Phase 3: set effectiveTimeout on each node
        for (DownstreamRegistration reg : nodes.values()) {
            if (reg.getTimeout() > 0) {
                // Per-node timeout — highest priority
                reg.setEffectiveTimeout(reg.getTimeout());
            } else {
                Long chainTimeout = chainTimeoutByNode.get(reg.getId());
                reg.setEffectiveTimeout(chainTimeout != null ? chainTimeout : globalTimeoutMs);
            }
        }
    }

    /**
     * Generates a Mermaid.js {@code flowchart TD} definition from this graph.
     * Used by {@link LazyGraphController} to render the dependency visualisation page.
     */
    public String toMermaidFlowchart() {
        StringBuilder sb = new StringBuilder("flowchart TD\n");
        Set<String> nodesWithEdges = new HashSet<>();

        for (DownstreamRegistration reg : nodes.values()) {
            for (String parentId : reg.getDependsOn()) {
                sb.append("    ").append(parentId).append(" --> ").append(reg.getId()).append("\n");
                nodesWithEdges.add(reg.getId());
                nodesWithEdges.add(parentId);
            }
        }

        // Isolated nodes (no edges) must be declared explicitly to appear in the chart
        for (String id : nodes.keySet()) {
            if (!nodesWithEdges.contains(id)) {
                sb.append("    ").append(id).append("\n");
            }
        }
        return sb.toString();
    }

    public Map<String, DownstreamRegistration> getNodes() {
        return nodes;
    }
}
