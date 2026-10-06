package vn.danang.polaris.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.order.dto.OrderItemRequest;
import vn.danang.polaris.order.entity.Order;
import vn.danang.polaris.order.entity.OrderStatus;
import vn.danang.polaris.order.repository.OrderRepository;
import vn.danang.polaris.order.service.OrderService;
import vn.danang.polaris.outbox.IntegrationEvent;
import vn.danang.polaris.outbox.IntegrationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TR-X8 against real PostgreSQL: transactions raising events for the same order are serialised, so the order's
 * events can never commit out of outbox id order (the precondition the relay's per-key ordering relies on, TR-E3).
 *
 * <p>Cancellation has no published contract yet, so a <b>test-only</b> listener records a test integration event for
 * each {@link OrderCancelled}, standing in for the later event-raising transitions (Plan 3). It can hold the first
 * transaction open right after its event is recorded, forcing the interleaving that would otherwise break ordering:
 * a lower id recorded first, a higher id recorded and committed by a concurrent transaction, then the lower id
 * committing last. The same hold on {@link OrderPlaced} forces the gaps plan's (S4) idempotency-key race, whose
 * loser must record nothing.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, OrderEventOrderingConcurrencyTest.ProbeConfig.class })
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OrderEventOrderingConcurrencyTest {

    private static final String SKU = "E4-ORDERING-SKU";
    private static final String SKU_B = "E4-ORDERING-SKU-B";
    private static final String TEST_EVENT_TYPE = "test.order.cancelled.v1";

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig {
        @Bean
        CancellationProbe cancellationProbe(IntegrationEventPublisher publisher) {
            return new CancellationProbe(publisher);
        }
    }

    /** Test-only translator for {@link OrderCancelled}; records which of its events were recorded and committed. */
    static final class CancellationProbe {
        private final IntegrationEventPublisher publisher;
        final List<UUID> recorded = Collections.synchronizedList(new ArrayList<>());
        final List<UUID> committed = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean holdNext = new AtomicBoolean();
        final AtomicBoolean holdNextPlaced = new AtomicBoolean();
        volatile CountDownLatch heldRecorded = new CountDownLatch(1);
        volatile CountDownLatch releaseHeld = new CountDownLatch(1);

        CancellationProbe(IntegrationEventPublisher publisher) {
            this.publisher = publisher;
        }

        void reset() {
            recorded.clear();
            committed.clear();
            holdNext.set(false);
            holdNextPlaced.set(false);
            heldRecorded = new CountDownLatch(1);
            releaseHeld = new CountDownLatch(1);
        }

        @EventListener
        public void on(OrderCancelled cancelled) throws InterruptedException {
            UUID ceId = publisher.publish(new IntegrationEvent(TEST_EVENT_TYPE, "/polaris/order/test",
                    "test.order.lifecycle", cancelled.orderNumber(), Map.of("orderNumber", cancelled.orderNumber())));
            recorded.add(ceId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    committed.add(ceId);
                }
            });
            if (holdNext.compareAndSet(true, false)) {
                hold();
            }
        }

        /** Optionally holds a placement open after its order row is inserted (records nothing itself). */
        @EventListener
        public void on(OrderPlaced placed) throws InterruptedException {
            if (holdNextPlaced.compareAndSet(true, false)) {
                hold();
            }
        }

        private void hold() throws InterruptedException {
            heldRecorded.countDown();
            releaseHeld.await(15, TimeUnit.SECONDS);
        }
    }

    record OutboxRow(long id, UUID eventId, String eventType) {}

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private CancellationProbe probe;

    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    @BeforeEach
    void setUp() {
        deleteTestData();
        probe.reset();
        createProduct(SKU);
        createProduct(SKU_B);
    }

    private void createProduct(String sku) {
        Product product = new Product();
        product.setSku(sku);
        product.setName("Ordering Test Item");
        product.setPrice(new BigDecimal("10.00"));
        product.setStockQty(100);
        product.setIsActive(true);
        product.setCreatedAt(Instant.now());
        productRepository.saveAndFlush(product);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        probe.releaseHeld.countDown();
        executor.shutdownNow();
        // Let every transaction finish (and release its row locks) before deleting the test data
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        deleteTestData();
    }

    private void deleteTestData() {
        List.of(SKU, SKU_B).forEach(sku -> transactionTemplate.executeWithoutResult(status -> productRepository.findBySku(sku).ifPresent(p -> {
            orderRepository.findAll().stream()
                    .filter(o -> o.getItems().stream().anyMatch(i -> i.getProduct().getId().equals(p.getId())))
                    .forEach(o -> {
                        jdbc.sql("DELETE FROM outbox_events WHERE event_key = ?").param(o.getOrderNumber()).update();
                        orderRepository.delete(o);
                    });
            productRepository.delete(p);
        })));
    }

    private String placeOrder() {
        return orderService.placeOrder(1L, List.of(new OrderItemRequest(SKU, 2)), null).getOrderNumber();
    }

    private List<OutboxRow> outboxRows(String key) {
        return jdbc.sql("SELECT id, event_id, event_type FROM outbox_events WHERE event_key = ? ORDER BY id")
                .param(key).query(OutboxRow.class).list();
    }

    private int stock() {
        return stock(SKU);
    }

    private int stock(String sku) {
        return productRepository.findBySku(sku).orElseThrow().getStockQty();
    }

    /** The test events of {@code key} in outbox id order must be exactly the committed ones, in commit order. */
    private void assertCommittedInIdOrder(String key) {
        List<OutboxRow> rows = outboxRows(key);
        assertThat(rows.getFirst().eventType()).isEqualTo("vn.danang.polaris.order.placed.v1");
        assertThat(rows.stream().filter(r -> r.eventType().equals(TEST_EVENT_TYPE)).map(OutboxRow::eventId).toList())
                .containsExactlyElementsOf(probe.committed);
    }

    @Test
    @DisplayName("cancelOrder locks the order before recording: a concurrent cancel waits, then records nothing")
    void cancelOrder_concurrentCancel_waitsForLockAndRecordsNothing() throws Exception {
        String orderNumber = placeOrder();
        probe.holdNext.set(true);

        Future<Order> first = executor.submit(() -> orderService.cancelOrder(orderNumber));
        assertThat(probe.heldRecorded.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Order> second = executor.submit(() -> orderService.cancelOrder(orderNumber));

        // While the first holds the order's row lock, the second cannot reach the point of recording an event
        Thread.sleep(500);
        assertThat(second.isDone()).isFalse();
        assertThat(probe.recorded).hasSize(1);

        probe.releaseHeld.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        ExecutionException rejected = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> second.get(10, TimeUnit.SECONDS));
        assertThat(rejected.getCause()).isInstanceOf(IllegalStateException.class);

        assertThat(probe.recorded).hasSize(1);
        assertThat(probe.committed).containsExactlyElementsOf(probe.recorded);
        assertCommittedInIdOrder(orderNumber);
        assertThat(stock()).isEqualTo(100);
    }

    @Test
    @DisplayName("@Version: an unlocked transition that recorded a lower id first rolls back with it when a later id committed")
    void unlockedConcurrentTransitions_loserRollsBackWithItsEvent() throws Exception {
        String orderNumber = placeOrder();
        probe.holdNext.set(true);

        // Transitions that (wrongly) skip the row lock: only the version column serialises them
        Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            Order order = orderRepository.findByOrderNumber(orderNumber).orElseThrow();
            order.cancel(Instant.now());
            orderRepository.save(order);
        }));
        assertThat(probe.heldRecorded.await(10, TimeUnit.SECONDS)).isTrue();

        // A concurrent transition records a higher id and commits while the first is still open
        executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            Order order = orderRepository.findByOrderNumber(orderNumber).orElseThrow();
            order.cancel(Instant.now());
            orderRepository.save(order);
        })).get(10, TimeUnit.SECONDS);
        assertThat(probe.committed).hasSize(1);

        // The first would now commit a lower id after a higher one was committed: its version check rolls it back
        probe.releaseHeld.countDown();
        ExecutionException lost = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> first.get(10, TimeUnit.SECONDS));
        assertThat(lost.getCause()).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(probe.recorded).hasSize(2);
        assertThat(probe.committed).hasSize(1).containsExactly(probe.recorded.get(1));
        assertCommittedInIdOrder(orderNumber);
    }

    @Test
    @DisplayName("Many concurrent cancels of one order: exactly one commits its event, stock restored once")
    void manyConcurrentCancels_exactlyOneCommits() throws Exception {
        String orderNumber = placeOrder();
        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Order>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(executor.submit(() -> {
                start.await();
                return orderService.cancelOrder(orderNumber);
            }));
        }
        start.countDown();

        int succeeded = 0;
        int rejected = 0;
        for (Future<Order> result : results) {
            try {
                result.get(20, TimeUnit.SECONDS);
                succeeded++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
                rejected++;
            }
        }

        assertThat(succeeded).isEqualTo(1);
        assertThat(rejected).isEqualTo(callers - 1);
        assertThat(probe.recorded).hasSize(1);
        assertThat(probe.committed).containsExactlyElementsOf(probe.recorded);
        assertCommittedInIdOrder(orderNumber);
        assertThat(stock()).isEqualTo(100);
    }

    @Test
    @DisplayName("S4 concurrent-duplicate path: the loser of the idempotency-key race re-reads the winner and records nothing")
    void idempotencyKeyRace_loserRecordsNothing() throws Exception {
        String key = "e4-race-" + UUID.randomUUID();
        long outboxBefore = jdbc.sql("SELECT COUNT(*) FROM outbox_events").query(Long.class).single();
        probe.holdNextPlaced.set(true);

        // The winner inserts its order and records order.placed, then stays uncommitted
        Future<OrderService.Placement> winner = executor.submit(() ->
                orderService.place(1L, List.of(new OrderItemRequest(SKU, 1)), key));
        assertThat(probe.heldRecorded.await(10, TimeUnit.SECONDS)).isTrue();

        // The loser (another SKU, so no shared product lock) passes both look-ups and blocks on the unique index
        Future<OrderService.Placement> loser = executor.submit(() ->
                orderService.place(1L, List.of(new OrderItemRequest(SKU_B, 1)), key));
        Thread.sleep(500);
        assertThat(loser.isDone()).isFalse();

        probe.releaseHeld.countDown();
        OrderService.Placement won = winner.get(10, TimeUnit.SECONDS);
        OrderService.Placement replayed = loser.get(10, TimeUnit.SECONDS);

        assertThat(won.replayed()).isFalse();
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.order().getOrderNumber()).isEqualTo(won.order().getOrderNumber());
        assertThat(outboxRows(won.order().getOrderNumber())).extracting(OutboxRow::eventType)
                .containsExactly("vn.danang.polaris.order.placed.v1");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM orders WHERE idempotency_key = ?").param(key).query(Long.class).single())
                .isEqualTo(1L);
        assertThat(stock(SKU_B)).isEqualTo(100);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_events").query(Long.class).single()).isEqualTo(outboxBefore + 1);
    }
}
