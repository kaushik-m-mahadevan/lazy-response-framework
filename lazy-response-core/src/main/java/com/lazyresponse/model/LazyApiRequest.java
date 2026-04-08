package com.lazyresponse.model;

import java.util.Map;

/**
 * Deserialization target for the {@code @LazyResponse} request body wrapper.
 *
 * <p>The HTTP body for every lazy endpoint must conform to:
 * <pre>{@code
 * {
 *   "request":  { ... },   // the inner request POJO (type determined by controller parameter)
 *   "template": { ... }    // the field selection template
 * }
 * }</pre>
 *
 * <p>The framework deserializes the body into this class, then re-deserializes the
 * {@code request} value into the controller method's declared parameter type before
 * constructing the {@link com.lazyresponse.context.ExecutionContext}.
 *
 * <p>The {@code template} value is a recursive JSON structure of keys only. Top-level keys
 * identify downstream domains. Leaf arrays list the primitive fields wanted. Nested objects
 * allow arbitrarily deep field selection for structured downstream results.
 */
public class LazyApiRequest {

    /** Raw deserialized request object. Re-typed to the controller's parameter type at runtime. */
    private Map<String, Object> request;

    /** The field selection template. Keys are downstream ids; values are field lists or nested maps. */
    private Map<String, Object> template;

    public LazyApiRequest() {
    }

    public Map<String, Object> getRequest() {
        return request;
    }

    public void setRequest(Map<String, Object> request) {
        this.request = request;
    }

    public Map<String, Object> getTemplate() {
        return template;
    }

    public void setTemplate(Map<String, Object> template) {
        this.template = template;
    }
}
