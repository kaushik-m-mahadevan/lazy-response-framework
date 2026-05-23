package com.lazyresponse.demo.downstream;

import com.lazyresponse.annotation.Default;
import com.lazyresponse.annotation.Downstream;
import com.lazyresponse.context.ExecutionContext;
import com.lazyresponse.demo.model.request.OrderDetailRequest;
import com.lazyresponse.demo.model.response.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Mock downstream implementations for the Order Details API demo.
 *
 * <h3>Simulated dependency graph:</h3>
 * <pre>
 *   account ──→ loyalty
 *   order   ──→ payment
 *           ──→ shipment
 *           ──→ inventory
 * </pre>
 *
 * <p>{@code order} and {@code account} are roots  -  they fire in parallel immediately.
 * {@code payment}, {@code shipment}, and {@code inventory} all depend on {@code order}.
 * {@code loyalty} depends on {@code account}.
 * The second stage ({@code payment}, {@code shipment}, {@code inventory}, {@code loyalty})
 * also runs in parallel once its parents complete.
 *
 * <h3>Simulated latencies (Thread.sleep):</h3>
 * <table>
 *   <tr><td>order</td><td>80ms</td><td>Fast DB read</td></tr>
 *   <tr><td>account</td><td>60ms</td><td>Fast DB read</td></tr>
 *   <tr><td>payment</td><td>400ms</td><td>Payment gateway is always slow</td></tr>
 *   <tr><td>shipment</td><td>200ms</td><td>Logistics API call</td></tr>
 *   <tr><td>inventory</td><td>150ms</td><td>Warehouse IMS call</td></tr>
 *   <tr><td>loyalty</td><td>120ms</td><td>Separate microservice</td></tr>
 * </table>
 *
 * <p>Total latency without the framework: 80+60+400+200+150+120 = ~1010ms (sequential).
 * With the framework: max(80, 60) + max(400, 200, 150, 120) = 80 + 400 = ~480ms.
 *
 * <h3>Simulated failure scenarios (driven by orderId / accountId suffix):</h3>
 * <ul>
 *   <li>{@code orderId} ending in {@code -FAIL}    → payment downstream throws an exception</li>
 *   <li>{@code orderId} ending in {@code -SLOW}    → payment sleeps 4000ms, triggering timeout</li>
 *   <li>{@code accountId} ending in {@code -NOLOY} → loyalty downstream throws (no loyalty record)</li>
 * </ul>
 */
@Service
public class OrderDetailDownstreams {

    // -------------------------------------------------------------------------
    // Root downstreams  -  no dependencies, fire in parallel immediately
    // -------------------------------------------------------------------------

    @Downstream(
        id          = "order",
        fields      = {"id", "status", "date", "total", "itemCount", "currencyCode"},
        chainTimeout = 2000
    )
    public OrderResponse fetchOrder(ExecutionContext ctx) {
        OrderDetailRequest req = ctx.getRequest(OrderDetailRequest.class);
        sleep(80);
        return new OrderResponse(
            req.getOrderId(),
            "SHIPPED",
            "2026-04-01T10:30:00Z",
            new BigDecimal("4299.00"),
            3,
            "INR"
        );
    }

    @Downstream(
        id          = "account",
        fields      = {"id", "name", "tier", "email", "phone", "countryCode"},
        chainTimeout = 1500
    )
    public AccountResponse fetchAccount(ExecutionContext ctx) {
        OrderDetailRequest req = ctx.getRequest(OrderDetailRequest.class);
        sleep(60);
        return new AccountResponse(
            req.getAccountId(),
            "Kaushik Rangarajan",
            "GOLD",
            "kaushik@example.com",
            "+91-98765-43210",
            "IN"
        );
    }

    // -------------------------------------------------------------------------
    // Second-stage downstreams  -  depend on order or account
    // -------------------------------------------------------------------------

    @Downstream(
        id        = "payment",
        fields    = {"status", "method", "paidAt", "amount", "transactionId", "gatewayReference"},
        dependsOn = {"order"},
        timeout   = 1500,
        defaults  = {
            @Default(field = "status",           value = "unknown"),
            @Default(field = "method",           value = "—"),
            @Default(field = "gatewayReference", value = "—")
        }
    )
    public PaymentResponse fetchPayment(ExecutionContext ctx) {
        OrderDetailRequest req   = ctx.getRequest(OrderDetailRequest.class);
        OrderResponse      order = ctx.get("order", OrderResponse.class);

        // Simulate failure scenarios for demo/testing purposes
        if (req.getOrderId() != null && req.getOrderId().endsWith("-FAIL")) {
            throw new RuntimeException("Payment gateway connection refused for order: " + order.getId());
        }
        if (req.getOrderId() != null && req.getOrderId().endsWith("-SLOW")) {
            sleep(4000); // Exceeds the 1500ms timeout  -  triggers timeout path
        }

        sleep(400);
        return new PaymentResponse(
            "PAID",
            "CARD",
            "2026-04-01T10:32:15Z",
            order.getTotal(),
            "TXN-" + order.getId(),
            "GW-REF-88291KA"
        );
    }

    @Downstream(
        id        = "shipment",
        fields    = {"trackingId", "carrier", "eta", "status", "currentLocation", "dispatchedAt"},
        dependsOn = {"order"},
        timeout   = 1200
    )
    public ShipmentResponse fetchShipment(ExecutionContext ctx) {
        OrderResponse order = ctx.get("order", OrderResponse.class);
        sleep(200);
        return new ShipmentResponse(
            "BD-" + order.getId() + "-TRK",
            "BLUEDART",
            "2026-04-05T18:00:00Z",
            "IN_TRANSIT",
            "Mumbai Hub",
            "2026-04-02T08:00:00Z"
        );
    }

    @Downstream(
        id        = "inventory",
        fields    = {"warehouseId", "warehouseName", "stockStatus", "reservedQty", "dispatchedAt", "zone"},
        dependsOn = {"order"},
        timeout   = 1000
    )
    public InventoryResponse fetchInventory(ExecutionContext ctx) {
        OrderResponse order = ctx.get("order", OrderResponse.class);
        sleep(150);
        return new InventoryResponse(
            "WH-BOM-04",
            "Mumbai Central Warehouse",
            "RESERVED",
            order.getItemCount(),
            "2026-04-02T07:45:00Z",
            "WEST"
        );
    }

    @Downstream(
        id        = "loyalty",
        fields    = {"points", "rewardTier", "nextReward", "expiryDate", "pointsEarnedThisYear", "enrolledInCashback"},
        dependsOn = {"account"},
        timeout   = 800,
        defaults  = {
            @Default(field = "points",    value = "0"),
            @Default(field = "rewardTier", value = "BRONZE")
        }
    )
    public LoyaltyResponse fetchLoyalty(ExecutionContext ctx) {
        OrderDetailRequest req     = ctx.getRequest(OrderDetailRequest.class);
        AccountResponse    account = ctx.get("account", AccountResponse.class);

        if (req.getAccountId() != null && req.getAccountId().endsWith("-NOLOY")) {
            throw new RuntimeException("No loyalty record found for account: " + account.getId());
        }

        sleep(120);
        return new LoyaltyResponse(
            12450,
            "GOLD",
            "500 more points to unlock PLATINUM",
            "2026-12-31",
            3200,
            true
        );
    }

    // -------------------------------------------------------------------------

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
