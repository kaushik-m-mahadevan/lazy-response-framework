package com.lazyresponse.model;

import java.util.Map;

/**
 * The response envelope returned by every {@code @LazyResponse} endpoint.
 *
 * <p>Structure is fixed regardless of which fields were requested or whether failures occurred:
 * <pre>{@code
 * {
 *   "data": {
 *     "order":   { "id": "ORD-123", "status": "shipped" },
 *     "payment": { "status": "unknown", "method": null }
 *   },
 *   "meta": {
 *     "errors":   [{ "downstream": "payment", "field": "method", "reason": "timeout" }],
 *     "warnings": []
 *   }
 * }
 * }</pre>
 *
 * <p>The {@code data} block contains only the fields that were requested. The outer key is
 * the downstream id. The inner map holds field name to value entries. Values may be nested
 * maps when the field selection template uses nested objects.
 */
public class LazyApiResponse {

    private final Map<String, Object> data;
    private final LazyMeta meta;

    public LazyApiResponse(Map<String, Object> data, LazyMeta meta) {
        this.data = data;
        this.meta = meta;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public LazyMeta getMeta() {
        return meta;
    }
}
