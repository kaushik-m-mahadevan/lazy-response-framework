package com.lazyresponse.demo.controller;

import com.lazyresponse.annotation.LazyAggregator;
import com.lazyresponse.annotation.LazyResponse;
import com.lazyresponse.demo.downstream.OrderDetailDownstreams;
import com.lazyresponse.demo.model.request.OrderDetailRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Demo endpoint modelling a real-world Order Details API.
 *
 * <p>A typical fat aggregation endpoint — callers want different subsets of data
 * depending on the screen. A checkout confirmation page needs payment + shipment.
 * An order list screen needs only order + account. A support dashboard needs everything.
 * Without the framework, all six downstreams are called on every request regardless.
 *
 * <h3>Dependency graph:</h3>
 * <pre>
 *   account ──→ loyalty
 *   order   ──→ payment
 *           ──→ shipment
 *           ──→ inventory
 * </pre>
 *
 * <p>Uses {@code @LazyAggregator} — the idiomatic composite of {@code @RestController}
 * and {@code @LazyController} — and declares {@code downstreams = {OrderDetailDownstreams.class}}
 * so this endpoint's graph is isolated from other lazy endpoints in the same application
 * (e.g., {@link ProductDetailController}).
 *
 * <p>The method body is intentionally empty — the framework intercepts this method
 * via AOP and owns request handling entirely.
 */
@LazyAggregator
@RequestMapping("/api/orders")
public class OrderDetailController {

    @LazyResponse(downstreams = {OrderDetailDownstreams.class})
    @PostMapping("/detail")
    public ResponseEntity<?> getOrderDetail(OrderDetailRequest request) {
        // Framework-owned. This body never executes.
        return null;
    }
}
