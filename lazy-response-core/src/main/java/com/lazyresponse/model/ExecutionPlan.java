package com.lazyresponse.model;

import java.util.List;

/**
 * The ordered set of execution stages for a single request, produced by
 * {@link com.lazyresponse.executor.ExecutionPlanner} after filtering the full dependency graph
 * against the request's field selection template.
 *
 * <p>{@code maxWidth} is the widest stage in the plan. This is the number of semaphore permits
 * atomically reserved before execution begins — it represents the maximum number of concurrent
 * downstream tasks at any point during this request's lifetime.
 */
public class ExecutionPlan {

    private final List<ExecutionStage> stages;
    private final int maxWidth;

    public ExecutionPlan(List<ExecutionStage> stages) {
        this.stages = List.copyOf(stages);
        this.maxWidth = stages.stream().mapToInt(ExecutionStage::width).max().orElse(0);
    }

    public List<ExecutionStage> getStages() {
        return stages;
    }

    /**
     * The maximum number of downstreams that run concurrently at any point in this plan.
     * Used to atomically reserve semaphore permits before execution starts.
     */
    public int getMaxWidth() {
        return maxWidth;
    }

    public boolean isEmpty() {
        return stages.isEmpty();
    }
}
