package com.lazyresponse.demo.model.response;

/**
 * Mocks the response from an internal Product Catalogue DB read.
 * Root downstream  -  no dependencies. Feeds reviews and pricing.
 */
public class ProductResponse {

    private String id;
    private String name;
    private String category;
    private String brand;
    private boolean active;
    private int stockCount;

    public ProductResponse() {
    }

    public ProductResponse(String id, String name, String category,
                           String brand, boolean active, int stockCount) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.brand = brand;
        this.active = active;
        this.stockCount = stockCount;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public int getStockCount() { return stockCount; }
    public void setStockCount(int stockCount) { this.stockCount = stockCount; }
}
