package com.lazyresponse.demo.model.response;

/**
 * Mocks the response from an internal User/Account Service.
 * Root downstream  -  no dependencies. Feeds loyalty.
 */
public class AccountResponse {

    private String id;
    private String name;
    private String tier;         // STANDARD | SILVER | GOLD | PLATINUM
    private String email;
    private String phone;
    private String countryCode;

    public AccountResponse() {
    }

    public AccountResponse(String id, String name, String tier,
                           String email, String phone, String countryCode) {
        this.id = id;
        this.name = name;
        this.tier = tier;
        this.email = email;
        this.phone = phone;
        this.countryCode = countryCode;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
}
