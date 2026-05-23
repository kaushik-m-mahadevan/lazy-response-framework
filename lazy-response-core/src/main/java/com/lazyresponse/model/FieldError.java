package com.lazyresponse.model;

/**
 * A single entry in the {@code meta.errors} block of the response envelope.
 *
 * <p>One entry is emitted per field that could not be populated and has no configured
 * {@code @Default} fallback. Fields that fail but have a {@code @Default} receive that
 * value in the {@code data} block and do not generate an error entry.
 */
public class FieldError {

    private final String downstream;
    private final String field;
    private final String reason;

    public FieldError(String downstream, String field, String reason) {
        this.downstream = downstream;
        this.field = field;
        this.reason = reason;
    }

    /** The id of the downstream that failed. */
    public String getDownstream() {
        return downstream;
    }

    /** The field name that could not be populated. */
    public String getField() {
        return field;
    }

    /**
     * The reason the field could not be populated. One of:
     * <ul>
     *   <li>{@code timeout}  -  the downstream exceeded its effective timeout</li>
     *   <li>{@code error}  -  the downstream threw an unhandled exception</li>
     *   <li>{@code dependency-failed}  -  a declared parent downstream failed</li>
     *   <li>{@code blocked}  -  a parent was blocked and this downstream was never reached</li>
     * </ul>
     */
    public String getReason() {
        return reason;
    }
}
