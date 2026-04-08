package com.lazyresponse.annotation;

import java.lang.annotation.*;

/**
 * Semantic marker for controller classes that contain {@link LazyResponse} endpoints.
 *
 * <p>This annotation signals that the class is a lazy aggregation controller — its methods
 * are framework-managed and should not be treated as ordinary handler methods by tooling
 * or documentation generators.
 *
 * <p>This annotation does not activate Spring MVC component scanning on its own.
 * It is designed to be used in combination with {@link org.springframework.web.bind.annotation.RestController}:
 *
 * <pre>{@code
 * @LazyController
 * @RestController
 * @RequestMapping("/api/orders")
 * public class OrderDetailController { ... }
 * }</pre>
 *
 * <p>For the common case of a pure lazy aggregation controller — where all endpoints are
 * {@link LazyResponse}-annotated — prefer the {@link LazyAggregator} composite annotation,
 * which combines {@code @LazyController} and {@code @RestController} into a single declaration:
 *
 * <pre>{@code
 * @LazyAggregator          // equivalent to @LazyController + @RestController
 * @RequestMapping("/api/orders")
 * public class OrderDetailController { ... }
 * }</pre>
 *
 * <p>Use {@code @LazyController} directly (without {@link LazyAggregator}) when you need
 * the semantic marker on a controller that mixes lazy and non-lazy endpoints, or when
 * applying {@code @RestController} is not appropriate.
 *
 * @see LazyAggregator
 * @see LazyResponse
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LazyController {
}
