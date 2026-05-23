package com.lazyresponse.executor;

import com.lazyresponse.model.DownstreamRegistration;
import com.lazyresponse.model.ExecutionPlan;
import com.lazyresponse.model.ExecutionStage;
import com.lazyresponse.registry.DownstreamRegistry;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Produces a per-request {@link ExecutionPlan} by filtering the full dependency graph
 * against the field selection template.
 *
 * <p>Only downstreams whose ids appear as top-level keys in the template are included.
 * Any dependency of a requested downstream is automatically included, even if not
 * explicitly mentioned in the template. Downstreams not transitively required by the
 * template are excluded entirely  -  they are never invoked.
 *
 * <p>Unknown template keys (ids with no registered {@code @Downstream}) are silently ignored.
 * The caller is responsible for correct template usage, as documented via the Swagger contract.
 *
 * <p>When a non-empty {@code scopedIds} set is provided (via
 * {@link com.lazyresponse.annotation.LazyResponse#downstreams()}), only those downstreams are
 * eligible  -  unknown or out-of-scope template keys are silently dropped. This enables safe
 * multi-endpoint deployments where each endpoint operates on its own isolated downstream graph.
 */
public class ExecutionPlanner {

    private final DownstreamRegistry registry;

    public ExecutionPlanner(DownstreamRegistry registry) {
        this.registry = registry;
    }

    /**
     * Builds a filtered {@link ExecutionPlan} for the given template with no scope restriction.
     * All registered downstreams are eligible. Equivalent to {@code plan(template, Set.of())}.
     *
     * @param template the field selection template from the request body
     */
    public ExecutionPlan plan(Map<String, Object> template) {
        return plan(template, Collections.emptySet());
    }

    /**
     * Builds a filtered {@link ExecutionPlan} for the given template, restricted to the
     * provided scope.
     *
     * @param template  the field selection template from the request body
     * @param scopedIds the downstream IDs eligible for this endpoint; empty means no restriction
     * @return an execution plan containing only the downstreams transitively required
     *         by the template within the scope; may be empty if no keys match
     */
    public ExecutionPlan plan(Map<String, Object> template, Set<String> scopedIds) {
        Set<String> requestedIds = extractRequestedDownstreamIds(template, scopedIds);
        Set<String> requiredIds = resolveTransitiveDependencies(requestedIds);

        List<ExecutionStage> allStages = registry.getGraph().buildStages();
        List<ExecutionStage> filteredStages = allStages.stream()
                .map(stage -> new ExecutionStage(
                        stage.getNodes().stream()
                                .filter(node -> requiredIds.contains(node.getId()))
                                .collect(Collectors.toList())))
                .filter(stage -> !stage.getNodes().isEmpty())
                .collect(Collectors.toList());

        return new ExecutionPlan(filteredStages);
    }

    /**
     * Extracts top-level template keys that correspond to registered downstream ids.
     * When {@code scopedIds} is non-empty, only IDs within the scope pass. Unknown keys
     * and out-of-scope keys are silently dropped.
     */
    private Set<String> extractRequestedDownstreamIds(Map<String, Object> template, Set<String> scopedIds) {
        return template.keySet().stream()
                .filter(registry::isRegistered)
                .filter(id -> scopedIds.isEmpty() || scopedIds.contains(id))
                .collect(Collectors.toSet());
    }

    /**
     * Walks the {@code dependsOn} graph upward from each requested downstream, collecting all
     * ancestor ids that must execute before the requested downstreams can run. This ensures
     * that dependencies are never skipped even if the caller did not mention them in the template.
     */
    private Set<String> resolveTransitiveDependencies(Set<String> requestedIds) {
        Set<String> required = new HashSet<>(requestedIds);
        Queue<String> queue = new LinkedList<>(requestedIds);
        Map<String, DownstreamRegistration> nodes = registry.getGraph().getNodes();

        while (!queue.isEmpty()) {
            String id = queue.poll();
            DownstreamRegistration reg = nodes.get(id);
            if (reg == null) {
                continue;
            }
            for (String parentId : reg.getDependsOn()) {
                if (required.add(parentId)) {
                    queue.offer(parentId);
                }
            }
        }
        return required;
    }
}
