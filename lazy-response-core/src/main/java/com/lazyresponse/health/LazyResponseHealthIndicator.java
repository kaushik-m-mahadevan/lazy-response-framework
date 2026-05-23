package com.lazyresponse.health;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

import java.util.concurrent.Semaphore;

/**
 * Spring Boot Actuator {@link org.springframework.boot.actuate.health.HealthIndicator} for
 * the Lazy Response Framework. Exposes semaphore availability as a health signal at
 * {@code /actuator/health/lazyResponse}.
 *
 * <p>Registered by the auto-configuration when {@code spring-boot-actuate} is on the
 * classpath. No additional configuration required.
 *
 * <h3>Health states:</h3>
 * <ul>
 *   <li>{@code UP}  -  at least one semaphore permit is available. Requests can be served.
 *       Reports {@code available} and {@code total} permit counts as detail fields.</li>
 *   <li>{@code DOWN}  -  no semaphore permits are available. All incoming requests will
 *       receive HTTP 503 until permits are freed. This indicates the thread pool is fully
 *       saturated and the system is under extreme load or a downstream is hanging.</li>
 * </ul>
 *
 * <h3>Example response:</h3>
 * <pre>{@code
 * {
 *   "status": "UP",
 *   "details": {
 *     "semaphore.available": 17,
 *     "semaphore.total": 20,
 *     "semaphore.utilization": "15%"
 *   }
 * }
 * }</pre>
 */
public class LazyResponseHealthIndicator extends AbstractHealthIndicator {

    private final Semaphore semaphore;
    private final int totalPermits;

    public LazyResponseHealthIndicator(Semaphore semaphore, int totalPermits) {
        super("Lazy Response Framework health check failed");
        this.semaphore = semaphore;
        this.totalPermits = totalPermits;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        int available = semaphore.availablePermits();
        int used = totalPermits - available;
        int utilizationPct = totalPermits > 0 ? (used * 100 / totalPermits) : 0;

        builder.withDetail("semaphore.available", available)
               .withDetail("semaphore.total", totalPermits)
               .withDetail("semaphore.utilization", utilizationPct + "%");

        if (available > 0) {
            builder.up();
        } else {
            builder.down()
                   .withDetail("message", "Thread pool fully saturated  -  all requests will be rejected (HTTP 503)");
        }
    }
}
