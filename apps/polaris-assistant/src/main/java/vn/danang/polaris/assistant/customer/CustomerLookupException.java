package vn.danang.polaris.assistant.customer;

/**
 * The caller's customer could not be looked up in Order Management (as opposed to "no customer is linked",
 * which {@link CurrentCustomerClient#findCurrentCustomerId} reports as an empty result).
 */
public class CustomerLookupException extends RuntimeException {

    public CustomerLookupException(String message) {
        super(message);
    }

    public CustomerLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
