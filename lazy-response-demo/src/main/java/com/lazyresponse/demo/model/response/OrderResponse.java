package com.lazyresponse.demo.model.response;

import java.math.BigDecimal;

/**
 * Mocks the response from an internal Order Service DB read.
 * Root downstream  -  no dependencies. Feeds payment, shipment, and inventory.
 */
public class OrderResponse {

    private String id;
    private String status;       // PENDING | CONFIRMED | SHIPPED | DELIVERED | CANCELLED
    private String date;         // ISO-8601 order placement date
    private BigDecimal total;
    private int itemCount;
    private String currencyCode;

    public OrderResponse() {
    }

    public OrderResponse(String id, String status, String date,
                         BigDecimal total, int itemCount, String currencyCode) {
        this.id = id;
        this.status = status;
        this.date = date;
        this.total = total;
        this.itemCount = itemCount;
        this.currencyCode = currencyCode;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }

    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
}
