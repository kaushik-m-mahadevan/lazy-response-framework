package com.lazyresponse.spi;

import com.lazyresponse.context.ExecutionContext;

import java.lang.reflect.Method;

/**
 * Default {@link DownstreamArgumentResolver} for {@code @Downstream} methods that follow
 * the standard single-{@link ExecutionContext}-parameter convention.
 *
 * <p>Supports any method whose sole parameter is, or extends, {@link ExecutionContext}.
 * Always registered automatically by the framework's auto-configuration. Custom resolvers
 * registered as beans are evaluated before this one, so they take precedence.
 *
 * <h3>Supported signature:</h3>
 * <pre>{@code
 * @Downstream(id = "order", fields = {"id", "status"})
 * public OrderResponse fetchOrder(ExecutionContext ctx) { ... }
 * }</pre>
 */
public class ExecutionContextArgumentResolver implements DownstreamArgumentResolver {

    @Override
    public boolean supports(Method method, Class<?> targetClass) {
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1 && ExecutionContext.class.isAssignableFrom(params[0]);
    }

    @Override
    public Object[] resolve(Method method, ExecutionContext ctx) {
        return new Object[]{ctx};
    }
}
