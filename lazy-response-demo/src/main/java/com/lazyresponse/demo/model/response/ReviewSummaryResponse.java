package com.lazyresponse.demo.model.response;

/**
 * Mocks the response from a Reviews Aggregation Service.
 * Depends on {@code product} — needs the product id to query reviews.
 */
public class ReviewSummaryResponse {

    private double averageRating;
    private int reviewCount;
    private String topReview;
    private boolean verifiedPurchaseOnly;

    public ReviewSummaryResponse() {
    }

    public ReviewSummaryResponse(double averageRating, int reviewCount,
                                  String topReview, boolean verifiedPurchaseOnly) {
        this.averageRating = averageRating;
        this.reviewCount = reviewCount;
        this.topReview = topReview;
        this.verifiedPurchaseOnly = verifiedPurchaseOnly;
    }

    public double getAverageRating() { return averageRating; }
    public void setAverageRating(double averageRating) { this.averageRating = averageRating; }

    public int getReviewCount() { return reviewCount; }
    public void setReviewCount(int reviewCount) { this.reviewCount = reviewCount; }

    public String getTopReview() { return topReview; }
    public void setTopReview(String topReview) { this.topReview = topReview; }

    public boolean isVerifiedPurchaseOnly() { return verifiedPurchaseOnly; }
    public void setVerifiedPurchaseOnly(boolean v) { this.verifiedPurchaseOnly = v; }
}
