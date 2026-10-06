package vn.danang.polaris.assistant.web;

import java.net.URI;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import vn.danang.polaris.assistant.ai.ModelFailures;
import vn.danang.polaris.assistant.ai.ModelUnavailableException;

/**
 * Maps {@link ModelUnavailableException} to RFC 7807 Problem Details: {@code 503 Service Unavailable}, with
 * {@code Retry-After} (RFC 9110 §10.2.3, delay-seconds) when one is known; or {@code 500 Internal Server Error}
 * without {@code Retry-After} when the provider rejected our own request (4xx other than 429), since retrying
 * won't help.
 * <p>
 * The provider's error text is never put in the response: it may hold internal details and is not actionable
 * for the user. Ordered ahead of the shared {@code GlobalExceptionHandler}, whose catch-all would answer 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ModelUnavailableExceptionHandler {

    static final String TYPE = "https://polaris.local/errors/assistant-unavailable";
    static final String REJECTED_TYPE = "https://polaris.local/errors/assistant-error";

    private static final Logger log = LoggerFactory.getLogger(ModelUnavailableExceptionHandler.class);

    @ExceptionHandler(ModelUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleModelUnavailable(ModelUnavailableException ex) {
        if (ModelFailures.isRejectedRequest(ex)) {
            return rejected(ex);
        }
        log.warn("AI model unavailable, answering 503: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The assistant is temporarily unavailable. Please try again shortly.");
        problem.setTitle("Assistant Temporarily Unavailable");
        problem.setType(URI.create(TYPE));
        problem.setProperty("remedy", "Retry the message later. Anything the assistant already did in this turn "
                + "(for example a staged order draft) has been kept.");

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
        ex.retryAfter().ifPresent(delay -> response.header(HttpHeaders.RETRY_AFTER, String.valueOf(toSeconds(delay))));
        return response.body(problem);
    }

    private ResponseEntity<ProblemDetail> rejected(ModelUnavailableException ex) {
        log.error("AI model rejected our request, answering 500: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "The assistant could not process this message.");
        problem.setTitle("Assistant Error");
        problem.setType(URI.create(REJECTED_TYPE));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /** Retry-After delay-seconds: whole seconds, rounded up, at least 1. */
    private static long toSeconds(Duration delay) {
        long seconds = delay.toSeconds() + (delay.getNano() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
