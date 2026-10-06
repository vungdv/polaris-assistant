package vn.danang.polaris.order.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import vn.danang.polaris.order.entity.Order;

public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {
    Optional<Order> findByOrderNumber(String orderNumber);

    /**
     * Loads the order with a row lock ({@code SELECT … FOR UPDATE}) for an event-raising transition, so concurrent
     * transitions of the same order are serialised before any event is recorded (TR-X8).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderNumber = :orderNumber")
    Optional<Order> findByOrderNumberForUpdate(@Param("orderNumber") String orderNumber);
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /** Next value of the {@code order_number_seq} sequence (V13); unique across restarts and replicas. */
    @Query(value = "SELECT nextval('order_number_seq')", nativeQuery = true)
    long nextOrderNumberValue();
}