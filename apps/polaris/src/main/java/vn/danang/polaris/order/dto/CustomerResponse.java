package vn.danang.polaris.order.dto;

import java.time.Instant;

import org.mapstruct.factory.Mappers;

import vn.danang.polaris.order.entity.Customer;
import vn.danang.polaris.order.mapper.CustomerMapper;

/**
 * Customer response DTO containing complete customer profile details (>30 attributes).
 * <p>
 * Demonstrates MapStruct's supreme value in real enterprise applications:
 * In complex domain entities with large numbers of attributes (30+ fields here), manual
 * constructor invocation or setter boilerplate is error-prone, fragile to reordering,
 * and tedious to maintain.
 * <p>
 * Because all properties in this record match {@link Customer} entity identically in name
 * and type, MapStruct automatically binds and maps every single property at compile time
 * with zero manual {@code @Mapping} annotations, full type safety, and zero reflection overhead.
 */
public record CustomerResponse(
    Long id,
    String fullName,
    String firstName,
    String lastName,
    String email,
    String secondaryEmail,
    String phone,
    String mobilePhone,
    String dateOfBirth,
    String gender,
    String avatarUrl,
    String company,
    String jobTitle,
    String department,
    String taxId,
    String billingAddressLine1,
    String billingAddressLine2,
    String billingCity,
    String billingState,
    String billingPostalCode,
    String billingCountry,
    String shippingAddressLine1,
    String shippingAddressLine2,
    String shippingCity,
    String shippingState,
    String shippingPostalCode,
    String shippingCountry,
    String customerTier,
    String status,
    String notes,
    Instant createdAt,
    Instant updatedAt,
    Long version
) {
    private static final CustomerMapper MAPPER = Mappers.getMapper(CustomerMapper.class);

    public static CustomerResponse from(Customer customer) {
        return MAPPER.toResponse(customer);
    }
}
