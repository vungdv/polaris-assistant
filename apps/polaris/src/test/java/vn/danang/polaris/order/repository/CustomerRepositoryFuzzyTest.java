package vn.danang.polaris.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.order.entity.Customer;

@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class CustomerRepositoryFuzzyTest {

    @Autowired
    private CustomerRepository customerRepository;

    @BeforeEach
    void setUp() {
        customerRepository.save(customer("Alicia Johnson", "alicia.johnson.fuzzy@example.com"));
        customerRepository.save(customer("Bob Nguyen Thanh", "bob.nguyen.thanh.fuzzy@example.com"));
    }

    private Customer customer(String name, String email) {
        Customer c = new Customer();
        c.setFullName(name);
        c.setEmail(email);
        c.setPhone("0000000000");
        c.setCreatedAt(Instant.now());
        return c;
    }

    @Test
    @DisplayName("Exact full name match returns one result")
    void exactFullNameMatch() {
        List<Customer> results = customerRepository.searchByNameFuzzy("Alice Tran", PageRequest.of(0, 10));
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getFullName()).isEqualTo("Alice Tran");
    }

    @Test
    @DisplayName("First name only 'Alice' matches Alice Tran and Alicia Johnson")
    void firstNameOnly_matchesMultiple() {
        List<Customer> results = customerRepository.searchByNameFuzzy("alic", PageRequest.of(0, 10));
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results).extracting(Customer::getFullName)
                .contains("Alice Tran", "Alicia Johnson");
    }

    @Test
    @DisplayName("Last name only 'Nguyen' matches Ben Nguyen and Bob Nguyen Thanh")
    void lastNameOnly_matchesMultiple() {
        List<Customer> results = customerRepository.searchByNameFuzzy("Nguyen", PageRequest.of(0, 10));
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results).extracting(Customer::getFullName)
                .contains("Ben Nguyen", "Bob Nguyen Thanh");
    }

    @Test
    @DisplayName("Partial name 'chi' matches Chi Le (case-insensitive)")
    void caseInsensitiveMatch() {
        List<Customer> results = customerRepository.searchByNameFuzzy("chi", PageRequest.of(0, 10));
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getFullName()).isEqualTo("Chi Le");
    }

    @Test
    @DisplayName("No match returns empty list")
    void noMatch_returnsEmpty() {
        List<Customer> results = customerRepository.searchByNameFuzzy("zzznomatch", PageRequest.of(0, 10));
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Limit is enforced via pageable")
    void limitEnforced() {
        List<Customer> results = customerRepository.searchByNameFuzzy("n", PageRequest.of(0, 2));
        assertThat(results).hasSizeLessThanOrEqualTo(2);
    }
}
