package com.lazyresponse.spi;

import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.context.ExecutionContext;

import java.lang.reflect.Method;

/**
 * SPI for resolving method arguments for {@code @Downstream} methods that do not follow
 * the default single-{@link ExecutionContext}-parameter convention.
 *
 * <p>Implement this interface and register it as a Spring bean to support {@code @Downstream}
 * on methods with custom signatures — the primary use case being {@code @FeignClient} interface
 * methods that take typed parameters instead of an {@link ExecutionContext}.
 *
 * <p>The framework ships with {@link ExecutionContextArgumentResolver} for standard methods.
 * Custom resolvers are checked first (in bean declaration order); the built-in resolver is
 * checked last as a fallback.
 *
 * <h3>Example — applying {@code @Downstream} directly on a Feign client:</h3>
 * <pre>{@code
 * // 1. Annotate the FeignClient method directly — no wrapper service needed
 * @FeignClient(name = "order-service")
 * interface OrderClient {
 *     @Downstream(id = "order", fields = {"id", "status", "total"}, chainTimeout = 2000)
 *     OrderResponse getOrder(@RequestParam String orderId);
 * }
 *
 * // 2. Register a resolver that bridges ExecutionContext → method arguments
 * @Component
 * public class OrderClientResolver implements DownstreamArgumentResolver {
 *
 *     @Override
 *     public boolean supports(Method method, Class<?> targetClass) {
 *         return OrderClient.class.isAssignableFrom(targetClass);
 *     }
 *
 *     @Override
 *     public Object[] resolve(Method method, ExecutionContext ctx) {
 *         return new Object[]{ ctx.getRequest(OrderDetailRequest.class).getOrderId() };
 *     }
 * }
 * }</pre>
 *
 * <p>With this setup the {@code @Downstream} annotation on {@code OrderClient.getOrder()}
 * is discovered automatically, and the framework invokes the Feign proxy directly — no
 * boilerplate wrapper {@code @Service} class required.
 *
 * @see ExecutionContextArgumentResolver
 * @see Downstream
 */
public interface DownstreamArgumentResolver {

    /**
     * Returns {@code true} if this resolver can produce arguments for the given method.
     *
     * @param method      the {@code @Downstream}-annotated method to be invoked
     * @param targetClass the actual (non-proxied) class that declares the method
     */
    boolean supports(Method method, Class<?> targetClass);

    /**
     * Resolves the argument array to pass to {@code method.invoke(bean, args)}.
     *
     * @param method the method to be invoked
     * @param ctx    the per-request execution context
     * @return the argument array; must match the method's declared parameter types exactly
     */
    Object[] resolve(Method method, ExecutionContext ctx);
}
