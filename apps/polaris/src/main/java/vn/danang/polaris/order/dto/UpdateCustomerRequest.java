package vn.danang.polaris.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload for updating a customer profile.
 * Incorporates an optimistic locking version token to guard against concurrent lost updates.
 */
public record UpdateCustomerRequest(
    @NotNull(message = "Version is required for optimistic concurrency control")
    @Schema(description = "Optimistic concurrency version token (obtained from previous GET representation)", example = "0")
    Long version,

    @NotBlank(message = "Full name must not be blank")
    @Schema(description = "Customer full name", example = "Alice Tran")
    String fullName,

    @Schema(description = "First name", example = "Alice")
    String firstName,

    @Schema(description = "Last name", example = "Tran")
    String lastName,

    @Schema(description = "Primary contact email", example = "alice.tran@example.com")
    String email,

    @Schema(description = "Secondary email address", example = "alice.personal@example.com")
    String secondaryEmail,

    @Schema(description = "Primary phone number", example = "0901111111")
    String phone,

    @Schema(description = "Mobile phone number", example = "0901111199")
    String mobilePhone,

    @Schema(description = "Date of birth in YYYY-MM-DD format", example = "1992-05-14")
    String dateOfBirth,

    @Schema(description = "Gender", example = "Female")
    String gender,

    @Schema(description = "Avatar photo URI", example = "https://polaris.local/avatars/alice.png")
    String avatarUrl,

    @Schema(description = "Company / employer name", example = "Danang Tech Solutions")
    String company,

    @Schema(description = "Job title", example = "Lead Principal Engineer")
    String jobTitle,

    @Schema(description = "Department name", example = "Platform Engineering")
    String department,

    @Schema(description = "Tax identification number", example = "VN-987654321")
    String taxId,

    @Schema(description = "Billing address line 1", example = "123 Bach Dang St")
    String billingAddressLine1,

    @Schema(description = "Billing address line 2", example = "Floor 4, Suite 402")
    String billingAddressLine2,

    @Schema(description = "Billing city", example = "Da Nang")
    String billingCity,

    @Schema(description = "Billing state or province", example = "Hai Chau")
    String billingState,

    @Schema(description = "Billing postal code", example = "550000")
    String billingPostalCode,

    @Schema(description = "Billing country", example = "Vietnam")
    String billingCountry,

    @Schema(description = "Shipping address line 1", example = "123 Bach Dang St")
    String shippingAddressLine1,

    @Schema(description = "Shipping address line 2", example = "Floor 4, Suite 402")
    String shippingAddressLine2,

    @Schema(description = "Shipping city", example = "Da Nang")
    String shippingCity,

    @Schema(description = "Shipping state or province", example = "Hai Chau")
    String shippingState,

    @Schema(description = "Shipping postal code", example = "550000")
    String shippingPostalCode,

    @Schema(description = "Shipping country", example = "Vietnam")
    String shippingCountry,

    @Schema(description = "Customer loyalty or account tier", example = "VIP")
    String customerTier,

    @Schema(description = "Account operational status", example = "ACTIVE")
    String status,

    @Schema(description = "Customer notes or account annotations", example = "Updated via customer portal")
    String notes
) {
    public static UpdateCustomerRequest of(Long version, String fullName) {
        return new UpdateCustomerRequest(
                version, fullName, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null
        );
    }
}
