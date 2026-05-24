package com.lazyresponse.model;

import com.lazyresponse.annotation.Default;
import com.lazyresponse.annotation.Downstream;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Internal registry record for a discovered {@code @Downstream} method.
 *
 * <p>Holds all metadata needed to invoke the downstream and assemble its contribution
 * to the response. The {@code effectiveTimeout} field is computed at startup by
 * {@link com.lazyresponse.graph.DependencyGraph#resolveChainTimeouts(long)} after the graph
 * is validated and cannot be set by application code.
 *
 * <p>{@code beanType} is the actual (non-proxied) class that declares the method, used for:
 * <ul>
 *   <li>Per-endpoint scoping via {@link com.lazyresponse.annotation.LazyResponse#downstreams()}</li>
 *   <li>Selecting the correct {@link com.lazyresponse.spi.DownstreamArgumentResolver}</li>
 * </ul>
 *
 * <p>{@code fieldTypes} maps each declared field name to its Java type, resolved from the
 * method's return type at registration time. Used to coerce {@link Default} string values
 * to the correct type when assembling failure responses.
 */
public class DownstreamRegistration {

    private final Object bean;
    private final Method method;
    private final Class<?> beanType;
    private final String id;
    private final List<String> fields;
    private final List<String> dependsOn;
    private final long chainTimeout;
    private final long timeout;
    private final Map<String, String> defaults;
    private final Map<String, Class<?>> fieldTypes;

    /**
     * Set by the framework during the startup graph resolution phase.
     * Priority: per-node timeout > chain timeout (most conservative) > global.
     */
    private long effectiveTimeout;

    public DownstreamRegistration(Object bean, Method method, Downstream annotation, Class<?> beanType) {
        this.bean = bean;
        this.method = method;
        this.beanType = beanType;
        this.id = annotation.id();
        this.fields = Collections.unmodifiableList(Arrays.asList(annotation.fields()));
        this.dependsOn = Collections.unmodifiableList(Arrays.asList(annotation.dependsOn()));
        this.chainTimeout = annotation.chainTimeout();
        this.timeout = annotation.timeout();
        this.defaults = Arrays.stream(annotation.defaults())
                .collect(Collectors.toUnmodifiableMap(Default::field, Default::value));
        this.fieldTypes = resolveFieldTypes(method.getReturnType(), annotation.fields());
    }

    /**
     * Resolves the Java type of each declared field by inspecting the method's return type.
     * Supports getter-based access ({@code getField()}, {@code isField()}) and direct field
     * access. Returns an empty map for raw {@link Map} return types (types cannot be resolved).
     */
    private static Map<String, Class<?>> resolveFieldTypes(Class<?> returnType, String[] fieldNames) {
        if (Map.class.isAssignableFrom(returnType) || returnType == void.class) {
            return Collections.emptyMap();
        }
        Map<String, Class<?>> types = new LinkedHashMap<>();
        for (String fieldName : fieldNames) {
            Class<?> type = resolveFieldType(returnType, fieldName);
            if (type != null) {
                types.put(fieldName, type);
            }
        }
        return Collections.unmodifiableMap(types);
    }

    private static Class<?> resolveFieldType(Class<?> clazz, String fieldName) {
        String capitalized = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        // Try standard getter: getFieldName()  -  getMethod() walks the full hierarchy
        try {
            return clazz.getMethod("get" + capitalized).getReturnType();
        } catch (NoSuchMethodException ignored) { }
        // Try boolean getter: isFieldName()  -  getMethod() walks the full hierarchy
        try {
            return clazz.getMethod("is" + capitalized).getReturnType();
        } catch (NoSuchMethodException ignored) { }
        // Try direct field access (for record-like classes).
        // getDeclaredField only checks the exact class, so walk the hierarchy manually.
        Class<?> searchClass = clazz;
        while (searchClass != null && searchClass != Object.class) {
            try {
                Field f = searchClass.getDeclaredField(fieldName);
                return f.getType();
            } catch (NoSuchFieldException ignored) { }
            searchClass = searchClass.getSuperclass();
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Object getBean() {
        return bean;
    }

    public Method getMethod() {
        return method;
    }

    public Class<?> getBeanType() {
        return beanType;
    }

    public String getId() {
        return id;
    }

    public List<String> getFields() {
        return fields;
    }

    public List<String> getDependsOn() {
        return dependsOn;
    }

    public long getChainTimeout() {
        return chainTimeout;
    }

    public long getTimeout() {
        return timeout;
    }

    public Map<String, String> getDefaults() {
        return defaults;
    }

    /**
     * Returns a map from field name to its resolved Java type, derived from the method's
     * return type at registration time. Empty for {@code Map}-returning downstreams.
     */
    public Map<String, Class<?>> getFieldTypes() {
        return fieldTypes;
    }

    public long getEffectiveTimeout() {
        return effectiveTimeout;
    }

    public void setEffectiveTimeout(long effectiveTimeout) {
        this.effectiveTimeout = effectiveTimeout;
    }

    public boolean isRootNode() {
        return dependsOn.isEmpty();
    }
}
