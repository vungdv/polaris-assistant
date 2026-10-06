package vn.danang.polaris.assistant.customer;

import java.util.Optional;

/**
 * Looks up customers through Order Management's published REST contract, always with the caller's own
 * token. {@code GET /api/v1/customers/me} resolves the customer linked to a signed-in caller: Order
 * Management decides the link from the verified token (JWT {@code sub}, one-time fallback on the verified
 * {@code email}); the assistant never trusts a customer id supplied by the model for a shopper.
 */
public interface CurrentCustomerClient {

    /**
     * @param callerBearerToken the caller's own access token (never a service token)
     * @return the linked customer, or empty if Order Management reports that no customer is linked to the caller
     * @throws CustomerLookupException if the lookup itself failed (unreachable, rejected token, unexpected
     *                                 response); its message is safe to show and carries no internal detail
     */
    Optional<CustomerRef> findCurrentCustomer(String callerBearerToken);

    /**
     * Best-effort display name for a customer a staff caller named ({@code GET /api/v1/customers/{id}},
     * which needs {@code customer.read}). Never fails: any problem yields empty and the card shows the id only.
     */
    Optional<String> findCustomerName(Long customerId, String callerBearerToken);
}
