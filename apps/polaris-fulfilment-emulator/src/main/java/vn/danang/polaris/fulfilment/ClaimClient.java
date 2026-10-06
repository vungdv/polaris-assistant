package vn.danang.polaris.fulfilment;

/** Port to Order's claim endpoint {@code POST /api/v1/orders/{orderNumber}/claim}: the external boundary. */
public interface ClaimClient {

    /** Claims the order for the partner: 200 is {@code WON}, 409 is {@code LOST}, anything else (or an I/O error) {@code FAILED}. */
    ClaimResult claim(String orderNumber, String partnerId);
}
