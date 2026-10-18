package vn.danang.polaris.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.TestcontainersConfiguration;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.order.dto.PlacedOrderSummary;
import vn.danang.polaris.order.entity.Customer;
import vn.danang.polaris.order.entity.Order;

@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class OrderRepositoryPlacedSummaryTest {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private ProductRepository productRepository;

    private Customer customer(String name) {
        Customer c = new Customer();
        c.setFullName(name);
        c.setEmail(name.replace(' ', '.').toLowerCase() + ".summary@example.com");
        c.setPhone("0000000000");
        c.setCreatedAt(Instant.now());
        return customerRepository.save(c);
    }

    private Product product(String sku, String price) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(sku);
        p.setPrice(new BigDecimal(price));
        p.setStockQty(100);
        p.setCreatedAt(Instant.now());
        return productRepository.save(p);
    }

    @Test
    @DisplayName("Returns customer name, total amount and summed item quantity for PLACED orders only")
    void placedOrdersOnly() {
        Customer alice = customer("Summary Alice");
        Product pen = product("SUM-PEN", "10.00");
        Product ink = product("SUM-INK", "5.00");

        orderRepository.save(Order.place("SUM-1", alice,
                List.of(new Order.Line(pen, 2), new Order.Line(ink, 3)), null, Instant.now()));
        Order cancelled = Order.place("SUM-2", alice, List.of(new Order.Line(pen, 1)), null, Instant.now());
        cancelled.cancel(Instant.now());
        orderRepository.save(cancelled);

        List<PlacedOrderSummary> result = orderRepository.findPlacedOrderSummaries();

        assertThat(result).filteredOn(s -> "Summary Alice".equals(s.customerName()))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.totalAmount()).isEqualByComparingTo("35.00");
                    assertThat(s.totalProductItems()).isEqualTo(5L);
                });
    }
}
