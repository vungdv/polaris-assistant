package vn.danang.polaris.assistant.ai;

/**
 * A non-2xx response from the model provider. Carries the HTTP status so GenAI telemetry can classify the
 * failure ({@code error.category}: rate_limit, auth_error, server_error, ...).
 */
public class ModelProviderException extends RuntimeException {

    private final int statusCode;

    public ModelProviderException(int statusCode) {
        super("Model provider returned HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
