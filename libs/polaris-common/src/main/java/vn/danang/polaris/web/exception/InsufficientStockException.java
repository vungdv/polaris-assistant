package vn.danang.polaris.web.exception;

public class InsufficientStockException extends RuntimeException {

    public static final String TYPE = "https://polaris.local/errors/out-of-stock";

    private final String sku;
    private final int requestedQuantity;
    private final int availableQuantity;

    public InsufficientStockException(String sku, int requestedQuantity, int availableQuantity) {
        super(String.format("Insufficient stock for product '%s'. Requested: %d, available: %d.", sku, requestedQuantity, availableQuantity));
        this.sku = sku;
        this.requestedQuantity = requestedQuantity;
        this.availableQuantity = availableQuantity;
    }

    public String getSku() {
        return sku;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }
}
