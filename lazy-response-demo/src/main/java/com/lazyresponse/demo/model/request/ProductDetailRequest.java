package com.lazyresponse.demo.model.request;

/**
 * Inner request POJO for the Product Details lazy endpoint.
 */
public class ProductDetailRequest {

    private String productId;

    public ProductDetailRequest() {
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }
}
