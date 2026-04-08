package com.lazyresponse.demo.controller;

import com.lazyresponse.annotation.LazyAggregator;
import com.lazyresponse.annotation.LazyResponse;
import com.lazyresponse.demo.downstream.ProductDetailDownstreams;
import com.lazyresponse.demo.model.request.ProductDetailRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Demo endpoint for the Product Details API.
 *
 * <p>Demonstrates two key framework features:
 * <ol>
 *   <li><b>Per-endpoint scoping</b> via {@link LazyResponse#downstreams()}: only downstreams
 *       declared in {@link ProductDetailDownstreams} are eligible for this endpoint.
 *       This makes it safe to deploy alongside {@link OrderDetailController} — the IDs
 *       {@code product}, {@code reviews}, and {@code pricing} are completely isolated from
 *       the Order endpoint's {@code order}, {@code account}, {@code payment}, etc.</li>
 *   <li><b>{@link LazyAggregator} composite annotation</b>: replaces the verbose
 *       {@code @LazyController + @RestController} pair with a single, purposeful annotation
 *       that communicates the role of this class clearly.</li>
 * </ol>
 *
 * <h3>Dependency graph:</h3>
 * <pre>
 *   product ──→ reviews
 *           ──→ pricing
 * </pre>
 *
 * <p>The method body is intentionally empty — the framework intercepts this via AOP.
 */
@LazyAggregator
@RequestMapping("/api/products")
public class ProductDetailController {

    @LazyResponse(downstreams = {ProductDetailDownstreams.class})
    @PostMapping("/detail")
    public ResponseEntity<?> getProductDetail(ProductDetailRequest request) {
        // Framework-owned. This body never executes.
        return null;
    }
}
