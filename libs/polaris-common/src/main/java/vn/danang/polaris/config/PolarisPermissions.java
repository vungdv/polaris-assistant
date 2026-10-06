package vn.danang.polaris.config;

/**
 * Standard fine-grained permission identifiers across Polaris bounded contexts.
 * <p>
 * Corresponds directly to Keycloak client roles provisioned under {@code polaris-api} in {@code docker/keycloak/polaris-realm.json}.
 */
public final class PolarisPermissions {

    private PolarisPermissions() {
    }

    /**
     * Read access to the product catalog — search products and look up SKUs.
     */
    public static final String CATALOG_READ = "catalog.read";

    /**
     * Write access to the product catalog — manage products and categories.
     */
    public static final String CATALOG_WRITE = "catalog.write";

    /**
     * Read access to orders — status checks, full details, and order history.
     */
    public static final String ORDER_READ = "order.read";

    /**
     * Write access to orders — place new orders and cancel existing ones.
     */
    public static final String ORDER_WRITE = "order.write";

    /**
     * Fulfilment access to orders — claim a placed order on behalf of a fulfilment partner.
     */
    public static final String ORDER_FULFIL = "order.fulfil";

    /**
     * Read access to customer profiles and account information.
     */
    public static final String CUSTOMER_READ = "customer.read";

    /**
     * Write access to customer profiles — update customer details and accounts.
     */
    public static final String CUSTOMER_WRITE = "customer.write";

    /**
     * Read access to store inventory, stock levels, and warehouse counts.
     */
    public static final String INVENTORY_READ = "inventory.read";

    /**
     * Write access to store inventory — adjust stock and sync product counts.
     */
    public static final String INVENTORY_WRITE = "inventory.write";
}
