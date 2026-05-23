package com.lazyresponse.registry;

import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.annotation.LazyResponse;
import com.lazyresponse.config.LazyResponseProperties;
import com.lazyresponse.exception.ApplicationStartupException;
import com.lazyresponse.model.DownstreamRegistration;
import com.lazyresponse.spi.DownstreamArgumentResolver;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spring {@link BeanPostProcessor} that scans all beans in the application context for
 * {@link Downstream}-annotated methods and registers them with the {@link DownstreamRegistry}.
 *
 * <h3>Discovery strategy:</h3>
 * <ul>
 *   <li>For Spring AOP proxies (e.g., {@code @Transactional}, Spring Security):
 *       {@link AopUtils#getTargetClass(Object)} unwraps to the real class, and its
 *       public methods are scanned.</li>
 *   <li>For JDK dynamic proxies (e.g., {@code @FeignClient}):
 *       the proxy's interfaces are scanned. This allows {@code @Downstream} to be
 *       placed directly on a Feign client interface method  -  no wrapper service needed.</li>
 * </ul>
 *
 * <p>Implements {@link ApplicationContextAware} to resolve {@link DownstreamRegistry} and
 * {@link LazyResponseProperties} lazily from the context rather than via constructor injection.
 * This is required because {@link BeanPostProcessor} beans are instantiated early in the
 * Spring lifecycle  -  before the normal bean post-processing phase  -  and constructor-injecting
 * regular beans at that point causes Spring to emit BeanPostProcessorChecker WARNs for every
 * eagerly-pulled dependency.
 *
 * <p>When the {@link ContextRefreshedEvent} fires, three actions are taken:
 * <ol>
 *   <li>The registry is sealed  -  triggers graph construction, cycle detection, timeout resolution</li>
 *   <li>All {@link LazyResponse}-annotated controller methods are validated against the method contract</li>
 *   <li>All registered {@code @Downstream} methods are validated to have a supporting
 *       {@link DownstreamArgumentResolver}</li>
 * </ol>
 */
public class DownstreamRegistryBeanPostProcessor implements BeanPostProcessor, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);

        // Standard case: Spring AOP proxy or plain bean  -  scan the target class
        scanForDownstreams(bean, targetClass);

        // JDK dynamic proxy case (e.g., @FeignClient)  -  scan the implemented interfaces.
        // AopUtils.getTargetClass() cannot unwrap a plain JDK proxy; the @Downstream
        // annotations live on the interface, not the generated proxy class.
        if (Proxy.isProxyClass(bean.getClass())) {
            for (Class<?> iface : bean.getClass().getInterfaces()) {
                // Avoid double-scanning if the target class IS the interface (edge case)
                if (!iface.equals(targetClass)) {
                    scanForDownstreams(bean, iface);
                }
            }
        }

        return bean;
    }

    private void scanForDownstreams(Object bean, Class<?> scanClass) {
        for (Method method : scanClass.getMethods()) {
            Downstream annotation = AnnotationUtils.findAnnotation(method, Downstream.class);
            if (annotation != null) {
                registry().register(bean, method, annotation, scanClass);
            }
        }
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed(ContextRefreshedEvent event) {
        registry().seal(properties().getTimeout().getGlobal());
        validateLazyResponseMethods(event);
        validateDownstreamArgumentResolvers(event);
        validateScopedDownstreams(event);
    }

    /**
     * Validates all {@link LazyResponse}-annotated methods in the refreshed context against
     * the controller method contract:
     * <ul>
     *   <li>Return type must be {@code ResponseEntity<?>}</li>
     *   <li>Exactly one parameter declared</li>
     *   <li>The parameter must not carry {@code @RequestBody}</li>
     * </ul>
     */
    private void validateLazyResponseMethods(ContextRefreshedEvent event) {
        String[] beanNames = event.getApplicationContext().getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Object bean = event.getApplicationContext().getBean(beanName);
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            for (Method method : targetClass.getMethods()) {
                if (AnnotationUtils.findAnnotation(method, LazyResponse.class) == null) {
                    continue;
                }
                validateReturnType(method);
                validateParameters(method);
            }
        }
    }

    /**
     * Validates that every registered {@code @Downstream} method has at least one
     * {@link DownstreamArgumentResolver} that declares support for it. Fails fast at startup
     * rather than at the first request so configuration errors surface immediately.
     */
    private void validateDownstreamArgumentResolvers(ContextRefreshedEvent event) {
        Map<String, DownstreamArgumentResolver> resolverBeans =
                event.getApplicationContext().getBeansOfType(DownstreamArgumentResolver.class);
        List<DownstreamArgumentResolver> resolvers = List.copyOf(resolverBeans.values());

        for (DownstreamRegistration reg : registry().getRegistrations().values()) {
            Method method = reg.getMethod();
            Class<?> beanType = reg.getBeanType();
            boolean supported = resolvers.stream().anyMatch(r -> r.supports(method, beanType));
            if (!supported) {
                throw new ApplicationStartupException(
                        "@Downstream method '" + method.toGenericString() + "' declared on '"
                                + beanType.getSimpleName() + "' has no registered "
                                + "DownstreamArgumentResolver. Either use the standard "
                                + "(ExecutionContext) parameter signature, or register a custom "
                                + "DownstreamArgumentResolver bean for this method.");
            }
        }
    }

    /**
     * Validates that every {@link LazyResponse#downstreams()} declaration resolves to at least
     * one registered {@code @Downstream} method. Catches the common mistake of forgetting
     * {@code @Service}/{@code @Component} on a downstream bean, or typo-ing the class name,
     * which would otherwise silently produce empty responses at runtime.
     */
    private void validateScopedDownstreams(ContextRefreshedEvent event) {
        String[] beanNames = event.getApplicationContext().getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Object bean = event.getApplicationContext().getBean(beanName);
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            for (Method method : targetClass.getMethods()) {
                LazyResponse ann = AnnotationUtils.findAnnotation(method, LazyResponse.class);
                if (ann == null || ann.downstreams().length == 0) {
                    continue;
                }
                Set<Class<?>> declaredTypes =
                        Arrays.stream(ann.downstreams()).collect(Collectors.toSet());
                Set<String> resolvedIds = registry().getDownstreamIdsForBeanTypes(declaredTypes);
                if (resolvedIds.isEmpty()) {
                    throw new ApplicationStartupException(
                            "@LazyResponse method '" + method.toGenericString()
                                    + "' declares downstreams = "
                                    + Arrays.toString(ann.downstreams())
                                    + " but none of the listed classes have any registered "
                                    + "@Downstream methods. Ensure each class is a Spring bean "
                                    + "(@Service, @Component, etc.) and its methods are annotated "
                                    + "with @Downstream.");
                }
            }
        }
    }

    private void validateReturnType(Method method) {
        if (!ResponseEntity.class.isAssignableFrom(method.getReturnType())) {
            throw new ApplicationStartupException(
                    "@LazyResponse method must declare ResponseEntity<?> as its return type. "
                            + "Offending method: " + method.toGenericString());
        }
    }

    private void validateParameters(Method method) {
        Parameter[] params = method.getParameters();
        if (params.length != 1) {
            throw new ApplicationStartupException(
                    "@LazyResponse method must declare exactly one parameter (the inner request POJO type). "
                            + "Offending method: " + method.toGenericString());
        }
        if (params[0].isAnnotationPresent(RequestBody.class)) {
            throw new ApplicationStartupException(
                    "@LazyResponse method parameter must not carry @RequestBody. "
                            + "The framework reads the body directly. "
                            + "Offending method: " + method.toGenericString());
        }
    }

    private DownstreamRegistry registry() {
        return applicationContext.getBean(DownstreamRegistry.class);
    }

    private LazyResponseProperties properties() {
        return applicationContext.getBean(LazyResponseProperties.class);
    }
}
