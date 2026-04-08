package com.lazyresponse.model;

import java.util.List;

/**
 * A group of {@link DownstreamRegistration}s that share the same topological depth and
 * can therefore execute concurrently within a single request's execution plan.
 *
 * <p>Produced by {@link com.lazyresponse.graph.DependencyGraph#buildStages()} via BFS
 * level-grouping of the topological sort. All nodes in a stage have their declared parents
 * in earlier stages, so no intra-stage ordering constraint exists.
 */
public class ExecutionStage {

    private final List<DownstreamRegistration> nodes;

    public ExecutionStage(List<DownstreamRegistration> nodes) {
        this.nodes = List.copyOf(nodes);
    }

    public List<DownstreamRegistration> getNodes() {
        return nodes;
    }

    /**
     * The number of downstreams in this stage. Used by the semaphore management logic
     * to calculate excess permit release after each stage completes.
     */
    public int width() {
        return nodes.size();
    }
}
