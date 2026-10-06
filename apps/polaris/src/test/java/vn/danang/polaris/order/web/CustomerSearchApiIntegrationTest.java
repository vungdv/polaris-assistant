package vn.danang.polaris.order.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.context.annotation.Import;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.web.support.JwtMockFactory;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestcontainersConfiguration.class)
class CustomerSearchApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/v1/customers/search?name=Alice returns Alice Tran from seed data")
    void search_byFirstName_returnsMatch() throws Exception {
        mockMvc.perform(get("/api/v1/customers/search")
                        .param("name", "Alice")
                        .with(JwtMockFactory.customerSuccess()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].full_name").value("Alice Tran"))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].email").exists());
    }

    @Test
    @DisplayName("GET /api/v1/customers/search?name=nguyen returns Ben Nguyen from seed data")
    void search_byLastName_caseInsensitive() throws Exception {
        mockMvc.perform(get("/api/v1/customers/search")
                        .param("name", "nguyen")
                        .with(JwtMockFactory.customerSuccess()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].full_name").value("Ben Nguyen"));
    }

    @Test
    @DisplayName("GET /api/v1/customers/search?name=nomatch returns empty list")
    void search_noMatch_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/customers/search")
                        .param("name", "zzznomatch")
                        .with(JwtMockFactory.customerSuccess()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/customers/search without name parameter returns 400")
    void search_missingName_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/customers/search")
                        .with(JwtMockFactory.customerSuccess()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/customers/search unauthenticated returns 401")
    void search_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/customers/search")
                        .param("name", "Alice"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/customers/search with limit=1 returns at most 1 result")
    void search_withLimit_enforcesMax() throws Exception {
        mockMvc.perform(get("/api/v1/customers/search")
                        .param("name", "e")   // likely to match many
                        .param("limit", "1")
                        .with(JwtMockFactory.customerSuccess()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("GET /api/v1/customers/1 returns 200 with Alice Tran and identical CustomerResponse mapped fields")
    void getCustomerById_success_returnsCustomerWithAllFields() throws Exception {
        mockMvc.perform(get("/api/v1/customers/1")
                        .with(JwtMockFactory.customerSuccess()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.fullName").value("Alice Tran"))
                .andExpect(jsonPath("$.firstName").value("Alice"))
                .andExpect(jsonPath("$.lastName").value("Tran"))
                .andExpect(jsonPath("$.email").value("alice.tran@example.com"))
                .andExpect(jsonPath("$.secondaryEmail").value("alice.personal@example.com"))
                .andExpect(jsonPath("$.phone").value("0901111111"))
                .andExpect(jsonPath("$.company").value("Danang Tech Solutions"))
                .andExpect(jsonPath("$.customerTier").value("GOLD"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.billingCity").value("Da Nang"))
                .andExpect(jsonPath("$.shippingCity").value("Da Nang"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("GET /api/v1/customers/999 returns 404 ProblemDetail when customer not found")
    void getCustomerById_notFound_returns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/customers/999")
                        .with(JwtMockFactory.customerSuccess()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail").value("Customer not found with id: 999"));
    }

    @Test
    @DisplayName("GET /api/v1/customers/1 unauthenticated returns 401")
    void getCustomerById_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/customers/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /api/v1/customers/1 with matching version updates customer and returns 200 with incremented version")
    void updateCustomer_validVersion_updatesSuccessfully() throws Exception {
        String payload = """
            {
                "version": 0,
                "fullName": "Alice Tran Senior",
                "company": "Polaris Global Tech",
                "customerTier": "PLATINUM"
            }
            """;

        mockMvc.perform(put("/api/v1/customers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(JwtMockFactory.customerSuccess()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.fullName").value("Alice Tran Senior"))
                .andExpect(jsonPath("$.company").value("Polaris Global Tech"))
                .andExpect(jsonPath("$.customerTier").value("PLATINUM"))
                .andExpect(jsonPath("$.email").value("alice.tran@example.com"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @DisplayName("PUT /api/v1/customers/1 with stale version returns 409 Conflict ProblemDetail")
    void updateCustomer_staleVersion_returns409Conflict() throws Exception {
        String payload = """
            {
                "version": 99,
                "fullName": "Concurrent Alice"
            }
            """;

        mockMvc.perform(put("/api/v1/customers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(JwtMockFactory.customerSuccess()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/optimistic-lock-conflict"))
                .andExpect(jsonPath("$.title").value("Optimistic Lock Conflict"))
                .andExpect(jsonPath("$.detail").value("Resource has been modified concurrently by another transaction. Please reload and retry."))
                .andExpect(jsonPath("$.remedy").value("Reload the latest resource representation and retry your update with the updated version."));
    }

    @Test
    @DisplayName("PUT /api/v1/customers/1 with missing version returns 400 Bad Request ProblemDetail")
    void updateCustomer_missingVersion_returns400BadRequest() throws Exception {
        String payload = """
            {
                "fullName": "Alice Tran"
            }
            """;

        mockMvc.perform(put("/api/v1/customers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(JwtMockFactory.customerSuccess()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/validation-error"))
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.invalid_param").value("version"));
    }

    @Test
    @DisplayName("PUT /api/v1/customers/1 with blank fullName returns 400 Bad Request ProblemDetail")
    void updateCustomer_blankFullName_returns400BadRequest() throws Exception {
        String payload = """
            {
                "version": 0,
                "fullName": "  "
            }
            """;

        mockMvc.perform(put("/api/v1/customers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(JwtMockFactory.customerSuccess()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/validation-error"))
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.invalid_param").value("fullName"));
    }

    @Test
    @DisplayName("PUT /api/v1/customers/999 returns 404 ProblemDetail when customer not found")
    void updateCustomer_notFound_returns404ProblemDetail() throws Exception {
        String payload = """
            {
                "version": 0,
                "fullName": "Nonexistent Customer"
            }
            """;

        mockMvc.perform(put("/api/v1/customers/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(JwtMockFactory.customerSuccess()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://polaris.local/errors/not-found"))
                .andExpect(jsonPath("$.detail").value("Customer not found with id: 999"));
    }

    @Test
    @DisplayName("PUT /api/v1/customers/1 unauthenticated returns 401")
    void updateCustomer_unauthenticated_returns401() throws Exception {
        String payload = """
            {
                "version": 0,
                "fullName": "Alice Tran"
            }
            """;

        mockMvc.perform(put("/api/v1/customers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/customers/search without customer.read permission returns 403")
    void search_missingCustomerReadPermission_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/customers/search")
                        .param("name", "Alice")
                        .with(JwtMockFactory.user()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/v1/customers/1 without customer.write permission returns 403")
    void updateCustomer_missingCustomerWritePermission_returns403() throws Exception {
        String payload = """
            {
                "version": 0,
                "fullName": "Alice Tran"
            }
            """;

        mockMvc.perform(put("/api/v1/customers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(JwtMockFactory.user()))
                .andExpect(status().isForbidden());
    }
}
