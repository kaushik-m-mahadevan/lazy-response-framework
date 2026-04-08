package com.lazyresponse.demo.model.response;

/**
 * Mocks the response from a Warehouse / Inventory Management System.
 * Depends on order (uses order id to determine which SKUs and warehouse are involved).
 * In a real system this would hit an IMS like SAP WM or a homegrown warehouse service.
 */
public class InventoryResponse {

    private String warehouseId;
    private String warehouseName;
    private String stockStatus;      // IN_STOCK | LOW_STOCK | OUT_OF_STOCK | RESERVED
    private int reservedQty;
    private String dispatchedAt;     // ISO-8601, null if not yet dispatched
    private String zone;             // Fulfilment zone: NORTH | SOUTH | EAST | WEST | CENTRAL

    public InventoryResponse() {
    }

    public InventoryResponse(String warehouseId, String warehouseName, String stockStatus,
                             int reservedQty, String dispatchedAt, String zone) {
        this.warehouseId = warehouseId;
        this.warehouseName = warehouseName;
        this.stockStatus = stockStatus;
        this.reservedQty = reservedQty;
        this.dispatchedAt = dispatchedAt;
        this.zone = zone;
    }

    public String getWarehouseId() { return warehouseId; }
    public void setWarehouseId(String warehouseId) { this.warehouseId = warehouseId; }

    public String getWarehouseName() { return warehouseName; }
    public void setWarehouseName(String warehouseName) { this.warehouseName = warehouseName; }

    public String getStockStatus() { return stockStatus; }
    public void setStockStatus(String stockStatus) { this.stockStatus = stockStatus; }

    public int getReservedQty() { return reservedQty; }
    public void setReservedQty(int reservedQty) { this.reservedQty = reservedQty; }

    public String getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(String dispatchedAt) { this.dispatchedAt = dispatchedAt; }

    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
}
