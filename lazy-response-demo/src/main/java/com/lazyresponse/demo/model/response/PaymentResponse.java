package com.lazyresponse.demo.model.response;

import java.math.BigDecimal;

/**
 * Mocks the response from a Payment Gateway / internal Payments Service.
 * Depends on order (needs order total and id to look up transaction).
 * Most latency-sensitive downstream — real payment gateways are slow.
 */
public class PaymentResponse {

    private String status;           // PAID | PENDING | FAILED | REFUNDED
    private String method;           // CARD | UPI | NETBANKING | WALLET | COD
    private String paidAt;           // ISO-8601 timestamp, null if not yet paid
    private BigDecimal amount;
    private String transactionId;
    private String gatewayReference;

    public PaymentResponse() {
    }

    public PaymentResponse(String status, String method, String paidAt,
                           BigDecimal amount, String transactionId, String gatewayReference) {
        this.status = status;
        this.method = method;
        this.paidAt = paidAt;
        this.amount = amount;
        this.transactionId = transactionId;
        this.gatewayReference = gatewayReference;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPaidAt() { return paidAt; }
    public void setPaidAt(String paidAt) { this.paidAt = paidAt; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getGatewayReference() { return gatewayReference; }
    public void setGatewayReference(String gatewayReference) { this.gatewayReference = gatewayReference; }
}
