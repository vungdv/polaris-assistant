package vn.danang.polaris.order.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import vn.danang.polaris.order.dto.OrderItemRequest;
import vn.danang.polaris.order.entity.Order;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.order.repository.CustomerRepository;
import vn.danang.polaris.order.repository.OrderRepository;
import org.springframework.context.annotation.Import;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.order.service.OrderService;
import vn.danang.polaris.web.exception.InsufficientStockException;
import vn.danang.polaris.web.support.JwtMockFactory;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public class OrderConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private vn.danang.polaris.catalog.service.ProductService productService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private void cleanupSku(String sku) {
        transactionTemplate.executeWithoutResult(status -> {
            productRepository.findBySku(sku).ifPresent(p -> {
                orderRepository.findAll().forEach(o -> {
                    if (o.getItems().stream().anyMatch(i -> i.getProduct().getId().equals(p.getId()))) {
                        orderRepository.delete(o);
                    }
                });
                productRepository.delete(p);
            });
        });
    }

    @BeforeEach
    @AfterEach
    void cleanup() {
        List.of("CONCUR-SKU-01", "CONCUR-MULTI-A", "CONCUR-MULTI-B", "CONCUR-HTTP-SKU", "CONCUR-MIXED-SKU", "CONCUR-MIXED-HTTP-SKU",
                        "CONCUR-IDEM-SKU", "CONCUR-IDEM-A", "CONCUR-IDEM-B", "CONCUR-IDEM-HTTP")
                .forEach(this::cleanupSku);
    }

    @Test
    @DisplayName("High Concurrency: 50 concurrent requests competing for stock of 15 items - zero dirty data or overselling")
    void testConcurrentOrderPlacement_noOverselling() throws InterruptedException {
        String sku = "CONCUR-SKU-01";
        productRepository.findBySku(sku).ifPresent(p -> {
            orderRepository.findAll().forEach(o -> {
                if (o.getItems().stream().anyMatch(i -> i.getProduct().getId().equals(p.getId()))) {
                    orderRepository.delete(o);
                }
            });
            productRepository.delete(p);
        });

        int initialStock = 15;
        Product product = new Product();
        product.setSku(sku);
        product.setName("Concurrency Test Item");
        product.setPrice(new BigDecimal("29.99"));
        product.setStockQty(initialStock);
        product.setIsActive(true);
        product.setCreatedAt(Instant.now());
        productRepository.saveAndFlush(product);

        int totalRequests = 50;
        int itemsPerOrder = 1;

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalRequests);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger outOfStockCount = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);
        List<Order> placedOrders = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Order order = orderService.placeOrder(1L, List.of(new OrderItemRequest(sku, itemsPerOrder)), null);
                    successCount.incrementAndGet();
                    placedOrders.add(order);
                } catch (InsufficientStockException e) {
                    outOfStockCount.incrementAndGet();
                } catch (Throwable e) {
                    otherErrors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(otherErrors.get()).isEqualTo(0);

        Product finalProduct = productRepository.findBySku(sku).orElseThrow();
        int finalStock = finalProduct.getStockQty();

        assertThat(finalStock).isEqualTo(0);
        assertThat(successCount.get()).isEqualTo(initialStock);
        assertThat(outOfStockCount.get()).isEqualTo(totalRequests - initialStock);
        assertThat(placedOrders).hasSize(initialStock);

        // Verify all order numbers are unique
        long uniqueOrderNumbers = placedOrders.stream().map(Order::getOrderNumber).distinct().count();
        assertThat(uniqueOrderNumbers).isEqualTo(initialStock);
    }

    @Test
    @DisplayName("Multi-Item Concurrency: Concurrent multi-item requests with reversed ordering - no deadlocks, exact stock decrement")
    void testConcurrentOrderPlacement_multiItem_noDeadlock() throws InterruptedException {
        String skuA = "CONCUR-MULTI-A";
        String skuB = "CONCUR-MULTI-B";

        productRepository.findBySku(skuA).ifPresent(productRepository::delete);
        productRepository.findBySku(skuB).ifPresent(productRepository::delete);

        Product prodA = new Product();
        prodA.setSku(skuA);
        prodA.setName("Item A");
        prodA.setPrice(new BigDecimal("10.00"));
        prodA.setStockQty(10);
        prodA.setIsActive(true);
        prodA.setCreatedAt(Instant.now());
        productRepository.saveAndFlush(prodA);

        Product prodB = new Product();
        prodB.setSku(skuB);
        prodB.setName("Item B");
        prodB.setPrice(new BigDecimal("20.00"));
        prodB.setStockQty(10);
        prodB.setIsActive(true);
        prodB.setCreatedAt(Instant.now());
        productRepository.saveAndFlush(prodB);

        int totalRequests = 30;
        ExecutorService executor = Executors.newFixedThreadPool(15);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalRequests);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger outOfStockCount = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            final boolean reverse = (i % 2 == 1);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    List<OrderItemRequest> items = reverse
                            ? List.of(new OrderItemRequest(skuB, 1), new OrderItemRequest(skuA, 1))
                            : List.of(new OrderItemRequest(skuA, 1), new OrderItemRequest(skuB, 1));
                    orderService.placeOrder(1L, items, null);
                    successCount.incrementAndGet();
                } catch (InsufficientStockException e) {
                    outOfStockCount.incrementAndGet();
                } catch (Throwable e) {
                    otherErrors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(otherErrors.get()).isEqualTo(0);
        assertThat(successCount.get()).isEqualTo(10);
        assertThat(outOfStockCount.get()).isEqualTo(20);

        Product finalA = productRepository.findBySku(skuA).orElseThrow();
        Product finalB = productRepository.findBySku(skuB).orElseThrow();
        assertThat(finalA.getStockQty()).isEqualTo(0);
        assertThat(finalB.getStockQty()).isEqualTo(0);
    }

    @Test
    @DisplayName("HTTP Controller Concurrency: Concurrent MockMvc requests return 201 Created and RFC 7807 400 ProblemDetail")
    void testConcurrentOrderPlacement_viaController_returnsCorrectProblemDetails() throws InterruptedException {
        String sku = "CONCUR-HTTP-SKU";
        productRepository.findBySku(sku).ifPresent(productRepository::delete);

        int initialStock = 5;
        Product product = new Product();
        product.setSku(sku);
        product.setName("HTTP Concurrency Test Item");
        product.setPrice(new BigDecimal("15.50"));
        product.setStockQty(initialStock);
        product.setIsActive(true);
        product.setCreatedAt(Instant.now());
        productRepository.saveAndFlush(product);

        int totalRequests = 20;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalRequests);

        AtomicInteger createdCount = new AtomicInteger(0);
        AtomicInteger badRequestCount = new AtomicInteger(0);
        AtomicInteger unexpectedStatusCount = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String payload = String.format("""
                        {
                          "customerId": 1,
                          "items": [
                            { "sku": "%s", "quantity": 1 }
                          ]
                        }
                        """, sku);

                    var response = mockMvc.perform(post("/api/v1/orders")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload)
                                    .with(JwtMockFactory.admin()))
                            .andReturn().getResponse();

                    int status = response.getStatus();
                    if (status == 201) {
                        createdCount.incrementAndGet();
                        assertThat(response.getHeader("Location")).isNotNull();
                    } else if (status == 400) {
                        badRequestCount.incrementAndGet();
                        JsonNode body = objectMapper.readTree(response.getContentAsString());
                        assertThat(body.get("title").asText()).isEqualTo("Insufficient Stock");
                        assertThat(body.get("type").asText()).isEqualTo("https://polaris.local/errors/out-of-stock");
                        assertThat(body.get("status").asInt()).isEqualTo(400);
                        assertThat(body.get("available_quantity").asInt()).isEqualTo(0);
                    } else {
                        unexpectedStatusCount.incrementAndGet();
                    }
                } catch (Throwable e) {
                    unexpectedStatusCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(unexpectedStatusCount.get()).isEqualTo(0);
        assertThat(createdCount.get()).isEqualTo(initialStock);
        assertThat(badRequestCount.get()).isEqualTo(totalRequests - initialStock);

        Product finalProduct = productRepository.findBySku(sku).orElseThrow();
        assertThat(finalProduct.getStockQty()).isEqualTo(0);
    }

    @Test
    @DisplayName("Mixed Concurrency: Users updating/restocking inventory concurrently with staffs placing orders")
    void testConcurrentOrderPlacementAndInventoryUpdate_noDirtyData() throws InterruptedException {
        String sku = "CONCUR-MIXED-SKU";
        productRepository.findBySku(sku).ifPresent(p -> {
            orderRepository.findAll().forEach(o -> {
                if (o.getItems().stream().anyMatch(i -> i.getProduct().getId().equals(p.getId()))) {
                    orderRepository.delete(o);
                }
            });
            productRepository.delete(p);
        });

        int initialStock = 10;
        Product product = new Product();
        product.setSku(sku);
        product.setName("Mixed Concurrency Item");
        product.setPrice(new BigDecimal("25.00"));
        product.setStockQty(initialStock);
        product.setIsActive(true);
        product.setCreatedAt(Instant.now());
        productRepository.saveAndFlush(product);

        int orderRequests = 40;
        int restockRequests = 5;
        int restockAmountEach = 10; // 5 * 10 = +50 total restocked. Total available = 10 + 50 = 60.
        int totalWorkers = orderRequests + restockRequests;

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalWorkers);

        AtomicInteger successfulOrders = new AtomicInteger(0);
        AtomicInteger outOfStockOrders = new AtomicInteger(0);
        AtomicInteger successfulRestocks = new AtomicInteger(0);
        AtomicInteger failedRestocks = new AtomicInteger(0);
        AtomicInteger unexpectedErrors = new AtomicInteger(0);

        // Submit 40 concurrent orders (each requesting 1 item)
        for (int i = 0; i < orderRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    orderService.placeOrder(1L, List.of(new OrderItemRequest(sku, 1)), null);
                    successfulOrders.incrementAndGet();
                } catch (InsufficientStockException e) {
                    outOfStockOrders.incrementAndGet();
                } catch (Throwable e) {
                    unexpectedErrors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Submit 5 concurrent restocks (+10 each) via ProductService.adjustInventory
        for (int i = 0; i < restockRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    productService.adjustInventory(sku, restockAmountEach);
                    successfulRestocks.incrementAndGet();
                } catch (Throwable e) {
                    failedRestocks.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(unexpectedErrors.get()).isEqualTo(0);
        assertThat(failedRestocks.get()).isEqualTo(0);
        assertThat(successfulRestocks.get()).isEqualTo(restockRequests);

        Product finalProduct = productRepository.findBySku(sku).orElseThrow();
        int finalStock = finalProduct.getStockQty();

        // Mathematical invariant: Final Stock == Initial Stock + Total Restocked - Total Bought
        int totalRestocked = successfulRestocks.get() * restockAmountEach;
        int totalBought = successfulOrders.get();
        int expectedFinalStock = initialStock + totalRestocked - totalBought;

        assertThat(finalStock).isEqualTo(expectedFinalStock);
        assertThat(finalStock).isGreaterThanOrEqualTo(0);
        assertThat(totalBought + outOfStockOrders.get()).isEqualTo(orderRequests);
    }

    @Test
    @DisplayName("Mixed HTTP Concurrency: Concurrent HTTP inventory updates with concurrent HTTP order placements")
    void testConcurrentOrderPlacementAndInventoryAdjustment_viaHttpEndpoints() throws InterruptedException {
        String sku = "CONCUR-MIXED-HTTP-SKU";
        productRepository.findBySku(sku).ifPresent(p -> {
            orderRepository.findAll().forEach(o -> {
                if (o.getItems().stream().anyMatch(i -> i.getProduct().getId().equals(p.getId()))) {
                    orderRepository.delete(o);
                }
            });
            productRepository.delete(p);
        });

        int initialStock = 8;
        Product product = new Product();
        product.setSku(sku);
        product.setName("Mixed HTTP Item");
        product.setPrice(new BigDecimal("12.50"));
        product.setStockQty(initialStock);
        product.setIsActive(true);
        product.setCreatedAt(Instant.now());
        productRepository.saveAndFlush(product);

        int orderRequests = 25;
        int restockRequests = 3;
        int restockDelta = 4; // 3 * 4 = +12. Total stock available = 8 + 12 = 20.
        int totalWorkers = orderRequests + restockRequests;

        ExecutorService executor = Executors.newFixedThreadPool(15);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalWorkers);

        AtomicInteger order201Created = new AtomicInteger(0);
        AtomicInteger order400OutOfStock = new AtomicInteger(0);
        AtomicInteger restock200Ok = new AtomicInteger(0);
        AtomicInteger unexpectedErrors = new AtomicInteger(0);

        for (int i = 0; i < orderRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String payload = String.format("""
                        {
                          "customerId": 1,
                          "items": [
                            { "sku": "%s", "quantity": 1 }
                          ]
                        }
                        """, sku);

                    var response = mockMvc.perform(post("/api/v1/orders")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload)
                                    .with(JwtMockFactory.admin()))
                            .andReturn().getResponse();

                    if (response.getStatus() == 201) {
                        order201Created.incrementAndGet();
                    } else if (response.getStatus() == 400) {
                        order400OutOfStock.incrementAndGet();
                    } else {
                        unexpectedErrors.incrementAndGet();
                    }
                } catch (Throwable e) {
                    unexpectedErrors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        for (int i = 0; i < restockRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String restockPayload = String.format("{\"delta\": %d}", restockDelta);
                    var response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .put("/api/v1/products/sku/" + sku + "/inventory")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(restockPayload)
                                    .with(JwtMockFactory.admin()))
                            .andReturn().getResponse();

                    if (response.getStatus() == 200) {
                        restock200Ok.incrementAndGet();
                    } else {
                        unexpectedErrors.incrementAndGet();
                    }
                } catch (Throwable e) {
                    unexpectedErrors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(unexpectedErrors.get()).isEqualTo(0);
        assertThat(restock200Ok.get()).isEqualTo(restockRequests);

        Product finalProduct = productRepository.findBySku(sku).orElseThrow();
        int finalStock = finalProduct.getStockQty();

        int totalRestocked = restock200Ok.get() * restockDelta;
        int totalBought = order201Created.get();
        int expectedStock = initialStock + totalRestocked - totalBought;

        assertThat(finalStock).isEqualTo(expectedStock);
        assertThat(finalStock).isGreaterThanOrEqualTo(0);
        assertThat(totalBought + order400OutOfStock.get()).isEqualTo(orderRequests);
    }

    private Product createProduct(String sku, int stock) {
        Product product = new Product();
        product.setSku(sku);
        product.setName("Idempotency Test Item " + sku);
        product.setPrice(new BigDecimal("10.00"));
        product.setStockQty(stock);
        product.setIsActive(true);
        product.setCreatedAt(Instant.now());
        return productRepository.saveAndFlush(product);
    }

    /** Recorded {@code order.placed.v1} events for an order: TR-O1 allows exactly one, even under a replay race. */
    private long placedEvents(String orderNumber) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE event_key = ? AND event_type = ?")
                .params(orderNumber, "vn.danang.polaris.order.placed.v1")
                .query(Long.class).single();
    }

    private long ordersWithKey(String key) {
        return transactionTemplate.execute(status ->
                orderRepository.findAll().stream().filter(o -> key.equals(o.getIdempotencyKey())).count());
    }

    @Test
    @DisplayName("Idempotency: N concurrent requests with the same key create one order, deduct stock once, and all get the same order")
    void concurrentSameIdempotencyKey_createsOneOrder_allCallersSeeSameNumber() throws InterruptedException {
        String sku = "CONCUR-IDEM-SKU";
        String key = "concur-idem-" + java.util.UUID.randomUUID();
        createProduct(sku, 100);

        int totalRequests = 20;
        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalRequests);
        List<OrderService.Placement> placements = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    placements.add(orderService.place(1L, List.of(new OrderItemRequest(sku, 3)), key));
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors.get()).isEqualTo(0);
        assertThat(placements).hasSize(totalRequests);
        assertThat(placements.stream().map(p -> p.order().getOrderNumber()).distinct()).hasSize(1);
        assertThat(placements.stream().filter(p -> !p.replayed())).hasSize(1);
        assertThat(ordersWithKey(key)).isEqualTo(1);
        assertThat(productRepository.findBySku(sku).orElseThrow().getStockQty()).isEqualTo(100 - 3);
        assertThat(placedEvents(placements.getFirst().order().getOrderNumber())).isEqualTo(1);
    }

    @Test
    @DisplayName("Idempotency: same-key requests on disjoint SKUs (no shared row lock) still yield exactly one order via the unique index")
    void concurrentSameIdempotencyKey_disjointSkus_loserReReadsWinner() throws InterruptedException {
        String skuA = "CONCUR-IDEM-A";
        String skuB = "CONCUR-IDEM-B";
        String key = "concur-idem-disjoint-" + java.util.UUID.randomUUID();
        createProduct(skuA, 50);
        createProduct(skuB, 50);

        int totalRequests = 20;
        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalRequests);
        List<OrderService.Placement> placements = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            final String sku = (i % 2 == 0) ? skuA : skuB;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    placements.add(orderService.place(1L, List.of(new OrderItemRequest(sku, 1)), key));
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors.get()).isEqualTo(0);
        assertThat(placements.stream().map(p -> p.order().getOrderNumber()).distinct()).hasSize(1);
        assertThat(ordersWithKey(key)).isEqualTo(1);
        // Losers of the unique-index race re-read the winner (S4's concurrent-duplicate path) and record nothing
        assertThat(placedEvents(placements.getFirst().order().getOrderNumber())).isEqualTo(1);
        int totalDeducted = (50 - productRepository.findBySku(skuA).orElseThrow().getStockQty())
                + (50 - productRepository.findBySku(skuB).orElseThrow().getStockQty());
        assertThat(totalDeducted).isEqualTo(1);
    }

    @Test
    @DisplayName("Idempotency over HTTP: concurrent POSTs with the same Idempotency-Key return one 201 and 200 replays of the same order")
    void concurrentSameIdempotencyKey_viaHttp_one201RestReplay200() throws InterruptedException {
        String sku = "CONCUR-IDEM-HTTP";
        String key = "concur-idem-http-" + java.util.UUID.randomUUID();
        createProduct(sku, 100);

        int totalRequests = 10;
        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalRequests);
        AtomicInteger created = new AtomicInteger(0);
        AtomicInteger replayed = new AtomicInteger(0);
        AtomicInteger unexpected = new AtomicInteger(0);
        List<String> orderNumbers = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    var response = mockMvc.perform(post("/api/v1/orders")
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(String.format("{ \"customerId\": 1, \"items\": [ { \"sku\": \"%s\", \"quantity\": 2 } ] }", sku))
                                    .with(JwtMockFactory.admin()))
                            .andReturn().getResponse();
                    if (response.getStatus() == 201) {
                        created.incrementAndGet();
                    } else if (response.getStatus() == 200) {
                        replayed.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                    orderNumbers.add(objectMapper.readTree(response.getContentAsString()).path("orderNumber").asText());
                } catch (Throwable e) {
                    unexpected.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(unexpected.get()).isEqualTo(0);
        assertThat(created.get()).isEqualTo(1);
        assertThat(replayed.get()).isEqualTo(totalRequests - 1);
        assertThat(orderNumbers.stream().distinct()).hasSize(1);
        assertThat(productRepository.findBySku(sku).orElseThrow().getStockQty()).isEqualTo(100 - 2);
        assertThat(placedEvents(orderNumbers.getFirst())).isEqualTo(1);
    }
}
