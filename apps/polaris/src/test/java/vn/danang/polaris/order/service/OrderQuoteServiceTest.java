package vn.danang.polaris.order.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.order.dto.QuoteRequest;
import vn.danang.polaris.order.dto.QuoteResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderQuoteService Unit Tests")
class OrderQuoteServiceTest {

    @Mock
    private ProductRepository productRepository;

    private OrderQuoteService service;

    @BeforeEach
    void setUp() {
        service = new OrderQuoteService(productRepository);
    }

    private static Product product(String sku, String name, String price, int stock, boolean active) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(name);
        p.setPrice(new BigDecimal(price));
        p.setStockQty(stock);
        p.setIsActive(active);
        return p;
    }

    @Test
    @DisplayName("All lines in stock: live prices, line totals and grand total, orderable")
    void quote_allOk() {
        when(productRepository.findBySkuIgnoreCase("NG-CHARGER-01"))
                .thenReturn(Optional.of(product("NG-CHARGER-01", "Nova 65W Fast Charger", "24.90", 200, true)));
        when(productRepository.findBySkuIgnoreCase("NG-EARBUD-01"))
                .thenReturn(Optional.of(product("NG-EARBUD-01", "Nova Wireless Earbuds", "49.90", 120, true)));

        QuoteResponse quote = service.quote(List.of(
                new QuoteRequest.Item("NG-CHARGER-01", 2),
                new QuoteRequest.Item("NG-EARBUD-01", 1)));

        assertThat(quote.orderable()).isTrue();
        assertThat(quote.totalAmount()).isEqualByComparingTo("99.70");
        assertThat(quote.lines()).hasSize(2);
        QuoteResponse.Line charger = quote.lines().get(0);
        assertThat(charger.sku()).isEqualTo("NG-CHARGER-01");
        assertThat(charger.name()).isEqualTo("Nova 65W Fast Charger");
        assertThat(charger.requestedQuantity()).isEqualTo(2);
        assertThat(charger.unitPrice()).isEqualByComparingTo("24.90");
        assertThat(charger.availableQuantity()).isEqualTo(200);
        assertThat(charger.lineTotal()).isEqualByComparingTo("49.80");
        assertThat(charger.problem()).isNull();
    }

    @Test
    @DisplayName("Short stock: line flagged insufficient_stock with requested/available, excluded from total")
    void quote_shortStock() {
        when(productRepository.findBySkuIgnoreCase("NG-WATCH-01"))
                .thenReturn(Optional.of(product("NG-WATCH-01", "Nova Smart Watch", "89.90", 3, true)));
        when(productRepository.findBySkuIgnoreCase("NG-CASE-01"))
                .thenReturn(Optional.of(product("NG-CASE-01", "Nova Phone Case", "14.90", 300, true)));

        QuoteResponse quote = service.quote(List.of(
                new QuoteRequest.Item("NG-WATCH-01", 5),
                new QuoteRequest.Item("NG-CASE-01", 1)));

        assertThat(quote.orderable()).isFalse();
        QuoteResponse.Line watch = quote.lines().get(0);
        assertThat(watch.problem()).isEqualTo(QuoteResponse.Problem.INSUFFICIENT_STOCK);
        assertThat(watch.requestedQuantity()).isEqualTo(5);
        assertThat(watch.availableQuantity()).isEqualTo(3);
        assertThat(watch.unitPrice()).isEqualByComparingTo("89.90");
        assertThat(watch.lineTotal()).isNull();
        assertThat(quote.lines().get(1).problem()).isNull();
        assertThat(quote.totalAmount()).isEqualByComparingTo("14.90");
    }

    @Test
    @DisplayName("Unknown SKU: line flagged not_found with no price or stock")
    void quote_unknownSku() {
        when(productRepository.findBySkuIgnoreCase("NOPE-01")).thenReturn(Optional.empty());

        QuoteResponse quote = service.quote(List.of(new QuoteRequest.Item("NOPE-01", 1)));

        assertThat(quote.orderable()).isFalse();
        QuoteResponse.Line line = quote.lines().get(0);
        assertThat(line.sku()).isEqualTo("NOPE-01");
        assertThat(line.name()).isNull();
        assertThat(line.unitPrice()).isNull();
        assertThat(line.availableQuantity()).isNull();
        assertThat(line.lineTotal()).isNull();
        assertThat(line.problem()).isEqualTo(QuoteResponse.Problem.NOT_FOUND);
        assertThat(quote.totalAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Inactive product: line flagged inactive even when stock is available")
    void quote_inactiveProduct() {
        when(productRepository.findBySkuIgnoreCase("NG-OLD-01"))
                .thenReturn(Optional.of(product("NG-OLD-01", "Retired Gadget", "10.00", 50, false)));

        QuoteResponse quote = service.quote(List.of(new QuoteRequest.Item("NG-OLD-01", 1)));

        assertThat(quote.orderable()).isFalse();
        assertThat(quote.lines().get(0).problem()).isEqualTo(QuoteResponse.Problem.INACTIVE);
        assertThat(quote.lines().get(0).lineTotal()).isNull();
    }

    @Test
    @DisplayName("Repeated SKU (any case): merged into one line with summed quantity, first-appearance order")
    void quote_repeatedSku_mergedIntoOneLine() {
        when(productRepository.findBySkuIgnoreCase("ng-speaker-01"))
                .thenReturn(Optional.of(product("NG-SPEAKER-01", "Nova Bluetooth Speaker", "39.90", 5, true)));
        when(productRepository.findBySkuIgnoreCase("NG-CASE-01"))
                .thenReturn(Optional.of(product("NG-CASE-01", "Nova Phone Case", "14.90", 300, true)));

        QuoteResponse quote = service.quote(List.of(
                new QuoteRequest.Item("ng-speaker-01", 3),
                new QuoteRequest.Item("NG-CASE-01", 1),
                new QuoteRequest.Item("NG-SPEAKER-01", 3)));

        assertThat(quote.lines()).hasSize(2);
        QuoteResponse.Line speaker = quote.lines().get(0);
        assertThat(speaker.sku()).isEqualTo("NG-SPEAKER-01");
        assertThat(speaker.requestedQuantity()).isEqualTo(6);
        assertThat(speaker.availableQuantity()).isEqualTo(5);
        assertThat(speaker.problem()).isEqualTo(QuoteResponse.Problem.INSUFFICIENT_STOCK);
        assertThat(quote.lines().get(1).sku()).isEqualTo("NG-CASE-01");
        assertThat(quote.orderable()).isFalse();
        assertThat(quote.totalAmount()).isEqualByComparingTo("14.90");
        verify(productRepository, times(1)).findBySkuIgnoreCase("ng-speaker-01");
    }

    @Test
    @DisplayName("Repeated SKU within stock: merged line is orderable and priced on the summed quantity")
    void quote_repeatedSku_withinStock() {
        when(productRepository.findBySkuIgnoreCase("NG-SPEAKER-01"))
                .thenReturn(Optional.of(product("NG-SPEAKER-01", "Nova Bluetooth Speaker", "39.90", 5, true)));

        QuoteResponse quote = service.quote(List.of(
                new QuoteRequest.Item("NG-SPEAKER-01", 2),
                new QuoteRequest.Item("NG-SPEAKER-01", 2)));

        assertThat(quote.lines()).hasSize(1);
        assertThat(quote.lines().get(0).requestedQuantity()).isEqualTo(4);
        assertThat(quote.lines().get(0).lineTotal()).isEqualByComparingTo("159.60");
        assertThat(quote.orderable()).isTrue();
        assertThat(quote.totalAmount()).isEqualByComparingTo("159.60");
    }

    @Test
    @DisplayName("Read-only: no locking query and no writes against the repository")
    void quote_isReadOnly() {
        when(productRepository.findBySkuIgnoreCase("NG-CASE-01"))
                .thenReturn(Optional.of(product("NG-CASE-01", "Nova Phone Case", "14.90", 300, true)));

        service.quote(List.of(new QuoteRequest.Item("NG-CASE-01", 1)));

        verify(productRepository).findBySkuIgnoreCase("NG-CASE-01");
        verifyNoMoreInteractions(productRepository);
    }

    @Test
    @DisplayName("Invalid input: empty, too many items, blank SKU, quantity < 1 or overflowing merged quantity is rejected")
    void quote_invalidInput() {
        assertThatThrownBy(() -> service.quote(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.quote(List.of(new QuoteRequest.Item("NG-CASE-01", 0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.quote(List.of(new QuoteRequest.Item(" ", 1))))
                .isInstanceOf(IllegalArgumentException.class);
        List<QuoteRequest.Item> tooMany = java.util.stream.IntStream.rangeClosed(0, QuoteRequest.MAX_ITEMS)
                .mapToObj(i -> new QuoteRequest.Item("SKU-" + i, 1)).toList();
        assertThatThrownBy(() -> service.quote(tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.quote(List.of(
                new QuoteRequest.Item("NG-CASE-01", Integer.MAX_VALUE),
                new QuoteRequest.Item("NG-CASE-01", 1)))).isInstanceOf(IllegalArgumentException.class);
    }
}
