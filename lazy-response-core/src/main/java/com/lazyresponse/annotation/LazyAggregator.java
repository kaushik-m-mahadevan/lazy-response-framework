package com.lazyresponse.annotation;

import org.springframework.core.annotation.AliasFor;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.*;

/**
 * Composite annotation for Spring MVC controllers that serve as lazy-resolution aggregation
 * endpoints. Combines {@link RestController} and {@link LazyController} into a single
 * declaration — the standard way to declare a controller in this framework.
 *
 * <p>{@code @LazyAggregator} exists because every lazy endpoint class needs both:
 * <ul>
 *   <li>{@link RestController} — to register with Spring MVC and enable JSON serialization</li>
 *   <li>{@link LazyController} — to signal that methods inside are framework-managed
 *       and should not be treated as ordinary handler methods by tooling or documentation</li>
 * </ul>
 *
 * <p>Using the two annotations separately works but is verbose and easy to forget one.
 * {@code @LazyAggregator} is the idiomatic, one-annotation way to declare an aggregation
 * controller. {@link LazyController} remains available for use cases where you need the
 * semantic marker without the {@link RestController} behaviour (e.g., mixed controllers
 * that have both lazy and non-lazy endpoints).
 *
 * <h3>Typical usage:</h3>
 * <pre>{@code
 * @LazyAggregator
 * @RequestMapping("/api/orders")
 * public class OrderDetailController {
 *
 *     @LazyResponse(downstreams = {OrderDetailDownstreams.class})
 *     @PostMapping("/detail")
 *     public ResponseEntity<?> getOrderDetail(OrderDetailRequest request) {
 *         return null; // framework-owned — this body never executes
 *     }
 * }
 * }</pre>
 *
 * <p>This is the exact equivalent of:
 * <pre>{@code
 * @LazyController
 * @RestController
 * @RequestMapping("/api/orders")
 * public class OrderDetailController { ... }
 * }</pre>
 *
 * @see LazyController
 * @see LazyResponse
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@RestController
@LazyController
public @interface LazyAggregator {

    /**
     * The value may indicate a suggestion for a logical component name,
     * to be turned into a Spring bean in case of an autodetected component.
     * Forwarded to {@link RestController#value()}.
     */
    @AliasFor(annotation = RestController.class)
    String value() default "";
}
