package vn.danang.polaris.order.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.order.dto.QuoteRequest;
import vn.danang.polaris.order.dto.QuoteResponse;

/**
 * Read-only stock and price verification for a prospective order (FR-14, BPMN {@code O_Verify}).
 * <p>
 * Reads products straight from the database (never the {@code products} cache, FR-1), takes no row
 * locks and writes nothing. The result is advisory: {@link OrderService#placeOrder} re-checks under
 * row lock at commit time.
 */
@Service
@Transactional(readOnly = true)
public class OrderQuoteService {

    private static final Logger log = LoggerFactory.getLogger(OrderQuoteService.class);

    private final ProductRepository productRepo;

    public OrderQuoteService(ProductRepository productRepo) {
        this.productRepo = productRepo;
    }

    /**
     * Quote the given lines. Lines repeating a SKU (case-insensitive) are merged into one line with
     * the summed quantity, in order of first appearance, since they draw on the same stock.
     */
    public QuoteResponse quote(List<QuoteRequest.Item> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Quote must contain at least one item");
        }
        if (items.size() > QuoteRequest.MAX_ITEMS) {
            throw new IllegalArgumentException("Quote must contain at most " + QuoteRequest.MAX_ITEMS + " items");
        }

        record MergedItem(String sku, int quantity) {}
        Map<String, MergedItem> merged = new LinkedHashMap<>();
        for (QuoteRequest.Item item : items) {
            if (item == null || item.sku() == null || item.sku().isBlank()) {
                throw new IllegalArgumentException("Item 'sku' is required");
            }
            if (item.quantity() == null || item.quantity() < 1) {
                throw new IllegalArgumentException("Item 'quantity' must be at least 1 for SKU '" + item.sku() + "'");
            }
            String sku = item.sku().trim();
            merged.merge(sku.toLowerCase(Locale.ROOT), new MergedItem(sku, item.quantity()),
                    (a, b) -> new MergedItem(a.sku(), sumQuantities(a.sku(), a.quantity(), b.quantity())));
        }

        List<QuoteResponse.Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean orderable = true;

        for (MergedItem item : merged.values()) {
            int quantity = item.quantity();
            Optional<Product> found = productRepo.findBySkuIgnoreCase(item.sku());
            if (found.isEmpty()) {
                lines.add(new QuoteResponse.Line(item.sku(), null, quantity, null, null, null, QuoteResponse.Problem.NOT_FOUND));
                orderable = false;
                continue;
            }

            Product product = found.get();
            BigDecimal unitPrice = product.getPrice();
            int available = product.getStockQty() != null ? product.getStockQty() : 0;

            QuoteResponse.Problem problem = null;
            if (Boolean.FALSE.equals(product.getIsActive())) {
                problem = QuoteResponse.Problem.INACTIVE;
            } else if (quantity > available) {
                problem = QuoteResponse.Problem.INSUFFICIENT_STOCK;
            }

            BigDecimal lineTotal = null;
            if (problem == null) {
                lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
                total = total.add(lineTotal);
            } else {
                orderable = false;
            }
            lines.add(new QuoteResponse.Line(
                    product.getSku(), product.getName(), quantity, unitPrice, available, lineTotal, problem));
        }

        log.debug("Order quote computed: requestedLines={}, lines={}, orderable={}, total={}",
                items.size(), lines.size(), orderable, total);
        return new QuoteResponse(orderable, lines, total);
    }

    private static int sumQuantities(String sku, int a, int b) {
        long sum = (long) a + b;
        if (sum > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Total quantity for SKU '" + sku + "' is too large");
        }
        return (int) sum;
    }
}
