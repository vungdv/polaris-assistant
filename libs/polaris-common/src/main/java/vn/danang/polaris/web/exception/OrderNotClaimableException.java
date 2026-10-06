package vn.danang.polaris.web.exception;

/**
 * Thrown when a claim targets an order that is no longer {@code PLACED} (already claimed, including a repeat by
 * the winner, or cancelled). Translated to 409 {@link #TYPE}; carries the current order state (orderStatus) but never the winner (TR-O2).
 */
public class OrderNotClaimableException extends RuntimeException {

    public static final String TYPE = "https://polaris.local/errors/order-not-claimable";

    private final transient Object status;

    public OrderNotClaimableException(String orderNumber, Object status) {
        super("Order " + orderNumber + " cannot be claimed: only PLACED orders can be claimed");
        this.status = status;
    }

    public String getStatus() {
        return String.valueOf(status);
    }
}
