package vn.danang.polaris.order.web;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.order.entity.Customer;
import vn.danang.polaris.order.repository.CustomerRepository;
import vn.danang.polaris.web.support.JwtMockFactory;

/**
 * {@code GET /api/v1/customers/me}: resolves the caller's own customer by JWT {@code sub},
 * falling back once to the verified {@code email} claim (decision D1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestcontainersConfiguration.class)
class CurrentCustomerApiIntegrationTest {

    /** Keycloak user ID of shopper alice.tran, linked to seeded customer 1 by V12. */
    private static final String ALICE_SUBJECT = "3f0c6a1e-5b2d-4c8e-9a71-0d1e2f3a4b01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerRepository customerRepository;

    private Customer unlinkedCustomer(String email) {
        Customer customer = new Customer();
        customer.setFullName("Dana Pham");
        customer.setEmail(email);
        customer.setCreatedAt(Instant.now());
        return customerRepository.saveAndFlush(customer);
    }

    @Test
    void me_linkedBySubject_returnsOwnProfile() throws Exception {
        mockMvc.perform(get("/api/v1/customers/me")
                        .with(JwtMockFactory.shopper(ALICE_SUBJECT, "alice.tran@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.fullName").value("Alice Tran"))
                .andExpect(jsonPath("$.authSubject").doesNotExist());
    }

    @Test
    void me_subjectWinsOverEmailClaim() throws Exception {
        // The token's email belongs to Ben, but the subject is Alice's: identity is bound by subject
        mockMvc.perform(get("/api/v1/customers/me")
                        .with(JwtMockFactory.shopper(ALICE_SUBJECT, "ben.nguyen@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Alice Tran"));
    }

    @Test
    void me_verifiedEmailFallback_linksSubjectOnce() throws Exception {
        Customer dana = unlinkedCustomer("dana.pham@example.com");

        // Email claims are matched case-insensitively
        mockMvc.perform(get("/api/v1/customers/me")
                        .with(JwtMockFactory.shopper("dana-subject", "Dana.Pham@Example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dana.getId()))
                .andExpect(jsonPath("$.fullName").value("Dana Pham"));

        assertThat(customerRepository.findById(dana.getId()).orElseThrow().getAuthSubject()).isEqualTo("dana-subject");

        // Once linked, the subject alone resolves the customer (the email claim is no longer needed)
        mockMvc.perform(get("/api/v1/customers/me")
                        .with(JwtMockFactory.shopper("dana-subject", "changed@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dana.getId()));
    }

    @Test
    void me_unverifiedEmail_isNotUsedForLinking() throws Exception {
        Customer dana = unlinkedCustomer("dana.pham@example.com");

        mockMvc.perform(get("/api/v1/customers/me")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(jwt -> jwt
                                .subject("dana-subject")
                                .claim("email", "dana.pham@example.com")
                                .claim("email_verified", false))))
                .andExpect(status().isNotFound());

        assertThat(customerRepository.findById(dana.getId()).orElseThrow().getAuthSubject()).isNull();
    }

    @Test
    void me_emailOfCustomerLinkedToAnotherSubject_returns404() throws Exception {
        // alice.tran@example.com is already linked to Alice's subject; another account must not take it over
        mockMvc.perform(get("/api/v1/customers/me")
                        .with(JwtMockFactory.shopper("attacker-subject", "alice.tran@example.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"))
                .andExpect(jsonPath("$.detail").value("No customer is linked to the authenticated user."));

        assertThat(customerRepository.findById(1L).orElseThrow().getAuthSubject()).isEqualTo(ALICE_SUBJECT);
    }

    @Test
    void me_noLinkedCustomer_returns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/customers/me")
                        .with(JwtMockFactory.shopper("unknown-subject", "nobody@example.com")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"));
    }

    @Test
    void me_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/customers/me"))
                .andExpect(status().isUnauthorized());
    }
}
