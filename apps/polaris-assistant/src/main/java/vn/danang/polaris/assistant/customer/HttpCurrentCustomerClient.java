package vn.danang.polaris.assistant.customer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.Nullable;

/**
 * {@link CurrentCustomerClient} over Order Management's REST contract ({@code /api/v1/customers/...}),
 * authenticated with the caller's own bearer token and carrying W3C {@code traceparent}.
 * <p>
 * Failure details (status codes, transport errors) are logged here and never put into exception messages,
 * because those messages end up in the model's context.
 */
@Component
public class HttpCurrentCustomerClient implements CurrentCustomerClient {

    static final String CURRENT_CUSTOMER_PATH = "/api/v1/customers/me";
    static final String CUSTOMER_PATH = "/api/v1/customers/";
    /** RFC 7807 type Order Management (S2) returns from {@code /customers/me} when no customer is linked. */
    static final String NOT_LINKED_PROBLEM_TYPE = "https://polaris.local/errors/not-found";

    private static final Logger log = LoggerFactory.getLogger(HttpCurrentCustomerClient.class);
    private static final String LOOKUP_FAILED = "The customer account could not be looked up in Order Management.";

    private final PolarisCoreApiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    @Nullable
    private final Tracer tracer;

    @Autowired
    public HttpCurrentCustomerClient(PolarisCoreApiProperties properties, ObjectMapper objectMapper, ObjectProvider<Tracer> tracerProvider) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build(), tracerProvider != null ? tracerProvider.getIfAvailable() : null);
    }

    public HttpCurrentCustomerClient(PolarisCoreApiProperties properties, ObjectMapper objectMapper, HttpClient httpClient, @Nullable Tracer tracer) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.tracer = tracer;
    }

    @Override
    public Optional<CustomerRef> findCurrentCustomer(String callerBearerToken) {
        if (callerBearerToken == null || callerBearerToken.isBlank()) {
            log.warn("Current customer lookup attempted without a caller token");
            throw new CustomerLookupException(LOOKUP_FAILED);
        }
        HttpResponse<String> response;
        try {
            response = get(CURRENT_CUSTOMER_PATH, callerBearerToken);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while resolving the current customer");
            throw new CustomerLookupException(LOOKUP_FAILED, e);
        } catch (Exception e) {
            log.error("Current customer lookup failed: error={}", e.toString());
            throw new CustomerLookupException(LOOKUP_FAILED, e);
        }

        int status = response.statusCode();
        if (status == 404 && isNotLinkedProblem(response.body())) {
            log.info("No customer linked to the caller: status=404");
            return Optional.empty();
        }
        if (status != 200) {
            log.warn("Current customer lookup rejected: status={} body={}", status, abbreviate(response.body()));
            throw new CustomerLookupException(LOOKUP_FAILED);
        }
        try {
            JsonNode body = objectMapper.readTree(response.body());
            JsonNode id = body.get("id");
            if (id == null || !id.canConvertToLong()) {
                log.warn("Current customer payload has no id: body={}", abbreviate(response.body()));
                throw new CustomerLookupException(LOOKUP_FAILED);
            }
            return Optional.of(new CustomerRef(id.asLong(), textOrNull(body.get("fullName"))));
        } catch (CustomerLookupException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Current customer payload unreadable: error={}", e.toString());
            throw new CustomerLookupException(LOOKUP_FAILED, e);
        }
    }

    @Override
    public Optional<String> findCustomerName(Long customerId, String callerBearerToken) {
        if (customerId == null || callerBearerToken == null || callerBearerToken.isBlank()) {
            return Optional.empty();
        }
        try {
            HttpResponse<String> response = get(CUSTOMER_PATH + customerId, callerBearerToken);
            if (response.statusCode() != 200) {
                log.info("Customer name lookup skipped: customerId={} status={}", customerId, response.statusCode());
                return Optional.empty();
            }
            return Optional.ofNullable(textOrNull(objectMapper.readTree(response.body()).get("fullName")));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.info("Customer name lookup failed: customerId={} error={}", customerId, e.toString());
            return Optional.empty();
        }
    }

    private HttpResponse<String> get(String path, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl().replaceAll("/+$", "") + path))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Accept", "application/json, application/problem+json")
                .header("Authorization", "Bearer " + bearerToken)
                .GET();
        injectTraceParent(builder);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * Only Order Management's own "no customer linked" problem counts as "not linked"; any other 404
     * (e.g. a gateway or a wrong base URL) is a failed lookup.
     */
    private boolean isNotLinkedProblem(String body) {
        try {
            JsonNode problem = objectMapper.readTree(body);
            return problem != null
                    && NOT_LINKED_PROBLEM_TYPE.equals(textOrNull(problem.get("type")))
                    && problem.path("status").asInt() == 404;
        } catch (Exception e) {
            return false;
        }
    }

    @Nullable
    private static String textOrNull(@Nullable JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static String abbreviate(@Nullable String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    private void injectTraceParent(HttpRequest.Builder builder) {
        if (tracer == null) {
            return;
        }
        Span currentSpan = tracer.currentSpan();
        TraceContext context = currentSpan != null ? currentSpan.context()
                : (tracer.currentTraceContext() != null ? tracer.currentTraceContext().context() : null);
        if (context != null && context.traceId() != null && context.spanId() != null) {
            String sampled = (context.sampled() != null && !context.sampled()) ? "00" : "01";
            builder.header("traceparent", "00-" + context.traceId() + "-" + context.spanId() + "-" + sampled);
        }
    }
}
