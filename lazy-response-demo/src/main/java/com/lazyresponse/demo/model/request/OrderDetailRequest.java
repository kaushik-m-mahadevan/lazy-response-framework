package com.lazyresponse.demo.model.request;

/**
 * Inner request POJO for the Order Details lazy endpoint.
 *
 * <p>Both fields are available to all downstream methods via
 * {@code ctx.getRequest(OrderDetailRequest.class)}. Downstreams use whichever
 * fields are relevant  -  order and payment care about {@code orderId},
 * account and loyalty care about {@code accountId}.
 */
public class OrderDetailRequest {

    private String orderId;
    private String accountId;

    public OrderDetailRequest() {
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }
}
