package vn.danang.polaris.fulfilment;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import vn.danang.polaris.events.fulfilment.FulfilmentEvents;
import vn.danang.polaris.events.order.OrderEvents;

/** {@code polaris.fulfilment.*} (F2 design §3). */
@ConfigurationProperties("polaris.fulfilment")
public record FulfilmentProperties(
        @DefaultValue({ "partner-north", "partner-central", "partner-south" }) List<String> partners,
        @DefaultValue ClaimPause claimPause,
        @DefaultValue("2s") Duration stepDelay,
        /** Seed for the claim-pause draws; unset means unseeded. Tests set it so pauses are deterministic. */
        Long randomSeed,
        @DefaultValue Topics topics,
        @DefaultValue OrderApi orderApi,
        @DefaultValue Auth auth) {

    public FulfilmentProperties {
        partners = List.copyOf(partners);
        if (partners.isEmpty() || partners.stream().anyMatch(p -> p.isBlank() || p.length() > 64)
                || partners.stream().distinct().count() != partners.size()) {
            throw new IllegalArgumentException("polaris.fulfilment.partners must be unique, non-blank ids of at most 64 chars");
        }
        if (claimPause.min().compareTo(claimPause.max()) > 0) {
            throw new IllegalArgumentException("polaris.fulfilment.claim-pause.min must not exceed max");
        }
    }

    public record ClaimPause(@DefaultValue("1s") Duration min, @DefaultValue("5s") Duration max) {
    }

    public record Topics(
            @DefaultValue(OrderEvents.DESTINATION) String offers,
            @DefaultValue(FulfilmentEvents.DESTINATION) String shipments,
            @DefaultValue("3") int shipmentPartitions,
            @DefaultValue("3") short shipmentReplicas,
            @DefaultValue("2") int shipmentMinInsyncReplicas) {
    }

    public record OrderApi(@DefaultValue("http://localhost:8080") String baseUrl) {
    }

    /** The one confidential service client shared by all partners (TR-F4). The secret comes from the environment. */
    public record Auth(
            @DefaultValue("https://id.polaris.local/realms/polaris/protocol/openid-connect/token") String tokenUri,
            @DefaultValue("polaris-fulfilment-emulator") String clientId,
            String clientSecret) {
    }
}
