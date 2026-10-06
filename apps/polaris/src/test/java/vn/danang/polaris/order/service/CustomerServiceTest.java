package vn.danang.polaris.order.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

import vn.danang.polaris.order.dto.CustomerResponse;
import vn.danang.polaris.order.dto.CustomerSummaryResponse;
import vn.danang.polaris.order.dto.UpdateCustomerRequest;
import vn.danang.polaris.order.entity.Customer;
import vn.danang.polaris.order.mapper.CustomerMapper;
import vn.danang.polaris.order.repository.CustomerRepository;
import vn.danang.polaris.order.security.CallerIdentity;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerService Unit Tests")
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    private final CustomerMapper customerMapper = Mappers.getMapper(CustomerMapper.class);

    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerService(customerRepository, customerMapper);
    }

    private Customer createSampleCustomer(Long id, String name) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setFullName(name);
        customer.setEmail(name.toLowerCase().replace(" ", ".") + "@example.com");
        customer.setPhone("0901234567");
        customer.setCreatedAt(Instant.parse("2026-03-01T10:00:00Z"));
        return customer;
    }

    @Nested
    @DisplayName("1. Happy path")
    class HappyPath {

        @Test
        @DisplayName("getCustomerById should return mapped CustomerResponse when customer is found")
        void getCustomerById_found_returnsMappedCustomerResponse() {
            Customer customer = createSampleCustomer(1L, "Alice Tran");
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));

            CustomerResponse response = customerService.getCustomerById(1L);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.fullName()).isEqualTo("Alice Tran");
            assertThat(response.email()).isEqualTo("alice.tran@example.com");
            assertThat(response.phone()).isEqualTo("0901234567");
            assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));

            verify(customerRepository).findById(1L);
        }

        @Test
        @DisplayName("searchByName should return mapped customer summaries")
        void searchByName_validName_returnsMappedSummaries() {
            Customer c1 = createSampleCustomer(1L, "Alice Tran");
            when(customerRepository.searchByNameFuzzy(eq("Alice"), any(Pageable.class)))
                    .thenReturn(List.of(c1));

            List<CustomerSummaryResponse> summaries = customerService.searchByName("Alice", 5);

            assertThat(summaries).hasSize(1);
            assertThat(summaries.get(0).id()).isEqualTo(1L);
            assertThat(summaries.get(0).fullName()).isEqualTo("Alice Tran");
        }

        @Test
        @DisplayName("updateCustomer should update and return CustomerResponse when version matches")
        void updateCustomer_validVersion_updatesAndReturnsCustomerResponse() {
            Customer customer = createSampleCustomer(1L, "Alice Tran");
            customer.setVersion(0L);
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
            when(customerRepository.saveAndFlush(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

            UpdateCustomerRequest request = UpdateCustomerRequest.of(0L, "Alice Tran New");

            CustomerResponse response = customerService.updateCustomer(1L, request);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.fullName()).isEqualTo("Alice Tran New");
            verify(customerRepository).findById(1L);
            verify(customerRepository).saveAndFlush(customer);
        }
    }

    @Nested
    @DisplayName("2. Invalid input")
    class InvalidInput {

        @Test
        @DisplayName("getCustomerById should throw ResourceNotFoundException when customer ID does not exist")
        void getCustomerById_notFound_throwsResourceNotFoundException() {
            when(customerRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerService.getCustomerById(999L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Customer not found with id: 999");

            verify(customerRepository).findById(999L);
        }

        @Test
        @DisplayName("updateCustomer should throw ObjectOptimisticLockingFailureException when version is stale")
        void updateCustomer_staleVersion_throwsObjectOptimisticLockingFailureException() {
            Customer customer = createSampleCustomer(1L, "Alice Tran");
            customer.setVersion(1L);
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));

            UpdateCustomerRequest request = UpdateCustomerRequest.of(0L, "Alice Tran New");

            assertThatThrownBy(() -> customerService.updateCustomer(1L, request))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        }

        @Test
        @DisplayName("updateCustomer should throw ResourceNotFoundException when customer ID does not exist")
        void updateCustomer_notFound_throwsResourceNotFoundException() {
            when(customerRepository.findById(999L)).thenReturn(Optional.empty());

            UpdateCustomerRequest request = UpdateCustomerRequest.of(0L, "Alice Tran New");

            assertThatThrownBy(() -> customerService.updateCustomer(999L, request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Customer not found with id: 999");

            verify(customerRepository).findById(999L);
        }
    }

    @Nested
    @DisplayName("3. Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("getCustomerById should map gracefully when optional entity fields are null")
        void getCustomerById_withNullOptionalFields_mapsGracefully() {
            Customer customer = new Customer();
            customer.setId(10L);
            customer.setFullName("Minimal Customer");
            when(customerRepository.findById(10L)).thenReturn(Optional.of(customer));

            CustomerResponse response = customerService.getCustomerById(10L);

            assertThat(response).isNotNull();
            assertThat(response.id()).isEqualTo(10L);
            assertThat(response.fullName()).isEqualTo("Minimal Customer");
            assertThat(response.email()).isNull();
            assertThat(response.phone()).isNull();
            assertThat(response.createdAt()).isNull();
        }

        @Test
        @DisplayName("updateCustomer with partial fields should preserve existing entity fields")
        void updateCustomer_partialFields_preservesExistingFields() {
            Customer customer = createSampleCustomer(1L, "Alice Tran");
            customer.setVersion(2L);
            customer.setCompany("Danang Tech Solutions");
            when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
            when(customerRepository.saveAndFlush(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

            UpdateCustomerRequest request = UpdateCustomerRequest.of(2L, "Alice Renamed");

            CustomerResponse response = customerService.updateCustomer(1L, request);

            assertThat(response.fullName()).isEqualTo("Alice Renamed");
            assertThat(response.company()).isEqualTo("Danang Tech Solutions");
            assertThat(response.email()).isEqualTo("alice.tran@example.com");
            assertThat(response.phone()).isEqualTo("0901234567");
        }
    }

    @Nested
    @DisplayName("4. Caller identity binding (D1, FR-10)")
    class CallerIdentityBinding {

        private final CallerIdentity shopper = new CallerIdentity("sub-alice", "alice.tran@example.com", true, false);
        private final CallerIdentity staff = new CallerIdentity("sub-staff", "staff@novagadgets.local", true, true);

        @Test
        @DisplayName("resolveCurrentCustomer finds the customer by subject without touching email")
        void resolveCurrentCustomer_bySubject() {
            Customer alice = createSampleCustomer(1L, "Alice Tran");
            alice.setAuthSubject("sub-alice");
            when(customerRepository.findByAuthSubject("sub-alice")).thenReturn(Optional.of(alice));

            assertThat(customerService.resolveCurrentCustomer(shopper)).isSameAs(alice);
            verify(customerRepository, never()).findAllByEmailIgnoreCase(any());
            verify(customerRepository, never()).linkAuthSubjectIfUnlinked(any(), any(), any());
        }

        @Test
        @DisplayName("resolveCurrentCustomer links an unlinked customer once via verified email (case-insensitive)")
        void resolveCurrentCustomer_emailFallbackLinksSubject() {
            CallerIdentity mixedCase = new CallerIdentity("sub-alice", "Alice.Tran@Example.com", true, false);
            Customer unlinked = createSampleCustomer(1L, "Alice Tran");
            Customer linked = createSampleCustomer(1L, "Alice Tran");
            linked.setAuthSubject("sub-alice");
            when(customerRepository.findByAuthSubject("sub-alice")).thenReturn(Optional.empty(), Optional.of(linked));
            when(customerRepository.findAllByEmailIgnoreCase("Alice.Tran@Example.com")).thenReturn(List.of(unlinked));
            when(customerRepository.linkAuthSubjectIfUnlinked(eq(1L), eq("sub-alice"), any())).thenReturn(1);

            Customer resolved = customerService.resolveCurrentCustomer(mixedCase);

            assertThat(resolved.getId()).isEqualTo(1L);
            assertThat(resolved.getAuthSubject()).isEqualTo("sub-alice");
            verify(customerRepository).linkAuthSubjectIfUnlinked(eq(1L), eq("sub-alice"), any());
        }

        @Test
        @DisplayName("resolveCurrentCustomer: losing a concurrent first login of the same account returns the winner's link")
        void resolveCurrentCustomer_concurrentFirstLogin_returnsExistingLink() {
            Customer unlinked = createSampleCustomer(1L, "Alice Tran");
            Customer linkedByWinner = createSampleCustomer(1L, "Alice Tran");
            linkedByWinner.setAuthSubject("sub-alice");
            when(customerRepository.findByAuthSubject("sub-alice")).thenReturn(Optional.empty(), Optional.of(linkedByWinner));
            when(customerRepository.findAllByEmailIgnoreCase("alice.tran@example.com")).thenReturn(List.of(unlinked));
            when(customerRepository.linkAuthSubjectIfUnlinked(eq(1L), eq("sub-alice"), any())).thenReturn(0);

            assertThat(customerService.resolveCurrentCustomer(shopper)).isSameAs(linkedByWinner);
        }

        @Test
        @DisplayName("resolveCurrentCustomer: losing a concurrent link to a different subject is not linked")
        void resolveCurrentCustomer_concurrentLinkByOtherSubject_notFound() {
            Customer unlinked = createSampleCustomer(1L, "Alice Tran");
            when(customerRepository.findByAuthSubject("sub-alice")).thenReturn(Optional.empty());
            when(customerRepository.findAllByEmailIgnoreCase("alice.tran@example.com")).thenReturn(List.of(unlinked));
            when(customerRepository.linkAuthSubjectIfUnlinked(eq(1L), eq("sub-alice"), any())).thenReturn(0);

            assertThatThrownBy(() -> customerService.resolveCurrentCustomer(shopper))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("resolveCurrentCustomer: a token without sub still resolves an unlinked customer by verified email, without linking")
        void resolveCurrentCustomer_noSubject_emailFallbackWithoutLink() {
            CallerIdentity noSub = new CallerIdentity(null, "alice.tran@example.com", true, false);
            Customer unlinked = createSampleCustomer(1L, "Alice Tran");
            when(customerRepository.findAllByEmailIgnoreCase("alice.tran@example.com")).thenReturn(List.of(unlinked));

            assertThat(customerService.resolveCurrentCustomer(noSub)).isSameAs(unlinked);
            verify(customerRepository, never()).findByAuthSubject(any());
            verify(customerRepository, never()).linkAuthSubjectIfUnlinked(any(), any(), any());
        }

        @Test
        @DisplayName("resolveCurrentCustomer: a token without sub cannot claim an already linked customer")
        void resolveCurrentCustomer_noSubject_linkedCustomer_notFound() {
            CallerIdentity noSub = new CallerIdentity(null, "alice.tran@example.com", true, false);
            Customer linked = createSampleCustomer(1L, "Alice Tran");
            linked.setAuthSubject("sub-alice");
            when(customerRepository.findAllByEmailIgnoreCase("alice.tran@example.com")).thenReturn(List.of(linked));

            assertThatThrownBy(() -> customerService.resolveCurrentCustomer(noSub))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("assertCustomerAccess: shopper may access own data, not another customer's; staff may access any")
        void assertCustomerAccess_ownership() {
            Customer alice = createSampleCustomer(1L, "Alice Tran");
            when(customerRepository.findByAuthSubject("sub-alice")).thenReturn(Optional.of(alice));

            customerService.assertCustomerAccess(shopper, 1L);
            assertThatThrownBy(() -> customerService.assertCustomerAccess(shopper, 2L))
                    .isInstanceOf(AccessDeniedException.class);
            customerService.assertCustomerAccess(staff, 2L);
        }

        @Test
        @DisplayName("resolveCustomerScope: staff may search unfiltered, shoppers are forced to their own customer")
        void resolveCustomerScope_staffAndShopper() {
            Customer alice = createSampleCustomer(1L, "Alice Tran");
            when(customerRepository.findByAuthSubject("sub-alice")).thenReturn(Optional.of(alice));

            assertThat(customerService.resolveCustomerScope(staff, null)).isNull();
            assertThat(customerService.resolveCustomerScope(shopper, null)).isEqualTo(1L);
            assertThatThrownBy(() -> customerService.resolveCustomerScope(shopper, 2L))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("resolveCurrentCustomer never re-links a customer owned by another subject")
        void resolveCurrentCustomer_emailOfOtherSubject_notFound() {
            Customer alice = createSampleCustomer(1L, "Alice Tran");
            alice.setAuthSubject("sub-original");
            when(customerRepository.findByAuthSubject("sub-alice")).thenReturn(Optional.empty());
            when(customerRepository.findAllByEmailIgnoreCase("alice.tran@example.com")).thenReturn(List.of(alice));

            assertThatThrownBy(() -> customerService.resolveCurrentCustomer(shopper))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(alice.getAuthSubject()).isEqualTo("sub-original");
            verify(customerRepository, never()).linkAuthSubjectIfUnlinked(any(), any(), any());
        }

        @Test
        @DisplayName("resolveCurrentCustomer ignores an unverified email")
        void resolveCurrentCustomer_unverifiedEmail_notFound() {
            CallerIdentity unverified = new CallerIdentity("sub-alice", "alice.tran@example.com", false, false);
            when(customerRepository.findByAuthSubject("sub-alice")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerService.resolveCurrentCustomer(unverified))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("No customer is linked to the authenticated user.");
            verify(customerRepository, never()).findAllByEmailIgnoreCase(any());
        }

        @Test
        @DisplayName("resolveOrderingCustomerId: shopper without customer ID gets their own customer")
        void resolveOrderingCustomerId_shopperDefaultsToOwn() {
            Customer alice = createSampleCustomer(1L, "Alice Tran");
            when(customerRepository.findByAuthSubject("sub-alice")).thenReturn(Optional.of(alice));

            assertThat(customerService.resolveOrderingCustomerId(shopper, null)).isEqualTo(1L);
            assertThat(customerService.resolveOrderingCustomerId(shopper, 1L)).isEqualTo(1L);
        }

        @Test
        @DisplayName("resolveOrderingCustomerId: shopper naming another customer is denied")
        void resolveOrderingCustomerId_shopperForOther_denied() {
            Customer alice = createSampleCustomer(1L, "Alice Tran");
            when(customerRepository.findByAuthSubject("sub-alice")).thenReturn(Optional.of(alice));

            assertThatThrownBy(() -> customerService.resolveOrderingCustomerId(shopper, 2L))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("resolveOrderingCustomerId: staff order for the named customer without a linked account")
        void resolveOrderingCustomerId_staffUsesRequested() {
            assertThat(customerService.resolveOrderingCustomerId(staff, 2L)).isEqualTo(2L);
            verify(customerRepository, never()).findByAuthSubject(any());
        }

        @Test
        @DisplayName("resolveOrderingCustomerId: staff must name a customer")
        void resolveOrderingCustomerId_staffWithoutCustomer_badRequest() {
            assertThatThrownBy(() -> customerService.resolveOrderingCustomerId(staff, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("resolveOrderingCustomerId: missing caller is treated as unlinked")
        void resolveOrderingCustomerId_nullCaller_notFound() {
            assertThatThrownBy(() -> customerService.resolveOrderingCustomerId(null, 1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
