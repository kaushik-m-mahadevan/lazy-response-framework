package com.lazyresponse.registry;

import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.exception.ApplicationStartupException;
import com.lazyresponse.graph.DependencyGraph;
import com.lazyresponse.model.DownstreamRegistration;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Central registry of all discovered {@link Downstream} methods in the application context.
 *
 * <p>Populated by {@link DownstreamRegistryBeanPostProcessor} during the bean initialization
 * phase. Sealed via {@link #seal(long)} when the application context finishes refreshing,
 * at which point the dependency graph is built, validated, and chain timeouts are resolved.
 *
 * <p>The registry is read-only after sealing. Any attempt to register a downstream after
 * sealing throws an {@link ApplicationStartupException}.
 */
public class DownstreamRegistry {

    private final Map<String, DownstreamRegistration> registrations = new LinkedHashMap<>();
    private DependencyGraph graph;
    private volatile boolean sealed = false;

    /**
     * Registers a discovered {@code @Downstream} method.
     *
     * @param targetClass the actual (non-proxied) class declaring the method  -  used for
     *                    per-endpoint scoping and argument resolver selection
     * @throws ApplicationStartupException if the registry is already sealed or if the id
     *                                     duplicates an existing registration
     */
    public synchronized void register(Object bean, Method method, Downstream annotation, Class<?> targetClass) {
        if (sealed) {
            throw new ApplicationStartupException(
                    "Cannot register @Downstream after the registry has been sealed. "
                            + "Method: " + method.toGenericString());
        }
        String id = annotation.id();
        if (registrations.containsKey(id)) {
            throw new ApplicationStartupException(
                    "Duplicate @Downstream id '" + id + "'. Already registered on: "
                            + registrations.get(id).getMethod().toGenericString()
                            + ". Conflicting method: " + method.toGenericString());
        }
        registrations.put(id, new DownstreamRegistration(bean, method, annotation, targetClass));
    }

    /**
     * Seals the registry, builds the dependency graph, validates references, detects cycles,
     * and resolves effective timeouts. Called once when the application context has finished
     * refreshing. Idempotent  -  subsequent calls are no-ops.
     *
     * @param globalTimeoutMs the global timeout floor from YAML, used as the base for
     *                        chain timeout resolution
     * @throws ApplicationStartupException if the graph has unresolved references or cycles
     */
    public synchronized void seal(long globalTimeoutMs) {
        if (sealed) {
            return;
        }
        graph = new DependencyGraph(new LinkedHashMap<>(registrations));
        graph.validateReferences();
        graph.topologicalSort();
        graph.resolveChainTimeouts(globalTimeoutMs);
        sealed = true;
    }

    /**
     * Returns the downstream IDs that belong to the given set of bean types.
     *
     * <p>Used by the aspect to restrict each {@code @LazyResponse} endpoint to only
     * the downstreams declared in its {@link com.lazyresponse.annotation.LazyResponse#downstreams()}
     * attribute. Returns an empty set if {@code beanTypes} is empty  -  the caller interprets
     * an empty set as "no restriction" (all downstreams in scope).
     *
     * @param beanTypes the set of declaring class types to filter by
     * @return downstream IDs whose declaring bean type is in {@code beanTypes},
     *         or an empty set if {@code beanTypes} is empty
     */
    public Set<String> getDownstreamIdsForBeanTypes(Set<Class<?>> beanTypes) {
        if (beanTypes.isEmpty()) {
            return Collections.emptySet();
        }
        return registrations.entrySet().stream()
                .filter(e -> beanTypes.contains(e.getValue().getBeanType()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns the validated, sealed dependency graph.
     *
     * @throws IllegalStateException if called before {@link #seal(long)}
     */
    public DependencyGraph getGraph() {
        if (!sealed) {
            throw new IllegalStateException(
                    "DownstreamRegistry has not been sealed yet. "
                            + "Ensure the application context has fully refreshed before accessing the graph.");
        }
        return graph;
    }

    public Map<String, DownstreamRegistration> getRegistrations() {
        return Collections.unmodifiableMap(registrations);
    }

    public boolean isRegistered(String id) {
        return registrations.containsKey(id);
    }
}
