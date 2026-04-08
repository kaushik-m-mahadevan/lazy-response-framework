package com.lazyresponse.executor;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * An {@link Executor} decorator that propagates the SLF4J {@link MDC} context map from the
 * submitting thread to each task's execution thread.
 *
 * <p>Without this decorator, downstream tasks running on the framework's thread pool lose the
 * MDC context (request trace ID, correlation ID, user ID, etc.) that was set on the request
 * thread. Log lines emitted from within {@code @Downstream} methods would be missing these
 * context fields, making distributed tracing and log aggregation impossible.
 *
 * <p>This decorator captures the MDC snapshot at submission time (on the request thread),
 * then restores it before the task runs (on the worker thread), and clears it when the
 * task completes. This ensures the worker thread's MDC is always in a known clean state
 * after the task, preventing context leakage to subsequent tasks reusing the same thread.
 *
 * <h3>Layering with security context:</h3>
 * <p>In the auto-configuration, this decorator wraps the outermost executor layer so MDC
 * is captured last (closest to the submission call site) and restored first (outermost on
 * the worker thread):
 * <pre>
 *   MdcDelegatingExecutor
 *     → DelegatingSecurityContextExecutorService (if Spring Security present)
 *       → ThreadPoolExecutor (raw JDK thread pool)
 * </pre>
 */
public class MdcDelegatingExecutor implements Executor {

    private final Executor delegate;

    public MdcDelegatingExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        // Capture the MDC map on the submitting (request) thread
        Map<String, String> capturedContext = MDC.getCopyOfContextMap();

        delegate.execute(() -> {
            Map<String, String> previousContext = MDC.getCopyOfContextMap();
            try {
                if (capturedContext != null) {
                    MDC.setContextMap(capturedContext);
                } else {
                    MDC.clear();
                }
                command.run();
            } finally {
                // Restore the worker thread's prior MDC state to prevent context leakage
                if (previousContext != null) {
                    MDC.setContextMap(previousContext);
                } else {
                    MDC.clear();
                }
            }
        });
    }
}
