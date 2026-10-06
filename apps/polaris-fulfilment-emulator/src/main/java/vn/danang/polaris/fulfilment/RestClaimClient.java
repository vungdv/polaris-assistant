package vn.danang.polaris.fulfilment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/** {@link ClaimClient} over a {@link RestClient} that carries the service-account bearer token (F2 design §5). */
class RestClaimClient implements ClaimClient {

    private static final Logger log = LoggerFactory.getLogger(RestClaimClient.class);

    record ClaimRequest(String partnerId) {
    }

    private final RestClient restClient;

    RestClaimClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ClaimResult claim(String orderNumber, String partnerId) {
        try {
            HttpStatusCode status = restClient.post()
                    .uri("/api/v1/orders/{orderNumber}/claim", orderNumber)
                    .body(new ClaimRequest(partnerId))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> { })
                    .toBodilessEntity()
                    .getStatusCode();
            if (status.value() == HttpStatus.OK.value()) {
                return ClaimResult.WON;
            }
            if (status.value() == HttpStatus.CONFLICT.value()) {
                return ClaimResult.LOST;
            }
            log.warn("Claim rejected orderNumber={} partnerId={} httpStatus={}", orderNumber, partnerId, status.value());
            return ClaimResult.FAILED;
        }
        catch (RuntimeException e) {
            log.error("Claim call failed orderNumber={} partnerId={}", orderNumber, partnerId, e);
            return ClaimResult.FAILED;
        }
    }
}
