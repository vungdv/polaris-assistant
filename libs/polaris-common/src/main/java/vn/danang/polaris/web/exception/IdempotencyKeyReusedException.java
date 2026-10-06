package vn.danang.polaris.web.exception;

/**
 * Thrown when an {@code Idempotency-Key} is replayed for a different customer than the one whose
 * order it already identifies, so a caller can never read back someone else's order by guessing or
 * reusing a key (FR-4, FR-10). Translated to 422 {@link #TYPE}.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public static final String TYPE = "https://polaris.local/errors/idempotency-key-reused";

    public IdempotencyKeyReusedException() {
        super("The Idempotency-Key has already been used for a different request. Use a new key for a new order.");
    }
}
