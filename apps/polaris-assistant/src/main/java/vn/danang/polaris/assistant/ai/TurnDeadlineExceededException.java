package vn.danang.polaris.assistant.ai;

import java.net.http.HttpTimeoutException;

/** The turn's time budget ran out before the model was called; the provider was never reached. */
public class TurnDeadlineExceededException extends HttpTimeoutException {

    public TurnDeadlineExceededException(String message) {
        super(message);
    }
}
