package com.lazyresponse.exception;

/**
 * Thrown during application startup when the Lazy Response Framework detects a fatal
 * configuration error. Causes the application context to fail to start.
 *
 * <p>Conditions that trigger this exception:
 * <ul>
 *   <li>A cycle is detected in the downstream dependency graph (via Kahn's algorithm)</li>
 *   <li>A {@code dependsOn} reference points to an unregistered downstream id</li>
 *   <li>Two downstreams declare the same {@code id}</li>
 *   <li>A {@code @LazyResponse} method violates the controller method contract</li>
 * </ul>
 */
public class ApplicationStartupException extends RuntimeException {

    public ApplicationStartupException(String message) {
        super(message);
    }
}
