package com.lazyresponse.demo.model.response;

/**
 * Mocks the response from a Logistics / Shipment Tracking Service.
 * Depends on order (needs order id to look up shipment record).
 */
public class ShipmentResponse {

    private String trackingId;
    private String carrier;          // BLUEDART | DELHIVERY | FEDEX | DTDC | EKART
    private String eta;              // ISO-8601 expected delivery date
    private String status;           // PROCESSING | DISPATCHED | IN_TRANSIT | OUT_FOR_DELIVERY | DELIVERED
    private String currentLocation;
    private String dispatchedAt;     // ISO-8601 timestamp

    public ShipmentResponse() {
    }

    public ShipmentResponse(String trackingId, String carrier, String eta,
                            String status, String currentLocation, String dispatchedAt) {
        this.trackingId = trackingId;
        this.carrier = carrier;
        this.eta = eta;
        this.status = status;
        this.currentLocation = currentLocation;
        this.dispatchedAt = dispatchedAt;
    }

    public String getTrackingId() { return trackingId; }
    public void setTrackingId(String trackingId) { this.trackingId = trackingId; }

    public String getCarrier() { return carrier; }
    public void setCarrier(String carrier) { this.carrier = carrier; }

    public String getEta() { return eta; }
    public void setEta(String eta) { this.eta = eta; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCurrentLocation() { return currentLocation; }
    public void setCurrentLocation(String currentLocation) { this.currentLocation = currentLocation; }

    public String getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(String dispatchedAt) { this.dispatchedAt = dispatchedAt; }
}
