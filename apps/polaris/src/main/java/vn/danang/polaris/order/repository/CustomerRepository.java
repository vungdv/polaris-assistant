package vn.danang.polaris.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import vn.danang.polaris.order.entity.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByEmail(String email);

    Optional<Customer> findByAuthSubject(String authSubject);

    List<Customer> findAllByEmailIgnoreCase(String email);

    /**
     * Links a customer to an identity-provider subject only if it is still unlinked.
     * A conditional UPDATE instead of an entity save makes concurrent first logins safe: under
     * PostgreSQL READ COMMITTED the loser re-evaluates {@code auth_subject IS NULL} after the winner
     * commits and updates nothing, instead of failing the transaction.
     *
     * @return number of rows updated (0 if the customer was already linked)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Customer c SET c.authSubject = :subject, c.updatedAt = :now, c.version = c.version + 1 "
            + "WHERE c.id = :id AND c.authSubject IS NULL")
    int linkAuthSubjectIfUnlinked(@Param("id") Long id, @Param("subject") String subject, @Param("now") java.time.Instant now);

    /**
     * Fuzzy customer name search using case-insensitive substring matching.
     * Works on both H2 (test) and PostgreSQL (production).
     *
     * For production PostgreSQL at scale, complement with the pg_trgm GIN index
     * and similarity() ordering documented in V9 migration notes.
     */
    @Query("SELECT c FROM Customer c WHERE LOWER(c.fullName) LIKE LOWER(CONCAT('%', :name, '%')) ORDER BY c.fullName ASC")
    List<Customer> searchByNameFuzzy(@Param("name") String name, org.springframework.data.domain.Pageable pageable);
}