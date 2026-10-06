package vn.danang.polaris.web.exception;

public class DuplicateSkuException extends DuplicateResourceException {

    private final String sku;

    public DuplicateSkuException(String sku) {
        super("Product", sku, "A product with SKU '" + sku + "' already exists.");
        this.sku = sku;
    }

    public String getSku() {
        return sku;
    }
}
