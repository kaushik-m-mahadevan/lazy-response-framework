package com.lazyresponse.demo.downstream;

import com.lazyresponse.annotation.Default;
import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.context.ExecutionContext;
import com.lazyresponse.demo.model.request.ProductDetailRequest;
import com.lazyresponse.demo.model.response.PricingResponse;
import com.lazyresponse.demo.model.response.ProductResponse;
import com.lazyresponse.demo.model.response.ReviewSummaryResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Mock downstream implementations for the Product Details API demo.
 *
 * <h3>Simulated dependency graph:</h3>
 * <pre>
 *   product ──→ reviews
 *           ──→ pricing
 * </pre>
 *
 * <p>{@code product} is the root — fires immediately. {@code reviews} and {@code pricing}
 * both depend on it and run in parallel once it completes.
 *
 * <h3>Simulated latencies:</h3>
 * <table>
 *   <tr><td>product</td><td>50ms</td><td>Catalogue DB read</td></tr>
 *   <tr><td>reviews</td><td>180ms</td><td>Reviews aggregation service</td></tr>
 *   <tr><td>pricing</td><td>120ms</td><td>Dynamic pricing engine</td></tr>
 * </table>
 *
 * <p>Sequential total: 50+180+120 = 350ms.
 * With the framework: 50 + max(180,120) = 50+180 = 230ms.
 *
 * <h3>Simulated failure scenarios:</h3>
 * <ul>
 *   <li>{@code productId} ending in {@code -GONE} → product not found, throws exception</li>
 *   <li>{@code productId} ending in {@code -NOREV} → reviews service unavailable</li>
 * </ul>
 *
 * <p>This service is registered with {@link com.lazyresponse.demo.controller.ProductDetailController}
 * via {@code @LazyResponse(downstreams = {ProductDetailDownstreams.class})}, demonstrating
 * per-endpoint downstream scoping. Its downstream IDs ({@code product}, {@code reviews},
 * {@code pricing}) are completely isolated from the Order endpoint's IDs.
 */
@Service
public class ProductDetailDownstreams {

    @Downstream(
        id           = "product",
        fields       = {"id", "name", "category", "brand", "active", "stockCount"},
        chainTimeout = 1500
    )
    public ProductResponse fetchProduct(ExecutionContext ctx) {
        ProductDetailRequest req = ctx.getRequest(ProductDetailRequest.class);
        if (req.getProductId() != null && req.getProductId().endsWith("-GONE")) {
            throw new RuntimeException("Product not found: " + req.getProductId());
        }
        sleep(50);
        return new ProductResponse(req.getProductId(), "Wireless Headphones Pro", "Electronics",
                "SoundMax", true, 142);
    }

    @Downstream(
        id        = "reviews",
        fields    = {"averageRating", "reviewCount", "topReview", "verifiedPurchaseOnly"},
        dependsOn = {"product"},
        timeout   = 800,
        defaults  = {
            @Default(field = "averageRating",        value = "0.0"),
            @Default(field = "reviewCount",          value = "0"),
            @Default(field = "verifiedPurchaseOnly", value = "false")
        }
    )
    public ReviewSummaryResponse fetchReviews(ExecutionContext ctx) {
        ProductDetailRequest req = ctx.getRequest(ProductDetailRequest.class);
        if (req.getProductId() != null && req.getProductId().endsWith("-NOREV")) {
            throw new RuntimeException("Reviews service unavailable");
        }
        sleep(180);
        return new ReviewSummaryResponse(4.3, 2187, "Exceptional noise cancellation!", true);
    }

    @Downstream(
        id        = "pricing",
        fields    = {"basePrice", "discountedPrice", "currency", "discountPercent", "onSale"},
        dependsOn = {"product"},
        timeout   = 600,
        defaults  = {
            @Default(field = "currency", value = "INR"),
            @Default(field = "onSale",   value = "false")
        }
    )
    public PricingResponse fetchPricing(ExecutionContext ctx) {
        sleep(120);
        return new PricingResponse(
                new BigDecimal("8999.00"),
                new BigDecimal("7499.00"),
                "INR", 17, true);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
