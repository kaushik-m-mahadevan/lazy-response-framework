package com.lazyresponse.swagger;

import com.lazyresponse.annotation.LazyResponse;
import com.lazyresponse.model.DownstreamRegistration;
import com.lazyresponse.registry.DownstreamRegistry;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.method.HandlerMethod;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * springdoc-openapi {@link OperationCustomizer} that enriches the OpenAPI documentation for
 * all {@link LazyResponse} endpoints.
 *
 * <p>Registered automatically by the framework's auto-configuration when springdoc is on the
 * classpath (conditional on {@code io.swagger.v3.oas.models.OpenAPI} being present).
 *
 * <p>For each {@code @LazyResponse} endpoint, this contributor:
 * <ul>
 *   <li>Replaces the default request body schema with the
 *       {@code {"request": {...}, "template": {...}}} wrapper structure</li>
 *   <li>Appends a description listing all registered downstream ids and their available
 *       fields, forming the published field selection contract</li>
 * </ul>
 */
public class LazyResponseSwaggerContributor implements OperationCustomizer {

    private final DownstreamRegistry registry;

    public LazyResponseSwaggerContributor(DownstreamRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        LazyResponse lazyResponse = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), LazyResponse.class);
        if (lazyResponse == null) {
            return operation;
        }

        operation.requestBody(buildRequestBodySchema(handlerMethod));
        operation.setDescription(buildFieldContractDescription(operation.getDescription(), lazyResponse));

        return operation;
    }

    /**
     * Replaces the request body schema with the lazy wrapper:
     * <pre>{@code
     * {
     *   "request":  { <inner POJO properties> },
     *   "template": { "<downstream-id>": ["field1", "field2"], ... }
     * }
     * }</pre>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private RequestBody buildRequestBodySchema(HandlerMethod handlerMethod) {
        Class<?> requestPojoType = handlerMethod.getMethodParameters()[0].getParameterType();

        Schema requestPojoSchema = new Schema<>()
                .$ref("#/components/schemas/" + requestPojoType.getSimpleName());

        Schema templateSchema = new ObjectSchema()
                .description("Field selection template. Top-level keys are downstream ids. "
                        + "Values are arrays of field names or nested objects for structured downstreams.")
                .additionalProperties(new Schema<>());

        Schema wrapperSchema = new ObjectSchema()
                .addProperty("request", requestPojoSchema)
                .addProperty("template", templateSchema)
                .required(List.of("request", "template"));

        return new RequestBody()
                .required(true)
                .content(new Content()
                        .addMediaType(
                                org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                                new MediaType().schema(wrapperSchema)));
    }

    /**
     * Appends the available downstream field contract to the operation description.
     * Only downstreams in scope for this endpoint are listed. When
     * {@link LazyResponse#downstreams()} is empty, all registered downstreams are shown.
     */
    private String buildFieldContractDescription(String existingDescription, LazyResponse lazyResponse) {
        Set<String> scopedIds = resolveScopedIds(lazyResponse);

        StringBuilder sb = new StringBuilder();
        if (existingDescription != null && !existingDescription.isBlank()) {
            sb.append(existingDescription).append("\n\n");
        }
        sb.append("**Available downstream fields:**\n\n");

        for (Map.Entry<String, DownstreamRegistration> entry
                : registry.getRegistrations().entrySet()) {
            String id = entry.getKey();
            if (!scopedIds.isEmpty() && !scopedIds.contains(id)) {
                continue; // out of scope for this endpoint
            }
            List<String> fields = entry.getValue().getFields();
            List<String> deps = entry.getValue().getDependsOn();

            sb.append("- **").append(id).append("**: `")
                    .append(String.join("`, `", fields)).append("`");
            if (!deps.isEmpty()) {
                sb.append(" _(requires: ").append(String.join(", ", deps)).append(")_");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private Set<String> resolveScopedIds(LazyResponse lazyResponse) {
        Class<?>[] declaredTypes = lazyResponse.downstreams();
        if (declaredTypes.length == 0) {
            return Collections.emptySet();
        }
        Set<Class<?>> beanTypes = Arrays.stream(declaredTypes).collect(Collectors.toSet());
        return registry.getDownstreamIdsForBeanTypes(beanTypes);
    }
}
