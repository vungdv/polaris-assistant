package vn.danang.polaris.web.exception;

import java.util.List;

/**
 * Thrown when an order names products that exist but are no longer sold ({@code is_active = false}), checked
 * under row lock just before the order commits. Translated to 409 {@link #TYPE}.
 */
public class ProductInactiveException extends RuntimeException {

    public static final String TYPE = "https://polaris.local/errors/product-inactive";

    private final List<String> skus;

    public ProductInactiveException(List<String> skus) {
        super("These products are no longer sold: " + String.join(", ", skus) + ".");
        this.skus = List.copyOf(skus);
    }

    public List<String> getSkus() {
        return skus;
    }
}
