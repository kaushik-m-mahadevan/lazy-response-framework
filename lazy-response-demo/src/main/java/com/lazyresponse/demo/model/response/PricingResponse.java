package com.lazyresponse.demo.model.response;

import java.math.BigDecimal;

/**
 * Mocks the response from a Dynamic Pricing Service.
 * Depends on {@code product}  -  needs the product category and brand for pricing rules.
 */
public class PricingResponse {

    private BigDecimal basePrice;
    private BigDecimal discountedPrice;
    private String currency;
    private int discountPercent;
    private boolean onSale;

    public PricingResponse() {
    }

    public PricingResponse(BigDecimal basePrice, BigDecimal discountedPrice,
                           String currency, int discountPercent, boolean onSale) {
        this.basePrice = basePrice;
        this.discountedPrice = discountedPrice;
        this.currency = currency;
        this.discountPercent = discountPercent;
        this.onSale = onSale;
    }

    public BigDecimal getBasePrice() { return basePrice; }
    public void setBasePrice(BigDecimal basePrice) { this.basePrice = basePrice; }

    public BigDecimal getDiscountedPrice() { return discountedPrice; }
    public void setDiscountedPrice(BigDecimal discountedPrice) { this.discountedPrice = discountedPrice; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public int getDiscountPercent() { return discountPercent; }
    public void setDiscountPercent(int discountPercent) { this.discountPercent = discountPercent; }

    public boolean isOnSale() { return onSale; }
    public void setOnSale(boolean onSale) { this.onSale = onSale; }
}
