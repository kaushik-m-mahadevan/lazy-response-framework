package com.lazyresponse.model;

import java.util.List;

/**
 * The {@code meta} block of every {@link LazyApiResponse}.
 *
 * <p>All fields are always present in the serialized response. Callers must not treat
 * an absent list as equivalent to an empty one.
 */
public class LazyMeta {

    private final List<FieldError> errors;
    private final List<String> warnings;
    private final boolean partial;

    public LazyMeta(List<FieldError> errors, List<String> warnings, boolean partial) {
        this.errors = List.copyOf(errors);
        this.warnings = List.copyOf(warnings);
        this.partial = partial;
    }

    /**
     * Fields that could not be populated and had no configured {@code @Default} fallback.
     * Empty when all requested fields were populated successfully or received a default value.
     */
    public List<FieldError> getErrors() {
        return errors;
    }

    /**
     * Noteworthy conditions where a downstream succeeded but something is worth surfacing  - 
     * for example, a downstream that responded slower than a soft threshold without hitting
     * its hard timeout.
     */
    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * {@code true} when at least one requested downstream failed, timed out, was blocked by
     * a failed parent, or returned {@code null}. {@code false} means every requested
     * downstream succeeded and produced a non-null result.
     *
     * <p>This is a convenience flag for clients that use HTTP status as a completeness proxy.
     * The full field-level detail is always in {@link #getErrors()}.
     */
    public boolean isPartial() {
        return partial;
    }
}
