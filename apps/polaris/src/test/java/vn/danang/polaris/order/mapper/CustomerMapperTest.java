package vn.danang.polaris.order.mapper;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import vn.danang.polaris.order.dto.CustomerResponse;
import vn.danang.polaris.order.dto.UpdateCustomerRequest;
import vn.danang.polaris.order.entity.Customer;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CustomerMapper Unit Tests")
class CustomerMapperTest {

    private final CustomerMapper mapper = Mappers.getMapper(CustomerMapper.class);

    private Customer createRichSampleCustomer() {
        Customer customer = new Customer();
        customer.setId(1L);
        customer.setFullName("Alice Tran");
        customer.setFirstName("Alice");
        customer.setLastName("Tran");
        customer.setEmail("alice.tran@example.com");
        customer.setSecondaryEmail("alice.personal@example.com");
        customer.setPhone("0901111111");
        customer.setMobilePhone("0901111199");
        customer.setDateOfBirth("1992-05-14");
        customer.setGender("Female");
        customer.setAvatarUrl("https://polaris.local/avatars/alice.png");
        customer.setCompany("Danang Tech Solutions");
        customer.setJobTitle("Senior Software Engineer");
        customer.setDepartment("Engineering");
        customer.setTaxId("VN-987654321");
        customer.setBillingAddressLine1("123 Bach Dang St");
        customer.setBillingAddressLine2("Floor 4, Suite 402");
        customer.setBillingCity("Da Nang");
        customer.setBillingState("Hai Chau");
        customer.setBillingPostalCode("550000");
        customer.setBillingCountry("Vietnam");
        customer.setShippingAddressLine1("123 Bach Dang St");
        customer.setShippingAddressLine2("Floor 4, Suite 402");
        customer.setShippingCity("Da Nang");
        customer.setShippingState("Hai Chau");
        customer.setShippingPostalCode("550000");
        customer.setShippingCountry("Vietnam");
        customer.setCustomerTier("GOLD");
        customer.setStatus("ACTIVE");
        customer.setNotes("High-value enterprise customer since 2024");
        customer.setCreatedAt(Instant.parse("2026-01-15T08:30:00Z"));
        customer.setUpdatedAt(Instant.parse("2026-03-01T10:00:00Z"));
        customer.setVersion(0L);
        return customer;
    }

    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("Should automatically map all 30+ identical properties from Customer entity to CustomerResponse")
        void shouldMapAllPropertiesFromCustomerToCustomerResponse() {
            Customer customer = createRichSampleCustomer();

            CustomerResponse response = mapper.toResponse(customer);

            assertThat(response).isNotNull();
            // Identifiers & Names
            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.fullName()).isEqualTo("Alice Tran");
            assertThat(response.firstName()).isEqualTo("Alice");
            assertThat(response.lastName()).isEqualTo("Tran");

            // Communications
            assertThat(response.email()).isEqualTo("alice.tran@example.com");
            assertThat(response.secondaryEmail()).isEqualTo("alice.personal@example.com");
            assertThat(response.phone()).isEqualTo("0901111111");
            assertThat(response.mobilePhone()).isEqualTo("0901111199");

            // Profile & Demographics
            assertThat(response.dateOfBirth()).isEqualTo("1992-05-14");
            assertThat(response.gender()).isEqualTo("Female");
            assertThat(response.avatarUrl()).isEqualTo("https://polaris.local/avatars/alice.png");

            // Organization
            assertThat(response.company()).isEqualTo("Danang Tech Solutions");
            assertThat(response.jobTitle()).isEqualTo("Senior Software Engineer");
            assertThat(response.department()).isEqualTo("Engineering");
            assertThat(response.taxId()).isEqualTo("VN-987654321");

            // Billing address
            assertThat(response.billingAddressLine1()).isEqualTo("123 Bach Dang St");
            assertThat(response.billingAddressLine2()).isEqualTo("Floor 4, Suite 402");
            assertThat(response.billingCity()).isEqualTo("Da Nang");
            assertThat(response.billingState()).isEqualTo("Hai Chau");
            assertThat(response.billingPostalCode()).isEqualTo("550000");
            assertThat(response.billingCountry()).isEqualTo("Vietnam");

            // Shipping address
            assertThat(response.shippingAddressLine1()).isEqualTo("123 Bach Dang St");
            assertThat(response.shippingAddressLine2()).isEqualTo("Floor 4, Suite 402");
            assertThat(response.shippingCity()).isEqualTo("Da Nang");
            assertThat(response.shippingState()).isEqualTo("Hai Chau");
            assertThat(response.shippingPostalCode()).isEqualTo("550000");
            assertThat(response.shippingCountry()).isEqualTo("Vietnam");

            // CRM & Audit
            assertThat(response.customerTier()).isEqualTo("GOLD");
            assertThat(response.status()).isEqualTo("ACTIVE");
            assertThat(response.notes()).isEqualTo("High-value enterprise customer since 2024");
            assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-01-15T08:30:00Z"));
            assertThat(response.updatedAt()).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
        }

        @Test
        @DisplayName("Should map list of Customer entities to CustomerResponse list preserving order and all fields")
        void shouldMapCustomerListToResponseList() {
            Customer c1 = createRichSampleCustomer();

            Customer c2 = new Customer();
            c2.setId(2L);
            c2.setFullName("Ben Nguyen");
            c2.setEmail("ben.nguyen@example.com");
            c2.setCompany("Mekong Logistics");

            List<CustomerResponse> responses = mapper.toResponseList(List.of(c1, c2));

            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).id()).isEqualTo(1L);
            assertThat(responses.get(0).company()).isEqualTo("Danang Tech Solutions");
            assertThat(responses.get(1).id()).isEqualTo(2L);
            assertThat(responses.get(1).company()).isEqualTo("Mekong Logistics");
        }

        @Test
        @DisplayName("Should map CustomerResponse DTO back to Customer entity preserving all 30+ fields")
        void shouldMapCustomerResponseToCustomerEntity() {
            Customer source = createRichSampleCustomer();
            CustomerResponse response = mapper.toResponse(source);

            Customer entity = mapper.toEntity(response);

            assertThat(entity).isNotNull();
            assertThat(entity.getId()).isEqualTo(1L);
            assertThat(entity.getFullName()).isEqualTo("Alice Tran");
            assertThat(entity.getFirstName()).isEqualTo("Alice");
            assertThat(entity.getLastName()).isEqualTo("Tran");
            assertThat(entity.getEmail()).isEqualTo("alice.tran@example.com");
            assertThat(entity.getCompany()).isEqualTo("Danang Tech Solutions");
            assertThat(entity.getJobTitle()).isEqualTo("Senior Software Engineer");
            assertThat(entity.getBillingCity()).isEqualTo("Da Nang");
            assertThat(entity.getShippingCity()).isEqualTo("Da Nang");
            assertThat(entity.getCustomerTier()).isEqualTo("GOLD");
            assertThat(entity.getStatus()).isEqualTo("ACTIVE");
            assertThat(entity.getNotes()).isEqualTo("High-value enterprise customer since 2024");
            assertThat(entity.getCreatedAt()).isEqualTo(Instant.parse("2026-01-15T08:30:00Z"));
            assertThat(entity.getUpdatedAt()).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
            assertThat(entity.getVersion()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should map via static CustomerResponse.from facade")
        void shouldMapViaCustomerResponseFromFacade() {
            Customer customer = createRichSampleCustomer();

            CustomerResponse response = CustomerResponse.from(customer);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.company()).isEqualTo("Danang Tech Solutions");
            assertThat(response.customerTier()).isEqualTo("GOLD");
            assertThat(response.version()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should update Customer entity in-place from UpdateCustomerRequest using MapStruct @MappingTarget")
        void shouldUpdateCustomerFromRequestUsingMappingTarget() {
            Customer customer = createRichSampleCustomer();

            UpdateCustomerRequest request = new UpdateCustomerRequest(
                    0L,
                    "Alice Tran Updated",
                    "Alice",
                    "Tran",
                    "alice.new@example.com",
                    null,
                    "0909999999",
                    null,
                    null,
                    null,
                    null,
                    "Polaris Global Corp",
                    "Chief Architect",
                    "Architecture",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "VIP",
                    "ACTIVE",
                    "Promoted to VIP"
            );

            mapper.updateCustomerFromRequest(request, customer);

            assertThat(customer.getFullName()).isEqualTo("Alice Tran Updated");
            assertThat(customer.getEmail()).isEqualTo("alice.new@example.com");
            assertThat(customer.getPhone()).isEqualTo("0909999999");
            assertThat(customer.getCompany()).isEqualTo("Polaris Global Corp");
            assertThat(customer.getJobTitle()).isEqualTo("Chief Architect");
            assertThat(customer.getCustomerTier()).isEqualTo("VIP");
            assertThat(customer.getNotes()).isEqualTo("Promoted to VIP");
            // Unspecified fields in request are preserved thanks to NullValuePropertyMappingStrategy.IGNORE
            assertThat(customer.getBillingCity()).isEqualTo("Da Nang");
            assertThat(customer.getShippingCity()).isEqualTo("Da Nang");
            assertThat(customer.getId()).isEqualTo(1L);
            assertThat(customer.getVersion()).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("2. Invalid / missing input")
    class InvalidInput {

        @Test
        @DisplayName("Should map Customer with sparse fields into CustomerResponse with null fields gracefully")
        void shouldMapCustomerWithSparseFields() {
            Customer customer = new Customer();
            customer.setId(4L);
            customer.setFullName("Sparse Customer");

            CustomerResponse response = mapper.toResponse(customer);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(4L);
            assertThat(response.fullName()).isEqualTo("Sparse Customer");
            assertThat(response.company()).isNull();
            assertThat(response.billingCity()).isNull();
            assertThat(response.shippingCountry()).isNull();
            assertThat(response.customerTier()).isNull();
            assertThat(response.updatedAt()).isNull();
        }

        @Test
        @DisplayName("Should map sparse CustomerResponse into Customer entity with null fields gracefully")
        void shouldMapSparseCustomerResponseToEntity() {
            Customer customer = new Customer();
            customer.setId(5L);
            customer.setFullName("Sparse DTO");
            CustomerResponse response = mapper.toResponse(customer);

            Customer entity = mapper.toEntity(response);

            assertThat(entity).isNotNull();
            assertThat(entity.getId()).isEqualTo(5L);
            assertThat(entity.getFullName()).isEqualTo("Sparse DTO");
            assertThat(entity.getCompany()).isNull();
            assertThat(entity.getBillingCity()).isNull();
            assertThat(entity.getShippingCountry()).isNull();
            assertThat(entity.getCustomerTier()).isNull();
        }
    }

    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Should return null when source Customer is null")
        void shouldReturnNullWhenCustomerIsNull() {
            assertThat(mapper.toResponse(null)).isNull();
            assertThat(CustomerResponse.from(null)).isNull();
        }

        @Test
        @DisplayName("Should return null when source CustomerResponse is null for toEntity")
        void shouldReturnNullWhenResponseIsNullForToEntity() {
            assertThat(mapper.toEntity(null)).isNull();
        }

        @Test
        @DisplayName("Should return null when customers list is null")
        void shouldReturnNullWhenCustomersListIsNull() {
            assertThat(mapper.toResponseList(null)).isNull();
        }

        @Test
        @DisplayName("Should return empty list when customers list is empty")
        void shouldReturnEmptyListWhenCustomersListIsEmpty() {
            assertThat(mapper.toResponseList(Collections.emptyList())).isEmpty();
        }
    }
}
