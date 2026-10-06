package vn.danang.polaris.order.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.danang.polaris.order.entity.Customer;

/**
 * Lightweight customer summary for fuzzy name search results.
 */
public record CustomerSummaryResponse(
        @JsonProperty("id") Long id,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("email") String email,
        @JsonProperty("phone") String phone
) {
    public static CustomerSummaryResponse from(Customer customer) {
        return new CustomerSummaryResponse(
                customer.getId(),
                customer.getFullName(),
                customer.getEmail(),
                customer.getPhone()
        );
    }
}
