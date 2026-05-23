package com.lazyresponse.demo.model.response;

/**
 * Mocks the response from a Loyalty / Rewards Points Service.
 * Depends on account (loyalty profile is keyed to account tier and id).
 * In a real system this is often a separate microservice with its own DB,
 * making it an ideal candidate for lazy loading  -  most pages don't need it.
 */
public class LoyaltyResponse {

    private int points;
    private String rewardTier;       // BRONZE | SILVER | GOLD | PLATINUM  -  may differ from account tier
    private String nextReward;       // Human-readable description of next milestone
    private String expiryDate;       // ISO-8601 date after which current points expire
    private int pointsEarnedThisYear;
    private boolean enrolledInCashback;

    public LoyaltyResponse() {
    }

    public LoyaltyResponse(int points, String rewardTier, String nextReward,
                           String expiryDate, int pointsEarnedThisYear, boolean enrolledInCashback) {
        this.points = points;
        this.rewardTier = rewardTier;
        this.nextReward = nextReward;
        this.expiryDate = expiryDate;
        this.pointsEarnedThisYear = pointsEarnedThisYear;
        this.enrolledInCashback = enrolledInCashback;
    }

    public int getPoints() { return points; }
    public void setPoints(int points) { this.points = points; }

    public String getRewardTier() { return rewardTier; }
    public void setRewardTier(String rewardTier) { this.rewardTier = rewardTier; }

    public String getNextReward() { return nextReward; }
    public void setNextReward(String nextReward) { this.nextReward = nextReward; }

    public String getExpiryDate() { return expiryDate; }
    public void setExpiryDate(String expiryDate) { this.expiryDate = expiryDate; }

    public int getPointsEarnedThisYear() { return pointsEarnedThisYear; }
    public void setPointsEarnedThisYear(int pointsEarnedThisYear) { this.pointsEarnedThisYear = pointsEarnedThisYear; }

    public boolean isEnrolledInCashback() { return enrolledInCashback; }
    public void setEnrolledInCashback(boolean enrolledInCashback) { this.enrolledInCashback = enrolledInCashback; }
}
