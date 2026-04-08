package com.lazyresponse.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a field-level fallback value for a {@link Downstream} when that downstream fails
 * under the {@code silent} failure strategy.
 *
 * <p>Used exclusively as an element inside {@link Downstream#defaults()}. Cannot be applied
 * directly to any program element.
 *
 * <p>Fields with no {@code @Default} return {@code null} on failure.
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Default {

    /** The name of the field this default applies to. Must match a name in {@link Downstream#fields()}. */
    String field();

    /** The string value to use when this field cannot be populated. */
    String value();
}
